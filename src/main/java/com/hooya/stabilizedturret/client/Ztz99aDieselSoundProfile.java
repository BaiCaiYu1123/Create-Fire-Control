package com.hooya.stabilizedturret.client;

/** Diesel-only mix parameters. Presentation never changes kinetic RPM. */
public final class Ztz99aDieselSoundProfile {
    public static final String[] LOOPS = {"engine_00", "engine_33_load", "engine_66_load", "engine_99_load"};
    public static final float HANDOFF_SECONDS = 0.75F;
    public static final float STARTUP_LOOP_ADVANCE_SECONDS = 1.0F;
    public static final float RELEASE_SECONDS = 0.50F;
    // rpm_result preset {ce7cfbf3-...}: global RPM -> derived instrument parameter.
    public static final float RPM_MAP_MIN = .14124293785310735F;
    private static final float[] ROOT = {0, 30, 55, 100};
    private static final float[] MINIMUM_RATE = {1, .704999983F, .779999971F, .104999997F};

    public static float clamp(float v, float low, float high) { return Math.max(low, Math.min(high, v)); }
    /** Start the existing loop fade one second earlier, keeping its duration and the start voice. */
    public static float startupEnvelope(float sourceElapsed, float duration) {
        float begin = Math.max(0, duration - HANDOFF_SECONDS - STARTUP_LOOP_ADVANCE_SECONDS);
        return clamp((sourceElapsed - begin) / HANDOFF_SECONDS, 0, 1);
    }
    public static float normalizedRpm(float rpm, float configuredMax) {
        if (!Float.isFinite(rpm) || !Float.isFinite(configuredMax) || configuredMax <= 0) return 0;
        return clamp(Math.abs(rpm) / configuredMax * 100F, 0, 100);
    }
    public static float instrumentRpm(float globalRpm) {
        return clamp((globalRpm-RPM_MAP_MIN)*100/(100-RPM_MAP_MIN), 0, 100);
    }
    /** Native per-instrument fades. Idle has NO RPM fade in the official event. */
    public static float[] rawWeights(float globalRpm) {
        float r = instrumentRpm(globalRpm);
        return new float[]{1,
                Ztz99aFmodFade.value(0,(r-1)/9)*Ztz99aFmodFade.value(1,(r-30)/15),
                Ztz99aFmodFade.value(2,(r-30)/15)*Ztz99aFmodFade.value(3,(r-65)/15.000007629F),
                Ztz99aFmodFade.value(4,(r-65)/15.000007629F)};
    }
    /** A common dB correction preserves native ratios, without crossover energy bumps. */
    public static float[] weights(float globalRpm) {
        float[] result=rawWeights(globalRpm);
        if ((result[1]>0 && result[2]>0) || (result[2]>0 && result[3]>0)) {
            float power=result[1]*result[1]+result[2]*result[2]+result[3]*result[3];
            float correction=(float)(1/Math.sqrt(power));
            for(int i=1;i<4;i++) result[i]*=correction;
        }
        return result;
    }
    /** FMOD AutoPitch is a linear playback-rate slope, NOT semitone interpolation. */
    public static float nativePitch(int layer, float instrumentRpm) {
        if (layer==0) return 1;
        float rate=MINIMUM_RATE[layer]+(1-MINIMUM_RATE[layer])*instrumentRpm/ROOT[layer];
        return rate*(layer==1 ? (float)Math.pow(2,-2/12.0) : 1);
    }
    /** Prepare muted instruments at their audible boundary; all audible rates fit MC .5–2. */
    public static float pitch(int layer, float rpm, float load) {
        return nativePitch(layer,Math.max(instrumentRpm(rpm), switch(layer) {
            case 1 -> 1; case 2 -> 30; case 3 -> 65; default -> 0;
        }));
    }
    private Ztz99aDieselSoundProfile() {}
}
