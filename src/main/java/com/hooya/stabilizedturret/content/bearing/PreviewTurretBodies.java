package com.hooya.stabilizedturret.content.bearing;

import java.util.*;

/** Follows a controlled bearing toward its load, never back through its parent into the chassis. */
public final class PreviewTurretBodies {
    private PreviewTurretBodies() {}

    public static Set<UUID> select(net.minecraft.server.level.ServerLevel level, Set<UUID> roots,
                                   Map<UUID, Set<UUID>> children) {
        Map<UUID, Set<UUID>> fixed = SableServoConstraints.fixedAdjacency(level);
        com.hooya.stabilizedturret.compat.synaxis.SynaxisWeldCompat.fixedWeldedAdjacency(level)
                .forEach((body, linked) -> fixed.computeIfAbsent(body, ignored -> new HashSet<>()).addAll(linked));
        return descendants(roots, children, fixed);
    }

    public static Set<UUID> descendants(Set<UUID> roots, Map<UUID, Set<UUID>> children,
                                        Map<UUID, Set<UUID>> fixedWelds) {
        Set<UUID> result = new HashSet<>(roots);
        ArrayDeque<UUID> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            UUID body = pending.removeFirst();
            for (UUID next : fixedWelds.getOrDefault(body, Set.of()))
                if (result.add(next)) pending.addLast(next);
            for (UUID next : children.getOrDefault(body, Set.of()))
                if (result.add(next)) pending.addLast(next);
        }
        return Set.copyOf(result);
    }
}
