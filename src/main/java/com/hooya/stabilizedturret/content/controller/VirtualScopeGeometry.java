package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Geometry shared by validation and camera placement, always in world coordinates. */
public final class VirtualScopeGeometry {
    private static final double AXIS_TOLERANCE = 1.0 / 16.0;

    public record Axis(Vec3 pivot, Vec3 direction) {
        public Axis {
            direction = direction.normalize();
        }
    }

    public static boolean coaxial(Axis first, Axis other) {
        return finite(first) && finite(other)
                && Math.abs(first.direction.dot(other.direction)) >= 0.9999
                && distanceSquared(first, other.pivot) <= AXIS_TOLERANCE * AXIS_TOLERANCE;
    }

    private static boolean finite(Axis axis) {
        return Double.isFinite(axis.pivot.x) && Double.isFinite(axis.pivot.y)
                && Double.isFinite(axis.pivot.z) && Double.isFinite(axis.direction.x)
                && Double.isFinite(axis.direction.y) && Double.isFinite(axis.direction.z)
                && axis.direction.lengthSqr() > 0.99;
    }

    public static double distanceSquared(Axis axis, Vec3 point) {
        return point.subtract(axis.pivot).cross(axis.direction).lengthSqr();
    }

    /** Use the nearest distinct gun axis; coaxial twin guns share a midpoint. */
    public static @Nullable Axis nearestCannonAxis(Vec3 bearing, List<Axis> cannons) {
        Axis nearest = cannons.stream().filter(VirtualScopeGeometry::finite)
                .min(java.util.Comparator.comparingDouble(axis -> distanceSquared(axis, bearing)))
                .orElse(null);
        if (nearest == null) return null;
        return midpoint(cannons.stream().filter(axis -> coaxial(nearest, axis)).toList());
    }

    /** The bound physical bearing owns the axis; guns only select a position along it. */
    public static Axis cameraAxis(Axis bearing, @Nullable Axis cannon) {
        if (cannon == null) return bearing;
        double along = cannon.pivot.subtract(bearing.pivot).dot(bearing.direction);
        return new Axis(bearing.pivot.add(bearing.direction.scale(along)), bearing.direction);
    }

    /** Midpoint of the outermost pivots, independent of binding order or duplicate guns. */
    public static @Nullable Axis midpoint(List<Axis> axes) {
        if (axes.isEmpty()) return null;
        Axis first = axes.getFirst();
        if (!axes.stream().allMatch(axis -> coaxial(first, axis))) return null;
        double min = 0, max = 0;
        for (Axis axis : axes) {
            double along = axis.pivot.subtract(first.pivot).dot(first.direction);
            min = Math.min(min, along);
            max = Math.max(max, along);
        }
        return new Axis(first.pivot.add(first.direction.scale((min + max) * 0.5)), first.direction);
    }

    private VirtualScopeGeometry() {}
}
