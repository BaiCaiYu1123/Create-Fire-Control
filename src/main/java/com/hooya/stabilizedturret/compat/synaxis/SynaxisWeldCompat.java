package com.hooya.stabilizedturret.compat.synaxis;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Optional bridge to the weld graph supplied by Synaxis' Photomancy
 * Honey-Welder consumer. Synaxis is deliberately accessed reflectively so
 * Create: Fire Control remains usable when Photomancy/Synaxis is absent.
 */
public final class SynaxisWeldCompat {
    private static final String WELD_SERVICE =
            "com.verr1.synaxis.compat.sableblueprint.weld.WeldConstraintService";
    private static final Map<ServerLevel, WeldSnapshot> SNAPSHOTS = new WeakHashMap<>();

    private static boolean reflectionResolved;
    private static boolean available;
    private static boolean failureLogged;
    private static Method definitionsMethod;
    private static Method bodyAMethod;
    private static Method bodyBMethod;
    private static Method modeMethod;
    private static Method anchorAMethod;
    private static Method anchorBMethod;
    private static Method dimensionMethod;
    private static Method idMethod;

    public static Set<UUID> expandWeldedSublevels(ServerLevel level, Collection<UUID> roots) {
        return expand(level, roots, false);
    }

    public static Set<UUID> expandFixedWeldedSublevels(ServerLevel level, Collection<UUID> roots) {
        return expand(level, roots, true);
    }

    /**
     * Returns the minimum number of Synaxis weld edges between two bodies.
     * A negative result means that the bodies are not currently welded.
     */
    public static int weldedDistance(ServerLevel level, UUID start, UUID target) {
        if (start == null || target == null) return -1;
        if (start.equals(target)) return 0;
        if (!resolveReflection()) return -1;
        try {
            return graphDistance(start, target, snapshot(level).adjacency);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure(exception);
            return -1;
        }
    }

    /** Snapshot of the current non-ground Synaxis weld graph in this dimension. */
    public static Map<UUID, Set<UUID>> weldedAdjacency(ServerLevel level) {
        if (!resolveReflection()) return Map.of();
        try {
            return snapshot(level).adjacency;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure(exception);
            return Map.of();
        }
    }

    /** Rigid members only; rotary welds must not expand a pitch collision override. */
    public static Map<UUID, Set<UUID>> fixedWeldedAdjacency(ServerLevel level) {
        if (!resolveReflection()) return Map.of();
        try {
            return snapshot(level).fixedAdjacency;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure(exception);
            return Map.of();
        }
    }

    /**
     * Builds an ordered fixed-weld tree rooted at the supplied physical
     * bodies. Every returned link retains Synaxis' actual model-space weld
     * points on both bodies; callers can therefore follow the selected blocks
     * instead of accidentally treating either body's origin/COM as the weld.
     */
    public static Map<UUID, FixedWeldLink> fixedWeldFollowers(
            ServerLevel level,
            Collection<UUID> roots
    ) {
        if (roots.isEmpty() || !resolveReflection()) return Map.of();
        try {
            WeldSnapshot snapshot = snapshot(level);
            Set<UUID> visited = new HashSet<>(roots);
            ArrayDeque<UUID> pending = new ArrayDeque<>(roots);
            Map<UUID, FixedWeldLink> result = new LinkedHashMap<>();
            while (!pending.isEmpty()) {
                UUID parent = pending.removeFirst();
                for (FixedWeldEdge edge : snapshot.fixedEdges) {
                    UUID child;
                    Vector3d parentAnchor;
                    Vector3d childAnchor;
                    if (parent.equals(edge.firstId)) {
                        child = edge.secondId;
                        parentAnchor = edge.firstAnchor;
                        childAnchor = edge.secondAnchor;
                    } else if (parent.equals(edge.secondId)) {
                        child = edge.firstId;
                        parentAnchor = edge.secondAnchor;
                        childAnchor = edge.firstAnchor;
                    } else {
                        continue;
                    }
                    if (!visited.add(child)) continue;
                    result.put(child, new FixedWeldLink(
                            parent,
                            child,
                            new Vector3d(parentAnchor),
                            new Vector3d(childAnchor)
                    ));
                    pending.addLast(child);
                }
            }
            return Collections.unmodifiableMap(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure(exception);
            return Map.of();
        }
    }

    private static Set<UUID> expand(ServerLevel level, Collection<UUID> roots, boolean fixedOnly) {
        if (roots.isEmpty() || !resolveReflection()) {
            return Set.copyOf(roots);
        }

        try {
            WeldSnapshot snapshot = snapshot(level);
            return expandConnected(roots, fixedOnly ? snapshot.fixedAdjacency : snapshot.adjacency);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailure(exception);
            return Set.copyOf(roots);
        }
    }

    private static WeldSnapshot snapshot(ServerLevel level) throws ReflectiveOperationException {
        synchronized (SNAPSHOTS) {
            WeldSnapshot snapshot = SNAPSHOTS.get(level);
            if (snapshot == null || snapshot.gameTime != level.getGameTime()) {
                snapshot = readSnapshot(level);
                SNAPSHOTS.put(level, snapshot);
            }
            return snapshot;
        }
    }

    private static void logFailure(Throwable exception) {
        if (failureLogged) return;
        failureLogged = true;
        StabilizedTurret.LOGGER.warn(
                "Unable to read Synaxis weld constraints; welded turret structures will not receive weight suppression",
                exception
        );
    }

    static Set<UUID> expandConnected(Collection<UUID> roots, Map<UUID, Set<UUID>> adjacency) {
        Set<UUID> result = new HashSet<>(roots);
        ArrayDeque<UUID> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            UUID current = pending.removeFirst();
            for (UUID neighbor : adjacency.getOrDefault(current, Set.of())) {
                if (result.add(neighbor)) {
                    pending.addLast(neighbor);
                }
            }
        }
        return result;
    }

    static int graphDistance(UUID start, UUID target, Map<UUID, Set<UUID>> adjacency) {
        if (start.equals(target)) return 0;
        Set<UUID> visited = new HashSet<>();
        ArrayDeque<GraphStep> pending = new ArrayDeque<>();
        visited.add(start);
        pending.addLast(new GraphStep(start, 0));
        while (!pending.isEmpty()) {
            GraphStep current = pending.removeFirst();
            for (UUID neighbor : adjacency.getOrDefault(current.id, Set.of())) {
                if (!visited.add(neighbor)) continue;
                int distance = current.distance + 1;
                if (target.equals(neighbor)) return distance;
                pending.addLast(new GraphStep(neighbor, distance));
            }
        }
        return -1;
    }

    private static WeldSnapshot readSnapshot(ServerLevel level) throws ReflectiveOperationException {
        Object value = definitionsMethod.invoke(null, level);
        if (!(value instanceof Iterable<?> definitions)) {
            return new WeldSnapshot(level.getGameTime(), Map.of(), Map.of(), List.of());
        }

        Map<UUID, Set<UUID>> adjacency = new HashMap<>();
        Map<UUID, Set<UUID>> fixedAdjacency = new HashMap<>();
        List<FixedWeldEdge> fixedEdges = new ArrayList<>();
        ResourceKey<Level> currentDimension = level.dimension();
        for (Object definition : definitions) {
            if (definition == null) {
                continue;
            }
            BodyRef first = readBody(bodyAMethod.invoke(definition));
            BodyRef second = readBody(bodyBMethod.invoke(definition));
            if (first == null || second == null
                    || !currentDimension.equals(first.dimension)
                    || !currentDimension.equals(second.dimension)
                    || isGround(first.id)
                    || isGround(second.id)) {
                continue;
            }
            adjacency.computeIfAbsent(first.id, ignored -> new HashSet<>()).add(second.id);
            adjacency.computeIfAbsent(second.id, ignored -> new HashSet<>()).add(first.id);
            Object mode = modeMethod.invoke(definition);
            if (mode instanceof Enum<?> enumValue && "FIXED".equals(enumValue.name())) {
                fixedAdjacency.computeIfAbsent(first.id, ignored -> new HashSet<>()).add(second.id);
                fixedAdjacency.computeIfAbsent(second.id, ignored -> new HashSet<>()).add(first.id);
                Object firstAnchorValue = anchorAMethod.invoke(definition);
                Object secondAnchorValue = anchorBMethod.invoke(definition);
                if (firstAnchorValue instanceof Vector3dc firstAnchor
                        && secondAnchorValue instanceof Vector3dc secondAnchor) {
                    fixedEdges.add(new FixedWeldEdge(
                            first.id,
                            second.id,
                            new Vector3d(firstAnchor),
                            new Vector3d(secondAnchor)
                    ));
                }
            }
        }

        return new WeldSnapshot(
                level.getGameTime(),
                immutableAdjacency(adjacency),
                immutableAdjacency(fixedAdjacency),
                List.copyOf(fixedEdges)
        );
    }

    private static Map<UUID, Set<UUID>> immutableAdjacency(Map<UUID, Set<UUID>> source) {
        Map<UUID, Set<UUID>> result = new HashMap<>();
        source.forEach((id, neighbors) -> result.put(id, Set.copyOf(neighbors)));
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static BodyRef readBody(Object body) throws ReflectiveOperationException {
        if (body == null) {
            return null;
        }
        Object dimension = dimensionMethod.invoke(body);
        Object id = idMethod.invoke(body);
        if (!(dimension instanceof ResourceKey<?> key) || !(id instanceof UUID uuid)) {
            return null;
        }
        return new BodyRef((ResourceKey<Level>) key, uuid);
    }

    private static boolean isGround(UUID id) {
        return id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() == 0L;
    }

    private static synchronized boolean resolveReflection() {
        if (reflectionResolved) {
            return available;
        }
        reflectionResolved = true;
        try {
            Class<?> serviceClass = Class.forName(WELD_SERVICE, false,
                    SynaxisWeldCompat.class.getClassLoader());
            definitionsMethod = serviceClass.getMethod("definitions", ServerLevel.class);

            Class<?> definitionClass = Class.forName(
                    "com.verr1.synaxis.compat.sableblueprint.weld.WeldConstraintDefinition",
                    false,
                    SynaxisWeldCompat.class.getClassLoader()
            );
            bodyAMethod = definitionClass.getMethod("bodyA");
            bodyBMethod = definitionClass.getMethod("bodyB");
            modeMethod = definitionClass.getMethod("mode");
            anchorAMethod = definitionClass.getMethod("anchorA");
            anchorBMethod = definitionClass.getMethod("anchorB");

            Class<?> bodyIdClass = Class.forName(
                    "com.verr1.synaxis.foundation.physics.BodyId",
                    false,
                    SynaxisWeldCompat.class.getClassLoader()
            );
            dimensionMethod = bodyIdClass.getMethod("dimension");
            idMethod = bodyIdClass.getMethod("id");
            available = true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            available = false;
        }
        return available;
    }

    private record BodyRef(ResourceKey<Level> dimension, UUID id) {
    }

    private record GraphStep(UUID id, int distance) {
    }

    private record WeldSnapshot(
            long gameTime,
            Map<UUID, Set<UUID>> adjacency,
            Map<UUID, Set<UUID>> fixedAdjacency,
            List<FixedWeldEdge> fixedEdges
    ) {
    }

    private record FixedWeldEdge(
            UUID firstId,
            UUID secondId,
            Vector3d firstAnchor,
            Vector3d secondAnchor
    ) {
    }

    public record FixedWeldLink(
            UUID parentId,
            UUID childId,
            Vector3d parentAnchor,
            Vector3d childAnchor
    ) {
    }

    private SynaxisWeldCompat() {
    }
}
