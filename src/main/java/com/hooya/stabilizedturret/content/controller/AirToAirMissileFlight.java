package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.config.MissileSettings;

import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;

/** One ignition clock for adopted AAM trails, remote visuals and native compatibility. */
public final class AirToAirMissileFlight {
    public static final int UNMANAGED = -2;
    public static final String CLIENT_AGE = "CreateFireControlAirPoweredAge";
    private static final String CLIENT_RECEIVED = "CreateFireControlAirAgeReceived";

    public static boolean managed(Entity missile) {
        return missile != null && MianbaoAircraftCompat.isAirToAir(MianbaoAircraftCompat.typeOf(missile))
                && (missile.getPersistentData().getBoolean("CreateFireControlAircraftGuided")
                || missile.getPersistentData().contains(CLIENT_AGE, Tag.TAG_INT));
    }

    /** -1 is an unignited release; packet age never starts at client tracking time. */
    public static int poweredAge(Entity missile) {
        var data = missile.getPersistentData();
        if (missile.level().isClientSide() && data.contains(CLIENT_AGE, Tag.TAG_INT)) {
            int age = data.getInt(CLIENT_AGE);
            return age < 0 ? age : (int) Math.min(Integer.MAX_VALUE,
                    age + Math.max(0L, missile.level().getGameTime() - data.getLong(CLIENT_RECEIVED)));
        }
        if (data.contains(FarMissileIgnitionHeading.RELEASE_TAG, Tag.TAG_COMPOUND)) {
            var release = data.getCompound(FarMissileIgnitionHeading.RELEASE_TAG);
            if (!release.getBoolean("SoundPlayed")) return -1;
            if (release.contains("LastStep", Tag.TAG_ANY_NUMERIC) && release.getLong("LastStep") != Long.MIN_VALUE)
                return (int) Math.min(Integer.MAX_VALUE,
                        Math.max(0L, missile.level().getGameTime() - release.getLong("LastStep")));
        }
        return Math.max(0, data.contains("CreateFireControlAircraftAge", Tag.TAG_ANY_NUMERIC)
                ? data.getInt("CreateFireControlAircraftAge") : missile.tickCount);
    }

    public static int packetAge(Entity missile) { return managed(missile) ? poweredAge(missile) : MissileSettings.visualPacketAge(missile); }

    public static void acceptAge(Entity missile, int age) {
        MissileSettings.acceptVisualAge(missile, age);
        if (age < -1 || !MianbaoAircraftCompat.isAirToAir(MianbaoAircraftCompat.typeOf(missile))) return;
        missile.getPersistentData().putInt(CLIENT_AGE, age);
        missile.getPersistentData().putLong(CLIENT_RECEIVED, missile.level().getGameTime());
    }

    public static boolean emitting(Entity missile) {
        if (!managed(missile)) return true;
        int age = poweredAge(missile);
        return age >= 0 && age < MissileSettings.poweredTicks(missile);
    }

    public static int nativeTickAge(Entity missile) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, poweredAge(missile)) + 1);
    }

    /** Keep a finite orphan safety cap without shortening a live device-owned flight. */
    public static int fallbackLifetimeTicks(Entity missile) {
        if (!managed(missile)) return 1200;
        var release = missile.getPersistentData().getCompound(FarMissileIgnitionHeading.RELEASE_TAG);
        int unpowered = Math.max(0, release.getInt("IgnitionTick"));
        return Math.max(1200, MissileSettings.lifetimeTicks(missile) + unpowered);
    }

    private AirToAirMissileFlight() { }
}
