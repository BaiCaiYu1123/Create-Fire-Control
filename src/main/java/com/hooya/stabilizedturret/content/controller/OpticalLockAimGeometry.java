package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** The manual sight ray and measured weapon ray share the locked target's view-depth plane. */
public final class OpticalLockAimGeometry {
    private OpticalLockAimGeometry() {}

    public static @Nullable Vec3 atTargetDepth(Vec3 origin, Vec3 direction, Vec3 camera, Vec3 target) {
        if (!finite(origin) || !finite(direction) || !finite(camera) || !finite(target)) return null;
        Vec3 normal = target.subtract(camera);
        if (normal.lengthSqr() < 1e-8 || direction.lengthSqr() < 1e-8) return null;
        normal = normal.normalize();
        direction = direction.normalize();
        double denominator = direction.dot(normal);
        if (denominator <= 1e-6) return null;
        double distance = target.subtract(origin).dot(normal) / denominator;
        if (!Double.isFinite(distance) || distance <= 0) return null;
        Vec3 point = origin.add(direction.scale(distance));
        return finite(point) ? point : null;
    }

    /** Preserve a real obstacle before the target plane, rather than drawing through it. */
    public static @Nullable Vec3 marker(Vec3 origin, Vec3 direction, Vec3 camera, Vec3 target,
                                        @Nullable Vec3 nearerHit) {
        Vec3 point = atTargetDepth(origin, direction, camera, target);
        return point != null && finite(nearerHit) && origin.distanceToSqr(nearerHit) < origin.distanceToSqr(point)
                ? nearerHit : point;
    }

    private static boolean finite(@Nullable Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
