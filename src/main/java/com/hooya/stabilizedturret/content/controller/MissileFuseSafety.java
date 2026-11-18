package com.hooya.stabilizedturret.content.controller;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;

/** Shared arming clock for native collision, mathematical collision and aircraft proximity fuzes. */
public final class MissileFuseSafety {
    public static final String LAUNCH_TICK_TAG = "CreateFireControlFuseLaunchTick";

    /** A newly adopted shot starts at elapsed zero even if it moves in this tick. */
    public static void launched(Entity missile) {
        missile.getPersistentData().putLong(LAUNCH_TICK_TAG, serverTime(missile));
    }

    public static void restoreClock(Entity missile, int savedFlightAge) {
        if (!missile.getPersistentData().contains(LAUNCH_TICK_TAG, Tag.TAG_ANY_NUMERIC)) {
            // Old saves counted the spawn tick. Treat only completed ticks as
            // elapsed; migration may add one safe tick but must never arm early.
            missile.getPersistentData().putLong(LAUNCH_TICK_TAG,
                    serverTime(missile) - Math.max(0L, (long) savedFlightAge - 1));
        }
    }

    public static boolean armed(Entity missile, int safetyTicks) {
        return armed(missile, safetyTicks, missile.tickCount);
    }

    public static boolean armed(Entity missile, int safetyTicks, int flightAgeTicks) {
        return elapsedTicks(missile, flightAgeTicks) >= safetyTicks;
    }

    public static long elapsedTicks(Entity missile) {
        return elapsedTicks(missile, missile.tickCount);
    }

    private static long elapsedTicks(Entity missile, int flightAgeTicks) {
        CompoundTag data = missile.getPersistentData();
        if (MianbaoAircraftCompat.typeOf(missile) != MianbaoAircraftCompat.WeaponType.FAR
                || !data.contains(FarMissileIgnitionHeading.RELEASE_TAG, Tag.TAG_COMPOUND)) {
            return data.contains(LAUNCH_TICK_TAG, Tag.TAG_ANY_NUMERIC)
                    ? Math.max(0L, serverTime(missile) - data.getLong(LAUNCH_TICK_TAG))
                    : flightAgeTicks;
        }
        CompoundTag release = data.getCompound(FarMissileIgnitionHeading.RELEASE_TAG);
        if (!release.getBoolean("SoundPlayed")) return -1;
        long elapsed;
        if (release.contains("LastStep", Tag.TAG_ANY_NUMERIC) && release.getLong("LastStep") != Long.MIN_VALUE) {
            // The last free-fall step is the ignition tick. This persisted world
            // time survives reloads (Entity.tickCount does not) and math handoff.
            elapsed = Math.max(0L, serverTime(missile) - release.getLong("LastStep"));
        } else {
            elapsed = Math.max(0L, (long) missile.tickCount - release.getInt("IgnitionTick"));
        }
        return elapsed;
    }

    public static int aircraftSafetyTicks(Entity missile) {
        CompoundTag data = missile.getPersistentData();
        if (data.contains("CreateFireControlSafetyTicks", Tag.TAG_ANY_NUMERIC))
            return Math.max(0, Math.min(200, data.getInt("CreateFireControlSafetyTicks")));
        return MianbaoAircraftCompat.isAirToAir(MianbaoAircraftCompat.typeOf(missile)) ? 6 : 20;
    }

    public static boolean protectedAircraft(Entity missile) {
        return missile.getPersistentData().getBoolean("CreateFireControlAircraftGuided")
                && !armed(missile, aircraftSafetyTicks(missile));
    }

    private static long serverTime(Entity missile) {
        var root = MianbaoMissileCompat.getRootServerLevel(missile);
        return root == null ? missile.level().getGameTime() : root.getGameTime();
    }

    private MissileFuseSafety() { }
}
