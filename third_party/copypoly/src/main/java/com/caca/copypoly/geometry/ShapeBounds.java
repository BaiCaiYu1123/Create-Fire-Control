// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// CopyPoly 1.9.1; see third_party/copypoly/LICENSE.

package com.caca.copypoly.geometry;

/** Editable pixel coordinates relative to the minimum corner of a block or a group. */
public record ShapeBounds(int x, int y, int z) {
    public static final int MAX_BLOCK_SPAN = 32;
    public static final ShapeBounds BLOCK = new ShapeBounds(16, 16, 16);

    public ShapeBounds {
        for (int extent : new int[]{x, y, z}) {
            if (extent < 16 || extent > 16 * MAX_BLOCK_SPAN || extent % 16 != 0)
                throw new IllegalArgumentException("range");
        }
    }

    public int axis(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
    public boolean multiBlock() { return x > 16 || y > 16 || z > 16; }
    public int maxPoints() { return multiBlock() ? 512 : Polyhedron.MAX_POINTS; }
    public int maxTriangles() { return multiBlock() ? 2048 : Polyhedron.MAX_TRIANGLES; }
    public int maxLinks() { return multiBlock() ? 4096 : 4032; }

    public Polyhedron.Point point(double px, double py, double pz) {
        return Polyhedron.Point.inBounds(px, py, pz, this);
    }

    public void require(Polyhedron.Point point) {
        if (point.x() > x || point.y() > y || point.z() > z) throw new IllegalArgumentException("range");
    }

    public Polyhedron.Point withAxis(Polyhedron.Point p, int axis, double value) {
        return point(axis == 0 ? value : p.x(), axis == 1 ? value : p.y(), axis == 2 ? value : p.z());
    }
}
