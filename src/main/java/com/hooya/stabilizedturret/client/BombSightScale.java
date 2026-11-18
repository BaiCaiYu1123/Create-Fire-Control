package com.hooya.stabilizedturret.client;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Scales the ground footprint so its projected diameter keeps the reference screenshot's screen share. */
public final class BombSightScale {
    // The reference is 2560 x 1440; the marked white circle spans approximately 195 pixels.
    public static final double SCREEN_DIAMETER = 195.0 / 1440.0;
    private static final int SAMPLES = 64;
    private static final double[] COS = new double[SAMPLES], SIN = new double[SAMPLES];
    static {
        for (int i = 0; i < SAMPLES; i++) {
            COS[i] = Math.cos(Math.PI * 2 * i / SAMPLES);
            SIN[i] = Math.sin(Math.PI * 2 * i / SAMPLES);
        }
    }

    public static double worldRadius(Vec3 relativeCentre, Matrix4fc view, Matrix4fc projection, double aspect) {
        if (!(aspect > 0) || !Double.isFinite(aspect)) return 0;
        Matrix4f matrix = new Matrix4f(projection).mul(view);
        double cx = matrix.m00() * relativeCentre.x + matrix.m10() * relativeCentre.y + matrix.m20() * relativeCentre.z + matrix.m30();
        double cy = matrix.m01() * relativeCentre.x + matrix.m11() * relativeCentre.y + matrix.m21() * relativeCentre.z + matrix.m31();
        double cw = matrix.m03() * relativeCentre.x + matrix.m13() * relativeCentre.y + matrix.m23() * relativeCentre.z + matrix.m33();
        double focal = Math.abs(projection.m11());
        if (!Double.isFinite(cx) || !Double.isFinite(cy) || !Double.isFinite(cw) || cw <= 0.001 || !(focal > 1.0e-6)) return 0;
        double depthVariation = Math.hypot(matrix.m03(), matrix.m23());
        double limit = depthVariation > 1.0e-9 ? cw * 0.99 / depthVariation : Double.MAX_VALUE;
        double high = Math.min(limit, cw * SCREEN_DIAMETER / focal);
        for (int i = 0; i < 32 && diameter(matrix, cx, cy, cw, high, aspect) < SCREEN_DIAMETER; i++) {
            if (high >= limit) return 0;
            high = Math.min(limit, high * 2);
        }
        double low = 0;
        for (int i = 0; i < 24; i++) {
            double radius = (low + high) * 0.5;
            if (diameter(matrix, cx, cy, cw, radius, aspect) < SCREEN_DIAMETER) low = radius;
            else high = radius;
        }
        return (low + high) * 0.5;
    }

    private static double diameter(Matrix4fc m, double cx, double cy, double cw, double radius, double aspect) {
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < SAMPLES; i++) {
            double x = radius * COS[i], z = radius * SIN[i];
            double w = cw + m.m03() * x + m.m23() * z;
            if (w <= 0.0001) return Double.POSITIVE_INFINITY;
            double px = (cx + m.m00() * x + m.m20() * z) / w;
            double py = (cy + m.m01() * x + m.m21() * z) / w;
            minX = Math.min(minX, px); maxX = Math.max(maxX, px);
            minY = Math.min(minY, py); maxY = Math.max(maxY, py);
        }
        // Both spans are in units of viewport height, independent of resolution or GUI scale.
        return Math.max((maxX - minX) * aspect, maxY - minY) * 0.5;
    }
    private BombSightScale() {}
}
