package com.hooya.stabilizedturret.compat.schematic;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Optional bridge to sable-schematic-api's complete constraint groups. */
public final class SableSchematicGroupCompat {
    private static final long GROUP_CACHE_TICKS = 20L;
    // Camera membership changes only when a bearing/weld is assembled or
    // removed. Rebuilding this graph every few ticks made head-aim player
    // ticks walk every loaded sublevel. Keep the in-memory graph stable for a
    // short window; the next topology change is still picked up promptly.
    private static final long CAMERA_GROUP_CACHE_TICKS = 100L;
    private static final Map<MinecraftServer, CachedGroups> GROUP_CACHE = new WeakHashMap<>();
    private static final Map<ServerLevel, CachedCameraComponents> CAMERA_GROUP_CACHE =
            new WeakHashMap<>();
    public static Set<UUID> connectedGroup(MinecraftServer server, UUID member) {
        if (server == null || member == null || !ModList.get().isLoaded("sable_schematic_api")) {
            return member == null ? Set.of() : Set.of(member);
        }
        for (Set<UUID> group : allConnectedGroups(server)) {
            if (group.contains(member)) return group;
        }
        return Set.of(member);
    }

    public static List<Set<UUID>> allConnectedGroups(MinecraftServer server) {
        if (server == null || !ModList.get().isLoaded("sable_schematic_api")) return List.of();
        long now = server.overworld().getGameTime();
        synchronized (GROUP_CACHE) {
            CachedGroups cached = GROUP_CACHE.get(server);
            if (cached != null && now >= cached.gameTime
                    && now - cached.gameTime < GROUP_CACHE_TICKS) {
                return cached.groups;
            }
        }
        List<Set<UUID>> groups;
        try {
            groups = ApiBridge.allConnectedGroups(server);
        } catch (RuntimeException | LinkageError ignored) {
            groups = List.of();
        }
        groups = List.copyOf(groups);
        synchronized (GROUP_CACHE) {
            GROUP_CACHE.put(server, new CachedGroups(now, groups));
        }
        return groups;
    }

    /**
     * Returns the transitive closure used by an unlocked Sable third-person
     * camera: physical actor dependencies (including bearing parent/children)
     * are treated as undirected edges. This method intentionally uses only
     * the in-memory Sable graph; schematic region files must never be scanned
     * from a player tick.
     */
    public static Set<UUID> connectedCameraGroup(ServerLevel level, UUID member) {
        if (level == null || member == null) return Set.of();
        long now = level.getGameTime();
        Map<UUID, Set<UUID>> components;
        synchronized (CAMERA_GROUP_CACHE) {
            CachedCameraComponents cached = CAMERA_GROUP_CACHE.get(level);
            if (cached != null && now >= cached.gameTime
                    && now - cached.gameTime < CAMERA_GROUP_CACHE_TICKS) {
                return cached.components.getOrDefault(member, Set.of(member));
            }
        }

        DisjointSubLevels disjoint = new DisjointSubLevels();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        List<? extends SubLevel> subLevels = container.getAllSubLevels();
        for (SubLevel subLevel : subLevels) {
            if (subLevel == null || subLevel.isRemoved()) continue;
            UUID id = subLevel.getUniqueId();
            if (id == null) continue;
            disjoint.add(id);
            try {
                for (BlockEntitySubLevelActor actor : subLevel.getPlot().getBlockEntityActors()) {
                    Iterable<SubLevel> dependencies = actor.sable$getConnectionDependencies();
                    if (dependencies == null) continue;
                    for (SubLevel dependency : dependencies) {
                        if (dependency != null && !dependency.isRemoved()
                                && dependency.getUniqueId() != null) {
                            disjoint.union(id, dependency.getUniqueId());
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // A bearing may assemble/disassemble while its live actor
                // collection is being sampled; the short cache retries it.
            }
        }
        // Do not call sable-schematic-api here. Its listAll() implementation
        // opens every region file and force()s it closed, which is a blocking
        // disk operation. This method runs from ServerAimSessionHandler on
        // every player tick while head aim is active. Physical Sable actor
        // dependencies are sufficient for the camera's own connected hull;
        // schematic groups are consumed by the explicit schematic workflows.
        disjoint.add(member);
        components = disjoint.components();
        synchronized (CAMERA_GROUP_CACHE) {
            CAMERA_GROUP_CACHE.put(level, new CachedCameraComponents(now, components));
        }
        return components.getOrDefault(member, Set.of(member));
    }

    private record CachedGroups(long gameTime, List<Set<UUID>> groups) {
    }

    private record CachedCameraComponents(long gameTime, Map<UUID, Set<UUID>> components) {
    }

    private static final class DisjointSubLevels {
        private final Map<UUID, UUID> parent = new HashMap<>();

        private void add(UUID id) {
            parent.putIfAbsent(id, id);
        }

        private UUID find(UUID id) {
            add(id);
            UUID direct = parent.get(id);
            if (!direct.equals(id)) parent.put(id, direct = find(direct));
            return direct;
        }

        private void union(UUID first, UUID second) {
            UUID firstRoot = find(first);
            UUID secondRoot = find(second);
            if (!firstRoot.equals(secondRoot)) parent.put(secondRoot, firstRoot);
        }

        private Map<UUID, Set<UUID>> components() {
            Map<UUID, Set<UUID>> byRoot = new HashMap<>();
            for (UUID id : parent.keySet()) {
                byRoot.computeIfAbsent(find(id), ignored -> new HashSet<>()).add(id);
            }
            Map<UUID, Set<UUID>> byMember = new HashMap<>();
            for (Set<UUID> mutable : byRoot.values()) {
                Set<UUID> component = Set.copyOf(mutable);
                for (UUID id : component) byMember.put(id, component);
            }
            return Map.copyOf(byMember);
        }
    }

    private static final class ApiBridge {
        private static List<Set<UUID>> allConnectedGroups(MinecraftServer server) {
            return dev.rew1nd.sableschematicapi.sublevel.SubLevelGroupService.listAll(server).stream()
                    .map(group -> Set.copyOf(group.memberIds()))
                    .toList();
        }
    }

    private SableSchematicGroupCompat() {
    }
}
