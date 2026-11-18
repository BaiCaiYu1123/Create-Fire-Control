package com.hooya.stabilizedturret.client;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Tests the same pure mixer used by the live sound controller, with measured source peaks. */
public final class Ztz99aDieselGainChecks {
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static void close(float a, float b, String label) { require(Math.abs(a-b)<.00001F,label+": "+a+" / "+b); }
    public static void run() throws Exception {
        var p = Ztz99aDieselGain.DEFAULTS;
        try (var in = Ztz99aDieselGainChecks.class.getResourceAsStream(
                "/assets/create_fire_control/sounds/engine/ztz99a/gain_reference.json")) {
            var json = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in),StandardCharsets.UTF_8)).getAsJsonObject();
            for (String name : new String[]{"layer00","layer06"}) {
                var points = json.getAsJsonObject("curves").getAsJsonObject(name).getAsJsonArray("points");
                var c = name.equals("layer00") ? p.idle() : p.loaded();
                var first = points.get(0).getAsJsonObject(); var last = points.get(1).getAsJsonObject();
                close(c.startLoad(),first.get("load").getAsFloat(),"XML start load");
                close(c.endLoad(),last.get("load").getAsFloat(),"XML end load");
                close(c.shape(),first.get("curveShape").getAsFloat(),"XML shape retained");
                close(c.db(c.startLoad()),first.get("db").getAsFloat(),"XML first gain");
                close(c.db(c.endLoad()),last.get("db").getAsFloat(),"XML last gain");
            }
        }
        close(p.idle().db(1),-80,"idle loaded end"); close(p.loaded().db(1),6,"loaded plateau");
        try (var in=Ztz99aDieselGainChecks.class.getResourceAsStream(
                "/assets/create_fire_control/sounds/engine/ztz99a/native_load_response.json")) {
            var reference=JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in),StandardCharsets.UTF_8)).getAsJsonObject();
            for (String name:new String[]{"layer00","layer06"}) {
                var values=reference.getAsJsonArray(name);require(values.size()==1001,"native FMOD sampling density");
                var curve=name.equals("layer00")?p.idle():p.loaded();
                for(int i=0;i<values.size();i++) close(curve.db(i/1000F*curve.endLoad()),
                        values.get(i).getAsFloat(),"native response dB point "+name+" "+i);
            }
        }
        close(new Ztz99aDieselGain.Curve(0,0,1,6,0).db(.5F),3,"zero shape is dB-linear");
        for(var authored:new Ztz99aDieselGain.Curve[]{p.idle(),p.loaded()}) {
            for(float offset:new float[]{-.00001F,.00001F}) {
                var tuned=new Ztz99aDieselGain.Curve(authored.startLoad(),authored.startDb(),
                        authored.endLoad(),authored.endDb(),authored.shape()+offset);
                require(Math.abs(tuned.db(.5F)-authored.db(.5F))<.01F,"continuous shape tuning around native default");
            }
        }
        require(p.idle().db(.5F)>-39 && p.loaded().db(.5F)>3,"authored acceleration directions");
        require(Math.abs(Ztz99aDieselGain.amplitude(p.idle().db(.5F))
                -(Ztz99aDieselGain.amplitude(2)+Ztz99aDieselGain.amplitude(-80))/2)>.01F,"not linear amplitude substitution");
        float lastIdle=2,lastLoaded=0;
        for (int l=0;l<=10000;l++) {
            float load=l/10000F, idle=p.idle().db(load), loaded=p.loaded().db(load);
            require(idle<=lastIdle+.00001F && loaded>=lastLoaded-.00001F,"monotone load curves");
            require(idle>=-80 && idle<=2 && loaded>=0 && loaded<=6,"no automation overshoot");
            lastIdle=idle;lastLoaded=loaded;
        }
        for (String sample : Ztz99aAudioCalibration.SAMPLES) {
            float gain=Ztz99aDieselGain.amplitude(Ztz99aAudioCalibration.gainDb(sample));
            require(gain*Ztz99aAudioCalibration.rms(sample)<=.120001F,"calibrated RMS "+sample);
            require(gain*Ztz99aAudioCalibration.peak(sample)<=.600001F,"calibrated peak "+sample);
        }
        // 1,002,001 stable points, including all overlaps, both load endpoints and the loaded plateau.
        float maxRms=0,maxRawPeak=0,maxLimitedPeak=0,maxRpmStep=0,maxLoadStep=0;
        float[] previousLoad=new float[1001];
        float shotPeak=0;
        for (String sample:Ztz99aAudioCalibration.SAMPLES) if (!Arrays.asList(Ztz99aDieselSoundProfile.LOOPS).contains(sample)) {
            float category=sample.startsWith("engine_blips") ? -13.75F
                    : sample.equals("engine_start")||sample.equals("engine_stop") ? -4 : -10;
            shotPeak=Math.max(shotPeak,Ztz99aDieselGain.amplitude(Ztz99aDieselGain.shotDb(sample,category,p))
                    *Ztz99aAudioCalibration.peak(sample));
        }
        var csv=new StringBuilder("rpm_percent,su_load,idle_automation_db,loaded_automation_db,idle_gain,low_gain,mid_gain,high_gain,rms,raw_peak_with_2_shots,limited_peak\n");
        for (int l=0;l<=1000;l++) {
            float previousRpm=0;
            for (int r=0;r<=1000;r++) {
                float load=l/1000F,rpm=r/10F;
                float[] weights=Ztz99aDieselSoundProfile.weights(rpm),gains=new float[4];float power=0;
                for(int i=0;i<4;i++) {
                    gains[i]=weights[i]*Ztz99aDieselGain.amplitude(Ztz99aDieselGain.loopDb(i,load,p));
                    require(Float.isFinite(gains[i]) && gains[i]>=0,"finite gain");
                    float rms=gains[i]*Ztz99aAudioCalibration.rms(Ztz99aDieselSoundProfile.LOOPS[i]);power+=rms*rms;
                }
                float rms=(float)Math.sqrt(power),peak=(Ztz99aDieselGain.loopPeak(gains)+2*shotPeak)*3;
                float limited=peak*Ztz99aDieselGain.headroom(peak,.8F);
                require(limited<=.800001F,"GUI 300%, continuous loops, two simultaneous shots headroom");
                maxRms=Math.max(maxRms,rms);maxRawPeak=Math.max(maxRawPeak,peak);maxLimitedPeak=Math.max(maxLimitedPeak,limited);
                if(r>0) maxRpmStep=Math.max(maxRpmStep,Math.abs(rms-previousRpm));
                if(l>0) maxLoadStep=Math.max(maxLoadStep,Math.abs(rms-previousLoad[r]));
                previousRpm=rms;previousLoad[r]=rms;
                if(l%50==0 && r%10==0) csv.append(String.format(Locale.ROOT,
                        "%.1f,%.3f,%.6f,%.6f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f%n",rpm,load,p.idle().db(load),p.loaded().db(load),
                        gains[0],gains[1],gains[2],gains[3],rms,peak,limited));
            }
        }
        float bedBound=.12F*Ztz99aDieselGain.amplitude(p.masterDb()+2);
        float loadBound=.12F*Ztz99aDieselGain.amplitude(p.masterDb()+6);
        require(maxRms<=(float)Math.hypot(bedBound,loadBound)+.000001F,"idle bed plus unit-power load group bound: "+maxRms);
        // Native low-in has a ~2.74 slope at its silent endpoint (not smoothstep).
        require(maxRpmStep<loadBound*3/9*.101F && maxLoadStep<.001F,
                "dense gain continuity: RPM step="+maxRpmStep+", SU step="+maxLoadStep);
        var a=new Ztz99aDieselGain.State();var b=new Ztz99aDieselGain.State();
        a.tick(60,0,.05F,p);b.tick(60,1,.05F,p);
        close(a.rpm(),b.rpm(),"SU does not drive RPM");require(b.gain(2)>a.gain(2),"SU drives loaded gain independently");
        a=new Ztz99aDieselGain.State();b=new Ztz99aDieselGain.State();
        a.tick(10,.5F,.05F,p);b.tick(90,.5F,.05F,p);close(a.load(),b.load(),"RPM does not drive SU");
        var state=new Ztz99aDieselGain.State();state.tick(0,0,.05F,p);
        float maxDbTick=0;
        for(int t=0;t<1200;t++) {
            float targetRpm=t<400?100:t<800?0:100,load=t<400?1:t<800?0:1;
            float[] before=new float[4];for(int i=0;i<4;i++)before[i]=state.db(i);
            state.tick(targetRpm,load,.05F,p);
            for(int i=0;i<4;i++) {
                float delta=Math.abs(state.db(i)-before[i]);maxDbTick=Math.max(maxDbTick,delta);
                require(delta<=1.20001F,"24 dB/s automation slew");
            }
            require(state.rpm()>=0&&state.rpm()<=100&&state.load()>=0&&state.load()<=1,"temporal input smoothing");
        }
        var hot=new Ztz99aDieselGain.Parameters(24,24,.25F,1.975F,.2F,24,p.idle(),p.loaded(),Map.of("engine_99_load",24F));
        float before=state.db(3);state.tick(100,1,.05F,hot);require(Math.abs(state.db(3)-before)<=1.20001F,"config hot reload gain slew");
        require(state.peak()*3*Ztz99aDieselGain.headroom(state.peak()*3,.8F)<=.800001F,"hot tuning remains bounded");
        close(Ztz99aDieselGain.shotEnvelope(0,2),0,"shot silent onset");
        close(Ztz99aDieselGain.shotEnvelope(2,0),0,"shot silent tail");
        var report=new JsonObject();report.addProperty("stableGridPoints",1002001);report.addProperty("maxRmsAtGui100",maxRms);
        report.addProperty("maxRawPeakAtGui300WithContinuousLoopsAndTwoShots",maxRawPeak);report.addProperty("maxLimitedPeak",maxLimitedPeak);
        report.addProperty("maxRmsChangePerRpmPoint1",maxRpmStep);report.addProperty("maxRmsChangePerLoadPoint001",maxLoadStep);
        report.addProperty("maxDbChangePerTick",maxDbTick);report.addProperty("interpolation","1001 native FMOD response points per curve, normalized to XML endpoints; custom shapes smoothly blend/warp tables (approximate)");
        Path directory=Path.of("build/reports/ztz99a-gain");Files.createDirectories(directory);
        Files.writeString(directory.resolve("sweep.csv"),csv);Files.writeString(directory.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("PASS: 1,002,001 RPM/SU combinations, XML endpoints, per-file calibration, temporal slew and peak budget\n"+report);
    }
    private Ztz99aDieselGainChecks() {}
}
