package com.hooya.stabilizedturret.client;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Audible invariants and reproducible traces, without a unit-power assumption in Reference. */
public final class TigerIResourceChecks {
    private static final TigerISoundConfig.Mode REF=TigerISoundConfig.Mode.FMOD_REFERENCE, OLD=TigerISoundConfig.Mode.LEGACY;
    public static void main(String[] args) throws Exception {
        require(TigerISoundConfig.mode()==REF,"default Reference mode");
        for(int i=0;i<7;i++)close(TigerISoundProfile.nativePitch(i,TigerISoundProfile.ROOT[i]),1,.000001F,"AutoPitch root");
        for(float rpm:new float[]{0,8,20,38,50,72,90,100}) {
            var a=new TigerIGain.State();var b=new TigerIGain.State();
            for(int tick=0;tick<160;tick++){step(a,rpm,0,REF,false);step(b,rpm,1,REF,false);}
            for(int i=0;i<7;i++) {
                close(a.gain(i),b.gain(i),.000001F,"SU must not replace Reference tracks");
                close(a.pitch(i),b.pitch(i),.000001F,"SU must not change Reference pitch");
                close(a.gain(i),TigerISoundProfile.referenceWeights(rpm)[i]*TigerIGain.amplitude(
                        TigerIGain.referenceLoopDb(i,rpm,TigerIGain.REFERENCE_DEFAULTS)),.000001F,"only authored fade and gains");
            }
        }
        close(TigerIGain.referenceLoopDb(0,0,TigerIGain.REFERENCE_DEFAULTS),-13,.00001F,"serial healthy idle routing incl. premaster");
        close(TigerIGain.referenceShotDb("revv-001",0,TigerIGain.REFERENCE_DEFAULTS),-10,.00001F,"authored revv gains, no RMS calibration");
        float[] last=null;
        for(int k=0;k<=10000;k++) {
            float[] w=TigerISoundProfile.referenceWeights(k*.01F);
            for(int i=0;i<7;i++) {
                require(Float.isFinite(w[i])&&w[i]>=0&&w[i]<=1.001,"Reference fade bounds");
                if(last!=null)require(Math.abs(w[i]-last[i])<.025,"no fade discontinuity");
                float p=TigerISoundProfile.referencePitch(i,k*.01F,1);
                require(p>=.5F&&p<=2,"audible climb fits Minecraft pitch range");
            }
            last=w;
        }
        close(TigerISoundProfile.climbSemitones(0),0,.00001F,"climb bottom");
        close(TigerISoundProfile.climbSemitones(.5F),3,.00001F,"climb midpoint");
        close(TigerISoundProfile.climbSemitones(1),6,.00001F,"climb top");
        for(int i=0;i<7;i++)close(TigerISoundProfile.referencePitch(i,50,1)/TigerISoundProfile.pitch(i,50,0),
                i<4?1:(float)Math.sqrt(2),.00001F,"climb only affects three loaded suffix tracks");
        var climb=new TigerIGain.State();step(climb,0,0,REF,true);
        for(int k=1;k<=40;k++)step(climb,k*2,0,REF,true);
        require(climb.climb()>.9,"fast acceleration reaches climb");
        float before=climb.climb();step(climb,80,0,REF,true);
        require(climb.climb()>before-.1,"no sudden pitch fall");
        for(int k=0;k<80;k++)step(climb,80,0,REF,true);
        require(climb.climb()<.01,"climb returns at stable RPM");
        var gate=new TigerITakes.ReferenceGate();int triggers=0;
        for(int k=0;k<100;k++)if(gate.tick(1,.05F))triggers++;
        require(triggers==1,"one loadswitch entry without periodic spam");
        gate.tick(0,.05F);close(gate.value(),.8F,.00001F,"descending seek 4/s");
        for(int k=0;k<6;k++)gate.tick(0,.05F);
        for(int k=0;k<6;k++)if(gate.tick(1,.05F))triggers++;
        require(triggers==2,"reentry without four second cooldown");
        close(TigerISpatialAudio.gain(3,1),1,.00001F,"near volume unchanged");
        require(TigerISpatialAudio.gain(16,1)<.5F,"clear attenuation within sixteen blocks");
        require(TigerISpatialAudio.gain(32,1)>.1F&&TigerISpatialAudio.gain(32,1)<.25F,"quieter but audible beyond old cutoff");
        require(TigerISpatialAudio.gain(100,1)<.1F,"distant engine stays quiet");
        float previousGain=1;
        for(int k=0;k<=2200;k++) {
            float distance=k*.1F,gain=TigerISpatialAudio.gain(distance,1);
            require(Float.isFinite(gain)&&gain>=0&&gain<=previousGain,"bounded monotonic distance rolloff");
            require(previousGain-gain<.02F,"continuous distance rolloff");
            close(TigerISpatialAudio.gain(distance*2,2),gain,.00001F,"distance scale preserves rolloff");
            previousGain=gain;
        }
        close(TigerISpatialAudio.gain(215,1),0,.00001F,"bank effective cutoff");
        close(TigerISpatialAudio.highFrequencyGain(3,1,-12),1,.00001F,"near HF unchanged");
        for(float load:new float[]{0,.25F,.5F,.75F,1}) {
            var old=new TigerIGain.State();
            for(int k=0;k<=1000;k++) {
                step(old,k*.1F,load,OLD,false);float power=0;
                for(int i=0;i<7;i++)power+=old.weight(i)*old.weight(i);
                close(power,1,.0002F,"Legacy equal-power preserved");
            }
        }
        var invalid=new TigerIGain.State();invalid.tick(Float.NaN,Float.POSITIVE_INFINITY,Float.NaN,TigerIGain.REFERENCE_DEFAULTS);
        for(int i=0;i<7;i++)require(Float.isFinite(invalid.gain(i))&&Float.isFinite(invalid.pitch(i)),"invalid input safe");
        JsonObject sounds=json("sounds.json");int count=0;
        for(String sample:TigerIAudioCalibration.SAMPLES) {
            if(sample.equals("idle_not_load"))continue;
            var sound=sounds.getAsJsonObject("engine.tiger_i."+sample).getAsJsonArray("sounds").get(0).getAsJsonObject();
            require(!sound.get("stream").getAsBoolean()&&sound.get("preload").getAsBoolean(),"static buffer");
            try(var input=TigerIResourceChecks.class.getResourceAsStream("/assets/create_fire_control/sounds/engine/tiger_i/"+sample+".ogg")) {
                require(input!=null&&new String(input.readNBytes(4),StandardCharsets.US_ASCII).equals("OggS"),"Ogg resource");
            }
            count++;
        }
        require(count==30&&!sounds.has("engine.tiger_i.idle_not_load"),"audible exterior resources");
        traces();
        System.out.println("PASS Tiger I Reference / Legacy: SU invariance, gains, fades, climb, loadswitch, spatial, resources; ten A/B scenarios");
    }
    private static void step(TigerIGain.State s,float r,float l,TigerISoundConfig.Mode m,boolean c) {
        s.tick(r,l,.05F,m==REF?TigerIGain.REFERENCE_DEFAULTS:TigerIGain.DEFAULTS,m,c,35,.12F,.65F);
    }
    private record Shot(String sample,float pitch,float db,float began) {}
    private static void traces() throws IOException {
        Path out=Path.of("build/reports/tiger_i_fix");Files.createDirectories(out);
        for(var mode:new TigerISoundConfig.Mode[]{REF,OLD})for(String scenario:List.of(
                "idle","low","medium","high","slow_acceleration","fast_acceleration","deceleration","load_sweep","stable_60s","start_stop")) {
            List<String> rows=new ArrayList<>();var state=new TigerIGain.State();var gate=new TigerITakes.ReferenceGate();
            var random=new Random(0x5449474552L);var shots=new ArrayList<Shot>();
            float oldRpm=0,derivative=0,cooldown=0,envelope=1,startAge=0;
            boolean wasEnabled=!scenario.equals("start_stop");String last="";
            String header="time,targetRpm,rpm,su,climb,loadswitch,enabled,envelope,headroom,predictedAllocatedSources,shots";
            for(int i=0;i<7;i++)header+=",w"+i+",db"+i+",g"+i+",p"+i;rows.add(header);
            float seconds=scenario.equals("stable_60s")?60:scenario.equals("slow_acceleration")?40:scenario.equals("start_stop")?18:14;
            for(int k=0;k<seconds*20;k++) {
                float t=k*.05F;boolean enabled=!scenario.equals("start_stop")||t>=1&&t<13;
                float input=switch(scenario) {
                    case "idle" -> 0;case "low" -> 20;case "medium","load_sweep" -> 50;case "high" -> 90;
                    case "slow_acceleration" -> Math.max(0,Math.min(100,(t-2)*4));
                    case "fast_acceleration" -> Math.max(0,Math.min(100,(t-2)*40));
                    case "deceleration" -> Math.max(0,90-Math.max(0,t-2)*12);
                    case "stable_60s" -> 60;default -> enabled?Math.max(0,Math.min(30,(t-1)*8)):0;
                };
                float demandTarget=scenario.contains("acceleration")&&t>=2?100:scenario.equals("start_stop")&&enabled?30:input;
                float load=scenario.equals("load_sweep")?Math.min(1,t/12):.5F;step(state,input,load,mode,true);
                derivative=TigerIGain.smooth(derivative,(state.rpm()-oldRpm)/.05F,.05F,.18F);oldRpm=state.rpm();
                float now=t;shots.removeIf(s->(now-s.began)*s.pitch>=TigerIAudioCalibration.duration(s.sample));
                cooldown=Math.max(0,cooldown-.05F);
                if(enabled!=wasEnabled) {
                    shots.clear();String sample=enabled?"start":"stop";
                    shots.add(new Shot(sample,1,mode==REF?-16:TigerIGain.shotDb(sample,-4,TigerIGain.DEFAULTS),t));
                    if(enabled){startAge=0;envelope=0;}wasEnabled=enabled;
                }
                if(scenario.equals("start_stop")&&enabled&&startAge<TigerIAudioCalibration.duration("start")) {
                    envelope=TigerISoundProfile.startupEnvelope(startAge,TigerIAudioCalibration.duration("start"));startAge+=.05F;
                } else envelope=TigerISoundProfile.clamp(envelope+(enabled?.125F:-.05F/.35F),0,1);
                boolean enter=gate.tick(enabled?Math.max(0,(demandTarget-input)/25):0,.05F);
                if(mode==REF&&enter&&enabled&&!scenario.equals("start_stop")&&shots.size()<2) {
                    var s=TigerITakes.referenceRevv(random,last,1.2F);last=s.sample();shots.add(new Shot(last,s.pitch(),-10,t));
                } else if(mode==OLD&&enabled&&cooldown==0&&shots.isEmpty()&&derivative>=15) {
                    var s=TigerITakes.select("revv",state.rpm(),state.load(),derivative,last);
                    if(s!=null) {
                        last=s.sample();shots.add(new Shot(last,s.pitch(),TigerIGain.shotDb(last,-14,TigerIGain.DEFAULTS)+s.trimDb(),t));
                        cooldown=Math.max(4,TigerIAudioCalibration.duration(last)/s.pitch()+.5F);
                    }
                }
                float peak=0;
                for(int i=0;i<7;i++)peak+=state.gain(i)*envelope*TigerIAudioCalibration.peak(TigerISoundProfile.LOOPS[i]);
                for(var s:shots)peak+=TigerIGain.amplitude(s.db)*TigerIAudioCalibration.peak(s.sample);
                float headroom=mode==REF?1:TigerIGain.headroom(peak*1.5F,.8F);
                String shotText=shots.stream().map(s->s.sample+":"+s.pitch+":"+s.db+":"+s.began).collect(java.util.stream.Collectors.joining(";"));
                String row=String.format(Locale.ROOT,"%.3f,%.5f,%.5f,%.5f,%.6f,%.6f,%s,%.6f,%.6f,%d,%s",
                        t,input,state.rpm(),state.load(),state.climb(),gate.value(),enabled,envelope,headroom,
                        (enabled||envelope>0?7:0)+shots.size(),shotText);
                for(int i=0;i<7;i++)row+=String.format(Locale.ROOT,",%.8f,%.6f,%.9f,%.8f",state.weight(i),
                        state.gain(i)>0?20*Math.log10(state.gain(i)):-100,state.gain(i)*envelope*headroom,state.pitch(i));
                rows.add(row);
            }
            Files.write(out.resolve(mode.name().toLowerCase(Locale.ROOT)+"_"+scenario+"_tracks.csv"),rows);
        }
    }
    private static JsonObject json(String name)throws Exception {
        try(var reader=new InputStreamReader(Objects.requireNonNull(TigerIResourceChecks.class.getResourceAsStream("/assets/create_fire_control/"+name)),StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    private static void close(float a,float b,float t,String label){require(Math.abs(a-b)<t,label+": "+a+" != "+b);}
    private static void require(boolean v,String label){if(!v)throw new AssertionError(label);}
}
