package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

/** Measurements in pixels of the supplied, uncropped 2560 x 1440 reference. */
public final class AntiRadiationGeometry {
    public static final double WIDTH = 2560, HEIGHT = 1440;
    public static final int LEFT = 1041, TOP = 470, RIGHT = 1519, BOTTOM = 970;
    public static final int ARM = 30, STROKE = 3, TARGET_WIDTH = 34, TARGET_HEIGHT = 36;
    public static final int RANGE_COLOR = 0x99000000, TARGET_COLOR = 0x66000000;
    // Provisional 1 Hz slow blink. Public WT HUD scripts do not expose the ARM search blink period.
    public static final int BLINK_HALF_PERIOD_TICKS = 10;
    // Keep the reference's 478 x 500 footprint at the standard 70-degree vertical FOV.
    private static final double REFERENCE_TAN = Math.tan(Math.toRadians(35));
    public static final double HALF_X = (RIGHT - LEFT) / HEIGHT * REFERENCE_TAN;
    public static final double HALF_Y = (BOTTOM - TOP) / HEIGHT * REFERENCE_TAN;

    /** Missile/rack axes, independent of the camera and mouse-flight steering circle. */
    public record Basis(Vec3 forward, Vec3 right, Vec3 up) {
        public static Basis of(Vec3 forward, Vec3 up) {
            forward = forward.normalize();
            Vec3 right = forward.cross(up).normalize();
            return new Basis(forward, right, right.cross(forward).normalize());
        }
        public Vec3 ray(double referenceX, double referenceY) {
            return forward.add(right.scale((referenceX - WIDTH / 2) * 2 * REFERENCE_TAN / HEIGHT))
                    .add(up.scale((HEIGHT / 2 - referenceY) * 2 * REFERENCE_TAN / HEIGHT));
        }
        public boolean contains(Vec3 relative) {
            double depth = relative.dot(forward);
            return depth > 0.05 && Math.abs(relative.dot(right)) <= depth * HALF_X + 1e-7
                    && Math.abs(relative.dot(up)) <= depth * HALF_Y + 1e-7;
        }
    }

    public static boolean searchFrameVisible(boolean enabled, boolean locked, double elapsedTicks) {
        return enabled && (locked || (long) Math.floor(Math.max(0, elapsedTicks) / BLINK_HALF_PERIOD_TICKS) % 2 == 0);
    }

    /** Height-based scaling keeps strokes and both frame shapes independent of GUI scale. */
    public static double referenceX(double screenX, double aspect) {
        return (screenX - 0.5) * HEIGHT * aspect + WIDTH * 0.5;
    }

    public static boolean contains(double screenX, double screenY, double aspect) {
        double x = referenceX(screenX, aspect), y = screenY * HEIGHT;
        double epsilon = 1.0e-7;
        return x >= LEFT - epsilon && x <= RIGHT + epsilon && y >= TOP - epsilon && y <= BOTTOM + epsilon;
    }

    private AntiRadiationGeometry() {}
}
