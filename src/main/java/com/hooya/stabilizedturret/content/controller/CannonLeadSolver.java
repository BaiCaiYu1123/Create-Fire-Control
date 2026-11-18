package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** A firing bearing and time solved together against CBC's actual swept motion. */
public final class CannonLeadSolver {
    private static final double TOLERANCE = 0.025;

    private CannonLeadSolver() {}

    public record Solution(Vec3 launchDirection, Vec3 intercept, double flightTicks) {}

    public static @Nullable Solution solve(CannonBallisticsCompat.ShotProfile shot,
                                           Vec3 target, Vec3 targetVelocity) {
        if (shot == null || !finite(shot.muzzle()) || !finite(shot.carrierVelocity())
                || !finite(target) || !finite(targetVelocity) || !Double.isFinite(shot.muzzleSpeed())
                || shot.muzzleSpeed() <= 1e-5 || !Double.isFinite(shot.gravity())
                || !Double.isFinite(shot.drag()) || shot.maxFlightTicks() < 1
                || target.distanceToSqr(shot.muzzle()) < 1e-8) return null;
        double limit = Math.min(1200, shot.maxFlightTicks());
        var seed = MovingTargetIntercept.solve(shot.muzzle(), shot.carrierVelocity(), shot.muzzleSpeed(),
                shot.gravity(), shot.drag(), shot.quadraticDrag(), limit, target, targetVelocity);
        if (seed == null) {
            // Constant-speed interception is also a useful seed when the quadratic
            // drag approximation declares a reachable, decelerating shot unreachable.
            seed = MovingTargetIntercept.solve(shot.muzzle(), shot.carrierVelocity(), shot.muzzleSpeed(),
                    0, 0, false, limit, target, targetVelocity);
        }
        if (seed == null) return null;
        return refine(shot, target, targetVelocity, seed.launchDirection(), seed.flightTicks(), limit);
    }

    private static @Nullable Solution refine(CannonBallisticsCompat.ShotProfile shot, Vec3 target,
            Vec3 targetVelocity, Vec3 direction, double time, double limit) {
        for (int iteration = 0; iteration < 18; iteration++) {
            Vec3 position = position(shot, direction, time);
            Vec3 miss = target.add(targetVelocity.scale(time)).subtract(position);
            double error = miss.lengthSqr();
            if (!finite(miss)) return null;
            if (error < TOLERANCE * TOLERANCE)
                return new Solution(direction, target.add(targetVelocity.scale(time)), time);
            Vec3 right = direction.cross(new Vec3(0, 1, 0));
            if (right.lengthSqr() < 1e-8) right = new Vec3(1, 0, 0);
            else right = right.normalize();
            Vec3 up = right.cross(direction).normalize();
            double epsilon = 1e-4;
            Vec3 dx = position(shot, direction.add(right.scale(epsilon)).normalize(), time)
                    .subtract(position).scale(1 / epsilon);
            Vec3 dy = position(shot, direction.add(up.scale(epsilon)).normalize(), time)
                    .subtract(position).scale(1 / epsilon);
            double dt = time + 0.001 <= limit ? 0.001 : -0.001;
            Vec3 dz = position(shot, direction, time + dt).subtract(position).scale(1 / dt)
                    .subtract(targetVelocity);
            double determinant = dx.dot(dy.cross(dz));
            if (!Double.isFinite(determinant) || Math.abs(determinant) < 1e-10) return null;
            double x = miss.dot(dy.cross(dz)) / determinant;
            double y = dx.dot(miss.cross(dz)) / determinant;
            double t = dx.dot(dy.cross(miss)) / determinant;
            double scale = Math.min(1, .25 / Math.max(.25, Math.hypot(x, y)));
            scale = Math.min(scale, Math.max(.25, time * .5) / Math.max(.25, Math.abs(t)));
            boolean improved = false;
            for (int trial = 0; trial < 8; trial++, scale *= .5) {
                double nextTime = time + t * scale;
                if (nextTime <= 1e-4 || nextTime > limit) continue;
                Vec3 nextDirection = direction.add(right.scale(x * scale)).add(up.scale(y * scale)).normalize();
                Vec3 nextMiss = target.add(targetVelocity.scale(nextTime))
                        .subtract(position(shot, nextDirection, nextTime));
                if (finite(nextMiss) && nextMiss.lengthSqr() < error) {
                    direction = nextDirection;
                    time = nextTime;
                    improved = true;
                    break;
                }
            }
            if (!improved) return null;
        }
        return null;
    }

    /** Fractional ticks use the same full movement segment swept by CBC collision. */
    static Vec3 position(CannonBallisticsCompat.ShotProfile shot, Vec3 direction, double time) {
        Vec3 position = shot.muzzle();
        Vec3 velocity = direction.scale(shot.muzzleSpeed()).add(shot.carrierVelocity());
        int ticks = (int) Math.floor(time);
        for (int tick = 0; tick < ticks; tick++) {
            var step = CannonProjectileFlight.step(position, velocity, shot.gravity(), shot.drag(), shot.quadraticDrag());
            position = step.position();
            velocity = step.velocity();
        }
        double fraction = time - ticks;
        return fraction < 1e-10 ? position : position.lerp(CannonProjectileFlight.step(
                position, velocity, shot.gravity(), shot.drag(), shot.quadraticDrag()).position(), fraction);
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
