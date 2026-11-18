package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

/** Executable regressions for cannon-pod drop and ownship-velocity compensation. */
public final class CannonTurretPodBallisticsChecks {
    public static void main(String[] args) {
        compensatesGravityAboveLineOfSight();
        compensatesCarrierCrossVelocity();
        usesOnlySlightlyReducedGravity();
    }

    private static void compensatesGravityAboveLineOfSight() {
        Vec3 solution = CannonTurretPodBallistics.solveDirection(
                Vec3.ZERO, new Vec3(0.0, 0.0, 1.0), Vec3.ZERO,
                new Vec3(0.0, 0.0, 100.0));
        require(solution != null, "stationary optical target must be solvable");
        require(solution.y > 0.0,
                "gravity compensation must elevate the cannon above line of sight");
        require(solution.z > 0.9, "solution must keep pointing toward the target");
    }

    private static void compensatesCarrierCrossVelocity() {
        Vec3 solution = CannonTurretPodBallistics.solveDirection(
                Vec3.ZERO, new Vec3(0.0, 0.0, 1.0),
                new Vec3(2.0, 0.0, 0.0),
                new Vec3(0.0, 0.0, 100.0));
        require(solution != null, "cross-velocity target must be solvable");
        require(solution.x < -0.05,
                "muzzle must lead against inherited rightward carrier velocity");
    }

    private static void usesOnlySlightlyReducedGravity() {
        require(CannonTurretPodBallistics.GRAVITY_PER_TICK > 0.0,
                "pod projectile must retain gravity");
        require(CannonTurretPodBallistics.GRAVITY_PER_TICK < 0.05,
                "pod projectile gravity must be lower than native autocannon gravity");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private CannonTurretPodBallisticsChecks() {
    }
}
