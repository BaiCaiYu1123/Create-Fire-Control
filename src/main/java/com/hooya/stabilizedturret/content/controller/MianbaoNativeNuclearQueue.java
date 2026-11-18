package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.CruiseMissileGuidance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;

/**
 * FIFO admission for the two fire-control-managed nuclear missile types.
 * Mianbao still owns every terrain mutation and native shockwave once work is
 * admitted; this class never reproduces an explosion or edits a block.
 */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoNativeNuclearQueue {
    /** Conservative fallback for paired versions that cannot report completion. */
    private static final int NATIVE_MAX_DURATION_TICKS = 360;
    private static final Deque<Work> WAITING = new ArrayDeque<>();
    private static Work active;

    public static void enqueue(ServerLevel level, UUID missileId, Vec3 position) {
        if (level == null || missileId == null || position == null) return;
        if (active != null && active.missileId.equals(missileId)) return;
        if (WAITING.stream().anyMatch(work -> work.missileId.equals(missileId))) return;
        long queuedAhead = WAITING.stream()
                .filter(work -> work.level.getServer() == level.getServer())
                .count();
        if (active != null && active.level.getServer() == level.getServer()) {
            queuedAhead++;
        }
        WAITING.addLast(new Work(level, missileId, position));
        StabilizedTurret.LOGGER.info(
                "Queued native Mianbao nuclear terrain missile={} impact={} queuedAhead={}",
                missileId, position, queuedAhead);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (active != null && active.level.getServer() != server) return;
        if (active == null) {
            active = poll(server);
            if (active == null) return;
            active.start();
            return;
        }
        if (!active.complete()) return;
        CruiseMissileGuidance.completeNativeNuclearImpactDestruction(active.missileId);
        StabilizedTurret.LOGGER.info(
                "Completed native Mianbao nuclear terrain missile={} impact={}",
                active.missileId, active.position);
        active = null;
        // The next queued detonation starts on the following server tick.
    }

    private static Work poll(MinecraftServer server) {
        Iterator<Work> iterator = WAITING.iterator();
        while (iterator.hasNext()) {
            Work work = iterator.next();
            if (work.level.getServer() != server) continue;
            iterator.remove();
            return work;
        }
        return null;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        WAITING.removeIf(work -> work.level.getServer() == server);
        if (active != null && active.level.getServer() == server) active = null;
    }

    private static final class Work {
        final ServerLevel level;
        final UUID missileId;
        final Vec3 position;
        long startedAt = Long.MIN_VALUE;
        boolean nativeStarted;

        Work(ServerLevel level, UUID missileId, Vec3 position) {
            this.level = level;
            this.missileId = missileId;
            this.position = position;
        }

        void start() {
            startedAt = level.getGameTime();
            nativeStarted = MianbaoCruiseMissileCompat.startNativeNuclearTerrain(
                    level, position);
            StabilizedTurret.LOGGER.info(
                    "Started native Mianbao nuclear terrain missile={} impact={} nativeStarted={}",
                    missileId, position, nativeStarted);
        }

        boolean complete() {
            if (!nativeStarted) return true;
            Boolean nativeActive = MianbaoCruiseMissileCompat
                    .isNativeNuclearTerrainActive(level, position);
            if (nativeActive != null) return !nativeActive;
            return level.getGameTime() - startedAt >= NATIVE_MAX_DURATION_TICKS;
        }
    }

    private MianbaoNativeNuclearQueue() {
    }
}
