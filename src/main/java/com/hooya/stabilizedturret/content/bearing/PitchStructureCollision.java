package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Removes collision geometry, while retaining the pitch assembly's mass and physical joints. */
public final class PitchStructureCollision {
    private static final Map<ServerLevel, Map<UUID, Suppressed>> DISABLED = new WeakHashMap<>();

    private PitchStructureCollision() {}

    static boolean hasOverrides(ServerLevel level) { return DISABLED.containsKey(level); }

    /** Fixed welds belong to the barrel; a carrier's rigid group must retain its collision. */
    static Set<UUID> selectBodies(Set<UUID> roots, Set<UUID> carriers, Map<UUID, Set<UUID>> fixed) {
        Set<UUID> selected = expand(roots, fixed);
        selected.removeAll(expand(carriers, fixed));
        return selected;
    }

    private static Set<UUID> expand(Set<UUID> roots, Map<UUID, Set<UUID>> graph) {
        Set<UUID> result = new HashSet<>(roots);
        ArrayDeque<UUID> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            for (UUID next : graph.getOrDefault(pending.removeFirst(), Set.of())) {
                if (result.add(next)) pending.addLast(next);
            }
        }
        return result;
    }

    public static boolean isDisabled(ServerLevel level, SubLevel body) {
        if (body == null || body.isRemoved()) return false;
        Map<UUID, Suppressed> states = DISABLED.get(level);
        Suppressed state = states == null ? null : states.get(body.getUniqueId());
        return state != null && state.body == body;
    }

    public static boolean isPlotDisabled(ServerLevel level, int chunkX, int chunkZ) {
        var plot = SubLevelContainer.getContainer(level).getPlot(chunkX, chunkZ);
        return plot != null && isDisabled(level, plot.getSubLevel());
    }

    public static boolean isContraptionDisabled(ServerLevel level, KinematicContraption contraption) {
        return contraption instanceof Entity entity && isDisabled(level, Sable.HELPER.getContaining(entity));
    }

    static void reconcile(ServerLevel level, Set<UUID> desired) {
        if (desired.isEmpty() && !DISABLED.containsKey(level)) return;
        Map<UUID, Suppressed> states = DISABLED.computeIfAbsent(level, ignored -> new HashMap<>());
        var container = SubLevelContainer.getContainer(level);
        PhysicsPipeline pipeline = SubLevelPhysicsSystem.require(level).getPipeline();
        for (var entry : new ArrayList<>(states.entrySet())) {
            Suppressed state = entry.getValue();
            if (desired.contains(entry.getKey()) && container.getSubLevel(entry.getKey()) == state.body
                    && !state.body.isRemoved()) continue;
            // Remove the predicate before restoring, so the upload hook accepts original blocks.
            states.remove(entry.getKey());
            if (!state.body.isRemoved() && container.getSubLevel(entry.getKey()) == state.body) {
                restore(pipeline, state);
            }
        }
        for (UUID id : desired) {
            if (!(container.getSubLevel(id) instanceof ServerSubLevel body) || body.isRemoved()) continue;
            Suppressed state = states.get(id);
            boolean added = state == null;
            if (added) {
                state = new Suppressed(body);
                states.put(id, state);
            }
            suppress(pipeline, state);
            if (added) refreshContraptions(pipeline, body);
        }
        if (states.isEmpty()) DISABLED.remove(level);
    }

    static void clear(ServerLevel level) {
        if (!DISABLED.containsKey(level)) return;
        reconcile(level, Set.of());
        DISABLED.remove(level);
    }

    private static void suppress(PhysicsPipeline pipeline, Suppressed state) {
        boolean changed = false;
        for (var holder : state.body.getPlot().getLoadedChunks()) {
            var chunk = holder.getChunk();
            var sections = chunk.getSections();
            for (int index = 0; index < sections.length; index++) {
                if (sections[index] == null || sections[index].hasOnlyAir()) continue;
                int x = chunk.getPos().x, y = chunk.getSectionYFromSectionIndex(index), z = chunk.getPos().z;
                if (state.sections.add(SectionPos.asLong(x, y, z))) {
                    pipeline.handleChunkSectionRemoval(x, y, z);
                    changed = true;
                }
            }
        }
        if (changed) {
            // Collider removal must not change the mass/inertia used by the bearing or recoil.
            pipeline.onStatsChanged(state.body);
            pipeline.wakeUp(state.body);
        }
    }

    private static void restore(PhysicsPipeline pipeline, Suppressed state) {
        // A separate toolgun no-collision choice belongs to the player, not to this binding.
        var tag = state.body.getUserDataTag();
        if (tag != null && tag.getBoolean("AstDisableStructureCollision")) return;
        for (var holder : state.body.getPlot().getLoadedChunks()) {
            var chunk = holder.getChunk();
            var sections = chunk.getSections();
            for (int index = 0; index < sections.length; index++) {
                if (sections[index] == null || sections[index].hasOnlyAir()) continue;
                int x = chunk.getPos().x, y = chunk.getSectionYFromSectionIndex(index), z = chunk.getPos().z;
                pipeline.handleChunkSectionAddition(sections[index], x, y, z, true);
            }
        }
        pipeline.onStatsChanged(state.body);
        pipeline.wakeUp(state.body);
        refreshContraptions(pipeline, state.body);
    }

    private static void refreshContraptions(PhysicsPipeline pipeline, ServerSubLevel body) {
        for (KinematicContraption contraption : new ArrayList<>(body.getPlot().getContraptions())) {
            if (!contraption.sable$isValid()) continue;
            pipeline.remove(contraption);
            pipeline.add(contraption);
        }
    }

    private static final class Suppressed {
        final ServerSubLevel body;
        final Set<Long> sections = new HashSet<>();
        Suppressed(ServerSubLevel body) { this.body = body; }
    }
}
