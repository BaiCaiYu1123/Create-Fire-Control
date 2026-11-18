package com.hooya.stabilizedturret.client;

import java.util.Map;
import static com.hooya.stabilizedturret.client.TigerISoundProfile.*;

/** dB automation and audio-only smoothing. Does not write any kinetic state. */
public final class TigerIGain {
    public record Parameters(float masterDb,float loopDb,float rpmSeconds,float loadSeconds,float gainSeconds,
            float maxDbPerSecond,float rpmSeekPerSecond,float pitchSeconds,float loadExponent,
            float unloadedDb,float loadedDb,Map<String,Float> trimsDb) {
        public float sampleDb(String sample) { return clamp(trimsDb.getOrDefault(sample,0F),-80,24); }
    }
    public static final Parameters DEFAULTS=new Parameters(-12,0,.18F,.5F,.12F,24,100,.08F,1,-2,6,Map.of());
    public static final Parameters REFERENCE_DEFAULTS=new Parameters(0,0,.18F,.5F,.12F,24,100,.08F,1,-2,6,Map.of());
    public static float amplitude(float db) { return (float)Math.pow(10,clamp(db,-100,24)/20); }
    /** User-measured whole-loop loudness correction at normalized RPM 0, 20, 50 and 90.
     * Applied equally after the Reference mix; smoothstep keeps knot slopes continuous. */
    public static float rpmLoudnessCompensationDb(float rpm) {
        float r=clamp(rpm,0,100),start,end,from,to;
        if(r<=20) { start=0;end=20;from=4.9F;to=7; }
        else if(r<=50) { start=20;end=50;from=7;to=3.4F; }
        else { start=50;end=90;from=3.4F;to=1.7F; }
        float t=clamp((r-start)/(end-start),0,1);
        return from+(to-from)*t*t*(3-2*t);
    }
    public static float smooth(float current,float target,float dt,float seconds) {
        return current+(target-current)*(float)-Math.expm1(-Math.max(0,dt)/Math.max(.001F,seconds));
    }
    public static float smoothDb(float current,float target,float dt,Parameters p) {
        return current+clamp(smooth(current,target,dt,p.gainSeconds)-current,-p.maxDbPerSecond*dt,p.maxDbPerSecond*dt);
    }
    public static float loopDb(int layer,float rpm,float load,Parameters p) {
        // Healthy FMOD group values. SU family selection is an explicit Minecraft mapping.
        float group=layer<4?p.unloadedDb:p.loadedDb;
        // Event master +2, engines_enemies -8; parent enemy bus -16 is a WT game mix setting,
        // not carried into the standalone per-engine presentation gain.
        return p.masterDb+p.loopDb+p.sampleDb(LOOPS[layer])+STATIC_DB[layer]+calibrationDb(layer)
                +group+2-8+rpmCompensatorDb(rpm);
    }
    public static float referenceLoopDb(int layer,float rpm,Parameters p) {
        // Healthy broken=0 replaces group base -3.02, it does not add to it.
        // Distinct serial faders multiply amplitudes: event +2, engines_enemies -8, enemy -16.
        return p.masterDb+p.loopDb+p.sampleDb(LOOPS[layer])+STATIC_DB[layer]
                +(layer<4?-2:6)+2-8-16+6+rpmCompensatorDb(rpm);
    }
    public static float referenceShotDb(String sample,float categoryDb,Parameters p) {
        // Premaster near/front automation: Distance +4 and Direction +2.
        // Revv layer: Tank_AI_Load_Speed_Gain automated +8 replaces base +6; local Gain -2.
        return p.masterDb+categoryDb+p.sampleDb(sample)+2-8-16+6+(sample.startsWith("revv")?8-2:0);
    }
    public static float shotDb(String sample,float categoryDb,Parameters p) {
        float calibration=clamp(20*(float)Math.log10(.14F/Math.max(.0001F,TigerIAudioCalibration.rms(sample))),-12,12);
        return p.masterDb+categoryDb+p.sampleDb(sample)+calibration;
    }
    public static float loopPeak(float[] gains) {
        float peak=0;for(int i=0;i<7;i++)peak+=gains[i]*TigerIAudioCalibration.peak(LOOPS[i]);return peak;
    }
    public static float headroom(float peak,float ceiling) { return peak>0?Math.min(1,ceiling/peak):1; }
    public static float shotEnvelope(float age,float remaining) {
        float t=clamp(Math.min(age/.15F,remaining/.35F),0,1);return t*t*(3-2*t);
    }
    public static final class State {
        private boolean initialized;
        private float rpm,load,climb,acceleration;
        private final float[] db=new float[7],gains=new float[7],pitches=new float[7],weights=new float[7];
        public void tick(float targetRpm,float actualLoad,float dt,Parameters p) {
            tick(targetRpm,actualLoad,dt,p,TigerISoundConfig.Mode.FMOD_REFERENCE,true,35,.12F,.65F);
        }
        public void tick(float targetRpm,float actualLoad,float dt,Parameters p,TigerISoundConfig.Mode mode,
                         boolean useClimb,float fullAcceleration,float attack,float release) {
            targetRpm=clamp(targetRpm,0,100);actualLoad=clamp(actualLoad,0,1);dt=clamp(dt,0,.25F);
            boolean reference=mode==TigerISoundConfig.Mode.FMOD_REFERENCE;
            float previous=rpm;
            if(!initialized) {
                initialized=true;rpm=targetRpm;load=actualLoad;
                previous=rpm;
                for(int i=0;i<7;i++){db[i]=reference?referenceLoopDb(i,rpm,p):loopDb(i,rpm,load,p);pitches[i]=TigerISoundProfile.pitch(i,rpm,load);}
            } else {
                rpm=reference?Math.max(targetRpm,rpm-100*dt):rpm+clamp(smooth(rpm,targetRpm,dt,p.rpmSeconds)-rpm,-p.rpmSeekPerSecond*dt,p.rpmSeekPerSecond*dt);
                load=smooth(load,actualLoad,dt,p.loadSeconds);
            }
            acceleration=smooth(acceleration,dt>0?(rpm-previous)/dt:0,dt,.15F);
            float climbTarget=reference&&useClimb?clamp(acceleration/Math.max(1,fullAcceleration),0,1):0;
            climb=smooth(climb,climbTarget,dt,climbTarget>climb?attack:release);
            float[] raw=reference?referenceWeights(rpm):TigerISoundProfile.weights(rpm);
            float utilization=(float)Math.pow(clamp(load,0,1),p.loadExponent);
            float unloaded=(float)Math.cos(utilization*Math.PI/2),loaded=(float)Math.sin(utilization*Math.PI/2);
            // Preserve idle at every SU value before the loaded family enters.
            float entrance=TigerIFmodFade.value(6,(rpm-1.43498F)/(15-1.43498F));
            unloaded=(float)Math.sqrt(1-loaded*loaded*entrance*entrance);
            for(int i=0;i<7;i++) {
                db[i]=reference?referenceLoopDb(i,rpm,p):smoothDb(db[i],loopDb(i,rpm,load,p),dt,p);
                weights[i]=reference?raw[i]:raw[i]*(i<4?unloaded:loaded);
                gains[i]=weights[i]*amplitude(db[i]);
                float targetPitch=reference?referencePitch(i,rpm,climb):TigerISoundProfile.pitch(i,rpm,load);
                pitches[i]=(float)Math.exp(smooth((float)Math.log(pitches[i]),(float)Math.log(targetPitch),dt,p.pitchSeconds));
            }
        }
        public float climb(){return climb;} public float acceleration(){return acceleration;}
        public float rpm(){return rpm;} public float load(){return load;}
        public float gain(int i){return gains[i];} public float db(int i){return db[i];}
        public float pitch(int i){return pitches[i];} public float weight(int i){return weights[i];}
    }
    private TigerIGain() {}
}
