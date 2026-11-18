// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// CopyPoly 1.9.1; see third_party/copypoly/LICENSE.

package com.caca.copypoly.geometry;

import java.util.*;

import static com.caca.copypoly.geometry.Polyhedron.Point;
import static com.caca.copypoly.geometry.Polyhedron.Triangle;

/** Dissolves redundant controls along a surface or crease without cutting a hole. */
public final class RegionVertexRemoval {
    private RegionVertexRemoval() {}

    public static List<Triangle> dissolve(List<Point> points, List<Triangle> faces, int vertex, ShapeBounds bounds) {
        List<Triangle> incident = faces.stream().filter(t -> t.uses(vertex)).toList();
        if (incident.isEmpty()) return contract(faces, vertex, -1);
        var neighbors = new LinkedHashSet<Integer>();
        for (var t : incident) for (int id : t.indices()) if (id != vertex) neighbors.add(id);
        for (int target : neighbors) {
            if (!preservesFaces(points, incident, vertex, target)) continue;
            List<Triangle> result = contract(faces, vertex, target);
            List<Point> kept = new ArrayList<>(points);
            kept.remove(vertex);
            try {
                return Polyhedron.mesh(kept, result, bounds).triangles();
            } catch (IllegalArgumentException badContraction) {
                // An edge with another shared neighbor can pinch two shells. Try the other direction.
            }
        }
        return null;
    }

    public record Simplification(List<Triangle> faces, Set<Integer> removed) {}

    /** Remove controls on a plane or straight crease in a consistently oriented closed mesh. */
    public static Simplification simplify(List<Point> points, List<Triangle> faces, Set<Integer> protectedVertices) {
        List<Triangle> remaining = new ArrayList<>(faces);
        Set<Integer> removed = new LinkedHashSet<>();
        for (int vertex = 0; vertex < points.size(); vertex++) {
            if (protectedVertices.contains(vertex)) continue;
            final int id = vertex;
            List<Triangle> incident = remaining.stream().filter(t -> t.uses(id)).toList();
            List<Triangle> replacement = fillRedundantVertex(points,incident,id);
            if (replacement == null) continue;
            remaining.removeIf(t -> t.uses(id));
            remaining.addAll(replacement);
            removed.add(id);
        }
        return new Simplification(List.copyOf(remaining),Set.copyOf(removed));
    }

    private record Wedge(int next, Polyhedron.Plane plane) {}

    private static List<Triangle> fillRedundantVertex(List<Point> points,List<Triangle> incident,int vertex) {
        if (incident.isEmpty()) return List.of();
        Map<Integer,Wedge> wedges = new LinkedHashMap<>();
        for (var face : incident) {
            int[] ids = face.indices();
            int at = ids[0] == vertex ? 0 : ids[1] == vertex ? 1 : 2;
            int from = ids[(at + 1) % 3], to = ids[(at + 2) % 3];
            var plane = Polyhedron.trianglePlane(points.get(vertex),points.get(from),points.get(to));
            if (wedges.put(from,new Wedge(to,plane)) != null) return null;
        }
        List<Integer> ring = new ArrayList<>();
        List<Polyhedron.Plane> planes = new ArrayList<>();
        int first = wedges.keySet().iterator().next(), current = first;
        do {
            Wedge step = wedges.get(current);
            if (step == null || ring.size() >= wedges.size()) return null;
            ring.add(current);
            planes.add(step.plane);
            current = step.next;
        } while (current != first);
        if (ring.size() != wedges.size()) return null;

        List<Integer> creases = new ArrayList<>();
        for (int i = 0; i < ring.size(); i++) {
            if (!planes.get(i).equals(planes.get(Math.floorMod(i - 1,ring.size())))) creases.add(i);
        }
        try {
            if (creases.isEmpty()) return Polyhedron.triangulatePolygon(points,ring);
            if (creases.size() != 2) return null;
            int a = creases.getFirst(), b = creases.getLast();
            if (!betweenOnLine(points.get(vertex),points.get(ring.get(a)),points.get(ring.get(b)))) return null;
            // Each half of the old fan keeps its plane. Its new boundary joins the two crease ends.
            var replacement = new ArrayList<>(Polyhedron.triangulatePolygon(points,ring.subList(a,b + 1)));
            var otherSide = new ArrayList<>(ring.subList(b,ring.size()));
            otherSide.addAll(ring.subList(0,a + 1));
            replacement.addAll(Polyhedron.triangulatePolygon(points,otherSide));
            return replacement;
        } catch (IllegalArgumentException invalidRing) {
            return null;
        }
    }

    private static boolean betweenOnLine(Point p, Point a, Point b) {
        long ux = (long)a.tx() - p.tx(), uy = (long)a.ty() - p.ty(), uz = (long)a.tz() - p.tz();
        long vx = (long)b.tx() - p.tx(), vy = (long)b.ty() - p.ty(), vz = (long)b.tz() - p.tz();
        return uy*vz == uz*vy && uz*vx == ux*vz && ux*vy == uy*vx && ux*vx + uy*vy + uz*vz < 0;
    }

    /** Compare oriented surface patches exactly, disregarding their tessellation. */
    public static boolean sameSurface(Polyhedron first, Polyhedron second) {
        return boundary(first).equals(boundary(second));
    }

    private record Line(int dx,int dy,int dz,long mx,long my,long mz) {
        Line(Point p,int x,int y,int z) {
            this(x,y,z,(long)p.ty()*z - (long)p.tz()*y,(long)p.tz()*x - (long)p.tx()*z,(long)p.tx()*y - (long)p.ty()*x);
        }
        int station(Point point) { return dx != 0 ? point.tx() : dy != 0 ? point.ty() : point.tz(); }
    }
    private record End(Polyhedron.Plane plane, Line line, int at) {}

    private static Map<End,Integer> boundary(Polyhedron mesh) {
        var boundary = new HashMap<End,Integer>();
        for (var face : mesh.faces()) {
            var points = face.vertices();
            for (int i = 0; i < points.size(); i++) {
                Point a = points.get(i), b = points.get((i+1) % points.size());
                int dx = b.tx()-a.tx(), dy = b.ty()-a.ty(), dz = b.tz()-a.tz();
                int length = gcd(gcd(Math.abs(dx),Math.abs(dy)),Math.abs(dz));
                if (a.compareTo(b) > 0) length = -length;
                Line line = new Line(a,dx/length,dy/length,dz/length);
                boundary.merge(new End(face.plane(),line,line.station(a)),1,Integer::sum);
                boundary.merge(new End(face.plane(),line,line.station(b)),-1,Integer::sum);
            }
        }
        // Interior diagonals and intermediate endpoints of collinear segments cancel out.
        boundary.values().removeIf(n -> n == 0);
        return boundary;
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int rest = a % b; a = b; b = rest; }
        return a;
    }

    private static boolean preservesFaces(List<Point> points, List<Triangle> incident, int vertex, int target) {
        Point p = points.get(target);
        try {
            for (var t : incident) {
                var plane = Polyhedron.trianglePlane(points.get(t.a()), points.get(t.b()), points.get(t.c()));
                if (t.uses(target)) continue;
                // Grid cuts are saved to the nearest thousandth of a pixel on each side.
                double length = Math.hypot(plane.x(), Math.hypot(plane.y(), plane.z()));
                if (Math.abs(plane.distance(p.x(), p.y(), p.z())) > length * .002) return false;
                var after = Polyhedron.trianglePlane(points.get(t.a() == vertex ? target : t.a()),
                    points.get(t.b() == vertex ? target : t.b()), points.get(t.c() == vertex ? target : t.c()));
                if ((double) plane.x() * after.x() + (double) plane.y() * after.y() + (double) plane.z() * after.z() <= 0) return false;
            }
            return true;
        } catch (IllegalArgumentException collapsed) {
            return false;
        }
    }

    private static List<Triangle> contract(List<Triangle> faces, int vertex, int target) {
        List<Triangle> result = new ArrayList<>();
        for (var t : faces) {
            if (t.uses(vertex) && t.uses(target)) continue;
            result.add(new Triangle(remap(t.a(), vertex, target), remap(t.b(), vertex, target), remap(t.c(), vertex, target)));
        }
        return result;
    }

    private static int remap(int id, int vertex, int target) {
        int kept = id == vertex ? target : id;
        return kept > vertex ? kept - 1 : kept;
    }
}
