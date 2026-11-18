package com.hooya.stabilizedturret.content.iff;

import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Shared IFF structure definition. Physical bearing chains, Photomancy/Sable
 * weld groups, and overlapping physical bounds all form one transitive group.
 */
public final class IffStructureTopology {
    private static final long CACHE_TICKS = 2L;
    private static final double OVERLAP_EPSILON = 1.0e-6;
    private static final Map<Level, CachedTopology> CACHE = new WeakHashMap<>();

    public static Set<UUID> expand(Level level, Set<UUID> seeds) {
        if (level == null || seeds == null || seeds.isEmpty()) return Set.of();
        Map<UUID, Set<UUID>> byMember = topology(level);
        Set<UUID> result = new HashSet<>();
        for (UUID seed : seeds) {
            result.addAll(byMember.getOrDefault(seed, Set.of(seed)));
        }
        return Set.copyOf(result);
    }

    private static Map<UUID, Set<UUID>> topology(Level level) {
        Level root = SubLevelContainer.getContainer(level).getLevel();
        long now = root.getGameTime();
        List<SubLevel> loaded = SubLevelContainer.getContainer(level).getAllSubLevels()
                .stream()
                .filter(subLevel -> subLevel != null && !subLevel.isRemoved())
                .map(subLevel -> (SubLevel) subLevel)
                .toList();
        Set<UUID> loadedIds = loaded.stream().map(SubLevel::getUniqueId)
                .collect(java.util.stream.Collectors.toSet());
        synchronized (CACHE) {
            CachedTopology cached = CACHE.get(root);
            if (cached != null && now >= cached.gameTime
                    && now - cached.gameTime < CACHE_TICKS
                    && cached.loadedIds.equals(loadedIds)) {
                return cached.byMember;
            }
        }

        DisjointSet union = new DisjointSet(loadedIds);
        for (SubLevel subLevel : loaded) {
            UUID parentId = subLevel.getUniqueId();
            for (SubLevel connected : SubLevelHelper.getConnectedChain(subLevel)) {
                if (connected != null && !connected.isRemoved()
                        && loadedIds.contains(connected.getUniqueId())) {
                    union.union(parentId, connected.getUniqueId());
                }
            }
            for (dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor actor
                    : subLevel.getPlot().getBlockEntityActors()) {
                if (actor instanceof SwivelBearingBlockEntity bearing
                        && bearing.isAssembled()
                        && bearing.getSubLevelID() != null
                        && loadedIds.contains(bearing.getSubLevelID())) {
                    union.union(parentId, bearing.getSubLevelID());
                }
            }
        }

        List<BoundedSubLevel> bounded = new ArrayList<>();
        for (SubLevel subLevel : loaded) {
            var physicalBounds = subLevel.boundingBox();
            AABB bounds = new AABB(
                    physicalBounds.minX(), physicalBounds.minY(), physicalBounds.minZ(),
                    physicalBounds.maxX(), physicalBounds.maxY(), physicalBounds.maxZ());
            if (!finite(bounds)) continue;
            bounded.add(new BoundedSubLevel(subLevel.getUniqueId(), bounds));
        }
        // Sweep on X before checking the other axes. Strict comparisons mean
        // merely touching faces do not merge two otherwise separate vehicles.
        bounded.sort(Comparator.comparingDouble(value -> value.bounds.minX));
        for (int firstIndex = 0; firstIndex < bounded.size(); firstIndex++) {
            BoundedSubLevel first = bounded.get(firstIndex);
            for (int secondIndex = firstIndex + 1;
                 secondIndex < bounded.size(); secondIndex++) {
                BoundedSubLevel second = bounded.get(secondIndex);
                if (second.bounds.minX >= first.bounds.maxX - OVERLAP_EPSILON) break;
                if (overlaps(first.bounds, second.bounds)) {
                    union.union(first.id, second.id);
                }
            }
        }

        Map<UUID, Set<UUID>> components = union.componentsByMember();
        synchronized (CACHE) {
            CACHE.put(root, new CachedTopology(now, Set.copyOf(loadedIds), components));
        }
        return components;
    }

    private static boolean overlaps(AABB first, AABB second) {
        return first.maxX - OVERLAP_EPSILON > second.minX
                && second.maxX - OVERLAP_EPSILON > first.minX
                && first.maxY - OVERLAP_EPSILON > second.minY
                && second.maxY - OVERLAP_EPSILON > first.minY
                && first.maxZ - OVERLAP_EPSILON > second.minZ
                && second.maxZ - OVERLAP_EPSILON > first.minZ;
    }

    private static boolean finite(AABB bounds) {
        return bounds != null
                && Double.isFinite(bounds.minX) && Double.isFinite(bounds.minY)
                && Double.isFinite(bounds.minZ) && Double.isFinite(bounds.maxX)
                && Double.isFinite(bounds.maxY) && Double.isFinite(bounds.maxZ);
    }

    private record BoundedSubLevel(UUID id, AABB bounds) {
    }

    private record CachedTopology(
            long gameTime, Set<UUID> loadedIds, Map<UUID, Set<UUID>> byMember) {
    }

    private static final class DisjointSet {
        private final Map<UUID, UUID> parent = new HashMap<>();

        private DisjointSet(Set<UUID> ids) {
            for (UUID id : ids) parent.put(id, id);
        }

        private UUID find(UUID id) {
            UUID direct = parent.get(id);
            if (direct == null) return id;
            if (!direct.equals(id)) parent.put(id, direct = find(direct));
            return direct;
        }

        private void union(UUID first, UUID second) {
            UUID firstRoot = find(first);
            UUID secondRoot = find(second);
            if (!firstRoot.equals(secondRoot)) parent.put(secondRoot, firstRoot);
        }

        private Map<UUID, Set<UUID>> componentsByMember() {
            Map<UUID, Set<UUID>> byRoot = new LinkedHashMap<>();
            for (UUID id : parent.keySet()) {
                byRoot.computeIfAbsent(find(id), ignored -> new HashSet<>()).add(id);
            }
            Map<UUID, Set<UUID>> byMember = new HashMap<>();
            for (Set<UUID> mutable : byRoot.values()) {
                Set<UUID> component = Set.copyOf(mutable);
                for (UUID member : component) byMember.put(member, component);
            }
            return Map.copyOf(byMember);
        }
    }

    private IffStructureTopology() {
    }
}
