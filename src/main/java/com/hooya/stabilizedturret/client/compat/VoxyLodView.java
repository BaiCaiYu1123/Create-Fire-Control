package com.hooya.stabilizedturret.client.compat;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** CPU equivalent of Voxy's section screen-area subdivision test. */
public record VoxyLodView(Vec3 origin, Vec3 forward, Vec3 right, Vec3 up,
                          double halfTanX, double halfTanY,
                          double minimumScreenArea, double fullDetailDistance) {
    public VoxyLodView {
        forward = forward.normalize(); right = right.normalize(); up = up.normalize();
        if (!(halfTanX > 0 && halfTanY > 0 && minimumScreenArea > 0 && fullDetailDistance >= 0))
            throw new IllegalArgumentException("Invalid Voxy detail view");
    }

    public boolean shouldRefine(AABB section) {
        double dx = Math.max(Math.max(section.minX - origin.x, origin.x - section.maxX), 0);
        double dz = Math.max(Math.max(section.minZ - origin.z, origin.z - section.maxZ), 0);
        if (dx * dx + dz * dz <= fullDetailDistance * fullDetailDistance) return true;
        double[] sx = new double[8], sy = new double[8];
        double minX = Double.POSITIVE_INFINITY, minY = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3((i & 1) == 0 ? section.minX : section.maxX,
                    (i & 2) == 0 ? section.minY : section.maxY,
                    (i & 4) == 0 ? section.minZ : section.maxZ).subtract(origin);
            double depth = corner.dot(forward);
            if (depth <= 1e-6) return true;
            sx[i] = .5 + corner.dot(right) / (2 * depth * halfTanX);
            sy[i] = .5 + corner.dot(up) / (2 * depth * halfTanY);
            minX = Math.min(minX, sx[i]); maxX = Math.max(maxX, sx[i]);
            minY = Math.min(minY, sy[i]); maxY = Math.max(maxY, sy[i]);
        }
        double area = (cross(sx, sy, 0, 1, 2) + cross(sx, sy, 0, 1, 4)
                + cross(sx, sy, 0, 4, 2) + cross(sx, sy, 7, 6, 5)
                + cross(sx, sy, 7, 6, 3) + cross(sx, sy, 7, 3, 5)) * .5;
        double centerX = (minX + maxX - 1) * halfTanX;
        double centerY = (minY + maxY - 1) * halfTanY;
        double stretchMax = Math.pow(1 + halfTanX * halfTanX + halfTanY * halfTanY, 1.5);
        double stretchNode = Math.pow(1 + centerX * centerX + centerY * centerY, 1.5);
        return !Double.isFinite(area) || area > minimumScreenArea
                || area * stretchMax / stretchNode > minimumScreenArea;
    }

    private static double cross(double[] x, double[] y, int origin, int a, int b) {
        return Math.abs((x[a] - x[origin]) * (y[b] - y[origin])
                - (y[a] - y[origin]) * (x[b] - x[origin]));
    }
}
