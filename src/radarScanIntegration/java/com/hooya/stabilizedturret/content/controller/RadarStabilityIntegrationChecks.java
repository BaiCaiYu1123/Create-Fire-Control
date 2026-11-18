package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Exercises continuous lock behavior, shared-cache pressure, physical jitter and deferred work. */
final class RadarStabilityIntegrationChecks {
    static void check(ServerLevel level) throws Exception {
        long before = level.getGameTime();
        var worlds = RadarVisibility.class.getDeclaredField("WORLDS"); worlds.setAccessible(true);
        ((Map<?, ?>) worlds.get(null)).remove(level);
        List<SubLevel> bodies = new ArrayList<>();
        try {
            Vec3 origin = new Vec3(1_200_000.5, 200.75, 1_200_000.5);
            var points = List.of(origin.add(4096, 0, -1800), origin.add(4096, 0, 0), origin.add(4096, 0, 1800));
            for (Vec3 point : points) bodies.add(RadarVisibilityIntegrationChecks.body(level, point));
            var radar = new Computer(origin); radar.setLevel(level);
            var range = StabilizerControllerBlockEntity.class.getDeclaredField("aircraftRadarRange"); range.setAccessible(true); range.setDouble(radar, 20_000);
            var scan = StabilizerControllerBlockEntity.class.getDeclaredMethod("updateRadarScan"); scan.setAccessible(true);
            boolean acquired = false;
            for (int i = 0; i < 800; i++) {
                // Far structures in a live Sable world never have bit-identical bounds between physics steps.
                for (SubLevel body : bodies) {
                    body.logicalPose().orientation().rotateY(1e-8);
                    body.updateBoundingBox(); body.forceUpdateGlobalBounds();
                }
                radar.origin = origin.add((i % 2) * .02, 0, 0);
                advance(level, 2); scan.invoke(radar);
                if (bodies.stream().allMatch(body -> radar.getRadarContact(body.getUniqueId()) != null)) { acquired = true; break; }
                Thread.sleep(2);
            }
            require(acquired, "multiple static far targets with physical jitter must not thrash the shared full-chunk cache");
            Object terrain = ((Map<?, ?>) worlds.get(null)).get(level);
            Map<?, ?> palettes = map(terrain, "saved"), summaries = map(terrain, "columns");
            require(palettes.size() <= 512 && summaries.size() > 512 && summaries.size() <= 8192,
                    "compact air summaries cover more than 512 columns without growing the full-palette cache");
            var lock = StabilizerControllerBlockEntity.class.getDeclaredField("radarLockedSubLevelId"); lock.setAccessible(true);
            UUID target = bodies.get(1).getUniqueId(); lock.set(radar, target);
            for (int i = 0; i < 30; i++) {
                radar.origin = origin.add((i % 2) * .02, 0, 0);
                advance(level, 2); scan.invoke(radar);
                require(bodies.stream().allMatch(body -> radar.getRadarContact(body.getUniqueId()) != null)
                        && target.equals(radar.getRadarLockedSubLevelId()), "stationary far tracks and lock remain continuous under jitter");
            }
            var remaining = terrain.getClass().getDeclaredField("remaining"); remaining.setAccessible(true);
            var begin = terrain.getClass().getDeclaredMethod("begin", long.class, UUID.class); begin.setAccessible(true);
            UUID fartherLocked = bodies.get(0).getUniqueId(); lock.set(radar, fartherLocked);
            // Expire confirmed samples without introducing new, unprepared terrain columns.
            advance(level, 6); begin.invoke(terrain, level.getGameTime(), UUID.randomUUID()); remaining.setInt(terrain, 800);
            scan.invoke(radar);
            var visibilityField = StabilizerControllerBlockEntity.class.getDeclaredField("radarVisibility"); visibilityField.setAccessible(true);
            Object lockedSample = map(visibilityField.get(radar), "samples").get(fartherLocked);
            var sampledTick = lockedSample.getClass().getDeclaredMethod("tick"); sampledTick.setAccessible(true);
            require(((Long) sampledTick.invoke(lockedSample)) == level.getGameTime(),
                    "a farther locked target is revalidated before nearer distractors consume the last scan budget");
            lock.set(radar, target);
            for (int i = 0; i < 30; i++) {
                radar.origin = radar.origin.add(9.75, 0, 0);
                advance(level, 2);
                if (i % 2 == 0) {
                    begin.invoke(terrain, level.getGameTime(), UUID.randomUUID()); remaining.setInt(terrain, 0);
                }
                scan.invoke(radar);
                require(target.equals(radar.getRadarLockedSubLevelId()) && radar.getRadarContact(target) != null,
                        "an unfinished budget-limited query must not clear an established lock");
            }
            // Pending does not mean visible for a target that has never been confirmed.
            var fresh = new RadarVisibility();
            remaining.setInt(terrain, 0);
            require(fresh.scan(level, radar.origin, Set.of(), List.of(new RadarVisibility.Target(UUID.randomUUID(),
                    origin.add(4000, 0, 0), new AABB(origin, origin).move(4000, 0, 0), Set.of()))).isEmpty(),
                    "a brand-new target does not inherit an older target's pending visibility");
            BlockPos wall = BlockPos.containing(radar.origin.lerp(points.get(1), .5));
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            advance(level, 6); scan.invoke(radar);
            require(radar.getRadarContact(target) == null && radar.getRadarLockedSubLevelId() == null,
                    "a confirmed wall clears the actual radar lock rather than coasting through it");
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            StabilizedTurret.LOGGER.info("RADAR STABILITY PASS: 3 far physical targets, >512 columns, fixed 512 palette cap, physical jitter, continuous lock, intermittent budget exhaustion, actual wall unlock");
            checkLowAltitude(level, worlds);
        } finally {
            for (SubLevel body : bodies) SubLevelContainer.getContainer(level).removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            ((Map<?, ?>) worlds.get(null)).remove(level);
            level.getServer().getWorldData().overworldData().setGameTime(before);
        }
    }
    private static void checkLowAltitude(ServerLevel level, java.lang.reflect.Field worlds) throws Exception {
        ((Map<?, ?>) worlds.get(null)).remove(level);
        Vec3 origin = new Vec3(1_400_000.5, 140.75, 1_400_000.5), point = origin.add(4096, 0, 0);
        SubLevel body = RadarVisibilityIntegrationChecks.body(level, point);
        try {
            var radar = new Computer(origin); radar.setLevel(level);
            var range = StabilizerControllerBlockEntity.class.getDeclaredField("aircraftRadarRange"); range.setAccessible(true); range.setDouble(radar, 20_000);
            var scan = StabilizerControllerBlockEntity.class.getDeclaredMethod("updateRadarScan"); scan.setAccessible(true);
            advance(level, 2); scan.invoke(radar); // Creates the bounded terrain cache.
            var states = new net.minecraft.world.level.chunk.PalettedContainer<>(
                    net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES);
            for (int y = 0; y < 10; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                states.set(x, y, z, Blocks.STONE.defaultBlockState());
            // A saved ground section shares the ray's section Y, but every ray voxel is air.
            var snapshot = new RocketTerrainCache.Snapshot(Map.of(8, states));
            int chunkX = BlockPos.containing(origin).getX() >> 4, chunkZ = BlockPos.containing(origin).getZ() >> 4;
            for (int x = chunkX; x <= chunkX + 256; x++)
                RadarVisibilityIntegrationChecks.installSaved(level, x, chunkZ, snapshot);
            boolean acquired = false;
            for (int i = 0; i < 4; i++) {
                advance(level, 2); scan.invoke(radar);
                if (radar.getRadarContact(body.getUniqueId()) != null) { acquired = true; break; }
            }
            require(acquired, "a stationary 4km low-altitude target above saved ground must be acquired");
            var lock = StabilizerControllerBlockEntity.class.getDeclaredField("radarLockedSubLevelId"); lock.setAccessible(true);
            lock.set(radar, body.getUniqueId());
            int loaded = level.getChunkSource().getLoadedChunksCount();
            for (int i = 0; i < 20; i++) {
                radar.origin = radar.origin.add(9.75, 0, 0);
                advance(level, 2); scan.invoke(radar);
                require(body.getUniqueId().equals(radar.getRadarLockedSubLevelId()),
                        "4km flight just above ground must finish within the existing section-work budget");
            }
            require(level.getChunkSource().getLoadedChunksCount() == loaded, "low-altitude saved terrain never installs chunks");
            BlockPos wall = BlockPos.containing(radar.origin.lerp(point, .5));
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            advance(level, 6); scan.invoke(radar);
            require(radar.getRadarLockedSubLevelId() == null, "low-altitude skipping still detects a real wall");
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            StabilizedTurret.LOGGER.info("RADAR LOW ALTITUDE PASS: 4km real target above saved nonempty sections, stationary acquisition, 351km/h continuous lock, original work cap, real wall unlock");
        } finally {
            SubLevelContainer.getContainer(level).removeSubLevel(body, SubLevelRemovalReason.REMOVED);
        }
    }
    private static Map<?, ?> map(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return (Map<?, ?>) field.get(object);
    }
    private static void advance(ServerLevel level, int count) { level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + count); }
    private static final class Computer extends StabilizerControllerBlockEntity {
        Vec3 origin;
        Computer(Vec3 origin) { super(BlockPos.containing(origin), ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState()); this.origin = origin; }
        @Override public boolean isRadarActive() { return true; }
        @Override public boolean isAircraftRadarMode() { return true; }
        @Override public Vec3 getRadarOrigin() { return origin; }
        @Override public Vec3 getAircraftRadarForward() { return new Vec3(1, 0, 0); }
        @Override public Vec3 getAircraftRadarUp() { return new Vec3(0, 1, 0); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private RadarStabilityIntegrationChecks() {}
}
