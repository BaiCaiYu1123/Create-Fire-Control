package com.hooya.stabilizedturret.content.bearing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Vector3d;

/** Regression checks for Simulated's child-side rotary constraint anchor. */
public final class BearingAssemblyPivotChecks {
    private static final double EPSILON = 1.0e-9;

    public static void main(String[] args) {
        assertPivot(Direction.UP, 100.5, 201.499, 300.5);
        assertPivot(Direction.DOWN, 100.5, 199.501, 300.5);
        assertPivot(Direction.NORTH, 100.5, 200.5, 299.501);
        assertPivot(Direction.SOUTH, 100.5, 200.5, 301.499);
        assertPivot(Direction.WEST, 99.501, 200.5, 300.5);
        assertPivot(Direction.EAST, 101.499, 200.5, 300.5);
    }

    private static void assertPivot(
            Direction facing,
            double expectedX,
            double expectedY,
            double expectedZ) {
        Vector3d actual = BearingAssemblyPosition.childConstraintPivot(
                new BlockPos(100, 200, 300), facing, new Vector3d());
        if (Math.abs(actual.x - expectedX) > EPSILON
                || Math.abs(actual.y - expectedY) > EPSILON
                || Math.abs(actual.z - expectedZ) > EPSILON) {
            throw new AssertionError("wrong assembly pivot for " + facing
                    + ": " + actual);
        }
    }

    private BearingAssemblyPivotChecks() {
    }
}
