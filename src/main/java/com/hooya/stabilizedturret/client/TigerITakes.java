package com.hooya.stabilizedturret.client;

import static com.hooya.stabilizedturret.client.TigerISoundProfile.*;

/** Deterministic, state-matched embellishments. These cues never control kinetic speed. */
public final class TigerITakes {
    public record Selection(String sample,float pitch,float trimDb,float score) {}
    /** One trigger on entry to the authored loadswitch instrument region; no time cooldown. */
    public static final class ReferenceGate {
        private float value;
        private boolean inside;
        public boolean tick(float demand,float dt) {
            demand=clamp(demand,-1,1);dt=clamp(dt,0,.25F);
            value+=clamp(demand-value,-4*dt,2*dt);
            boolean next=value>=.1F;
            boolean entered=next&&!inside;inside=next;return entered;
        }
        public float value(){return value;}
    }
    public static Selection referenceRevv(java.util.Random random,String last,float semitones) {
        int choice;
        do {choice=1+random.nextInt(5);}while(("revv-00"+choice).equals(last));
        return new Selection("revv-00"+choice,(float)Math.pow(2,(random.nextFloat()*2-1)*semitones/12),0,0);
    }

    /** Power-weighted tonal cue of the active recordings at their independent AutoPitch. */
    public static float tone(float rpm,float load) {
        float[] w=weights(rpm); float power=0,sum=0;
        // Measured cues are matching hints, not asserted physical RPM.
        for(int i=0;i<7;i++) {
            float utilization=clamp(load,0,1);
            float family=i<4?(float)Math.cos(utilization*Math.PI/2):(float)Math.sin(utilization*Math.PI/2);
            if(rpm<15 && i==0)family=1;
            float a=w[i]*w[i]*family*family; power+=a;
            sum+=a*TigerIAudioCalibration.cue(LOOPS[i])[2]*pitch(i,rpm,0);
        }
        return power>.01F ? sum/power : TigerIAudioCalibration.cue(LOOPS[0])[2];
    }
    public static Selection select(String family,float rpm,float load,float rpmPerSecond,String lastSample) {
        int count=switch(family) { case "revv" -> 5; case "release" -> 7; case "blips" -> 9; default -> 0; };
        Selection best=null;
        for(int i=1;i<=count;i++) {
            String sample=family+String.format(java.util.Locale.ROOT,"-%03d",i);
            float[] cue=TigerIAudioCalibration.cue(sample);
            float rate=clamp(tone(rpm,load)/cue[0],.85F,1.15F);
            float duration=TigerIAudioCalibration.duration(sample)/rate;
            float expected=tone(clamp(rpm+rpmPerSecond*duration*.35F,0,100),load);
            float onsetError=Math.abs((float)Math.log(cue[0]*rate/tone(rpm,load)));
            float trajectoryError=Math.abs((float)Math.log(cue[2]*rate/expected));
            float directionPenalty=family.equals("release") ? Math.max(0,cue[2]-cue[0])/50
                    : family.equals("revv") ? Math.max(0,cue[0]-cue[2])/50 : 0;
            float irregularity=0;
            for(int k=1;k<4;k++) irregularity+=Math.max(0,Math.abs(cue[k]-cue[k-1])-22)/70;
            float score=onsetError+trajectoryError*.65F+directionPenalty+irregularity
                    +(sample.equals(lastSample) ? .025F : 0);
            float confidence=TigerIAudioCalibration.confidence(sample);
            if(onsetError>.23F || score>.65F) continue; // Prefer loops over a badly matched take.
            float trim=-Math.min(8,score*10+(1-confidence)*6);
            if(best==null || score<best.score()) best=new Selection(sample,rate,trim,score);
        }
        return best;
    }
    /** Natural trajectory in the source; used to fade out instead of continually retuning it. */
    public static float cueAt(String sample,float sourceFraction) {
        float[] cues=TigerIAudioCalibration.cue(sample);
        float[] fractions={.12F,.3F,.5F,.7F,.88F};
        for(int i=1;i<5;i++) if(sourceFraction<fractions[i]) {
            float t=clamp((sourceFraction-fractions[i-1])/(fractions[i]-fractions[i-1]),0,1);
            return cues[i-1]+(cues[i]-cues[i-1])*t;
        }
        return cues[4];
    }
    public static float releaseEnvelope(float age) {
        if(age<0) return 1;
        float t=clamp(age/.45F,0,1);return 1-t*t*(3-2*t);
    }
    private TigerITakes() {}
}
