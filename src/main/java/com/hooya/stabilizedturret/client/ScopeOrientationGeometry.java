package com.hooya.stabilizedturret.client;

/** Screen-up is the scope's viewing direction; both outlines rotate independently. */
final class ScopeOrientationGeometry {
    static final double REFERENCE_HEIGHT = 800.0;
    static final double RADIUS = 54.0;
    private static final double[][] HULL = {
            {-14, -33}, {14, -33}, {19, -24}, {19, 30}, {14, 35}, {-14, 35}, {-19, 30}, {-19, -24}
    };
    private static final double[][] LEFT_TRACK = {
            {-24, -30}, {-19, -34}, {-19, 34}, {-24, 30}
    };
    private static final double[][] RIGHT_TRACK = {
            {24, -30}, {19, -34}, {19, 34}, {24, 30}
    };
    private static final double[][] TURRET = {
            {-7, -14}, {7, -14}, {13, -6}, {12, 10}, {7, 15}, {-7, 15}, {-12, 10}, {-13, -6}
    };

    record Layout(double x, double y, double scale) {}
    interface Lines { void line(double x0, double y0, double x1, double y1); }

    static Layout layout(int width, int height) {
        // Same normalized region as the user's lower-right reference; preserve tank proportions.
        double scale = Math.min(height / REFERENCE_HEIGHT, width / 1000.0);
        double x = Math.min(width * 0.825, width - 12 - (RADIUS + 3) * scale);
        return new Layout(x, height - 112 * scale, scale);
    }

    static void hull(double degrees, Lines lines) {
        outline(HULL, degrees, lines);
        outline(LEFT_TRACK, degrees, lines);
        outline(RIGHT_TRACK, degrees, lines);
        // A pointed glacis and a flat rear make front/back unambiguous.
        segment(-12, -25, 0, -31, degrees, lines);
        segment(0, -31, 12, -25, degrees, lines);
        segment(-13, 25, 13, 25, degrees, lines);
        for (int y = -23; y <= 23; y += 9) {
            segment(-24, y, -20, y, degrees, lines);
            segment(20, y, 24, y, degrees, lines);
        }
    }

    static void turret(double degrees, Lines lines) {
        outline(TURRET, degrees, lines);
        segment(-2, -14, -2, -46, degrees, lines);
        segment(2, -14, 2, -46, degrees, lines);
        segment(-3, -46, 3, -46, degrees, lines);
        segment(-3, -46, -3, -42, degrees, lines);
        segment(3, -46, 3, -42, degrees, lines);
        // Compact hatch, drawn with straight strokes like the rest of the silhouette.
        segment(-4, -3, 4, -3, degrees, lines);
        segment(4, -3, 4, 4, degrees, lines);
        segment(4, 4, -4, 4, degrees, lines);
        segment(-4, 4, -4, -3, degrees, lines);
    }

    static void dial(Lines lines) {
        // Just four reference ticks and a view-direction chevron, without an opaque panel.
        for (int i = 0; i < 4; i++) segment(0, -RADIUS, 0, -RADIUS + 4, i * 90, lines);
        segment(-3, -RADIUS + 4, 0, -RADIUS, 0, lines);
        segment(0, -RADIUS, 3, -RADIUS + 4, 0, lines);
    }

    private static void outline(double[][] points, double degrees, Lines lines) {
        for (int i = 0; i < points.length; i++) {
            double[] a = points[i], b = points[(i + 1) % points.length];
            segment(a[0], a[1], b[0], b[1], degrees, lines);
        }
    }

    private static void segment(double x0, double y0, double x1, double y1, double degrees, Lines lines) {
        double cos = Math.cos(Math.toRadians(degrees)), sin = Math.sin(Math.toRadians(degrees));
        lines.line(x0 * cos - y0 * sin, x0 * sin + y0 * cos,
                x1 * cos - y1 * sin, x1 * sin + y1 * cos);
    }

    private ScopeOrientationGeometry() {}
}
