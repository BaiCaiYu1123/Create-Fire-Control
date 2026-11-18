package com.hooya.stabilizedturret.client;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Crops a collision traversal to occupied bounds without changing the original ray. */
public final class AimRaycastGeometry {
    private AimRaycastGeometry() {}

    public record Segment(Vec3 from, Vec3 to) {}

    public static Segment crop(Vec3 from, Vec3 to, AABB bounds) {
        Vec3 delta = to.subtract(from);
        double enter = 0, exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            double start = axis == 0 ? from.x : axis == 1 ? from.y : from.z;
            double step = axis == 0 ? delta.x : axis == 1 ? delta.y : delta.z;
            double min = axis == 0 ? bounds.minX : axis == 1 ? bounds.minY : bounds.minZ;
            double max = axis == 0 ? bounds.maxX : axis == 1 ? bounds.maxY : bounds.maxZ;
            if (Math.abs(step) < 1e-12) {
                if (start < min || start > max) return null;
                continue;
            }
            double a = (min - start) / step, b = (max - start) / step;
            enter = Math.max(enter, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
            if (enter > exit) return null;
        }
        // Start just outside the entry face so VoxelShape.clip reports the
        // correct face rather than treating the ray as starting in the block.
        // VoxelShape.clip probes 0.001 of the segment ahead to detect an
        // inside start. Leave more than that probe outside the occupied box.
        double padding = Math.max(1e-4 / Math.max(1, delta.length()), (exit - enter) * .002);
        return new Segment(from.lerp(to, Math.max(0, enter - padding)),
                from.lerp(to, Math.min(1, exit + padding)));
    }
}
