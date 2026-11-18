package com.hooya.stabilizedturret.client;
/** Measured mono-copy calibration. Native OpenAL panning handles the listener left/right channels. */
public final class Ztz99aDieselSpatialAudio {
    public static float trimDb(String sample) {
        return switch(sample) {
            case "engine_00" -> 0.18785024F;
            case "engine_33_load" -> 0.00000000F;
            case "engine_66_load" -> 0.00000000F;
            case "engine_99_load" -> 0.00000000F;
            case "engine_blips-001" -> 0.14308756F;
            case "engine_blips-002" -> 0.13437436F;
            case "engine_blips-003" -> -0.01454850F;
            case "engine_blips-004" -> 0.00000000F;
            case "engine_blips-005" -> 0.00000000F;
            case "engine_blips-006" -> 0.00000000F;
            case "engine_blips-007" -> 0.00000000F;
            case "engine_blips-008" -> 0.00000000F;
            case "engine_release-001" -> 0.03728686F;
            case "engine_release-002" -> 0.07653454F;
            case "engine_release-003" -> 0.00000000F;
            case "engine_release-004" -> 0.03693870F;
            case "engine_release-005" -> 0.00000000F;
            case "engine_release-006" -> 0.09754926F;
            case "engine_release-007" -> 0.14316684F;
            case "engine_revv-001" -> 0.00000000F;
            case "engine_revv-002" -> 0.00000000F;
            case "engine_revv-003" -> 0.00000000F;
            case "engine_revv-004" -> 0.00000000F;
            case "engine_revv-005" -> 0.00000000F;
            case "engine_start" -> 0.08675461F;
            case "engine_stop" -> 0.05674480F;
            default -> 0;
        };
    }
    private Ztz99aDieselSpatialAudio() {}
}
