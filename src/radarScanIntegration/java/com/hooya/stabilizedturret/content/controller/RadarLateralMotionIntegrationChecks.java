package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** The live aircraft crosses terrain columns laterally, rather than merely shortening the same ray. */
final class RadarLateralMotionIntegrationChecks {
    static void check(ServerLevel level) throws Exception {
        check(level, new Vec3(1_600_000.5, 394.338, 1_600_000.5), new Vec3(-403, -143, 2701));
        check(level, new Vec3(1_800_000.5, 200.75, 1_800_000.5), new Vec3(0, 0, 4096));
    }
    private static void check(ServerLevel level, Vec3 start, Vec3 offset) throws Exception {
        var worlds = RadarVisibility.class.getDeclaredField("WORLDS"); worlds.setAccessible(true);
        ((Map<?, ?>) worlds.get(null)).remove(level);
        long before = level.getGameTime();
        var body = RadarVisibilityIntegrationChecks.body(level, start.add(offset));
        try {
            var radar = new Computer(start); radar.setLevel(level);
            var range = StabilizerControllerBlockEntity.class.getDeclaredField("aircraftRadarRange"); range.setAccessible(true); range.setDouble(radar, 20_000);
            var scan = StabilizerControllerBlockEntity.class.getDeclaredMethod("updateRadarScan"); scan.setAccessible(true);
            boolean acquired = false;
            for (int i = 0; i < 400; i++) {
                advance(level); scan.invoke(radar);
                if (radar.getRadarContact(body.getUniqueId()) != null) { acquired = true; break; }
                Thread.sleep(3);
            }
            if (!acquired) {
                Object terrain = ((Map<?, ?>) worlds.get(null)).get(level);
                StabilizedTurret.LOGGER.error("LATERAL COLD body={} plot={} own={} removed={} terrain={}",
                        StabilizerControllerBlockEntity.subLevelWorldBounds(body), body.getPlot().getBoundingBox(),
                        radar.getOwnControlledSubLevelIds(), body.isRemoved(), terrain);
                var v = StabilizerControllerBlockEntity.class.getDeclaredField("radarVisibility"); v.setAccessible(true);
                Object visibility = v.get(radar);
                for (String name : List.of("samples", "pending")) {
                    var f = RadarVisibility.class.getDeclaredField(name); f.setAccessible(true);
                    StabilizedTurret.LOGGER.error("LATERAL COLD {}: {}", name, f.get(visibility));
                }
                require(terrain != null, "the physical fixture must enter the radar candidate list");
                var f = terrain.getClass().getDeclaredField("saved"); f.setAccessible(true);
                int waiting = 0, invalid = 0;
                for (Object entry : ((Map<?, ?>) f.get(terrain)).values()) {
                    var futureField = entry.getClass().getDeclaredField("future"); futureField.setAccessible(true);
                    var future = (java.util.concurrent.CompletableFuture<?>) futureField.get(entry);
                    if (!future.isDone()) waiting++; else if (future.getNow(null) == null) invalid++;
                }
                StabilizedTurret.LOGGER.error("LATERAL COLD cache={} waiting={} invalid={} body={} offset={}", ((Map<?, ?>) f.get(terrain)).size(), waiting, invalid, StabilizerControllerBlockEntity.subLevelWorldBounds(body), offset);
            }
            require(acquired, "live-shaped static oblique sight acquires its target");
            var lock = StabilizerControllerBlockEntity.class.getDeclaredField("radarLockedSubLevelId"); lock.setAccessible(true); lock.set(radar, body.getUniqueId());
            int loaded = level.getChunkSource().getLoadedChunksCount(), totalReads = 0;
            double worstScanMs = 0;
            for (int i = 0; i < 60; i++) {
                radar.origin = radar.origin.add(9.75, 0, 0);
                advance(level); long began = System.nanoTime(); scan.invoke(radar);
                worstScanMs = Math.max(worstScanMs, (System.nanoTime() - began) / 1e6); Thread.sleep(50);
                Object terrain = ((Map<?, ?>) worlds.get(null)).get(level);
                var reads = terrain.getClass().getDeclaredField("reads"); reads.setAccessible(true);
                totalReads += reads.getInt(terrain);
                require(reads.getInt(terrain) <= 128,
                        "lateral maintenance respects the world-wide asynchronous request cap");
                require(body.getUniqueId().equals(radar.getRadarLockedSubLevelId()),
                        "live-shaped lateral 351km/h flight keeps a confirmed lock, scan=" + i);
            }
            require(level.getChunkSource().getLoadedChunksCount() == loaded, "lateral maintenance loads zero chunks");
            // The high-altitude case's midpoint is above the world's build ceiling.
            // Use the actual physical centre and a valid point closer to the target.
            Vec3 actualTarget = StabilizerControllerBlockEntity.subLevelWorldBounds(body).getCenter();
            BlockPos wall = BlockPos.containing(radar.origin.lerp(actualTarget, .75));
            require(level.setBlockAndUpdate(wall, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()),
                    "the lateral regression must actually place its wall inside build height");
            advance(level); level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 12);
            scan.invoke(radar);
            require(radar.getRadarLockedSubLevelId() == null, "lateral query completion never renews an old segment forever through a real wall");
            level.setBlockAndUpdate(wall, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            StabilizedTurret.LOGGER.info("RADAR LATERAL PASS: offset={}, 351km/h, 60 continuous locks, {} async requests, max simulation scan {}ms, no chunk loads, real wall unlock", offset, totalReads, worstScanMs);
        } finally {
            SubLevelContainer.getContainer(level).removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            ((Map<?, ?>) worlds.get(null)).remove(level);
            level.getServer().getWorldData().overworldData().setGameTime(before);
        }
    }
    private static void advance(ServerLevel level) { level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 2); }
    private static final class Computer extends StabilizerControllerBlockEntity {
        Vec3 origin;
        Computer(Vec3 origin) { super(BlockPos.containing(origin), ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState()); this.origin = origin; }
        @Override public boolean isRadarActive() { return true; }
        @Override public boolean isAircraftRadarMode() { return true; }
        @Override public Vec3 getRadarOrigin() { return origin; }
        @Override public Vec3 getAircraftRadarForward() { return new Vec3(0, 0, 1); }
        @Override public Vec3 getAircraftRadarUp() { return new Vec3(0, 1, 0); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
