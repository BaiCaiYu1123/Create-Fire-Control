package com.hooya.stabilizedturret.content.display;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Simultaneous motion over one completed server tick, in root-world coordinates. */
final class MovingMissileFuze {
    record Contact(double fraction, Vec3 interceptor, Vec3 target) {}

    static @Nullable Contact firstContact(Vec3 interceptorFrom, Vec3 interceptorTo,
                                          Vec3 targetFrom, Vec3 targetTo, double range) {
        if (!Double.isFinite(range) || range <= 0 || !finite(interceptorFrom)
                || !finite(interceptorTo) || !finite(targetFrom) || !finite(targetTo)) return null;
        Vec3 relative = interceptorFrom.subtract(targetFrom);
        Vec3 travel = interceptorTo.subtract(interceptorFrom).subtract(targetTo.subtract(targetFrom));
        double c = relative.lengthSqr() - range * range;
        double fraction = 0;
        if (c > 0) {
            double a = travel.lengthSqr(), b = relative.dot(travel);
            if (a < 1e-24 || b >= 0) return null;
            double discriminant = b * b - a * c;
            if (discriminant < 0) return null;
            // Stable near root of a*t^2 + 2*b*t + c = 0.
            fraction = c / (-b + Math.sqrt(discriminant));
            if (fraction < 0 || fraction > 1) return null;
        }
        return new Contact(fraction,
                interceptorFrom.lerp(interceptorTo, fraction), targetFrom.lerp(targetTo, fraction));
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    private MovingMissileFuze() {}
}
