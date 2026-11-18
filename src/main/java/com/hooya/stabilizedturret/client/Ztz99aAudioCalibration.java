package com.hooya.stabilizedturret.client;
/** Measured from the original Vorbis files; filenames do not imply an RPM band. */
public final class Ztz99aAudioCalibration {
    public static float duration(String sample) {
        return switch (sample) {
            case "engine_00" -> 6.1077098F;
            case "engine_33_load" -> 6.4526304F;
            case "engine_66_load" -> 8.0505669F;
            case "engine_99_load" -> 7.0538776F;
            case "engine_blips-001" -> 1.3599773F;
            case "engine_blips-002" -> 1.1599773F;
            case "engine_blips-003" -> 1.2856009F;
            case "engine_blips-004" -> 1.9199773F;
            case "engine_blips-005" -> 2.0762358F;
            case "engine_blips-006" -> 1.4917460F;
            case "engine_blips-007" -> 1.8203628F;
            case "engine_blips-008" -> 1.3499773F;
            case "engine_release-001" -> 4.0859410F;
            case "engine_release-002" -> 3.8999773F;
            case "engine_release-003" -> 4.0570975F;
            case "engine_release-004" -> 4.1715420F;
            case "engine_release-005" -> 4.9606349F;
            case "engine_release-006" -> 4.4494785F;
            case "engine_release-007" -> 4.6948299F;
            case "engine_revv-001" -> 4.3550567F;
            case "engine_revv-002" -> 3.2309751F;
            case "engine_revv-003" -> 3.6000000F;
            case "engine_revv-004" -> 3.6736961F;
            case "engine_revv-005" -> 2.8712925F;
            case "engine_start" -> 3.6451701F;
            case "engine_stop" -> 3.4799773F;
            default -> throw new IllegalArgumentException(sample);
        };
    }
    public static float gainDb(String sample) {
        return switch (sample) {
            case "engine_00" -> -3.6092747F;
            case "engine_33_load" -> -12.3372249F;
            case "engine_66_load" -> -11.4541373F;
            case "engine_99_load" -> -10.7908184F;
            case "engine_blips-001" -> -6.8323043F;
            case "engine_blips-002" -> -7.3275328F;
            case "engine_blips-003" -> -10.4540751F;
            case "engine_blips-004" -> -9.3412545F;
            case "engine_blips-005" -> -10.1105052F;
            case "engine_blips-006" -> -6.0151254F;
            case "engine_blips-007" -> -8.3910129F;
            case "engine_blips-008" -> -8.3675204F;
            case "engine_release-001" -> -4.6499892F;
            case "engine_release-002" -> -4.6999126F;
            case "engine_release-003" -> -4.8218892F;
            case "engine_release-004" -> -6.9608318F;
            case "engine_release-005" -> -7.9021711F;
            case "engine_release-006" -> -5.4339820F;
            case "engine_release-007" -> -4.6029117F;
            case "engine_revv-001" -> -10.8906234F;
            case "engine_revv-002" -> -10.4762245F;
            case "engine_revv-003" -> -10.9116350F;
            case "engine_revv-004" -> -10.4288763F;
            case "engine_revv-005" -> -10.0444974F;
            case "engine_start" -> -4.9923929F;
            case "engine_stop" -> -1.2318245F;
            default -> throw new IllegalArgumentException(sample);
        };
    }
    public static float peak(String sample) {
        return switch (sample) {
            case "engine_00" -> 0.5998205F;
            case "engine_33_load" -> 1.0613209F;
            case "engine_66_load" -> 1.0635244F;
            case "engine_99_load" -> 1.1209198F;
            case "engine_blips-001" -> 1.0612713F;
            case "engine_blips-002" -> 1.0044703F;
            case "engine_blips-003" -> 1.0469124F;
            case "engine_blips-004" -> 1.0663484F;
            case "engine_blips-005" -> 1.0385829F;
            case "engine_blips-006" -> 1.0411479F;
            case "engine_blips-007" -> 1.0371747F;
            case "engine_blips-008" -> 1.0600138F;
            case "engine_release-001" -> 1.0248274F;
            case "engine_release-002" -> 1.0307347F;
            case "engine_release-003" -> 1.0453115F;
            case "engine_release-004" -> 1.0386134F;
            case "engine_release-005" -> 1.0622588F;
            case "engine_release-006" -> 1.0472246F;
            case "engine_release-007" -> 1.0192878F;
            case "engine_revv-001" -> 1.1215086F;
            case "engine_revv-002" -> 1.0728393F;
            case "engine_revv-003" -> 1.0676118F;
            case "engine_revv-004" -> 1.1038396F;
            case "engine_revv-005" -> 1.1053026F;
            case "engine_start" -> 1.0660336F;
            case "engine_stop" -> 0.6914209F;
            default -> throw new IllegalArgumentException(sample);
        };
    }
    public static float rms(String sample) {
        return switch (sample) {
            case "engine_00" -> 0.1818214F;
            case "engine_33_load" -> 0.4966409F;
            case "engine_66_load" -> 0.4486298F;
            case "engine_99_load" -> 0.4156446F;
            case "engine_blips-001" -> 0.2635096F;
            case "engine_blips-002" -> 0.2789702F;
            case "engine_blips-003" -> 0.3998389F;
            case "engine_blips-004" -> 0.3517580F;
            case "engine_blips-005" -> 0.3843320F;
            case "engine_blips-006" -> 0.2398488F;
            case "engine_blips-007" -> 0.3153057F;
            case "engine_blips-008" -> 0.3144541F;
            case "engine_release-001" -> 0.1692357F;
            case "engine_release-002" -> 0.1972162F;
            case "engine_release-003" -> 0.1458569F;
            case "engine_release-004" -> 0.2674378F;
            case "engine_release-005" -> 0.2980505F;
            case "engine_release-006" -> 0.2243264F;
            case "engine_release-007" -> 0.1865227F;
            case "engine_revv-001" -> 0.4204481F;
            case "engine_revv-002" -> 0.4008598F;
            case "engine_revv-003" -> 0.4214664F;
            case "engine_revv-004" -> 0.3986806F;
            case "engine_revv-005" -> 0.3814223F;
            case "engine_start" -> 0.2072614F;
            case "engine_stop" -> 0.0929962F;
            default -> throw new IllegalArgumentException(sample);
        };
    }
    public static final String[] SAMPLES = {"engine_00", "engine_33_load", "engine_66_load", "engine_99_load", "engine_blips-001", "engine_blips-002", "engine_blips-003", "engine_blips-004", "engine_blips-005", "engine_blips-006", "engine_blips-007", "engine_blips-008", "engine_release-001", "engine_release-002", "engine_release-003", "engine_release-004", "engine_release-005", "engine_release-006", "engine_release-007", "engine_revv-001", "engine_revv-002", "engine_revv-003", "engine_revv-004", "engine_revv-005", "engine_start", "engine_stop"};
    private Ztz99aAudioCalibration() {}
}
