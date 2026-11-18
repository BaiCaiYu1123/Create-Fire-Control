// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// CopyPoly 1.9.1; see third_party/copypoly/LICENSE.

package com.caca.copypoly.geometry;

import java.math.BigDecimal;
import java.util.List;
import static com.caca.copypoly.geometry.Polyhedron.PIXEL_SCALE;

/** Pixel-space snapping relative to where the drag began. */
public final class VertexEditing {
    private VertexEditing() {}

    public static double snap(double start, double delta, boolean fine) {
        int step = fine ? 100 : PIXEL_SCALE;
        long ticks = Math.round(start * PIXEL_SCALE) + Math.round(delta * PIXEL_SCALE / step) * step;
        return Math.clamp(ticks, 0, 16 * PIXEL_SCALE) / (double) PIXEL_SCALE;
    }

    public static List<Polyhedron.Point> translate(List<Polyhedron.Point> points,int axis,double delta,boolean fine) {
        return translate(points, axis, delta, fine, ShapeBounds.BLOCK);
    }

    /** Move every selected point by the same offset, stopping at the edit bounds. */
    public static List<Polyhedron.Point> translate(List<Polyhedron.Point> points,int axis,double delta,boolean fine,ShapeBounds bounds) {
        if (points.isEmpty()) return List.of();
        if (axis < 0 || axis > 2 || !Double.isFinite(delta)) throw new IllegalArgumentException("axis");
        int limit = bounds.axis(axis) * PIXEL_SCALE;
        long min = limit, max = 0;
        for (var p : points) {
            long ticks = Math.round(p.axis(axis) * PIXEL_SCALE);
            min = Math.min(min, ticks);
            max = Math.max(max, ticks);
        }
        int step = fine ? 100 : PIXEL_SCALE;
        long offset = Math.clamp(Math.round(delta * PIXEL_SCALE / step) * step, -min, limit - max);
        return points.stream().map(p -> bounds.withAxis(p, axis, (Math.round(p.axis(axis) * PIXEL_SCALE) + offset) / (double) PIXEL_SCALE)).toList();
    }

    public static String format(double value) {
        return BigDecimal.valueOf(Math.round(value * PIXEL_SCALE), 3).stripTrailingZeros().toPlainString();
    }

    public static Polyhedron.Point mirror(Polyhedron.Point point, int axis) {
        return mirror(point, axis, ShapeBounds.BLOCK);
    }

    public static Polyhedron.Point mirror(Polyhedron.Point point, int axis, ShapeBounds bounds) {
        if (axis < 0 || axis > 2) throw new IllegalArgumentException("axis");
        return bounds.withAxis(point, axis, bounds.axis(axis) - point.axis(axis));
    }

    public static Polyhedron.Point rotate(Polyhedron.Point point, int axis, int quarterTurns) {
        return rotate(point, axis, quarterTurns, ShapeBounds.BLOCK);
    }

    /** Quarter turns around the group center. The edited shape must fit the same bounds. */
    public static Polyhedron.Point rotate(Polyhedron.Point point, int axis, int quarterTurns, ShapeBounds bounds) {
        if (axis < 0 || axis > 2) throw new IllegalArgumentException("axis");
        int cx = bounds.x() * PIXEL_SCALE / 2, cy = bounds.y() * PIXEL_SCALE / 2, cz = bounds.z() * PIXEL_SCALE / 2;
        int x = point.tx() - cx, y = point.ty() - cy, z = point.tz() - cz;
        for (int turn = 0; turn < Math.floorMod(quarterTurns, 4); turn++) {
            int a = x, b = y, c = z;
            if (axis == 0) { y = -c; z = b; }
            else if (axis == 1) { x = c; z = -a; }
            else { x = -b; y = a; }
        }
        return bounds.point((x + cx) / (double) PIXEL_SCALE, (y + cy) / (double) PIXEL_SCALE, (z + cz) / (double) PIXEL_SCALE);
    }
}
