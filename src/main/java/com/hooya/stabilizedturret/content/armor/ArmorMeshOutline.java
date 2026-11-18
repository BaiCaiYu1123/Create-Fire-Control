package com.hooya.stabilizedturret.content.armor;

import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Surface creases for selection rendering, without building or simplifying an editor mesh. */
final class ArmorMeshOutline {
    private static final double EPS = 1e-8;
    record Line(Vec3 a, Vec3 b) {}
    private record Normal(long x, long y, long z) {
        Normal(Vec3 n) { this(quantize(n.x), quantize(n.y), quantize(n.z)); }
    }
    // Parameterize a supporting line by its dominant coordinate. This also groups
    // long edges with their shorter neighbors at the T-junctions produced by CSG.
    private record Key(int axis, long slopeA, long slopeB, long interceptA, long interceptB) {}
    private record Event(double at, Vec3 point, Normal normal, int change) {}

    static List<Line> build(ArmorMesh mesh) {
        var lines = new LinkedHashMap<Key, List<Event>>();
        for (var face : mesh.faces()) {
            var normal = new Normal(face.normal());
            for (int i = 0; i < face.vertices().size(); i++) {
                var a = face.vertices().get(i);
                var b = face.vertices().get((i + 1) % face.vertices().size());
                var d = b.subtract(a);
                double largest = Math.max(Math.abs(d.x), Math.max(Math.abs(d.y), Math.abs(d.z)));
                // Equal slopes must pick the same axis on both sides of a split edge.
                int axis = Math.abs(d.x) >= largest * (1-EPS) ? 0 : Math.abs(d.y) >= largest * (1-EPS) ? 1 : 2;
                double delta = coord(d, axis);
                if (Math.abs(delta) < EPS) continue;
                int u = (axis + 1) % 3, v = (axis + 2) % 3;
                double su = coord(d, u) / delta, sv = coord(d, v) / delta;
                var key = new Key(axis, quantize(su), quantize(sv),
                        quantize(coord(a, u) - su * coord(a, axis)), quantize(coord(a, v) - sv * coord(a, axis)));
                var events = lines.computeIfAbsent(key, k -> new ArrayList<>());
                if (delta < 0) { var swap = a; a = b; b = swap; }
                events.add(new Event(coord(a, axis), a, normal, 1));
                events.add(new Event(coord(b, axis), b, normal, -1));
            }
        }
        var result = new ArrayList<Line>();
        for (var events : lines.values()) {
            events.sort(Comparator.comparingDouble(Event::at));
            var active = new HashMap<Normal, Integer>();
            int count = 0;
            Vec3 start = null, end = null;
            for (int i = 0; i < events.size();) {
                var event = events.get(i);
                do {
                    var next = events.get(i++);
                    count += next.change;
                    active.compute(next.normal, (n, old) -> {
                        int value = (old == null ? 0 : old) + next.change;
                        return value == 0 ? null : value;
                    });
                } while (i < events.size() && events.get(i).at - event.at < EPS);
                boolean visible = i < events.size() && (count == 1 || active.size() > 1);
                if (visible) {
                    if (start == null) start = event.point;
                    end = events.get(i).point;
                } else if (start != null) {
                    result.add(new Line(start, end));
                    start = null;
                }
            }
        }
        return List.copyOf(result);
    }

    private static long quantize(double value) { return Math.round(value / EPS); }
    private static double coord(Vec3 p, int axis) { return axis == 0 ? p.x : axis == 1 ? p.y : p.z; }
    private ArmorMeshOutline() {}
}
