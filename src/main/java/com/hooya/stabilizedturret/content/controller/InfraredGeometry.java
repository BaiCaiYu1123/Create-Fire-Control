package com.hooya.stabilizedturret.content.controller;

/** Measured from the supplied, original 2560 x 1440 reference, in physical pixels. */
public final class InfraredGeometry {
    public static final double WIDTH = 2560, HEIGHT = 1440;
    public static final double CENTER_X = 1273.5, CENTER_Y = 650.5, RADIUS = 886.75;
    public static final double SEEKER_X = 1274.5, SEEKER_Y = 605.5, SEEKER_RADIUS = 40;
    public static final double STROKE = 3.0, VIEW_RADIUS = 28.5;
    // Foreground before alpha blending, recovered against both bright and dark reference backgrounds.
    public static final int GREEN = 0xFF11D700, RED = 0xFFFF2920, VIEW_GREEN = 0xFF14FF00;
    public static final double RANGE = 2000;
    // Reference-sized 2D angular guide. This is a local display calibration,
    // not a recovered War Thunder engine formula or a projected 2 km world rim.
    public static final double HUD_REFERENCE_HALF_TAN_Y = Math.tan(Math.toRadians(35));
    /** True forward hemisphere, including the +/-90 degree equator. */
    public static final double GIMBAL_HALF_ANGLE_DEGREES = 90;
    public static final double GIMBAL_TANGENT = Double.POSITIVE_INFINITY;
    public static final double GIMBAL_COS = 0, GIMBAL_SIN = 1;
    // Local 2 Hz approximation; public War Thunder HUD sources do not expose its frequency.
    // 250 ms on, 250 ms off at 20 TPS. Both circles share one phase.
    public static final int BLINK_HALF_PERIOD = 5, BLINK_TICKS = 4 * 2 * BLINK_HALF_PERIOD;

    public static double referenceX(double screenX, double aspect) {
        return (screenX - .5) * HEIGHT * aspect + WIDTH * .5;
    }
    public static double screenX(double referenceX, double aspect) {
        return .5 + (referenceX - WIDTH * .5) / (HEIGHT * aspect);
    }
    public static boolean contains(double screenX, double screenY, double aspect) {
        return Math.hypot(referenceX(screenX, aspect) - CENTER_X, screenY * HEIGHT - CENTER_Y) <= RADIUS;
    }
    public static boolean blinking(long now, long lostAt) {
        return lostAt != Long.MIN_VALUE && now >= lostAt && now - lostAt < BLINK_TICKS;
    }
    public static boolean visible(long now, long lostAt) {
        return pulseVisible(blinking(now, lostAt) ? now - lostAt : now);
    }
    public static boolean pulseVisible(double elapsedTicks) {
        return (long) Math.floor(Math.max(0, elapsedTicks) / BLINK_HALF_PERIOD) % 2 == 0;
    }
    private InfraredGeometry() {}
}
