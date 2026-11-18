package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.config.MissileSettings;

/** Native engine/effect differences; guidance remains in each original controlling device. */
public record MianbaoMissileFlightProfile(String timer, double motorBase, int motorStart, int motorEnd,
                                        int smokeStart, int smokeEnd, boolean large, boolean laserSpark) {
    public static MianbaoMissileFlightProfile of(String path) {
        MianbaoMissileFlightProfile original = defaults(path);
        var defaults = MissileSettings.defaultsByModel().get(path);
        if (defaults == null || MissileSettings.poweredTicks(path) == Math.round(defaults.poweredSeconds() * 20)) return original;
        int duration = MissileSettings.poweredTicks(path);
        return new MianbaoMissileFlightProfile(original.timer, original.motorBase,
                original.motorStart, Math.max(original.motorStart, (int) Math.round(original.motorEnd * MissileSettings.poweredScale(path))),
                original.smokeStart, duration - 1, original.large, original.laserSpark);
    }
    private static MianbaoMissileFlightProfile defaults(String path) {
        if (path.equals("antiairmissiletanshe")) return new MianbaoMissileFlightProfile("fadongji1", 6, 5, 15, 5, Integer.MAX_VALUE, false, false);
        if (path.startsWith("farmissile")) return aircraft(6, MianbaoAircraftCompat.airToAirBurnTicks(MianbaoAircraftCompat.WeaponType.FAR));
        if (path.startsWith("closemissile")) return aircraft(5, 100);
        if (path.equals("missile_2tanshe") || path.equals("medium_aim_missile_2tanshe")
                || path.equals("mica_em_tanshe") || path.equals("aim_120_tanshe"))
            return aircraft(5.5, MianbaoAircraftCompat.airToAirBurnTicks(MianbaoAircraftCompat.WeaponType.MEDIUM));
        // The ARM's math-flight exhaust must last for its full 20-second flight, beyond the AGM's 50-tick burn.
        if (path.equals("antiradiationmissiletanshe"))
            return new MianbaoMissileFlightProfile("fadongji", 4.5, 1, 10, 1,
                    MianbaoAircraftCompat.lifetimeTicks(MianbaoAircraftCompat.WeaponType.ANTI_RADIATION), false, false);
        if (path.startsWith("agm") || path.startsWith("agmmissile"))
            return new MianbaoMissileFlightProfile("fadongji", 4.5, 1, 10, 1, 50, false, false);
        if (path.equals("missiletanshe")) return new MianbaoMissileFlightProfile("fadongji1", 7, 1, 10, 1, 15, false, true);
        if (path.startsWith("groundmissile") || path.startsWith("groundnuclear")) {
            boolean later = path.equals("groundmissile_4tanshe") || path.startsWith("groundnuclear");
            return new MianbaoMissileFlightProfile("time", 1, 0, 0, later ? 11 : 10,
                    LandAttackMissileFlight.POWERED_TICKS - 1, true, false);
        }
        if (path.contains("cruise")) return new MianbaoMissileFlightProfile("fadongji", 1, 0, 0, 15,
                LandAttackMissileFlight.POWERED_TICKS - 1, false, false);
        // Missile2trailProcedure is inherited by both medium variants, MICA-EM and AIM-120D.
        return aircraft(5.5, 200);
    }
    private static MianbaoMissileFlightProfile aircraft(double motor, int smokeEnd) {
        return new MianbaoMissileFlightProfile("fadongji1", motor, 1, 10, 1, smokeEnd, false, false);
    }
    public boolean smoke(double age) { return age >= smokeStart && age <= smokeEnd; }
    public double motorMultiplier(double age) {
        return age >= motorStart && age <= motorEnd ? Math.pow(motorBase, .1) : 1;
    }
}
