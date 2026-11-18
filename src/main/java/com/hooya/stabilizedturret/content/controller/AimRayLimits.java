package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

/** Shared sight-query and packet limits; these never extend a projectile's flight. */
public final class AimRayLimits {
    public static final double LOADED_DISTANCE = 4096.0;
    /** Matches Voxy's 16 * 3000 far projection. */
    public static final double LOD_DISTANCE = 48_000.0;
    /** Leaves room for client/server pose skew beyond the LOD query distance. */
    public static final double MAX_TARGET_DISTANCE = 65_536.0;

    public static boolean withinTargetRange(Vec3 origin, Vec3 target) {
        double distanceSquared = target.distanceToSqr(origin);
        return Double.isFinite(distanceSquared)
                && distanceSquared <= MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE;
    }

    /** A local sight result must have a finite target strictly ahead of the camera. */
    public static boolean isForwardTarget(Vec3 origin, Vec3 direction, Vec3 target) {
        return isFinite(origin) && isFinite(direction) && isFinite(target)
                && direction.lengthSqr() >= 1.0e-12
                && withinTargetRange(origin, target)
                && target.subtract(origin).dot(direction.normalize()) > 0.0;
    }

    private static boolean isFinite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x)
                && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private AimRayLimits() {}
}
