package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** A range/elevation sample changes only on a rangefinder pulse, never on a head-aim tick. */
public record LaserRangeCompensation(double distance, double elevationRadians) {
    public static final LaserRangeCompensation ZERO = new LaserRangeCompensation(0, 0);

    public LaserRangeCompensation {
        if (!Double.isFinite(distance) || distance < 0 || !Double.isFinite(elevationRadians)
                || Math.abs(elevationRadians) > Math.PI / 2)
            throw new IllegalArgumentException("Invalid laser range compensation");
    }

    public static @Nullable LaserRangeCompensation measure(CannonBallisticsCompat.ShotProfile profile, Vec3 target) {
        if (profile == null || !finite(target)) return null;
        Vec3 displacement = target.subtract(profile.muzzle());
        if (displacement.lengthSqr() < 1e-12) return ZERO;
        Vec3 launch = CannonBallisticsCompat.solveLaunchDirection(
                CannonBallisticsCompat.withCarrierVelocity(profile, Vec3.ZERO), target);
        if (launch == null) return null;
        return new LaserRangeCompensation(displacement.length(), elevation(launch) - elevation(displacement));
    }

    /** Tilt in the gravity/world vertical plane, so vehicle roll cannot rotate drop compensation sideways. */
    public static Vec3 raise(Vec3 sight, double radians) {
        Vec3 direction = sight.normalize();
        if (Math.abs(radians) < 1e-12) return direction;
        double horizontal = Math.hypot(direction.x, direction.z);
        if (horizontal < 1e-10) return direction;
        double angle = Math.clamp(elevation(direction) + radians, -Math.PI / 2, Math.PI / 2);
        double scale = Math.cos(angle) / horizontal;
        return new Vec3(direction.x * scale, Math.sin(angle), direction.z * scale).normalize();
    }

    public static @Nullable Vec3 launchDirection(@Nullable CannonBallisticsCompat.ShotProfile profile,
            Vec3 muzzle, Vec3 forward, Vec3 velocity, Vec3 target, double radians) {
        return launchDirection(profile, muzzle, forward, velocity, target, radians, null);
    }

    /** A virtual scope uses terrain depth for motion compensation, never for muzzle convergence. */
    public static @Nullable Vec3 launchDirection(@Nullable CannonBallisticsCompat.ShotProfile profile,
            Vec3 muzzle, Vec3 forward, Vec3 velocity, Vec3 target, double radians,
            @Nullable Vec3 parallelSight) {
        Vec3 displacement = target.subtract(muzzle);
        if (parallelSight != null) {
            if (displacement.lengthSqr() < 1e-12) return raise(parallelSight, radians);
            displacement = parallelSight.normalize().scale(displacement.length());
        }
        if (displacement.lengthSqr() < 1e-12) return null;
        Vec3 raised = raise(displacement, radians);
        if (profile == null || velocity.lengthSqr() < 1e-12) return raised;
        // First predict where the saved angle would land without vehicle speed.
        // Motion compensation restores that same point; it never fixes a stale
        // range sample by silently adding new drop elevation.
        Vec3 stationaryImpact = stationaryPoint(profile, muzzle, raised, displacement.normalize(), displacement.length());
        if (stationaryImpact == null) return null;
        return CannonBallisticsCompat.solveLaunchDirection(
                CannonBallisticsCompat.withFrame(profile, muzzle, forward, velocity), stationaryImpact);
    }

    private static @Nullable Vec3 stationaryPoint(CannonBallisticsCompat.ShotProfile profile,
            Vec3 muzzle, Vec3 raised, Vec3 planeNormal, double range) {
        Vec3 position = muzzle, velocity = raised.scale(profile.muzzleSpeed());
        for (int tick = 0; tick < Math.min(1200, profile.maxFlightTicks()); tick++) {
            var step = CannonProjectileFlight.step(position, velocity, profile.gravity(), profile.drag(), profile.quadraticDrag());
            if (!finite(step.position()) || !finite(step.velocity())) return null;
            double from = position.subtract(muzzle).dot(planeNormal);
            double to = step.position().subtract(muzzle).dot(planeNormal);
            if (from < range && to >= range)
                return position.lerp(step.position(), (range - from) / (to - from));
            position = step.position();
            velocity = step.velocity();
        }
        return null;
    }

    private static double elevation(Vec3 direction) {
        return Math.atan2(direction.y, Math.hypot(direction.x, direction.z));
    }
    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
