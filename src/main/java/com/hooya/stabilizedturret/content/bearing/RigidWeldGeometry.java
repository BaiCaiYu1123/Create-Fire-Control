package com.hooya.stabilizedturret.content.bearing;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Attachment coordinates come from saved weld frames, never from a deflected pose. */
final class RigidWeldGeometry {
    record Link<K>(K parent, K child, Vector3d parentAnchor, Vector3d childAnchor,
                   Quaterniond parentFrame, Quaterniond childFrame) {}
    record Geometry(Vector3d anchor, Vector3d axis) {}

    static <K> Map<K, Geometry> from(K source, Vector3d sourceAnchor, Vector3d sourceAxis,
                                   List<Link<K>> links) {
        Map<K, Geometry> geometry = new HashMap<>();
        if (source == null) return geometry;
        geometry.put(source, new Geometry(new Vector3d(sourceAnchor), new Vector3d(sourceAxis)));
        ArrayDeque<K> pending = new ArrayDeque<>();
        pending.add(source);
        while (!pending.isEmpty()) {
            K current = pending.removeFirst();
            Geometry from = geometry.get(current);
            for (Link<K> edge : links) {
                K next;
                Vector3d fromAnchor, toAnchor;
                boolean inverse;
                if (edge.child.equals(current)) {
                    next = edge.parent; fromAnchor = edge.childAnchor; toAnchor = edge.parentAnchor;
                    inverse = false;
                } else if (edge.parent.equals(current)) {
                    next = edge.child; fromAnchor = edge.parentAnchor; toAnchor = edge.childAnchor;
                    inverse = true;
                } else continue;
                if (geometry.containsKey(next)) continue;
                Quaterniond rotation = new Quaterniond(edge.parentFrame).mul(new Quaterniond(edge.childFrame).conjugate());
                if (inverse) rotation.conjugate();
                geometry.put(next, new Geometry(rotation.transform(new Vector3d(from.anchor).sub(fromAnchor)).add(toAnchor),
                        rotation.transform(new Vector3d(from.axis))));
                // Oversized weld groups keep the ordinary bearing-plate fallback.
                if (geometry.size() > ArticulatedServoSolver.MAX_BODIES)
                    return Map.of(source, new Geometry(new Vector3d(sourceAnchor), new Vector3d(sourceAxis)));
                pending.addLast(next);
            }
        }
        return geometry;
    }
}
