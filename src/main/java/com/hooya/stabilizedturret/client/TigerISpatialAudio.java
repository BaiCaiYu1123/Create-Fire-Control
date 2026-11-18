package com.hooya.stabilizedturret.client;

/** Game-space distance rolloff with the installed bank's measured far-distance cutoff.
 * Scale changes distance only; volume never changes the audible range. */
public final class TigerISpatialAudio {
    private static final float[] DISTANCE={3,5,10,16,32,64,100,128,150,175,190,200,210,214,215};
    private static final float[] GAIN={1,.99132568F,.97071898F,.94787847F,.89562504F,.81958649F,
            .76399776F,.73469606F,.71750473F,.60910752F,.49877939F,.40469610F,.16585569F,.00679333F,0};
    public static float gain(float distance,float scale) {
        float d=distance/Math.max(.1F,scale);
        if(d<=3)return 1;
        // The bank curve alone stays almost flat over normal block distances.
        // Add inverse-distance rolloff while preserving the near mix and far cutoff.
        float rolloff=8/(8+d-3);
        for(int i=1;i<DISTANCE.length;i++)if(d<DISTANCE[i]) {
            float t=(d-DISTANCE[i-1])/(DISTANCE[i]-DISTANCE[i-1]);
            return (GAIN[i-1]+(GAIN[i]-GAIN[i-1])*t)*rolloff;
        }
        return 0;
    }
    /** Extra MC air-absorption mapping, independent of the measured volume curve. */
    public static float highFrequencyGain(float distance,float scale,float lossDb) {
        return TigerIGain.amplitude(lossDb*TigerISoundProfile.clamp((distance/scale-3)/197,0,1));
    }
    public interface FilterChannel {
        void tigerI$highFrequency(java.util.function.DoubleSupplier supplier);
        boolean tigerI$filterActive();
    }
    /** Gain_tank_engines_enemy_ai direction curve: +2 at front, +6 at +/-180 degrees.
     * Near/front +2 is already present in the shared reference dB; return only its change. */
    public static float directionalGain(float degrees) {
        return TigerIGain.amplitude(4*TigerISoundProfile.clamp(Math.abs(degrees)/180,0,1));
    }
    private TigerISpatialAudio() {}
}
