package com.hooya.stabilizedturret.content.bearing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Position convention used by Simulated when it creates a rotary bearing constraint. */
final class BearingAssemblyPosition {
    // Simulated 1.3.0 passes the float literal 0.001F to Vec3.scale(double).
    private static final double CONSTRAINT_GAP = 0.0010000000474974513;

    static Vector3d childConstraintPivot(
            BlockPos platePos,
            Direction plateFacing,
            Vector3d destination) {
        Vec3 anchor = Vec3.atCenterOf(platePos.relative(plateFacing))
                .subtract(Vec3.atLowerCornerOf(plateFacing.getNormal())
                        .scale(CONSTRAINT_GAP));
        return destination.set(anchor.x, anchor.y, anchor.z);
    }

    private BearingAssemblyPosition() {
    }
}
