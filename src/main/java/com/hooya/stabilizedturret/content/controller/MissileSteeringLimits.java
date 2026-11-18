package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

/** One turn budget, independent of target selection and the flight backend. */
public final class MissileSteeringLimits {
    private MissileSteeringLimits() {}

    public static double maxTurn(double speed, double maximumSpeed,
                                 double angularLimit, double lateralAcceleration) {
        double current = Math.max(0, speed);
        double accelerationLimit = Math.atan2(lateralAcceleration, Math.max(1e-8, current));
        double fullSpeedLimit = Math.min(angularLimit, Math.atan2(lateralAcceleration, maximumSpeed));
        // Preserve the full-speed radius while accelerating, as well as both
        // established ceilings. Above the speed envelope, overload still wins.
        return Math.min(Math.min(angularLimit, accelerationLimit), fullSpeedLimit * current / maximumSpeed);
    }

    public static Vec3 limitVelocity(Vec3 previous, Vec3 requested, double maxRadians) {
        return AcceleratingMissileIntercept.rotateTowards(previous, requested, maxRadians).scale(requested.length());
    }
}
