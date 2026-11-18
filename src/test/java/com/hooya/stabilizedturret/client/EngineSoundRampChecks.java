package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.turbine.EngineControlSettings;
import com.hooya.stabilizedturret.content.turbine.EngineDriveBehavior;
import com.hooya.stabilizedturret.content.turbine.EngineSoundRpmController;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.List;
import java.util.Locale;

/** Full production mix timeline, including the steady period after a 128/256 ramp. */
public final class EngineSoundRampChecks {
    private static final class Mix {
        private final Object controller;
        private final Method update, independentUpdate;
        private final Field pitches, volumes, climb, envelope;
        Mix() throws ReflectiveOperationException {
            var type=Class.forName("com.hooya.stabilizedturret.client.GasTurbineSoundManager$EngineSoundController");
            var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
            controller=constructor.newInstance();
            update=type.getDeclaredMethod("updateMixParameters",float.class,float.class,float.class,
                    float.class,boolean.class,float.class);update.setAccessible(true);
            independentUpdate=type.getDeclaredMethod("updateMixParameters",float.class,float.class,float.class,
                    float.class,boolean.class,boolean.class);independentUpdate.setAccessible(true);
            pitches=field(type,"pitches");volumes=field(type,"volumes");
            climb=field(type,"smoothedClimb");envelope=field(type,"masterEnvelope");
            envelope.setFloat(controller,1);
        }
        void tick(float rpm,float load,float requestedClimb) throws ReflectiveOperationException {
            update.invoke(controller,rpm,256F,load,128F,true,requestedClimb);
        }
        void tickIndependent(float rpm,float load) throws ReflectiveOperationException {
            independentUpdate.invoke(controller,rpm,256F,load,128F,true,true);
        }
        float[] pitches() throws IllegalAccessException { return (float[])pitches.get(controller); }
        float[] volumes() throws IllegalAccessException { return (float[])volumes.get(controller); }
        float climb() throws IllegalAccessException { return climb.getFloat(controller); }
    }
    public static void main(String[] args) {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        verify();
    }
    public static void verify() { run(true); }
    private static void run(boolean verify) {
        try {
            String packagedJar=System.getProperty("create_fire_control.engineSoundRamp.jar");
            if(packagedJar!=null) {
                var expected=Path.of(packagedJar).toRealPath();
                for(var type:new Class<?>[]{GasTurbineSoundManager.class,EngineSoundRpmController.class}) {
                    var actual=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
                    require(actual.equals(expected),"128/256 mix must use production classes from the delivered JAR");
                }
            }
            var settings=new EngineControlSettings(150,"",List.of(EngineDriveBehavior.defaults()),true,128,128,256);
            var csv=new StringBuilder("scenario,tick,rpm,climb,layer,pitch,gain\n");
            float worstOldOvershoot=0;
            for(int interval:new int[]{1,2,4}) for(int sign:new int[]{1,-1}) {
                var server=new EngineSoundRpmController();var client=new EngineSoundRpmController();
                client.synchronize(0,true);
                var attached=new Mix();var oldIndependent=new Mix();var independent=new Mix();
                float physicalRpm=0,oldPeak=0,settledPitch=0;
                int serverTicks=0;
                for(int tick=0;tick<440;tick++) {
                    // Idle first; hold one behavior continuously; settle; then release.
                    float target=tick>=100 && tick<340?sign*256:0;
                    boolean serverAdvanced=tick%interval==0 && !(interval>1 && tick>=120 && tick<135);
                    if(serverAdvanced) {
                        server.tick(settings,true,target,.05F);
                        if(++serverTicks%2==0 || Math.abs(server.rpm())==256 || server.rpm()==0) {
                            client.synchronize(server.rpm(),false);physicalRpm=Math.abs(server.rpm());
                        }
                    }
                    client.tickClient(settings,true,.05F);
                    float rpm=Math.abs(client.rpm());
                    attached.tick(physicalRpm,0,-1);
                    oldIndependent.tick(rpm,0,EngineSoundRpmController.acceleratingMagnitude(client.rpm(),target)?1:0);
                    independent.tickIndependent(rpm,0);
                    if(rpm>=255.999F && tick<340) {
                        oldPeak=Math.max(oldPeak,oldIndependent.pitches()[3]);
                        settledPitch=independent.pitches()[3];
                    }
                    if(verify) {
                        require(independent.climb()==0,"independent RPM must not add a second pitch ramp");
                        if(rpm>=255.999F && tick<340)
                            require(Math.abs(independent.pitches()[3]-1)<.00001F,"held maximum has no pitch overshoot or relaxation");
                    }
                    if(interval==1 && sign==1) {
                        append(csv,"attached",tick,physicalRpm,attached);
                        append(csv,"independent-v3",tick,rpm,oldIndependent);
                        append(csv,"independent",tick,rpm,independent);
                    }
                }
                worstOldOvershoot=Math.max(worstOldOvershoot,oldPeak/settledPitch-1);
            }
            require(worstOldOvershoot>.17F,"replay must expose the previous independent climb pitch overshoot");
            var switched=new Mix();
            for(int i=0;i<30;i++) switched.tick(128,0,1);
            require(switched.climb()>.9F,"mode-switch fixture begins with a real climb boost");
            switched.tickIndependent(128,0);
            require(switched.climb()==0,"enabling independent RPM clears the previous boost immediately");
            var unloaded=new Mix();var loaded=new Mix();
            for(int i=0;i<100;i++) { unloaded.tickIndependent(256,0);loaded.tickIndependent(256,.8F); }
            for(int i=0;i<4;i++) require(Math.abs(unloaded.pitches()[i]-loaded.pitches()[i])<.00001F,
                    "real SU load does not change the independent RPM pitch");
            require(loaded.volumes()[7]>unloaded.volumes()[7],"real SU load still selects the load sound layers");
            var report=Path.of("build/reports/engine-sound-128-256-mix.csv");
            Files.createDirectories(report.getParent());Files.writeString(report,csv);
            System.out.printf(Locale.ROOT,"128/256 production mix: v3 high-layer pitch overshoot %.2f%%; independent climb=0; trace=%s%n",
                    worstOldOvershoot*100,report);
        } catch(ReflectiveOperationException|java.io.IOException|java.net.URISyntaxException error) { throw new RuntimeException(error); }
    }
    private static void append(StringBuilder csv,String scenario,int tick,float rpm,Mix mix) throws IllegalAccessException {
        for(int layer=0;layer<8;layer++) csv.append(scenario).append(',').append(tick).append(',')
                .append(rpm).append(',').append(mix.climb()).append(',').append(layer).append(',')
                .append(mix.pitches()[layer]).append(',').append(mix.volumes()[layer]).append('\n');
    }
    private static Field field(Class<?> type,String name) throws NoSuchFieldException {
        var field=type.getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
