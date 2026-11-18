package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Pure moving-target intercept math, kept independent of optional cannon APIs. */
final class MovingTargetIntercept {
    private MovingTargetIntercept() {
    }

    static @Nullable Solution solve(
            Vec3 muzzle,
            Vec3 carrierVelocity,
            double muzzleSpeed,
            double gravity,
            double drag,
            boolean quadraticDrag,
            double maxTime,
            Vec3 target,
            Vec3 targetVelocity
    ) {
        if (!isFinite(muzzle) || !isFinite(carrierVelocity)
                || !isFinite(target) || !isFinite(targetVelocity)
                || !(muzzleSpeed > 1.0e-5) || !(maxTime > 0.0)) return null;
        double effectiveDrag = Math.max(0.0, drag);
        if (quadraticDrag) effectiveDrag *= Math.max(muzzleSpeed, 1.0);
        effectiveDrag = Math.min(0.999, effectiveDrag);

        // At an infinitesimal time a non-zero displacement always requires
        // more than the available muzzle speed. Search for the first point at
        // which it becomes reachable, selecting the near-side intercept before
        // an incoming missile crosses the muzzle.
        double previousTime = 1.0e-4;
        double previousFunction = speedError(
                muzzle, carrierVelocity, muzzleSpeed, gravity, effectiveDrag,
                target, targetVelocity, previousTime);
        if (!Double.isFinite(previousFunction)) return null;

        double time = Math.min(0.25, maxTime);
        while (true) {
            double function = speedError(
                    muzzle, carrierVelocity, muzzleSpeed, gravity, effectiveDrag,
                    target, targetVelocity, time);
            if (!Double.isFinite(function)) return null;
            if (previousFunction > 0.0 && function <= 0.0) {
                double low = previousTime;
                double high = time;
                for (int iteration = 0; iteration < 28; iteration++) {
                    double middle = (low + high) * 0.5;
                    double middleFunction = speedError(
                            muzzle, carrierVelocity, muzzleSpeed, gravity, effectiveDrag,
                            target, targetVelocity, middle);
                    if (middleFunction > 0.0) low = middle;
                    else high = middle;
                }
                double flightTicks = (low + high) * 0.5;
                Vec3 predicted = target.add(targetVelocity.scale(flightTicks));
                Vec3 relativeVelocity = requiredVelocity(
                        predicted.subtract(muzzle), flightTicks, gravity, effectiveDrag)
                        .subtract(carrierVelocity);
                if (relativeVelocity.lengthSqr() < 1.0e-10) return null;
                return new Solution(
                        predicted, relativeVelocity.normalize(), flightTicks);
            }
            if (time >= maxTime) return null;
            previousTime = time;
            previousFunction = function;
            time = Math.min(time + 0.25, maxTime);
        }
    }

    private static double speedError(
            Vec3 muzzle,
            Vec3 carrierVelocity,
            double muzzleSpeed,
            double gravity,
            double drag,
            Vec3 target,
            Vec3 targetVelocity,
            double time
    ) {
        Vec3 displacement = target.add(targetVelocity.scale(time)).subtract(muzzle);
        return requiredVelocity(displacement, time, gravity, drag)
                .subtract(carrierVelocity).length() - muzzleSpeed;
    }

    private static Vec3 requiredVelocity(
            Vec3 displacement,
            double time,
            double gravity,
            double drag
    ) {
        int fullTicks = Math.max(0, (int) Math.floor(time));
        double partialTick = Math.max(0.0, time - fullTicks);
        if (drag < 1.0e-8) {
            double gravityDisplacement = gravity * (
                    0.5 * fullTicks * fullTicks
                            + partialTick * (fullTicks + 0.5));
            return new Vec3(
                    displacement.x / time,
                    (displacement.y - gravityDisplacement) / time,
                    displacement.z / time);
        }
        double q = 1.0 - Math.min(drag, 1.0);
        double qn = Math.pow(q, fullTicks);
        double velocitySum = (1.0 - qn) / drag;
        double halfStepFactor = 1.0 - drag * 0.5;
        double launchCoefficient = halfStepFactor
                * (velocitySum + partialTick * qn);
        double gravityDisplacement = halfStepFactor * gravity / drag * (
                fullTicks - velocitySum + partialTick * (1.0 - qn))
                + 0.5 * gravity * (fullTicks + partialTick);
        if (launchCoefficient < 1.0e-8) {
            return displacement.scale(1.0 / Math.max(time, 1.0e-8));
        }
        return new Vec3(
                displacement.x / launchCoefficient,
                (displacement.y - gravityDisplacement) / launchCoefficient,
                displacement.z / launchCoefficient);
    }

    private static boolean isFinite(Vec3 value) {
        return value != null && Double.isFinite(value.x)
                && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    record Solution(Vec3 target, Vec3 launchDirection, double flightTicks) {
    }
}
