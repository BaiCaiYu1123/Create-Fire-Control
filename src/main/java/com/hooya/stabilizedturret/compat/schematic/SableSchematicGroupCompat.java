package com.hooya.stabilizedturret.compat.schematic;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Optional bridge to sable-schematic-api's complete constraint groups. */
public final class SableSchematicGroupCompat {
    private static final long GROUP_CACHE_TICKS = 200L;
    private static final Map<MinecraftServer, CachedGroups> GROUP_CACHE = new WeakHashMap<>();
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

    private record CachedGroups(long gameTime, List<Set<UUID>> groups) {
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
