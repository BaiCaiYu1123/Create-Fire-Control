package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import com.hooya.stabilizedturret.content.controller.LaserRangeCompensation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;
import java.util.function.DoubleFunction;

/** Axis inverse shared by pose locks and physical servos; muzzle and bore rotate together. */
public final class LegacyFiniteAimSolver {
    private LegacyFiniteAimSolver() {}

    public record Ray(Vec3 muzzle, Vec3 forward, Vec3 target,
                      @Nullable CannonBallisticsCompat.ShotProfile profile,
                      Vec3 inheritedVelocity, @Nullable DoubleFunction<Vec3> targetAtAngle,
                      double elevationCorrection, boolean fixedElevation, @Nullable Vec3 parallelSight) {
        public Ray(Vec3 muzzle, Vec3 forward, Vec3 target,
                   @Nullable CannonBallisticsCompat.ShotProfile profile, Vec3 inheritedVelocity,
                   @Nullable DoubleFunction<Vec3> targetAtAngle, double elevationCorrection, boolean fixedElevation) {
            this(muzzle, forward, target, profile, inheritedVelocity, targetAtAngle, elevationCorrection,
                    fixedElevation, null);
        }
        public Ray(Vec3 muzzle, Vec3 forward, Vec3 target,
                   @Nullable CannonBallisticsCompat.ShotProfile profile, Vec3 inheritedVelocity,
                   @Nullable DoubleFunction<Vec3> targetAtAngle, double elevationCorrection) {
            this(muzzle, forward, target, profile, inheritedVelocity, targetAtAngle, elevationCorrection, true);
        }
        public Ray(Vec3 muzzle, Vec3 forward, Vec3 target,
                   @Nullable CannonBallisticsCompat.ShotProfile profile, Vec3 inheritedVelocity,
                   @Nullable DoubleFunction<Vec3> targetAtAngle) {
            this(muzzle, forward, target, profile, inheritedVelocity, targetAtAngle, 0, false);
        }
        public Ray(Vec3 muzzle, Vec3 forward, Vec3 target,
                   @Nullable CannonBallisticsCompat.ShotProfile profile) {
            this(muzzle, forward, target, profile, Vec3.ZERO, null);
        }
    }

    public static double correction(List<Ray> rays, Vec3 pivot, Vec3 axis, Vec3 up, boolean yaw) {
        if (rays.isEmpty()) return 0.0;
        double seed = 0.0;
        if (yaw) {
            double sine = 0.0, cosine = 0.0;
            boolean reachable = false;
            for (Ray ray : rays) {
                Vec3 forward = transverse(ray.forward(), axis).normalize();
                Vec3 radial = transverse(ray.parallelSight() == null ? ray.target().subtract(pivot)
                        : ray.parallelSight(), axis);
                if (forward.lengthSqr() < 1e-12 || radial.lengthSqr() < 1e-12) continue;
                Vec3 side = axis.cross(forward);
                if (ray.parallelSight() != null) {
                    double heading = Math.atan2(radial.dot(side), radial.dot(forward));
                    sine += Math.sin(heading);
                    cosine += Math.cos(heading);
                    reachable = true;
                    continue;
                }
                Vec3 offset = ray.muzzle().subtract(pivot);
                double lateral = offset.dot(side), axial = offset.dot(forward), radius = radial.length();
                double heading = Math.atan2(radial.dot(side), radial.dot(forward));
                // A target inside the muzzle's swept radius can have no forward
                // ray solution. Select its nearest muzzle pose, never a rearward
                // inverse that reverses by 180 degrees on consecutive ticks.
                boolean front = radius * radius > lateral * lateral
                        && Math.sqrt(radius * radius - lateral * lateral) > axial;
                double angle = heading - (front ? Math.asin(Math.clamp(lateral / radius, -1.0, 1.0))
                        : Math.atan2(lateral, axial));
                sine += Math.sin(angle);
                cosine += Math.cos(angle);
                reachable |= front;
            }
            seed = Math.atan2(sine, cosine);
            if (!reachable && rays.stream().allMatch(ray -> ray.targetAtAngle() == null)) return seed;
        }

        double angle = seed;
        double residual = error(rays, pivot, axis, up, yaw, angle);
        double best = angle, bestError = Math.abs(residual);
        double initial = error(rays, pivot, axis, up, yaw, 0.0);
        if (Double.isFinite(initial) && (!Double.isFinite(bestError) || Math.abs(initial) < bestError)) {
            best = 0.0;
            bestError = Math.abs(initial);
        }
        // The analytic muzzle-parallax seed solves ordinary yaw immediately.
        // A bounded Newton solve also accounts for gravity on tilted axes and
        // elevation-dependent muzzle movement, without tick-to-tick feedback.
        for (int iteration = 0; iteration < 6 && Double.isFinite(residual); iteration++) {
            if (Math.abs(residual) < 1e-8) return wrap(angle);
            double probe = 1e-4;
            double derivative = wrap(error(rays, pivot, axis, up, yaw, angle + probe) - residual) / probe;
            if (!Double.isFinite(derivative) || Math.abs(derivative) < 1e-5) break;
            double step = Math.clamp(-residual / derivative, -Math.PI / 4, Math.PI / 4);
            double next = angle + step;
            double nextError = error(rays, pivot, axis, up, yaw, next);
            for (int backoff = 0; backoff < 3 && (!Double.isFinite(nextError)
                    || Math.abs(nextError) > Math.abs(residual)); backoff++) {
                step *= 0.5;
                next = angle + step;
                nextError = error(rays, pivot, axis, up, yaw, next);
            }
            if (!Double.isFinite(nextError) || Math.abs(nextError) >= Math.abs(residual)) break;
            angle = next;
            residual = nextError;
            if (Math.abs(residual) < bestError || !Double.isFinite(bestError)) {
                best = angle;
                bestError = Math.abs(residual);
            }
        }
        return Double.isFinite(bestError) ? wrap(best) : seed;
    }

    private static double error(List<Ray> rays, Vec3 pivot, Vec3 axis, Vec3 up, boolean yaw, double angle) {
        Quaterniond rotation = new Quaterniond().rotationAxis(angle, axis.x, axis.y, axis.z);
        Vec3 from = Vec3.ZERO, to = Vec3.ZERO;
        for (Ray ray : rays) {
            Vec3 muzzle = pivot.add(rotate(rotation, ray.muzzle().subtract(pivot)));
            Vec3 forward = rotate(rotation, ray.forward());
            Vec3 target = ray.targetAtAngle() == null ? ray.target() : ray.targetAtAngle().apply(angle);
            if (target == null) return Double.NaN;
            Vec3 requested = ray.fixedElevation()
                    ? LaserRangeCompensation.launchDirection(ray.profile(), muzzle, forward,
                            ray.inheritedVelocity(), target, ray.elevationCorrection(), ray.parallelSight())
                    : ray.profile() == null ? target.subtract(muzzle).normalize()
                            : CannonBallisticsCompat.solveLaunchDirection(CannonBallisticsCompat.withFrame(
                                    ray.profile(), muzzle, forward, ray.inheritedVelocity()), target);
            if (requested == null || requested.lengthSqr() < 1e-12) return Double.NaN;
            if (!yaw) requested = pitchDirection(requested, forward, up);
            from = from.add(transverse(forward, axis).normalize());
            to = to.add(transverse(requested, axis).normalize());
        }
        if (from.lengthSqr() < 1e-12 || to.lengthSqr() < 1e-12) return Double.NaN;
        return Math.atan2(axis.dot(from.cross(to)), from.dot(to));
    }

    static Vec3 pitchDirection(Vec3 requested, Vec3 forward, Vec3 up) {
        double vertical = Math.clamp(requested.dot(up), -1.0, 1.0);
        Vec3 horizontal = transverse(forward, up);
        if (horizontal.lengthSqr() < 1e-12) return forward;
        return horizontal.normalize().scale(Math.sqrt(Math.max(0.0, 1.0 - vertical * vertical)))
                .add(up.scale(vertical)).normalize();
    }

    private static Vec3 transverse(Vec3 value, Vec3 axis) { return value.subtract(axis.scale(value.dot(axis))); }
    private static double wrap(double angle) { return Math.atan2(Math.sin(angle), Math.cos(angle)); }
    private static Vec3 rotate(Quaterniond rotation, Vec3 value) {
        Vector3d transformed = rotation.transform(new Vector3d(value.x, value.y, value.z));
        return new Vec3(transformed.x, transformed.y, transformed.z);
    }
}
