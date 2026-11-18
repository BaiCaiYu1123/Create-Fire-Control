package com.hooya.stabilizedturret.client;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Locale;

/** Opt-in checks against the saved pre-change algorithm, with no game world needed. */
public final class AbramsSoundMixChecks {
    private static final float[] RMS = {0.06213F, 0.19586998F, 0.16357537F, 0.15211471F,
            0.26864118F, 0.27317766F, 0.21441896F, 0.25275791F};
    private static final float[] PEAK = {0.25124127F, 0.81222737F, 0.71098304F, 0.72083157F,
            0.92790872F, 0.9836185F, 0.91405833F, 0.94088054F};
    private static final Class<?> CURRENT = GasTurbineSoundManager.class;
    private static Class<?> baseline;
    private static boolean reference;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        baseline = Class.forName("com.hooya.stabilizedturret.client.AbramsLegacySoundBaseline");
        String packagedJar=System.getProperty("create_fire_control.abrams.packaged_jar");
        if(packagedJar!=null) {
            Path expectedJar=Path.of(packagedJar).toRealPath();
            for(Class<?> production:new Class<?>[]{CURRENT,AbramsFmodReference.class}) {
                Path actualJar=Path.of(production.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
                check(actualJar.equals(expectedJar),"production classes really loaded from delivered JAR");
            }
            System.out.println("Verified production code source: "+expectedJar);
        }
        reference = "fmod_reference".equals(System.getProperty(
                "create_fire_control.abrams.expected_mix", "fmod_reference"));
        Field mode = CURRENT.getDeclaredField("FMOD_REFERENCE");
        mode.setAccessible(true);
        check(mode.getBoolean(null) == reference, "production manager selects expected mode");
        check(AbramsFmodReference.isReference(null), "default FMOD Reference");
        check(AbramsFmodReference.isReference("fmod_reference"), "explicit FMOD Reference");
        check(AbramsFmodReference.isReference("FMOD"), "FMOD alias");
        check(!AbramsFmodReference.isReference("legacy"), "explicit Legacy fallback");
        check(!AbramsFmodReference.isReference("unknown"), "invalid mode Legacy");
        check(AbramsFmodReference.debugLoad("NaN") == -1, "NaN debug input");
        check(AbramsFmodReference.debugLoad("Infinity") == -1, "infinite debug input");
        check(AbramsFmodReference.debugLoad("2") == -1, "out-of-range debug input");
        check(AbramsFmodReference.debugRpm("NaN") == -1, "invalid debug RPM");
        check(AbramsFmodReference.debugRpm("101") == -1, "out-of-range debug RPM");
        near(AbramsFmodReference.debugRpm("33"),33,0,"sound-only debug RPM percent");
        near(AbramsFmodReference.loadEffectDb(0), -10, 1e-6F, "low-load endpoint");
        near(AbramsFmodReference.loadEffectDb(AbramsFmodReference.LOAD_FIRST_POINT), -10, 1e-6F, "first point");
        near(AbramsFmodReference.loadEffectDb(1), -4, 1e-6F, "high-load endpoint");
        near(AbramsFmodReference.loadEffectDb((1 + AbramsFmodReference.LOAD_FIRST_POINT)/2), -7, 1e-6F, "dB-linear midpoint");
        near(AbramsFmodReference.loadBusGain(0), 0.1581248F, 1e-6F, "bus + automated gain only");
        near(AbramsFmodReference.loadBusGain(1), 0.31550045F, 1e-6F, "no duplicate static +2");
        float lastGain = 0;
        for (int i=0; i<=10000; i++) {
            float gain = AbramsFmodReference.loadBusGain(i/10000F);
            check(Float.isFinite(gain) && gain>=lastGain, "monotonic load automation");
            lastGain = gain;
            compareWeights("fillRpmWeights", i/100F);
            compareWeights("fillLoadWeights", i/10000F);
        }
        for (float v : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -10, 10})
            check(Float.isFinite(AbramsFmodReference.loadBusGain(v)), "finite invalid automation input");
        near(AbramsFmodReference.climbGain(0), 1, 0, "stationary master");
        near(AbramsFmodReference.climbGain(1), 1.4125375F, 1e-6F, "climb +3dB");
        // Both loads select only load99: any volume change must come from Gain,
        // rather than a FadeCurve, instrument change, or the load pitch.
        float lowLoadGain = mix(100,.85F,0,1)[0][7];
        float highLoadGain = mix(100,1,0,1)[0][7];
        near(highLoadGain / lowLoadGain, reference
                ? AbramsFmodReference.loadBusGain(1) / AbramsFmodReference.loadBusGain(.85F)
                : 1F, 1e-6F, "load automation reaches production layer volume");
        near(highLoadGain, db(reference ? -10.02F : -4.02F) * db(-8F), 1e-7F,
                "final load volume has no duplicate legacy bus gain or compensation");

        float maxPeak=0;
        // Both parameter sheets can overlap at once. Verify the actual production kernel.
        for(int r=0;r<=100;r++) for(int l=0;l<=100;l++) for(float climb:new float[]{0,1}) {
            float[][] mix=mix(r,l/100F,climb,1);
            float peak=0;
            for(int i=0;i<8;i++) {
                float volume=mix[0][i]; float pitch=mix[1][i];
                check(Float.isFinite(volume) && volume>=0 && volume*.6F*3<=1, "source gain bounded at GUI300%");
                check(Float.isFinite(pitch) && pitch>=.35F && pitch<=2, "finite pitch");
                peak+=volume*.6F*3*PEAK[i];
            }
            maxPeak=Math.max(maxPeak,peak);
            if(!reference) compareLegacy(r,l/100F,climb,mix);
        }
        if(reference) check(maxPeak<1, "reference conservative summed peak bound below full scale");
        for(float b:new float[]{1,10,30,45,65,80}) {
            for(float load:new float[]{.055F,.375F,.725F}) continuous(mix(b-.0001F,load,0,1),mix(b+.0001F,load,0,1));
        }
        for(float b:new float[]{.01F,.10F,.30F,.45F,.65F,.80F}) {
            for(float rpm:new float[]{5.5F,37.5F,72.5F}) continuous(mix(rpm,b-.000001F,0,1),mix(rpm,b+.000001F,0,1));
        }
        // Attack/release and the final startup handoff scale this same kernel.
        for(float envelope:new float[]{0,.0001F,.25F,.5F,.9999F,1}) {
            float[][] m=mix(50,.5F,0,envelope), full=mix(50,.5F,0,1);
            for(int i=0;i<8;i++) near(m[0][i],full[0][i]*envelope,1e-7F,"smooth envelope scaling");
        }
        float v=0;
        for(int tick=1;tick<=50;tick++) v=(float)call(CURRENT,"approach",new Class<?>[]{float.class,float.class,float.class},v,1F,.02F);
        near(v,1,1e-6F,"load seek full-range 2.5 seconds");
        compareEnvelopes();
        checkLiveController();

        Path out=Path.of(args[0]); Files.createDirectories(out.getParent());
        try(PrintWriter writer=new PrintWriter(Files.newBufferedWriter(out))) {
            writer.println("scenario,rpm,load,climb,envelope,layer,gain,pitch,backend_pitch,rms_estimate,total_rms_estimate_dbfs");
            for(float[] s:new float[][]{{0,0},{0,1},{5,.05F},{50,.5F},{100,1},{5.5F,.055F},{37.5F,.375F},{72.5F,.725F}})
                writeScenario(writer,"steady",s[0],s[1],0,1);
            for(float[] s:new float[][]{{0,0},{10,0},{33,.25F},{50,.5F},{66,.75F},{80,1},{100,1}})
                writeScenario(writer,"requested",s[0],s[1],0,1);
            for(int t=0;t<=200;t++) {
                writeScenario(writer,"acceleration",t*.5F,t/200F,1,1);
                writeScenario(writer,"deceleration",100-t*.5F,1-t/200F,0,1);
            }
            for(int t=0;t<=337;t++) {
                float env=Math.max(0,Math.min(1,(t*.05F-(16.82F-1.5F))/1.5F));
                writeScenario(writer,"startup_handoff",0,0,0,env);
            }
            for(int t=0;t<=44;t++) writeScenario(writer,"shutdown_release",0,0,0,Math.max(0,1-t*.05F/2.1538457F));
        }
        System.out.println("PASS "+(reference?"FMOD Reference":"Legacy")+"; mode selection, final load Gain, golden coefficients, 20,402 mixes, boundaries, seek, envelopes; conservative peak="+maxPeak);
    }

    private static void checkLiveController() throws Exception {
        Class<?> type=Class.forName(CURRENT.getName()+"$EngineSoundController");
        Constructor<?> constructor=type.getDeclaredConstructor(); constructor.setAccessible(true);
        Method update=type.getDeclaredMethod("updateMixParameters",float.class,float.class,float.class,float.class,boolean.class);
        update.setAccessible(true);
        Field envelope=type.getDeclaredField("masterEnvelope"),load=type.getDeclaredField("smoothedLoad"),
                volumes=type.getDeclaredField("volumes"),bus=type.getDeclaredField("loadBusGain"),
                rc=type.getDeclaredField("rpmCompensation"),lc=type.getDeclaredField("loadCompensation");
        for(Field field:new Field[]{envelope,load,volumes,bus,rc,lc}) field.setAccessible(true);
        for(float[] scenario:new float[][]{{0,0},{10,0},{33,.25F},{50,.5F},{66,.75F},{80,1},{100,1}}) {
            Object controller=constructor.newInstance(); envelope.setFloat(controller,1);
            load.setFloat(controller,scenario[1]);
            update.invoke(controller,scenario[0],100F,scenario[1],128F,true);
            float[] actual=(float[])volumes.get(controller);
            float[] expected=mix(scenario[0],scenario[1],0,1)[0];
            for(int i=0;i<8;i++) near(actual[i],expected[i],1e-7F,"live controller selects exact production mix");
            near(bus.getFloat(controller),reference?AbramsFmodReference.loadBusGain(scenario[1]):db(-4.02F),1e-7F,"live bus selection");
            if(reference) {near(rc.getFloat(controller),1,0,"no RPM compensation");near(lc.getFloat(controller),1,0,"no load compensation");}
        }
        Object controller=constructor.newInstance(); envelope.setFloat(controller,1);
        for(int tick=1;tick<=50;tick++) {
            update.invoke(controller,50F,100F,1F,128F,true);
            near(load.getFloat(controller),tick*.02F,1e-6F,"live load rise at 0.4 per second");
        }
        float loaded=((float[])volumes.get(controller))[7];
        check(loaded>0,"fixed RPM responds to changed SU sound input");
        for(int tick=1;tick<=50;tick++) update.invoke(controller,50F,100F,0F,128F,true);
        near(load.getFloat(controller),0,1e-6F,"live load fall reaches zero");
        near(((float[])volumes.get(controller))[7],0,0,"high-load layer disappears at fixed RPM");
        near((float)call(CURRENT,"loopInstanceVolume",new Class<?>[]{float.class,float.class},.6F,0F),reference?0F:.00006F,1e-10F,"actual instance floor");
    }

    private static void compareEnvelopes() throws Exception {
        Class<?> a=Class.forName(CURRENT.getName()+"$EngineSoundController");
        Class<?> b=Class.forName(baseline.getName()+"$EngineSoundController");
        Constructor<?> ca=a.getDeclaredConstructor(),cb=b.getDeclaredConstructor();ca.setAccessible(true);cb.setAccessible(true);
        Object now=ca.newInstance(),old=cb.newInstance();
        Method startA=a.getDeclaredMethod("beginStartupHandoff"),startB=b.getDeclaredMethod("beginStartupHandoff");
        Method tickA=a.getDeclaredMethod("updateMasterEnvelope",boolean.class),tickB=b.getDeclaredMethod("updateMasterEnvelope",boolean.class);
        Field envA=a.getDeclaredField("masterEnvelope"),envB=b.getDeclaredField("masterEnvelope");
        for(var x:new java.lang.reflect.AccessibleObject[]{startA,startB,tickA,tickB,envA,envB})x.setAccessible(true);
        for(int cycle=0;cycle<8;cycle++) {
            startA.invoke(now);startB.invoke(old);
            // The requested idle handoff is the same 1.5-second fade, shifted
            // exactly 20 audio ticks earlier than the archived startup mix.
            for(int t=0;t<20;t++) tickB.invoke(old,true);
            for(int t=0;t<(cycle%2==0?350:20);t++) {
                tickA.invoke(now,true);tickB.invoke(old,true);
                near(envA.getFloat(now),envB.getFloat(old),.0001F,"idle fade is one second earlier than baseline");
                check(envA.getFloat(now)>=0&&envA.getFloat(now)<=1,"bounded startup");
            }
            for(int t=0;t<45;t++) {
                tickA.invoke(now,false);tickB.invoke(old,false);
                near(envA.getFloat(now),envB.getFloat(old),0,"shutdown envelope matches baseline");
            }
        }
    }

    private static void compareWeights(String method,float value) throws Exception {
        float[] a=new float[4], b=new float[4];
        call(CURRENT,method,new Class<?>[]{float.class,float[].class},value,a);
        call(baseline,method,new Class<?>[]{float.class,float[].class},value,b);
        check(Arrays.equals(a,b),"Legacy FadeCurve preserved bitwise");
        for(float weight:a) check(Float.isFinite(weight)&&weight>=0&&weight<=1,"bounded weights");
    }
    private static float[][] mix(float rpm,float load,float climb,float envelope) throws Exception {
        float[] rw=new float[4],lw=new float[4],volumes=new float[8],pitches=new float[8];
        call(CURRENT,"fillRpmWeights",new Class<?>[]{float.class,float[].class},rpm,rw);
        call(CURRENT,"fillLoadWeights",new Class<?>[]{float.class,float[].class},load,lw);
        float rc=reference?1:(float)call(CURRENT,"crossfadeCompensation",new Class<?>[]{float.class,float[].class,boolean.class},rpm,rw,true);
        float lc=reference?1:(float)call(CURRENT,"crossfadeCompensation",new Class<?>[]{float.class,float[].class,boolean.class},load,lw,false);
        call(CURRENT,"calculateLayerMix",new Class<?>[]{float.class,float.class,float.class,float.class,float.class,float.class,float.class,float.class,float.class,float[].class,float[].class,float[].class,float[].class},
                rpm,load,envelope,dbPitch(climb*3),1F,rc,lc,reference?AbramsFmodReference.loadBusGain(load):db(-4.02F),reference?AbramsFmodReference.climbGain(climb):1F,rw,lw,volumes,pitches);
        return new float[][]{volumes,pitches};
    }
    private static void compareLegacy(float rpm,float load,float climb,float[][] current) throws Exception {
        float[] rw=new float[4],lw=new float[4];
        call(baseline,"fillRpmWeights",new Class<?>[]{float.class,float[].class},rpm,rw);
        call(baseline,"fillLoadWeights",new Class<?>[]{float.class,float[].class},load,lw);
        float rc=(float)call(baseline,"crossfadeCompensation",new Class<?>[]{float.class,float[].class,boolean.class},rpm,rw,true);
        float lc=(float)call(baseline,"crossfadeCompensation",new Class<?>[]{float.class,float[].class,boolean.class},load,lw,false);
        for(int i=0;i<4;i++) {
            float instr=(float)call(baseline,"rpmInstrumentGain",new Class<?>[]{int.class},i);
            float a=rw[i]*db(-6.02F)*instr*1F*rc*1F*db(-8F);
            float b=lw[i]*db(-4.02F)*1F*lc*1F*db(-8F);
            check(Float.floatToIntBits(a)==Float.floatToIntBits(current[0][i]),"Legacy RPM gain bitwise");
            check(Float.floatToIntBits(b)==Float.floatToIntBits(current[0][4+i]),"Legacy load gain bitwise");
            for(boolean loaded:new boolean[]{false,true}) {
                float root=(float)call(baseline,loaded?"loadRoot":"rpmRoot",new Class<?>[]{int.class},i);
                float min=(float)call(baseline,loaded?"loadMinPitch":"rpmMinPitch",new Class<?>[]{int.class},i);
                float p=(float)call(baseline,"calculateAutoPitch",new Class<?>[]{float.class,float.class,float.class},loaded?load:rpm,root,min);
                p=(float)call(baseline,"clampPitch",new Class<?>[]{float.class},p*dbPitch(climb*3));
                check(Float.floatToIntBits(p)==Float.floatToIntBits(current[1][(loaded?4:0)+i]),"Legacy pitch bitwise");
            }
        }
    }
    private static void continuous(float[][] a,float[][] b) {
        for(int i=0;i<8;i++) near(a[0][i],b[0][i],1e-5F,"gain boundary continuity");
    }
    private static void writeScenario(PrintWriter w,String name,float rpm,float load,float climb,float env) throws Exception {
        float[][] m=mix(rpm,load,climb,env);float power=0;
        float[] applied=new float[8];
        for(int i=0;i<8;i++) {
            applied[i]=(float)call(CURRENT,"loopInstanceVolume",new Class<?>[]{float.class,float.class},.6F,m[0][i]);
            power+=(float)Math.pow(applied[i]*RMS[i],2);
        }
        double total=power>0?10*Math.log10(power):-300;
        for(int i=0;i<8;i++) w.printf(Locale.ROOT,"%s,%.6f,%.6f,%.6f,%.6f,%d,%.9f,%.9f,%.9f,%.9f,%.6f%n",name,rpm,load,climb,env,i,applied[i],m[1][i],Math.max(.5F,Math.min(2,m[1][i])),applied[i]*RMS[i],total);
    }
    private static Object call(Class<?> cls,String name,Class<?>[] types,Object...args) throws Exception {
        Method m=cls.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);
    }
    private static float db(float x){return (float)Math.pow(10,x/20.0);}
    private static float dbPitch(float x){return (float)Math.pow(2,x/12.0);}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    private static void near(float a,float b,float tolerance,String label){check(Math.abs(a-b)<=tolerance,label+": "+a+" vs "+b);}
}
