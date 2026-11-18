package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Map;

/** A moving radar must finish a cold 4 km query without losing the prefix to cache expiry. */
final class RadarLongRangeIntegrationChecks {
    static void check(ServerLevel level) throws Exception {
        long originalTime = level.getGameTime();
        try {
            for (boolean aircraft : new boolean[]{true, false}) checkMode(level, aircraft);
        } finally {
            resetTerrain(level);
            level.getServer().getWorldData().overworldData().setGameTime(originalTime);
        }
    }

    private static void checkMode(ServerLevel level, boolean aircraft) throws Exception {
        resetTerrain(level);
        Vec3 start = new Vec3(1_000_000.5, 200.75, aircraft ? 1_000_000.5 : 1_001_024.5);
        // Saved disposable worlds can retain a previous fixture plot until a physics tick.
        // Such an overlapping hull is a real obstruction, so remove only our old fixture position.
        var container = SubLevelContainer.getContainer(level);
        for (var previous : java.util.List.copyOf(container.getAllSubLevels())) {
            if (previous != null && !previous.isRemoved()
                    && StabilizerControllerBlockEntity.subLevelWorldBounds(previous).getCenter()
                    .distanceToSqr(start.add(4096, 0, 0)) < 4)
                container.removeSubLevel(previous, SubLevelRemovalReason.REMOVED);
        }
        SubLevel body = RadarVisibilityIntegrationChecks.body(level, start.add(4096, 0, 0));
        try {
            var radar = new MovingRadar(start, aircraft); radar.setLevel(level);
            for (String name : new String[]{"aircraftRadarRange", "radarRange"}) {
                var field = StabilizerControllerBlockEntity.class.getDeclaredField(name); field.setAccessible(true);
                field.setDouble(radar, 20_000);
            }
            Method scan = StabilizerControllerBlockEntity.class.getDeclaredMethod("updateRadarScan"); scan.setAccessible(true);
            int loaded = level.getChunkSource().getLoadedChunksCount();
            int detectedAt = -1;
            // Advance game ticks independently of I/O completion. Origin movement invalidates
            // each per-target ray, exactly as it does on a flying aircraft.
            for (int attempt = 0; attempt < 320; attempt++) {
                radar.origin = start.add(attempt * .5, 0, 0);
                advance(level, 2);
                scan.invoke(radar);
                if (radar.getRadarContact(body.getUniqueId()) != null) { detectedAt = attempt; break; }
                Thread.sleep(3);
            }
            if (detectedAt < 0) diagnostics(level, radar);
            require(detectedAt >= 0, "moving " + (aircraft ? "aircraft" : "ground")
                    + " radar must confirm an unobstructed 4km physical target from a cold terrain cache");
            require(level.getChunkSource().getLoadedChunksCount() == loaded,
                    "cold 4km confirmation must not load or generate terrain chunks");

            for (int i = 0; i < 20; i++) {
                radar.origin = radar.origin.add(9.75, 0, 0); // 351 km/h at the normal two-tick scan interval.
                advance(level, 2); scan.invoke(radar);
                require(radar.getRadarContact(body.getUniqueId()) != null,
                        "a warm long-range contact must survive aircraft movement at 351km/h");
            }

            BlockPos wall = BlockPos.containing(start.add(2000, 0, 0));
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            int withWall = level.getChunkSource().getLoadedChunksCount();
            advance(level, 6); scan.invoke(radar);
            require(radar.getRadarContact(body.getUniqueId()) == null,
                    "a real wall still hides the same 4km physical target after the cache fix");
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            advance(level, 6); scan.invoke(radar);
            require(radar.getRadarContact(body.getUniqueId()) != null,
                    "removing a wall from the loaded chunk must immediately replace its saved snapshot");
            require(level.getChunkSource().getLoadedChunksCount() == withWall,
                    "rechecking a distant wall must not load additional chunks");
            StabilizedTurret.LOGGER.info("RADAR COLD 4KM PASS: aircraft={} coldScans={} finalDistance={}m, moving origin, 351km/h sustained track, real structure, wall/removal, zero query-loaded chunks",
                    aircraft, detectedAt + 1, radar.origin.distanceTo(start.add(4096, 0, 0)));
        } finally {
            SubLevelContainer.getContainer(level).removeSubLevel(body, SubLevelRemovalReason.REMOVED);
        }
    }

    private static void advance(ServerLevel level, int ticks) {
        level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + ticks);
    }
    private static void resetTerrain(ServerLevel level) throws Exception {
        var field = RadarVisibility.class.getDeclaredField("WORLDS"); field.setAccessible(true);
        ((Map<?, ?>) field.get(null)).remove(level);
    }
    private static void diagnostics(ServerLevel level, MovingRadar radar) throws Exception {
        var worlds = RadarVisibility.class.getDeclaredField("WORLDS"); worlds.setAccessible(true);
        Object terrain = ((Map<?, ?>) worlds.get(null)).get(level);
        var savedField = terrain.getClass().getDeclaredField("saved"); savedField.setAccessible(true);
        var saved = (Map<?, ?>) savedField.get(terrain);
        int pending = 0, invalid = 0;
        for (var entry : saved.entrySet()) {
            var futureField = entry.getValue().getClass().getDeclaredField("future"); futureField.setAccessible(true);
            var future = (java.util.concurrent.CompletableFuture<?>) futureField.get(entry.getValue());
            if (!future.isDone()) pending++;
            else if (future.getNow(null) == null) {
                invalid++;
                StabilizedTurret.LOGGER.error("COLD RADAR INVALID SNAPSHOT: {}", new net.minecraft.world.level.ChunkPos((Long) entry.getKey()));
            }
        }
        var visibilityField = StabilizerControllerBlockEntity.class.getDeclaredField("radarVisibility"); visibilityField.setAccessible(true);
        Object visibility = visibilityField.get(radar);
        var samples = RadarVisibility.class.getDeclaredField("samples"); samples.setAccessible(true);
        StabilizedTurret.LOGGER.error("COLD RADAR DIAGNOSTIC: saved={} pending={} invalid={} samples={}",
                saved.size(), pending, invalid, samples.get(visibility));
    }
    private static final class MovingRadar extends StabilizerControllerBlockEntity {
        Vec3 origin;
        final boolean aircraft;
        MovingRadar(Vec3 origin, boolean aircraft) {
            super(BlockPos.containing(origin), ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
            this.origin = origin; this.aircraft = aircraft;
        }
        @Override public boolean isRadarActive() { return true; }
        @Override public boolean isAircraftRadarMode() { return aircraft; }
        @Override public Vec3 getRadarOrigin() { return origin; }
        @Override public Vec3 getAircraftRadarForward() { return new Vec3(1, 0, 0); }
        @Override public Vec3 getAircraftRadarUp() { return new Vec3(0, 1, 0); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private RadarLongRangeIntegrationChecks() {}
}
