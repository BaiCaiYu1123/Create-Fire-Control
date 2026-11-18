package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

public final class CannonMovingInterceptChecks {
    private CannonMovingInterceptChecks() {
    }

    public static void main(String[] args) {
        fastIncomingTargetStaysInFrontOfGun();
        stationaryTargetRetainsNormalFlightTime();
        unreachableRecedingTargetHasNoFalseSolution();
    }

    private static void fastIncomingTargetStaysInFrontOfGun() {
        MovingTargetIntercept.Solution solution =
                MovingTargetIntercept.solve(
                        Vec3.ZERO, Vec3.ZERO, 4.0, 0.0, 0.0, false, 1200.0,
                        new Vec3(0.0, 0.0, 600.0),
                        new Vec3(0.0, 0.0, -12.0));
        require(solution != null, "fast incoming target must be interceptable");
        requireNear(solution.flightTicks(), 37.5, 0.01,
                "incoming intercept time must include target closing speed");
        require(solution.target().z > 0.0,
                "incoming prediction must remain in front of the muzzle");
        require(solution.launchDirection().z > 0.99,
                "gun must not reverse toward the extrapolated post-passage position");
    }

    private static void stationaryTargetRetainsNormalFlightTime() {
        MovingTargetIntercept.Solution solution =
                MovingTargetIntercept.solve(
                        Vec3.ZERO, Vec3.ZERO, 4.0, 0.0, 0.0, false, 1200.0,
                        new Vec3(0.0, 0.0, 600.0),
                        Vec3.ZERO);
        require(solution != null, "stationary target must be interceptable");
        requireNear(solution.flightTicks(), 150.0, 0.01,
                "stationary intercept time must remain range / muzzle speed");
        require(solution.launchDirection().z > 0.99,
                "stationary target direction must remain unchanged");
    }

    private static void unreachableRecedingTargetHasNoFalseSolution() {
        MovingTargetIntercept.Solution solution =
                MovingTargetIntercept.solve(
                        Vec3.ZERO, Vec3.ZERO, 4.0, 0.0, 0.0, false, 1200.0,
                        new Vec3(0.0, 0.0, 100.0),
                        new Vec3(0.0, 0.0, 6.0));
        require(solution == null,
                "a faster receding target must not create a false rearward intercept");
    }

    private static void requireNear(
            double actual,
            double expected,
            double tolerance,
            String message
    ) {
        require(Math.abs(actual - expected) <= tolerance,
                message + ": expected=" + expected + ", actual=" + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
