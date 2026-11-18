package com.hooya.stabilizedturret.client;
/** Measured harmonic cues; full trajectories/confidence in reference/ztz99a/take_analysis.json. */
final class Ztz99aTakeCalibration {
    static float[] cue(String sample) { return switch(sample) {
        case "engine_00" -> new float[]{51.75F,51.50F,52.00F,51.75F,51.75F};
        case "engine_33_load" -> new float[]{55.25F,64.50F,57.25F,58.50F,60.25F};
        case "engine_66_load" -> new float[]{64.00F,67.25F,70.50F,58.50F,60.75F};
        case "engine_99_load" -> new float[]{98.75F,96.75F,96.25F,94.75F,94.25F};
        case "engine_blips-001" -> new float[]{49.25F,58.75F,56.75F,40.25F,38.00F};
        case "engine_blips-002" -> new float[]{43.25F,53.25F,55.00F,48.75F,39.00F};
        case "engine_blips-003" -> new float[]{65.25F,64.50F,62.00F,63.25F,44.50F};
        case "engine_blips-004" -> new float[]{61.00F,59.75F,58.25F,59.25F,58.75F};
        case "engine_blips-005" -> new float[]{64.00F,61.25F,51.75F,63.50F,49.75F};
        case "engine_blips-006" -> new float[]{62.50F,43.25F,61.25F,61.75F,63.75F};
        case "engine_blips-007" -> new float[]{55.25F,65.50F,67.00F,39.75F,38.00F};
        case "engine_blips-008" -> new float[]{52.50F,60.00F,61.00F,38.50F,70.50F};
        case "engine_release-001" -> new float[]{61.75F,49.25F,45.25F,39.00F,70.00F};
        case "engine_release-002" -> new float[]{38.00F,72.75F,69.00F,59.00F,52.75F};
        case "engine_release-003" -> new float[]{95.50F,82.75F,38.00F,64.25F,57.25F};
        case "engine_release-004" -> new float[]{40.00F,44.50F,57.25F,38.00F,41.75F};
        case "engine_release-005" -> new float[]{43.50F,68.00F,66.00F,63.25F,61.75F};
        case "engine_release-006" -> new float[]{59.25F,41.00F,39.25F,38.00F,38.00F};
        case "engine_release-007" -> new float[]{41.00F,38.00F,60.50F,42.00F,54.25F};
        case "engine_revv-001" -> new float[]{51.00F,61.25F,73.75F,89.50F,106.00F};
        case "engine_revv-002" -> new float[]{58.75F,67.50F,76.75F,89.50F,99.00F};
        case "engine_revv-003" -> new float[]{50.50F,59.25F,69.00F,81.50F,93.75F};
        case "engine_revv-004" -> new float[]{48.50F,58.75F,69.25F,82.00F,95.00F};
        case "engine_revv-005" -> new float[]{63.25F,69.25F,74.75F,82.00F,87.00F};
        case "engine_start" -> new float[]{98.00F,107.00F,59.50F,51.25F,51.75F};
        case "engine_stop" -> new float[]{56.00F,65.50F,56.00F,47.75F,38.00F};
        default -> throw new IllegalArgumentException(sample);
    }; }
    static float confidence(String sample) { return switch(sample) {
        case "engine_00" -> 1.00000F;
        case "engine_33_load" -> 1.00000F;
        case "engine_66_load" -> 1.00000F;
        case "engine_99_load" -> 1.00000F;
        case "engine_blips-001" -> 0.94802F;
        case "engine_blips-002" -> 0.81624F;
        case "engine_blips-003" -> 1.00000F;
        case "engine_blips-004" -> 1.00000F;
        case "engine_blips-005" -> 0.92405F;
        case "engine_blips-006" -> 0.99124F;
        case "engine_blips-007" -> 0.94131F;
        case "engine_blips-008" -> 0.94558F;
        case "engine_release-001" -> 0.95889F;
        case "engine_release-002" -> 0.97523F;
        case "engine_release-003" -> 0.84258F;
        case "engine_release-004" -> 0.91021F;
        case "engine_release-005" -> 0.91173F;
        case "engine_release-006" -> 0.81098F;
        case "engine_release-007" -> 0.85406F;
        case "engine_revv-001" -> 1.00000F;
        case "engine_revv-002" -> 1.00000F;
        case "engine_revv-003" -> 0.98106F;
        case "engine_revv-004" -> 1.00000F;
        case "engine_revv-005" -> 1.00000F;
        case "engine_start" -> 0.86346F;
        case "engine_stop" -> 0.79881F;
        default -> throw new IllegalArgumentException(sample);
    }; }
    private Ztz99aTakeCalibration() {}
}
