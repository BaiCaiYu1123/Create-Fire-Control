package com.hooya.stabilizedturret.client;

import static com.hooya.stabilizedturret.client.Ztz99aDieselSoundProfile.*;

/** Deterministic, state-matched embellishments. These cues never control kinetic speed. */
public final class Ztz99aDieselTakes {
    public enum Audition { LOOPS_ONLY, LOOPS_REVV, LOOPS_RELEASE, FULL }
    public record Selection(String sample, float pitch, float trimDb, float score) {}

    public static boolean allowed(Audition mode,String family) {
        return mode==Audition.FULL || mode==Audition.LOOPS_REVV && family.equals("revv")
                || mode==Audition.LOOPS_RELEASE && family.equals("release");
    }
    /** Power-weighted tonal cue of the active recordings at their independent AutoPitch. */
    public static float tone(float rpm) {
        float[] w=weights(rpm); float power=0,sum=0;
        // Idle is a load-dependent bed, so match the RPM instruments once they enter.
        for(int i=1;i<4;i++) {
            float a=w[i]*w[i]; power+=a;
            sum+=a*Ztz99aTakeCalibration.cue(LOOPS[i])[2]*pitch(i,rpm,0);
        }
        return power>.01F ? sum/power : Ztz99aTakeCalibration.cue(LOOPS[0])[2];
    }
    public static Selection select(String family,float rpm,float rpmPerSecond,String lastSample) {
        int count=switch(family) { case "revv" -> 5; case "release" -> 7; case "blips" -> 8; default -> 0; };
        Selection best=null;
        for(int i=1;i<=count;i++) {
            String sample="engine_"+family+String.format(java.util.Locale.ROOT,"-%03d",i);
            float[] cue=Ztz99aTakeCalibration.cue(sample);
            float rate=clamp(tone(rpm)/cue[0],.85F,1.15F);
            float duration=Ztz99aAudioCalibration.duration(sample)/rate;
            float expected=tone(clamp(rpm+rpmPerSecond*duration*.35F,0,100));
            float onsetError=Math.abs((float)Math.log(cue[0]*rate/tone(rpm)));
            float trajectoryError=Math.abs((float)Math.log(cue[2]*rate/expected));
            float directionPenalty=family.equals("release") ? Math.max(0,cue[2]-cue[0])/50
                    : family.equals("revv") ? Math.max(0,cue[0]-cue[2])/50 : 0;
            float irregularity=0;
            for(int k=1;k<4;k++) irregularity+=Math.max(0,Math.abs(cue[k]-cue[k-1])-22)/70;
            float score=onsetError+trajectoryError*.65F+directionPenalty+irregularity
                    +(sample.equals(lastSample) ? .025F : 0);
            float confidence=Ztz99aTakeCalibration.confidence(sample);
            if(onsetError>.23F || score>.65F) continue; // Prefer loops over a badly matched take.
            float trim=-Math.min(8,score*10+(1-confidence)*6);
            if(best==null || score<best.score()) best=new Selection(sample,rate,trim,score);
        }
        return best;
    }
    /** Natural trajectory in the source; used to fade out instead of continually retuning it. */
    public static float cueAt(String sample,float sourceFraction) {
        float[] cues=Ztz99aTakeCalibration.cue(sample);
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
    private Ztz99aDieselTakes() {}
}
