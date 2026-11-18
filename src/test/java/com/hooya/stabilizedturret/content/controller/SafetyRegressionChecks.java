package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

/** Lightweight checks for invariants that do not require a running Minecraft level. */
public final class SafetyRegressionChecks {
    public static void main(String[] args) {
        double perpendicularRelativeSpeed =
                MianbaoCountermeasureManager.relativeSpeedMetersPerSecond(
                        new Vec3(1.0, 0.0, 0.0),
                        new Vec3(0.0, 1.0, 0.0));
        double expected = Math.sqrt(2.0) * 20.0;
        if (Math.abs(perpendicularRelativeSpeed - expected) > 1.0e-9) {
            throw new AssertionError(
                    "countermeasure threshold must use full relative velocity magnitude");
        }
        double coMovingRelativeSpeed =
                MianbaoCountermeasureManager.relativeSpeedMetersPerSecond(
                        new Vec3(4.0, -2.0, 1.0),
                        new Vec3(4.0, -2.0, 1.0));
        if (coMovingRelativeSpeed != 0.0) {
            throw new AssertionError(
                    "co-moving missile and target must have zero relative speed");
        }

        if (!CannonMountCompat.isFireRequestFresh(100L, 100L)
                || !CannonMountCompat.isFireRequestFresh(100L, 102L)
                || CannonMountCompat.isFireRequestFresh(100L, 103L)
                || CannonMountCompat.isFireRequestFresh(101L, 100L)) {
            throw new AssertionError(
                    "automatic cannon requests must share the server clock and expire after two ticks");
        }

        int remainingWaveBlasts = 61;
        int maximumWaveBudget = 0;
        for (int elapsedTicks = 0; elapsedTicks < 11; elapsedTicks++) {
            int budget = MianbaoNuclearDetonation.visibleWaveBlastBudget(
                    remainingWaveBlasts, elapsedTicks);
            maximumWaveBudget = Math.max(maximumWaveBudget, budget);
            remainingWaveBlasts -= Math.min(remainingWaveBlasts, budget);
        }
        if (remainingWaveBlasts != 0 || maximumWaveBudget != 6) {
            throw new AssertionError(
                    "61 nuclear wave sources must be spread over 11 ticks with a maximum budget of 6");
        }

    }

    private SafetyRegressionChecks() {
    }
}
