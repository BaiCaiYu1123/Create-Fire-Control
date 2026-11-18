package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.config.MissileSettings;

import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;

/** Shared cleanup and effects clocks; burnout never changes motion or guidance. */
public final class LandAttackMissileFlight {
    public static final int LIFETIME_TICKS = 120 * 20;
    public static final int POWERED_TICKS = 50 * 20;
    public static final int UNMANAGED = -2;
    private static final String CLIENT_AGE = "CreateFireControlLandPoweredAge";
    private static final String CLIENT_RECEIVED = "CreateFireControlLandAgeReceived";

    public static boolean isTypePath(String path) {
        return switch (path) {
            case "cruisemissiletanshe", "cruisemissilelowtanshe", "nuclearcruisemissiletanshe",
                    "groundnuclearcruisemissiletanshe", "groundmissiletanshe", "groundmissile_2tanshe",
                    "groundmissile_3tanshe", "groundmissile_4tanshe", "ballistic_missile_entity" -> true;
            default -> false;
        };
    }

    public static boolean managed(Entity missile) {
        if (missile == null) return false;
        var data = missile.getPersistentData();
        return (MianbaoCruiseMissileCompat.isMissile(missile) || MianbaoBallisticMissileCompat.isMissile(missile))
                && (data.getBoolean("CreateFireControlCruiseGuided")
                || data.getBoolean("CreateFireControlBallisticGuided") || data.contains(CLIENT_AGE, Tag.TAG_INT));
    }

    public static int poweredAge(Entity missile) {
        var data = missile.getPersistentData();
        if (missile.level().isClientSide() && data.contains(CLIENT_AGE, Tag.TAG_INT)) {
            int age = data.getInt(CLIENT_AGE);
            return age < 0 ? age : (int) Math.min(Integer.MAX_VALUE,
                    age + Math.max(0L, missile.level().getGameTime() - data.getLong(CLIENT_RECEIVED)));
        }
        return MianbaoBallisticMissileCompat.isMissile(missile)
                ? MianbaoBallisticMissileCompat.launchAge(missile) : missile.tickCount;
    }

    public static int packetAge(Entity missile) { return managed(missile) ? poweredAge(missile) : UNMANAGED; }

    public static void acceptAge(Entity missile, int age) {
        if (age < -1 || !(MianbaoCruiseMissileCompat.isMissile(missile)
                || MianbaoBallisticMissileCompat.isMissile(missile))) return;
        missile.getPersistentData().putInt(CLIENT_AGE, age);
        missile.getPersistentData().putLong(CLIENT_RECEIVED, missile.level().getGameTime());
    }

    public static boolean powered(double age) { return age >= 0 && age < POWERED_TICKS; }
    public static boolean emitting(Entity missile) { return !managed(missile) || poweredAge(missile) >= 0 && poweredAge(missile) < MissileSettings.poweredTicks(missile); }

    private LandAttackMissileFlight() { }
}
