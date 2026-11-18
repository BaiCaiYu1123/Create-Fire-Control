package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

/** Pure aiming geometry shared by grouped turret control and regression checks. */
final class TurretAimConvergence {
    private static final double MIN_DIRECTION_LENGTH_SQR = 1.0e-8;

    private TurretAimConvergence() {
    }

    /** A surface inside the barrel's sweep cannot be a forward convergence point. */
    static final class NearTargetGuard {
        private boolean directionOnly;

        boolean update(Vec3 pivot, Vec3 muzzle, Vec3 target) {
            // Use the rotation-invariant reach, not the bore's current forward
            // dot product: turning away from a valid distant target is ordinary
            // acquisition, and turning through a nearby block must not rearm it.
            double clearance = target.distanceTo(pivot) - muzzle.distanceTo(pivot);
            // Half a block of entry clearance; another half block before
            // reacquisition prevents packet/pose noise from toggling the inverse.
            directionOnly = clearance <= (directionOnly ? 1.0 : 0.5);
            return directionOnly;
        }
    }

    static Vec3 requestedDirection(
            boolean hasWorldTarget,
            Vec3 worldTarget,
            Vec3 origin,
            Vec3 fallbackDirection
    ) {
        Vec3 requested = hasWorldTarget
                ? worldTarget.subtract(origin)
                : fallbackDirection;
        return requested.lengthSqr() < MIN_DIRECTION_LENGTH_SQR
                ? Vec3.ZERO
                : requested.normalize();
    }
}
