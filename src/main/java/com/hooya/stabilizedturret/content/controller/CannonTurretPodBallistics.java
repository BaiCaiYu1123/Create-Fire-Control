package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Pure cannon-pod ballistic math, deliberately independent of Sable runtime classes. */
final class CannonTurretPodBallistics {
    static final double MUZZLE_SPEED = 10.0;
    static final double GRAVITY_PER_TICK = 0.035;
    static final double DRAG_PER_TICK = 0.01;
    private static final int MAX_FLIGHT_TICKS = 400;

    static @Nullable Vec3 solveDirection(
            Vec3 muzzle, Vec3 forward, Vec3 carrierVelocity, Vec3 target
    ) {
        if (!finite(muzzle) || !finite(forward) || !finite(carrierVelocity) || !finite(target)
                || forward.lengthSqr() < 1.0e-10) {
            return null;
        }
        Vec3 displacement = target.subtract(muzzle);
        if (displacement.lengthSqr() < 1.0e-10) return forward.normalize();

        double previousTime = 1.0e-4;
        double previousError = speedError(displacement, carrierVelocity, previousTime);
        for (double time = 0.25; time <= MAX_FLIGHT_TICKS; time += 0.25) {
            double error = speedError(displacement, carrierVelocity, time);
            if (previousError > 0.0 && error <= 0.0) {
                double low = previousTime;
                double high = time;
                for (int iteration = 0; iteration < 28; iteration++) {
                    double middle = (low + high) * 0.5;
                    if (speedError(displacement, carrierVelocity, middle) > 0.0) {
                        low = middle;
                    } else {
                        high = middle;
                    }
                }
                Vec3 relative = requiredWorldVelocity(
                        displacement, (low + high) * 0.5).subtract(carrierVelocity);
                return relative.lengthSqr() < 1.0e-10 ? null : relative.normalize();
            }
            previousTime = time;
            previousError = error;
        }
        return null;
    }

    private static double speedError(
            Vec3 displacement, Vec3 carrierVelocity, double flightTicks
    ) {
        return requiredWorldVelocity(displacement, flightTicks)
                .subtract(carrierVelocity).length() - MUZZLE_SPEED;
    }

    /** Exact AbstractArrow air motion, including post-move 0.99 drag and gravity. */
    private static Vec3 requiredWorldVelocity(Vec3 displacement, double flightTicks) {
        int fullTicks = Math.max(0, (int) Math.floor(flightTicks));
        double partialTick = Math.max(0.0, flightTicks - fullTicks);
        double velocityMultiplier = 1.0 - DRAG_PER_TICK;
        double qn = Math.pow(velocityMultiplier, fullTicks);
        double velocitySum = (1.0 - qn) / DRAG_PER_TICK;
        double launchCoefficient = velocitySum + partialTick * qn;
        if (launchCoefficient < 1.0e-10) {
            return displacement.scale(1.0 / Math.max(flightTicks, 1.0e-10));
        }
        double accumulatedGravity = GRAVITY_PER_TICK * (
                (fullTicks - velocitySum) / DRAG_PER_TICK
                        + partialTick * (1.0 - qn) / DRAG_PER_TICK);
        return new Vec3(
                displacement.x / launchCoefficient,
                (displacement.y + accumulatedGravity) / launchCoefficient,
                displacement.z / launchCoefficient);
    }

    private static boolean finite(@Nullable Vec3 vector) {
        return vector != null && Double.isFinite(vector.x)
                && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private CannonTurretPodBallistics() {
    }
}
