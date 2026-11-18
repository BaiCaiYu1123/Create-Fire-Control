package com.hooya.stabilizedturret.client;

import java.nio.file.*;
import java.util.*;
import java.net.URLClassLoader;
import java.lang.reflect.*;
import static com.hooya.stabilizedturret.client.Ztz99aDieselSoundProfile.*;

/** Exports the live pure mixer, and the rollback JAR's actual old mixer, at 20 Hz. */
public final class Ztz99aDieselTransitionChecks {
    private static void require(boolean value,String label) { if(!value) throw new AssertionError(label); }
    private static void close(float a,float b,String label) { require(Math.abs(a-b)<.00002F,label+": "+a+" / "+b); }
    private static float ramp(float t,float start,float end,float seconds) { return start+(end-start)*clamp(t/seconds,0,1); }
    private static float[] input(String name,float t) {
        return switch(name) {
            case "idle_low" -> new float[]{ramp(t-2,0,20,12),.25F};
            case "low_mid" -> new float[]{ramp(t-2,20,55,14),.6F};
            case "mid_high" -> new float[]{ramp(t-2,55,100,16),.8F};
            case "high_idle" -> new float[]{ramp(t-2,100,0,14),.4F};
            case "fast_accel" -> new float[]{ramp(t-2,0,100,2),.75F};
            case "slow_accel" -> new float[]{ramp(t-2,0,100,35),.75F};
            case "fast_decel" -> new float[]{ramp(t-2,100,0,2),.5F};
            case "su_sweep" -> new float[]{5,t<14?ramp(t-2,0,1,10):ramp(t-16,1,0,10)};
            case "long_loop" -> new float[]{72.5F,.7F};
            default -> throw new IllegalArgumentException(name);
        };
    }
    public static void run() throws Exception {
        close(nativePitch(1,30),(float)Math.pow(2,-2/12.),"low root and -2 semitones");
        close(nativePitch(2,55),1,"mid root55");close(nativePitch(3,100),1,"high root100");
        close(nativePitch(3,0),.105F,"FMOD minimum is a playback rate");
        close(pitch(0,100,0),1,"idle has no AutoPitch");
        Path nativeProbe=Path.of("reference/ztz99a/native_pitch_probe.json");
        if(Files.exists(nativeProbe)) {
            var json=com.google.gson.JsonParser.parseString(Files.readString(nativeProbe)).getAsJsonArray();
            int checked=0;
            for(var entry:json) {
                var row=entry.getAsJsonObject();float r=row.get("rpm").getAsFloat();
                for(var voice:row.getAsJsonArray("channels")) {
                    var v=voice.getAsJsonObject();String name=v.get("name").getAsString();
                    int layer=Arrays.asList(LOOPS).indexOf(name);
                    if(layer>=0 && weights(r)[layer]>0) {
                        close(nativePitch(layer,instrumentRpm(r)),v.get("pitch").getAsFloat(),"native bank AutoPitch "+name+" rpm="+r);checked++;
                    }
                }
            }
            require(checked>=20,"native bank audible pitch coverage");
            System.out.println("PASS native FMOD bank: "+checked+" audible per-track AutoPitch values");
        }
        for(int i=0;i<4;i++) close(pitch(i,40,0),pitch(i,40,1),"SU does not drive pitch");
        for(int curve=0;curve<5;curve++) {
            float previous=Ztz99aFmodFade.value(curve,0);
            boolean rising=curve==0||curve==2||curve==4;
            for(int k=1;k<=10000;k++) {
                float v=Ztz99aFmodFade.value(curve,k/10000F);
                require((v-previous)*(rising?1:-1)>=-.000001F,"native fade monotonic, no stale unvirtualization spike");
                require(Math.abs(v-previous)<.001F,"native fade incremental continuity");previous=v;
            }
        }
        for(var mode:Ztz99aDieselTakes.Audition.values()) {
            require(Ztz99aDieselTakes.allowed(mode,"blips")== (mode==Ztz99aDieselTakes.Audition.FULL),"blip audition isolation");
        }
        for(String family:new String[]{"revv","release","blips"}) for(int r=0;r<=100;r++) {
            var take=Ztz99aDieselTakes.select(family,r,family.equals("release")?-30:30,"");
            if(take!=null) {
                require(take.pitch()>=.85F&&take.pitch()<=1.15F && take.trimDb()<=0,"matched take pitch/gain range");
                require(take.equals(Ztz99aDieselTakes.select(family,r,family.equals("release")?-30:30,"")),"deterministic state selection");
            }
        }
        Path out=Path.of("build/reports/ztz99a-transition");Files.createDirectories(out);
        var p=Ztz99aDieselGain.DEFAULTS;
        Path jar=Path.of("build/dist/ztz99a-transition/rollback/create-fire-control-0.8.1-ztz99a.2.jar");
        try(Before before=Files.exists(jar)?new Before(jar):null) {
            for(String scenario:new String[]{"idle_low","low_mid","mid_high","high_idle","fast_accel","slow_accel","fast_decel","su_sweep","long_loop"}) {
                var mix=new Ztz99aDieselGain.State(); if(before!=null) before.reset();
                var afterCsv=new StringBuilder(header());var beforeCsv=new StringBuilder(header());
                var takesCsv=new StringBuilder("time,sample,pitch,gain\n");
                Ztz99aDieselTakes.Selection selection=null;float shotAge=0,shotSource=0,releaseAge=-1,cooldown=0;
                float blipCooldown=12,settled=0;boolean hadMotion=false;
                String lastTake="";
                float seconds=scenario.equals("long_loop")?180:scenario.equals("slow_accel")?45:32;
                float previousRpm=input(scenario,0)[0],derivative=0,maxPitchStep=0,maxGainStep=0,maxPeak=0;
                float[] oldP={1,1,1,1},oldG=new float[4];
                for(int tick=0;tick<seconds*20;tick++) {
                    float t=tick*.05F;float[] input=input(scenario,t);
                    mix.tick(input[0],input[1],.05F,p);
                    derivative=Ztz99aDieselGain.smooth(derivative,(mix.rpm()-previousRpm)/.05F,.05F,.18F);previousRpm=mix.rpm();
                    cooldown=Math.max(0,cooldown-.05F);
                    blipCooldown=Math.max(0,blipCooldown-.05F);
                    if(Math.abs(derivative)>1.5F) { hadMotion=true;settled=0; } else settled+=.05F;
                    if(selection==null && cooldown==0 && Math.abs(derivative)>=12) {
                        selection=Ztz99aDieselTakes.select(derivative>0?"revv":"release",mix.rpm(),derivative,lastTake);
                        if(selection!=null) {
                            lastTake=selection.sample();shotAge=0;shotSource=0;releaseAge=-1;
                            blipCooldown=12;
                            cooldown=Math.max(5.5F,Ztz99aAudioCalibration.duration(lastTake)/selection.pitch()+.5F);
                        }
                    }
                    if(selection==null && hadMotion && settled>.35F && settled<2 && mix.rpm()>1 && blipCooldown==0) {
                        selection=Ztz99aDieselTakes.select("blips",mix.rpm(),0,lastTake);
                        if(selection!=null) { lastTake=selection.sample();shotAge=0;shotSource=0;releaseAge=-1; }
                        hadMotion=false;blipCooldown=12;
                    }
                    if(selection!=null) {
                        float length=Ztz99aAudioCalibration.duration(selection.sample());
                        float cue=Ztz99aDieselTakes.cueAt(selection.sample(),shotSource/length)*selection.pitch();
                        boolean reversed=selection.sample().contains("revv")?derivative<3:selection.sample().contains("release")?derivative>-3:false;
                        if(releaseAge<0 && (reversed || shotAge>.35F && Math.abs(Math.log(cue/Ztz99aDieselTakes.tone(mix.rpm())))>.32)) releaseAge=0;
                        float remaining=Math.max(0,(length-shotSource)/selection.pitch());
                        float category=selection.sample().contains("blips")?-13.75F:-14;
                        float g=Ztz99aDieselGain.amplitude(Ztz99aDieselGain.shotDb(selection.sample(),category,p)+selection.trimDb())
                                *Ztz99aDieselGain.shotEnvelope(shotAge,remaining)*Ztz99aDieselTakes.releaseEnvelope(releaseAge);
                        takesCsv.append(String.format(Locale.ROOT,"%.3f,%s,%.8f,%.8f%n",t,selection.sample(),selection.pitch(),g));
                        shotAge+=.05F;shotSource+=.05F*selection.pitch();if(releaseAge>=0)releaseAge+=.05F;
                        if(remaining<=0 || releaseAge>=.45F) selection=null;
                    }
                    float[] gain=new float[4],pitch=new float[4];float power=0;
                    for(int i=0;i<4;i++) {
                        gain[i]=mix.gain(i);pitch[i]=mix.pitch(i);
                        power+=(float)Math.pow(gain[i]*Ztz99aAudioCalibration.rms(LOOPS[i]),2);
                        if(tick>0) { maxPitchStep=Math.max(maxPitchStep,Math.abs(pitch[i]-oldP[i]));maxGainStep=Math.max(maxGainStep,Math.abs(gain[i]-oldG[i])); }
                        oldP[i]=pitch[i];oldG[i]=gain[i];
                    }
                    maxPeak=Math.max(maxPeak,mix.peak());
                    require(mix.peak()<.8F,"default single engine peak headroom");
                    for(float v:pitch) require(v>=.5F&&v<=2,"audible and prepared OpenAL rates");
                    float[] actualWeights=new float[4];for(int i=0;i<4;i++)actualWeights[i]=mix.weight(i);
                    append(afterCsv,t,input,mix.rpm(),mix.load(),actualWeights,pitch,gain,(float)Math.sqrt(power),mix.peak(),derivative);
                    if(before!=null) {
                        before.tick(input);append(beforeCsv,t,input,before.rpm(),before.load(),before.weights(),before.pitches(),before.gains(),0,0,0);
                    }
                }
                require(maxPitchStep<.04F && maxGainStep<.04F,"bounded tick pitch/gain changes "+scenario+" pitch="+maxPitchStep+" gain="+maxGainStep);
                Files.writeString(out.resolve(scenario+"_after.csv"),afterCsv);
                Files.writeString(out.resolve(scenario+"_takes.csv"),takesCsv);
                if(before!=null) Files.writeString(out.resolve(scenario+"_before.csv"),beforeCsv);
                System.out.printf(Locale.ROOT,"PASS %s: %.0fs, max pitch/tick %.6f, gain/tick %.6f, aligned peak %.6f%n",scenario,seconds,maxPitchStep,maxGainStep,maxPeak);
            }
        }
        Files.writeString(out.resolve("take-selection.csv"),takeTable());
    }
    private static String header() { return "time,target_rpm,target_su,rpm,su,w0,w1,w2,w3,p0,p1,p2,p3,g0,g1,g2,g3,rms_estimate,aligned_peak,derivative\n"; }
    private static void append(StringBuilder b,float t,float[] input,float rpm,float su,float[] w,float[] p,float[] g,float rms,float peak,float derivative) {
        b.append(String.format(Locale.ROOT,"%.3f,%.4f,%.4f,%.6f,%.6f",t,input[0],input[1],rpm,su));
        for(float[] a:new float[][]{w,p,g}) for(float v:a) b.append(String.format(Locale.ROOT,",%.8f",v));
        b.append(String.format(Locale.ROOT,",%.8f,%.8f,%.6f%n",rms,peak,derivative));
    }
    private static String takeTable() {
        var b=new StringBuilder("family,rpm,speed,sample,pitch,trim_db,score,duration\n");
        for(String family:new String[]{"revv","release","blips"}) for(float speed:new float[]{5,15,35,50}) for(int r=0;r<=100;r+=5) {
            var s=Ztz99aDieselTakes.select(family,r,family.equals("release")?-speed:speed,"");
            if(s!=null) b.append(String.format(Locale.ROOT,"%s,%d,%.1f,%s,%.6f,%.4f,%.6f,%.6f%n",family,r,speed,s.sample(),s.pitch(),s.trimDb(),s.score(),Ztz99aAudioCalibration.duration(s.sample())/s.pitch()));
        }
        return b.toString();
    }
    private static final class Before implements AutoCloseable {
        final URLClassLoader loader; final Class<?> stateClass;final Object params;final Method tick,rpm,load,gain,weight,pitch;Object state;
        Before(Path jar) throws Exception {
            loader=new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},ClassLoader.getPlatformClassLoader());
            String prefix="com.hooya.stabilizedturret.client.";
            var gainClass=loader.loadClass(prefix+"Ztz99aDieselGain");params=gainClass.getField("DEFAULTS").get(null);
            stateClass=loader.loadClass(prefix+"Ztz99aDieselGain$State");
            tick=stateClass.getMethod("tick",float.class,float.class,float.class,params.getClass());rpm=stateClass.getMethod("rpm");load=stateClass.getMethod("load");gain=stateClass.getMethod("gain",int.class);
            var profile=loader.loadClass(prefix+"Ztz99aDieselSoundProfile");weight=profile.getMethod("weights",float.class);pitch=profile.getMethod("pitch",int.class,float.class,float.class);
        }
        void reset() throws Exception { state=stateClass.getConstructor().newInstance(); }
        void tick(float[] input) throws Exception { tick.invoke(state,input[0],input[1],.05F,params); }
        float rpm() throws Exception { return (float)rpm.invoke(state); }float load() throws Exception { return (float)load.invoke(state); }
        float[] weights() throws Exception { return (float[])weight.invoke(null,rpm()); }
        float[] pitches() throws Exception { float[] a=new float[4];for(int i=0;i<4;i++) a[i]=(float)pitch.invoke(null,i,rpm(),load());return a; }
        float[] gains() throws Exception { float[] a=new float[4];for(int i=0;i<4;i++) a[i]=(float)gain.invoke(state,i);return a; }
        public void close() throws Exception { loader.close(); }
    }
}
