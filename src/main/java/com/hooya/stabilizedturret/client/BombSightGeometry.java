package com.hooya.stabilizedturret.client;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/** World-space ground footprint. No camera-facing vertices or screen-space fall line. */
public final class BombSightGeometry {
    public static final double RADIUS = 5.0;
    static final double SAMPLE_DEPTH = 8.0;
    static final double SURFACE_LIFT = 0.08;
    private static final int SEGMENTS = 64;
    public record Segment(Vec3 from, Vec3 to) {}

    public static List<Segment> create(Vec3 centre, UnaryOperator<Vec3> surface) {
        return create(centre, RADIUS, surface);
    }

    public static List<Segment> create(Vec3 centre, double radius, UnaryOperator<Vec3> surface) {
        List<Segment> result = new ArrayList<>(SEGMENTS + 8);
        if (!finite(centre) || !Double.isFinite(radius) || radius <= 0) return List.of();
        Vec3 previous = sample(centre.add(radius, 0, 0), surface);
        for (int i = 1; i <= SEGMENTS; i++) {
            double angle = Math.PI * 2 * i / SEGMENTS;
            Vec3 next = sample(centre.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius), surface);
            addSegment(result, previous, next);
            previous = next;
        }
        // A short cross remains entirely inside the ground ring.
        double arm = radius * 0.28;
        for (int axis = 0; axis < 2; axis++) {
            for (int i = 0; i < 4; i++) {
                double from = -arm + arm * 2 * i / 4, to = -arm + arm * 2 * (i + 1) / 4;
                addSegment(result, sample(centre.add(axis == 0 ? from : 0, 0, axis == 1 ? from : 0), surface),
                        sample(centre.add(axis == 0 ? to : 0, 0, axis == 1 ? to : 0), surface));
            }
        }
        return List.copyOf(result);
    }

    private static Vec3 sample(Vec3 requested, UnaryOperator<Vec3> surface) {
        Vec3 point = surface.apply(requested);
        // Vertical terrain probes must stay in their world column. Plot-local hits must never enter the mesh.
        if (!finite(point) || Math.abs(point.x - requested.x) > 1.0e-4 || Math.abs(point.z - requested.z) > 1.0e-4
                || Math.abs(point.y - requested.y) > SAMPLE_DEPTH + SURFACE_LIFT + 1.0e-4) return null;
        return point;
    }

    private static void addSegment(List<Segment> result, Vec3 from, Vec3 to) {
        // Leave a gap at cliffs and rejected samples instead of joining unrelated surfaces with a spike.
        if (from != null && to != null && Math.abs(from.y - to.y) <= 1.25) result.add(new Segment(from, to));
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
    private BombSightGeometry() {}
}
