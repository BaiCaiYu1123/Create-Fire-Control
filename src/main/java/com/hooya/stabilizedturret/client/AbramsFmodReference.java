package com.hooya.stabilizedturret.client;

/** Abrams exterior event gain automation; independent of Create's power model. */
final class AbramsFmodReference {
    static final String MODE_PROPERTY = "create_fire_control.abrams.mix";
    static final String DEFAULT_MIX = "fmod_reference";
    static final String LOAD_PROPERTY = "create_fire_control.abrams.debug_load";
    static final String RPM_PROPERTY = "create_fire_control.abrams.debug_rpm";
    static final float LOAD_FIRST_POINT = 0.0042372881355932203F;
    static final float LOAD_BUS_DB = -6.02F;
    static final float LOAD_MIN_DB = -10.0F;
    static final float LOAD_MAX_DB = -4.0F;
    // Bundled mono assets, measured before editing. Diagnostics only: these
    // values never calibrate or normalize playback, and packs may replace them.
    private static final float[] SOURCE_RMS = {0.06213F, 0.19586998F, 0.16357537F, 0.15211471F,
            0.26864118F, 0.27317766F, 0.21441896F, 0.25275791F};

    static float rmsEstimateDbfs(float[] audibleGains) {
        double power = 0;
        for (int i = 0; i < SOURCE_RMS.length; i++) {
            double rms = audibleGains[i] * SOURCE_RMS[i];
            power += rms * rms;
        }
        return power > 0 ? (float) (10.0 * Math.log10(power)) : -300.0F;
    }

    static boolean isReference(String value) {
        String mode = value == null ? DEFAULT_MIX : value;
        return "fmod_reference".equalsIgnoreCase(mode)
                || "fmod".equalsIgnoreCase(mode);
    }

    // No curveShape on either point in the official XML: linear in dB.
    // Automation SETS GainEffect.gain. Its stored +2 dB is not another gain.
    static float loadEffectDb(float load) {
        float t = unit((unit(load) - LOAD_FIRST_POINT) / (1.0F - LOAD_FIRST_POINT));
        return LOAD_MIN_DB + (LOAD_MAX_DB - LOAD_MIN_DB) * t;
    }

    static float loadBusGain(float load) {
        return dbToLinear(LOAD_BUS_DB + loadEffectDb(load));
    }

    // The master's second GainEffect also has a linear climb curve (0,0),(1,3).
    // Its Distance curve is intentionally replaced by the mod's existing range.
    static float climbGain(float climb) {
        return dbToLinear(3.0F * unit(climb));
    }

    static float debugLoad(String value) {
        if (value == null) return -1.0F;
        try {
            float load = Float.parseFloat(value);
            return Float.isFinite(load) && load >= 0.0F && load <= 1.0F ? load : -1.0F;
        } catch (NumberFormatException ignored) {
            return -1.0F;
        }
    }

    static float debugRpm(String value) {
        if (value == null) return -1.0F;
        try {
            float rpm = Float.parseFloat(value);
            return Float.isFinite(rpm) && rpm >= 0.0F && rpm <= 100.0F ? rpm : -1.0F;
        } catch (NumberFormatException ignored) {
            return -1.0F;
        }
    }

    static float unit(float value) {
        return Float.isFinite(value) ? Math.max(0.0F, Math.min(value, 1.0F)) : 0.0F;
    }

    static float dbToLinear(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    private AbramsFmodReference() {}
}
