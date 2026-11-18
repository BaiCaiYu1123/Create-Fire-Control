package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Initial discovery starts during lateral motion, with no stationary acquisition/warm-up. */
public final class RadarColdAcquisitionChecks {
    public static void check(ServerLevel level) throws Exception {
        var worlds = RadarVisibility.class.getDeclaredField("WORLDS"); worlds.setAccessible(true);
        if (Boolean.getBoolean("create_fire_control.radarColdCrowdOnly")) {
            checkCrowd(level, worlds); return;
        }
        long before = level.getGameTime();
        int scenario = 0;
        try {
            for (boolean aircraft : new boolean[]{true, false})
                for (boolean sourceMoving : new boolean[]{true, false})
                    for (boolean targetMoving : new boolean[]{false, true})
                        check(level, worlds, scenario++, aircraft, sourceMoving, targetMoving, 4096, 200.75);
            // Captured live geometry: high aircraft looking down at a lower physical structure.
            check(level, worlds, scenario++, true, true, false, 1948.64, 556.63);
            check(level, worlds, scenario++, true, false, false, 19_000, 200.75);
        } finally {
            ((Map<?, ?>) worlds.get(null)).remove(level);
            level.getServer().getWorldData().overworldData().setGameTime(before);
        }
    }
    private static void checkCrowd(ServerLevel level, java.lang.reflect.Field worlds) throws Exception {
        long before = level.getGameTime();
        ((Map<?, ?>) worlds.get(null)).remove(level);
        Vec3 origin = new Vec3(3_500_000.5, 200.75, 3_500_000.5);
        var targets = new ArrayList<RadarVisibility.Target>();
        for (int i = 0; i < 20_000; i++) {
            double angle = (i % 16) * Math.PI / 8;
            Vec3 center = origin.add(4096 * Math.cos(angle), 0, 4096 * Math.sin(angle));
            targets.add(new RadarVisibility.Target(new UUID(17, i + 1), center,
                    new net.minecraft.world.phys.AABB(center, center).inflate(.5), Set.of()));
        }
        RadarVisibility[] scanners = {new RadarVisibility(), new RadarVisibility(), new RadarVisibility(), new RadarVisibility()};
        int loaded = level.getChunkSource().getLoadedChunksCount(), totalReads = 0, peakPending = 0;
        double maxMs = 0, totalMs = 0;
        try {
            for (int round = 0; round < 16; round++) {
                level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 2);
                for (int index = 0; index < scanners.length; index++) {
                    long began = System.nanoTime();
                    scanners[index].scan(level, origin.add(round * 9.75, 0, index * 16), Set.of(), targets);
                    double ms = (System.nanoTime() - began) / 1e6;
                    maxMs = Math.max(maxMs, ms); totalMs += ms;
                    for (String cache : List.of("samples", "pending", "motion")) {
                        var f = RadarVisibility.class.getDeclaredField(cache); f.setAccessible(true);
                        require(((Map<?, ?>) f.get(scanners[index])).size() <= 4096, "per-scanner cache cap: " + cache);
                    }
                }
                Object terrain = ((Map<?, ?>) worlds.get(null)).get(level);
                var reads = terrain.getClass().getDeclaredField("reads"); reads.setAccessible(true);
                int count = reads.getInt(terrain); totalReads += count;
                require(count <= 128, "four radars must share one async request cap");
                var remaining = terrain.getClass().getDeclaredField("remaining"); remaining.setAccessible(true);
                require(remaining.getInt(terrain) >= -1, "four radars share the world step cap");
                var saved = terrain.getClass().getDeclaredField("saved"); saved.setAccessible(true);
                require(((Map<?, ?>) saved.get(terrain)).size() <= 512, "full terrain cache cap");
                int inFlight = 0;
                for (Object entry : ((Map<?, ?>) saved.get(terrain)).values()) {
                    var future = entry.getClass().getDeclaredField("future"); future.setAccessible(true);
                    if (!((java.util.concurrent.CompletableFuture<?>) future.get(entry)).isDone()) inFlight++;
                }
                peakPending = Math.max(peakPending, inFlight);
                require(inFlight <= 128, "four radars share the decoder queue cap");
                Thread.sleep(50);
            }
            require(level.getChunkSource().getLoadedChunksCount() == loaded, "mass cold acquisition loads zero chunks");
            StabilizedTurret.LOGGER.info("RADAR COLD CROWD PASS: 4 radars x 20,000 targets x 16 moving scans; requests={} peakPending={} avgScanMs={} maxScanMs={} shared128Reads/16384Steps fixedCaches zeroChunkLoads",
                    totalReads, peakPending, totalMs / 64, maxMs);
        } finally {
            ((Map<?, ?>) worlds.get(null)).remove(level);
            level.getServer().getWorldData().overworldData().setGameTime(before);
        }
    }
    private static void check(ServerLevel level, java.lang.reflect.Field worlds, int scenario,
                              boolean aircraft, boolean sourceMoving, boolean targetMoving,
                              double range, double altitude) throws Exception {
        ((Map<?, ?>) worlds.get(null)).remove(level);
        Vec3 start = new Vec3(2_500_000.5 + scenario * 32768, altitude, 2_500_000.5);
        Vec3 targetStart = start.add(altitude > 500 ? -73.16 : 0,
                altitude > 500 ? 251.3358 - altitude : 0, range);
        if (range > 10_000) writeSavedGround(level, start, targetStart);
        var body = RadarVisibilityIntegrationChecks.body(level, targetStart);
        var radar = new Computer(start, aircraft); radar.setLevel(level);
        for (String name : List.of("aircraftRadarRange", "radarRange", "radarDepression", "aircraftRadarVerticalAngle")) {
            var f = StabilizerControllerBlockEntity.class.getDeclaredField(name); f.setAccessible(true);
            f.setDouble(radar, name.endsWith("Range") ? 20_000 : 90);
        }
        var scan = StabilizerControllerBlockEntity.class.getDeclaredMethod("updateRadarScan"); scan.setAccessible(true);
        int loaded = level.getChunkSource().getLoadedChunksCount(), acquired = -1, totalReads = 0, peakPending = 0;
        double worstMs = 0;
        try {
            int limit = range > 10_000 ? 32 : 12;
            for (int i = 0; i < limit + 40; i++) {
                radar.origin = start.add(sourceMoving ? i * 9.75 : 0, 0, 0);
                Vec3 position = targetStart.add(targetMoving ? -i * 6.5 : 0, 0, 0);
                body.logicalPose().position().set(position.x, position.y, position.z);
                body.updateLastPose(); body.updateBoundingBox(); body.forceUpdateGlobalBounds();
                level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 2);
                long began = System.nanoTime(); scan.invoke(radar);
                worstMs = Math.max(worstMs, (System.nanoTime() - began) / 1e6);
                Object terrain = ((Map<?, ?>) worlds.get(null)).get(level);
                require(terrain != null, "fixture must enter the candidate list: "
                        + StabilizerControllerBlockEntity.subLevelWorldBounds(body));
                var reads = terrain.getClass().getDeclaredField("reads"); reads.setAccessible(true);
                int count = reads.getInt(terrain); totalReads += count;
                require(count <= 128, "the dimension must never issue over 128 async terrain requests per tick");
                var saved = terrain.getClass().getDeclaredField("saved"); saved.setAccessible(true);
                int inFlight = 0;
                for (Object entry : ((Map<?, ?>) saved.get(terrain)).values()) {
                    var future = entry.getClass().getDeclaredField("future"); future.setAccessible(true);
                    if (!((java.util.concurrent.CompletableFuture<?>) future.get(entry)).isDone()) inFlight++;
                }
                peakPending = Math.max(peakPending, inFlight);
                require(inFlight <= 128, "async decode queue remains bounded");
                boolean visible = radar.getRadarContact(body.getUniqueId()) != null;
                if (acquired < 0 && visible) {
                    acquired = i;
                    var lock = StabilizerControllerBlockEntity.class.getDeclaredField("radarLockedSubLevelId");
                    lock.setAccessible(true); lock.set(radar, body.getUniqueId());
                }
                if (acquired >= 0) require(visible && body.getUniqueId().equals(radar.getRadarLockedSubLevelId()),
                        "cold acquired target/lock must stay continuous at scan " + i);
                if (i == limit - 1) require(acquired >= 0, "cold acquisition timed out: aircraft=" + aircraft
                        + " sourceMoving=" + sourceMoving + " targetMoving=" + targetMoving + " range=" + range);
                Thread.sleep(50);
            }
            require(level.getChunkSource().getLoadedChunksCount() == loaded, "radar must load zero terrain chunks");
            StabilizedTurret.LOGGER.info("RADAR COLD DISCOVERY PASS: aircraft={} sourceMoving={} targetMoving={} range={} altitude={} acquiredScan={} requests={} peakPending={} maxScanMs={} continuous40+ zeroChunkLoads",
                    aircraft, sourceMoving, targetMoving, range, altitude, acquired + 1, totalReads, peakPending, worstMs);
        } finally { SubLevelContainer.getContainer(level).removeSubLevel(body, SubLevelRemovalReason.REMOVED); }
    }
    private static void writeSavedGround(ServerLevel level, Vec3 from, Vec3 to) throws Exception {
        // Exported from the user's world by the read-only diagnostic. Write copies
        // solely to this disposable test world's storage, without installing chunks.
        var path = java.nio.file.Path.of(System.getProperty("create_fire_control.radarTerrainFixture"));
        net.minecraft.nbt.CompoundTag template;
        try (var in = new java.io.DataInputStream(java.nio.file.Files.newInputStream(path))) {
            template = net.minecraft.nbt.NbtIo.read(in);
        }
        int x = BlockPos.containing(from).getX() >> 4;
        int minZ = BlockPos.containing(from).getZ() >> 4, maxZ = BlockPos.containing(to).getZ() >> 4;
        var writes = new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        for (int z = minZ; z <= maxZ; z++) {
            var tag = template.copy(); tag.putInt("xPos", x); tag.putInt("zPos", z);
            writes.add(level.getChunkSource().chunkMap.write(new net.minecraft.world.level.ChunkPos(x, z), tag));
            if (writes.size() == 32) {
                java.util.concurrent.CompletableFuture.allOf(writes.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
                writes.clear();
            }
        }
        java.util.concurrent.CompletableFuture.allOf(writes.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
        StabilizedTurret.LOGGER.info("RADAR COLD FIXTURE: {} saved real-ground columns; no chunks installed", maxZ - minZ + 1);
    }
    private static final class Computer extends StabilizerControllerBlockEntity {
        Vec3 origin; final boolean aircraft;
        Computer(Vec3 origin, boolean aircraft) {
            super(BlockPos.containing(origin), ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
            this.origin = origin; this.aircraft = aircraft;
        }
        @Override public boolean isRadarActive() { return true; }
        @Override public boolean isAircraftRadarMode() { return aircraft; }
        @Override public Vec3 getRadarOrigin() { return origin; }
        @Override public Vec3 getAircraftRadarForward() { return new Vec3(0, 0, 1); }
        @Override public Vec3 getAircraftRadarUp() { return new Vec3(0, 1, 0); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
