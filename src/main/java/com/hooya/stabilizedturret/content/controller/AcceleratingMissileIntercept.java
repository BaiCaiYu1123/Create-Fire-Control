package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Collision-course guidance for a missile whose speed is commanded after each tick. */
public final class AcceleratingMissileIntercept {
    private static final double ROOT_TOLERANCE = 1.0e-9;
    // Classical N=3 PN minimises effort in the linear constant-speed model.
    private static final double NAVIGATION_CONSTANT = 3.0;

    private AcceleratingMissileIntercept() {
    }

    public static @Nullable Solution solve(
            Vec3 missilePosition, Vec3 targetPosition, Vec3 targetVelocity,
            SpeedProfile profile, int ageTicks, int remainingTicks
    ) {
        if (!isFinite(missilePosition) || !isFinite(targetPosition)
                || !isFinite(targetVelocity) || remainingTicks <= 0) return null;
        Vec3 relative = targetPosition.subtract(missilePosition);
        if (relative.lengthSqr() < 1.0e-12) return null;
        double x = relative.x;
        double y = relative.y;
        double z = relative.z;
        double targetSpeedSqr = targetVelocity.lengthSqr();
        double travel = 0.0;

        // The post-tick command at age a moves the projectile on the NEXT tick.
        // Within each movement interval, travel is D + speedAt(a + k) * u.
        // Solve |relative + velocity * (k + u)| = travel for 0 <= u <= 1.
        // Checking both quadratic roots also finds a fast incoming target that
        // enters and leaves the reachable sphere within a single tick.
        for (int tick = 0; tick < remainingTicks; tick++) {
            double speed = profile.speedAt(ageTicks + tick);
            double a = targetSpeedSqr - speed * speed;
            double b = 2.0 * (x * targetVelocity.x + y * targetVelocity.y
                    + z * targetVelocity.z - travel * speed);
            double c = x * x + y * y + z * z - travel * travel;
            double fraction = firstRootInTick(a, b, c);
            if (Double.isFinite(fraction)) {
                double flightTicks = tick + fraction;
                Vec3 displacement = relative.add(targetVelocity.scale(flightTicks));
                Vec3 point = targetPosition.add(targetVelocity.scale(flightTicks));
                return new Solution(point, displacement.normalize(), flightTicks);
            }
            travel += speed;
            x += targetVelocity.x;
            y += targetVelocity.y;
            z += targetVelocity.z;
        }
        return null;
    }

    private static double firstRootInTick(double a, double b, double c) {
        if (Math.abs(a) < 1.0e-12) {
            return Math.abs(b) < 1.0e-12 ? Double.NaN : rootInTick(-c / b);
        }
        double discriminant = b * b - 4.0 * a * c;
        double roundoff = 1.0e-12 * Math.max(1.0, Math.abs(b * b) + Math.abs(4.0 * a * c));
        if (discriminant < -roundoff) return Double.NaN;
        double sqrt = Math.sqrt(Math.max(0.0, discriminant));
        // This form avoids subtracting nearly equal numbers for the near root.
        double q = -0.5 * (b + Math.copySign(sqrt, b));
        if (q == 0.0) return rootInTick(-b / (2.0 * a));
        double first = rootInTick(q / a);
        double second = rootInTick(c / q);
        if (!Double.isFinite(first)) return second;
        return !Double.isFinite(second) ? first : Math.min(first, second);
    }

    private static double rootInTick(double root) {
        return Double.isFinite(root) && root >= -ROOT_TOLERANCE && root <= 1.0 + ROOT_TOLERANCE
                ? Math.max(0.0, Math.min(1.0, root)) : Double.NaN;
    }

    public static Vec3 targetPositionAt(Vec3 sample, Vec3 velocity, long sampleTick, long now) {
        if (sampleTick == Long.MIN_VALUE) return sample;
        return sample.add(velocity.scale(Math.max(0L, now - sampleTick)));
    }

    public static Vec3 guide(
            Vec3 missilePosition, Vec3 currentDirection, Vec3 targetPosition,
            Vec3 targetVelocity, SpeedProfile profile, int ageTicks,
            int remainingTicks, double maxSteerRadians
    ) {
        return guide(missilePosition, currentDirection, targetPosition, targetVelocity,
                profile, ageTicks, remainingTicks, maxSteerRadians, NAVIGATION_CONSTANT);
    }

    static Vec3 guide(
            Vec3 missilePosition, Vec3 currentDirection, Vec3 targetPosition,
            Vec3 targetVelocity, SpeedProfile profile, int ageTicks,
            int remainingTicks, double maxSteerRadians, double navigationConstant
    ) {
        Solution solution = solve(missilePosition, targetPosition, targetVelocity,
                profile, ageTicks, remainingTicks);
        Vec3 relative = targetPosition.subtract(missilePosition);
        if (!isFinite(relative) || relative.lengthSqr() < 1.0e-12) {
            return rotateTowards(currentDirection, currentDirection, 0.0);
        }
        Vec3 from = rotateTowards(currentDirection, currentDirection, 0.0);
        double speed = profile.speedAt(ageTicks);
        if (solution == null) {
            // No reachable lead point: acquire the current bearing gradually.
            return acquire(from, relative, relative.length() / Math.max(1.0e-8, speed),
                    maxSteerRadians, navigationConstant);
        }
        Vec3 desired = solution.direction();
        double time = Math.max(1.0, solution.flightTicks());
        double error = Math.acos(Math.max(-1.0, Math.min(1.0, from.dot(desired))));
        if (error < 1.0e-8 || time <= navigationConstant) {
            return rotateTowards(from, desired, maxSteerRadians);
        }

        // Use the average remaining scheduled speed in the PN geometry. At the
        // solved collision heading, v_target - v_virtual = -relative / time,
        // so LOS rate is exactly zero even while the motor is accelerating.
        // Using current motor speed here would manufacture a LOS rate and bend
        // an already correct trajectory during every acceleration tick.
        double virtualSpeed = relative.add(targetVelocity.scale(solution.flightTicks())).length()
                / Math.max(1.0e-8, solution.flightTicks());
        Vec3 los = relative.normalize();
        Vec3 relativeVelocity = targetVelocity.subtract(from.scale(virtualSpeed));
        Vec3 losRate = relative.cross(relativeVelocity).scale(1.0 / relative.lengthSqr());
        double closingSpeed = Math.max(-relativeVelocity.dot(los), virtualSpeed * 0.25);
        Vec3 pnTurn = losRate.cross(from).scale(navigationConstant * closingSpeed
                / Math.max(1.0e-8, virtualSpeed));

        // Classical PN assumes forward acquisition. It has a zero/reversed
        // response for an aft target. Blend into a time-to-go capture command
        // for large errors, rather than demanding the entire hardware limit.
        Vec3 captureBearing = rotateTowards(from, desired, Math.PI / 2.0);
        Vec3 capture = captureBearing.subtract(from.scale(from.dot(captureBearing)))
                .normalize().scale(navigationConstant * error / time);
        double blend = Math.max(0.0, Math.min(1.0, (error - Math.PI / 6.0) / (Math.PI / 6.0)));
        blend = blend * blend * (3.0 - 2.0 * blend);
        Vec3 demand = pnTurn.scale(1.0 - blend).add(capture.scale(blend));
        double turn = demand.length();
        if (turn < 1.0e-12) return from;
        Vec3 turnBearing = from.scale(Math.cos(Math.min(Math.PI / 2.0, turn)))
                .add(demand.scale(Math.sin(Math.min(Math.PI / 2.0, turn)) / turn));
        return rotateTowards(from, turnBearing, Math.min(maxSteerRadians, error));
    }

    /** Time-to-go acquisition for laser/waypoint/decoy bearings without a velocity sample. */
    public static Vec3 acquire(Vec3 current, Vec3 displacement, double flightTicks, double maxRadians) {
        return acquire(current, displacement, flightTicks, maxRadians, NAVIGATION_CONSTANT);
    }

    private static Vec3 acquire(Vec3 current, Vec3 displacement, double flightTicks,
                                double maxRadians, double navigationConstant) {
        Vec3 from = rotateTowards(current, current, 0.0);
        if (!isFinite(displacement) || displacement.lengthSqr() < 1.0e-12) return from;
        double error = Math.acos(Math.max(-1.0, Math.min(1.0, from.dot(displacement.normalize()))));
        double demand = navigationConstant * error / Math.max(navigationConstant, flightTicks);
        return rotateTowards(from, displacement, Math.min(maxRadians, demand));
    }

    public static Vec3 rotateTowards(Vec3 current, Vec3 desired, double maxRadians) {
        Vec3 from = isFinite(current) && current.lengthSqr() >= 1.0e-12
                ? current.normalize() : new Vec3(0.0, 0.0, 1.0);
        if (!isFinite(desired) || desired.lengthSqr() < 1.0e-12) return from;
        Vec3 to = desired.normalize();
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, from.dot(to))));
        double step = Math.max(0.0, Math.min(Math.PI, maxRadians));
        if (angle <= step) return to;
        Vec3 axis = from.cross(to);
        if (axis.lengthSqr() < 1.0e-12) {
            axis = from.cross(Math.abs(from.y) < 0.9
                    ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0));
        }
        axis = axis.normalize();
        return from.scale(Math.cos(step)).add(axis.cross(from).scale(Math.sin(step))).normalize();
    }

    private static boolean isFinite(Vec3 value) {
        return value != null && Double.isFinite(value.x)
                && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    public record SpeedProfile(double initialSpeed, double maximumSpeed, int accelerationTicks) {
        public SpeedProfile {
            if (!Double.isFinite(initialSpeed) || !Double.isFinite(maximumSpeed)
                    || initialSpeed < 0.0 || maximumSpeed <= 0.0 || accelerationTicks <= 0) {
                throw new IllegalArgumentException("Invalid missile speed profile");
            }
        }

        public double speedAt(int ageTicks) {
            return initialSpeed + (maximumSpeed - initialSpeed)
                    * Math.min(accelerationTicks, Math.max(0, ageTicks)) / accelerationTicks;
        }
    }

    public record Solution(Vec3 point, Vec3 direction, double flightTicks) {
    }
}
