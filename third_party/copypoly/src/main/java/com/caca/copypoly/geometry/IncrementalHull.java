// SPDX-License-Identifier: MIT
// CopyPoly integration; see third_party/copypoly/LICENSE.

package com.caca.copypoly.geometry;

import com.caca.copypoly.geometry.Polyhedron.Plane;
import com.caca.copypoly.geometry.Polyhedron.Point;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds the supporting planes of a large hull using exact lattice predicates. */
final class IncrementalHull {
    private record Facet(int a, int b, int c, Plane plane) {}

    private record Edge(int from, int to) {
        long key() {
            return ((long) Math.min(from, to) << 32) | Math.max(from, to);
        }
    }

    private IncrementalHull() {
    }

    static Set<Plane> planes(List<Point> points) {
        int a = 0, b = points.size() - 1, c = -1;
        Plane base = null;
        for (int i = 1; i < b; i++) {
            try {
                base = Polyhedron.trianglePlane(points.get(a), points.get(b), points.get(i));
                c = i;
                break;
            } catch (IllegalArgumentException collinear) {
                // Find the first point away from the initial edge.
            }
        }
        if (base == null) throw new IllegalArgumentException("flat");
        int d = -1;
        long height = 0;
        for (int i = 0; i < points.size(); i++) {
            long distance = Math.abs(base.side(points.get(i)));
            if (distance > height) {
                height = distance;
                d = i;
            }
        }
        if (d < 0) throw new IllegalArgumentException("flat");

        List<Point> seed = List.of(points.get(a), points.get(b), points.get(c), points.get(d));
        var facets = new ArrayList<>(List.of(
                orient(points, seed, a, b, c), orient(points, seed, a, b, d),
                orient(points, seed, a, c, d), orient(points, seed, b, c, d)));
        for (int i = 0; i < points.size(); i++) {
            if (i == a || i == b || i == c || i == d) continue;
            Point point = points.get(i);
            var horizon = new LinkedHashMap<Long, Edge>();
            facets.removeIf(facet -> {
                if (facet.plane.side(point) <= 0) return false;
                toggle(horizon, facet.a, facet.b);
                toggle(horizon, facet.b, facet.c);
                toggle(horizon, facet.c, facet.a);
                return true;
            });
            for (var edge : horizon.values()) {
                facets.add(orient(points, seed, edge.from, edge.to, i));
            }
        }
        Set<Plane> planes = new LinkedHashSet<>();
        for (var facet : facets) planes.add(facet.plane);
        return planes;
    }

    private static void toggle(Map<Long, Edge> horizon, int a, int b) {
        var edge = new Edge(a, b);
        long key = edge.key();
        if (horizon.remove(key) == null) horizon.put(key, edge);
    }

    private static Facet orient(List<Point> points, List<Point> seed, int a, int b, int c) {
        Plane plane = Polyhedron.trianglePlane(points.get(a), points.get(b), points.get(c));
        // The seed tetrahedron's centroid stays strictly inside every later hull.
        long interior = 0;
        for (Point point : seed) interior += plane.side(point);
        if (interior > 0) {
            return new Facet(a, c, b, new Plane(-plane.x(), -plane.y(), -plane.z(), -plane.d()));
        }
        return new Facet(a, b, c, plane);
    }
}
