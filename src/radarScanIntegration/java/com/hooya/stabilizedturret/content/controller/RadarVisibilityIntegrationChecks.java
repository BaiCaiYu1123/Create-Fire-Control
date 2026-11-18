package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.display.RadarScanIntegration;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Real server chunks/plots and production scans. Runs only in a disposable integration world. */
public final class RadarVisibilityIntegrationChecks {
    private static int fixtureBlockSequence;
    public static void check(ServerLevel level) throws Exception {
        List<SubLevel> bodies = new ArrayList<>();
        long originalTime = level.getGameTime();
        var raycastSetting = com.hooya.stabilizedturret.config.FireControlSettings.RADAR_RAYCAST;
        boolean originalRaycastSetting = raycastSetting.get();
        raycastSetting.set(true);
        try {
            RadarMotionMatrixChecks.check(level);
            RadarLateralMotionIntegrationChecks.check(level);
            RadarLongRangeIntegrationChecks.check(level);
            RadarStabilityIntegrationChecks.check(level);
            Vec3 from = new Vec3(320.5, 200.75, 320.5), to = from.add(48, 0, 0);
            for (int x = 20; x <= 24; x++) level.getChunk(x, 20);
            BlockPos wall = BlockPos.containing(from).east(8);
            // Make this disposable fixture repeatable after an earlier failed assertion.
            BlockPos oldBearingPos = BlockPos.containing(from).east();
            if (level.getBlockEntity(oldBearingPos) instanceof
                    com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity oldBearing
                    && oldBearing.isRunning()) oldBearing.disassemble();
            for (BlockPos pos : List.of(BlockPos.containing(from), oldBearingPos,
                    oldBearingPos.below(), oldBearingPos.above(), wall)) level.removeBlock(pos, false);
            RadarVisibility scanner = new RadarVisibility();
            var target = target(UUID.randomUUID(), to, Set.of());
            require(sees(scanner, level, from, target, Set.of()), "clear terrain");
            for (BlockState state : new BlockState[]{Blocks.STONE.defaultBlockState(),
                    Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                    Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.AIR.defaultBlockState()}) {
                level.setBlockAndUpdate(wall, state);
                next(level, 5);
                boolean expected = !state.is(Blocks.STONE);
                require(sees(scanner, level, from, target, Set.of()) == expected,
                        "actual collision shape / no fluid collision: " + state);
            }
            level.setBlockAndUpdate(wall, Blocks.OAK_SLAB.defaultBlockState()
                    .setValue(BlockStateProperties.WATERLOGGED, true)
                    .setValue(BlockStateProperties.SLAB_TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP));
            next(level, 5);
            require(!sees(scanner, level, from, target, Set.of()), "waterlogged solid still blocks");
            var raycast = com.hooya.stabilizedturret.config.FireControlSettings.RADAR_RAYCAST;
            boolean originalRaycast = raycast.get();
            try {
                raycast.set(false);
                require(sees(scanner, level, from, target, Set.of()),
                        "disabled radar raycast sees through an unchanged solid wall immediately");
                raycast.set(true);
                require(!sees(scanner, level, from, target, Set.of()),
                        "re-enabled radar raycast rejects the same wall without stale clear cache");
            } finally {
                raycast.set(originalRaycast);
            }
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());

            SubLevel body = body(level, to); bodies.add(body);
            Computer receiver = new Computer(to, true, Set.of(body.getUniqueId())); receiver.setLevel(level);
            var physical = target(body.getUniqueId(), to, Set.of(body.getUniqueId()));
            next(level, 5);
            require(sees(scanner, level, from, physical, Set.of()), "target's own hull is not an occluder");
            SubLevel blocker = body(level, from.add(8, 0, 0)); bodies.add(blocker);
            next(level, 5);
            require(!sees(scanner, level, from, physical, Set.of()), "other Sable hull blocks");
            scanner.clear();
            require(sees(scanner, level, from, physical, Set.of(blocker.getUniqueId())), "own hull is ignored");
            blocker.logicalPose().orientation().rotationY(.65).rotateZ(.3);
            blocker.updateBoundingBox(); blocker.forceUpdateGlobalBounds();
            scanner.clear();
            require(!sees(scanner, level, from, physical, Set.of()), "rotated physical hull blocks");
            SubLevelContainer.getContainer(level).removeSubLevel(blocker, SubLevelRemovalReason.REMOVED);
            bodies.remove(blocker);

            // Only hardware-presence/orientation are supplied; updateRadarScan and all filtering remain production code.
            for (boolean aircraft : new boolean[]{false, true}) {
                Computer computer = new Computer(from, aircraft); computer.setLevel(level);
                Method scan = StabilizerControllerBlockEntity.class.getDeclaredMethod("updateRadarScan"); scan.setAccessible(true);
                ControllerTracker.add(computer);
                try {
                    RwrThreat.Type warning = aircraft ? RwrThreat.Type.RFL : RwrThreat.Type.AAA;
                    next(level, 5); scan.invoke(computer);
                    require(computer.getRadarContact(body.getUniqueId()) != null, "clear " + aircraft + " computer radar");
                    requireRwr(receiver, warning, true, "unblocked computer illuminates aircraft");
                    level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
                    next(level, 5); scan.invoke(computer);
                    require(computer.getRadarContact(body.getUniqueId()) == null, "blocked " + aircraft + " computer radar");
                    requireRwr(receiver, warning, false, "blocked computer stops RWR illumination warning");
                    level.setBlockAndUpdate(wall, Blocks.WATER.defaultBlockState());
                    next(level, 5); scan.invoke(computer);
                    require(computer.getRadarContact(body.getUniqueId()) != null, "water ignored by " + aircraft + " computer radar");
                    requireRwr(receiver, warning, true, "computer RWR warning passes through water");
                    level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
                } finally { ControllerTracker.remove(computer); }
            }
            checkMissileLocks(level, from, body, wall);
            BlockPos displayPos = BlockPos.containing(from);
            level.setBlockAndUpdate(displayPos, ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState());
            var display = (ConnectedDisplayBlockEntity) level.getBlockEntity(displayPos);
            display.onLoad();
            BlockPos bearingPos = displayPos.east();
            level.setBlockAndUpdate(bearingPos.below(), com.simibubi.create.AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(BlockStateProperties.FACING, net.minecraft.core.Direction.UP));
            level.setBlockAndUpdate(bearingPos, com.simibubi.create.AllBlocks.MECHANICAL_BEARING.getDefaultState()
                    .setValue(BlockStateProperties.FACING, net.minecraft.core.Direction.UP));
            level.setBlockAndUpdate(bearingPos.above(), Blocks.STONE.defaultBlockState());
            var bearing = (com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity) level.getBlockEntity(bearingPos);
            bearing.setSpeed(16); bearing.assemble(); bearing.setSpeed(16);
            require(bearing.isRunning() && bearing.getMovedContraption() != null, "real display radar bearing assembled");
            display.bind(BindingSlot.RADAR_BEARING, BindingRef.capture(level, bearingPos));
            var chicken = RadarScanIntegration.chicken(level, to.add(0, -.25, 2));
            Method scanDisplay = ConnectedDisplayBlockEntity.class.getDeclaredMethod("scan", long.class); scanDisplay.setAccessible(true);
            next(level, 5); scanDisplay.invoke(display, level.getGameTime());
            require(display.getContacts().stream().anyMatch(c -> c.key().id().equals(chicken.getUUID())), "display sees clear living target");
            requireRwr(receiver, RwrThreat.Type.AAA, true, "display radar illuminates aircraft");
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            next(level, 5); scanDisplay.invoke(display, level.getGameTime());
            require(display.getContacts().stream().noneMatch(c -> c.key().id().equals(chicken.getUUID())), "occlusion bypasses display's 40-tick track hold");
            requireRwr(receiver, RwrThreat.Type.AAA, false, "blocked display radar stops RWR warning");
            level.setBlockAndUpdate(wall, Blocks.WATER.defaultBlockState());
            next(level, 5); scanDisplay.invoke(display, level.getGameTime());
            requireRwr(receiver, RwrThreat.Type.AAA, true, "display RWR warning passes through water");
            next(level, 5);
            requireRwr(receiver, RwrThreat.Type.AAA, false, "held display contact is not current radar illumination");
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            chicken.discard();
            StabilizedTurret.LOGGER.info("RWR OCCLUSION PASS: aircraft/ground/display emitters, obstruction removal, fluid transparency, stale display plot rejection, zero extra server rays");

            // Deterministic completed disk snapshot: verify a wall in genuinely unloaded terrain without loading it.
            Vec3 farFrom = new Vec3(48_000.5, 200.75, 48_000.5), farTo = farFrom.add(8, 0, 0);
            int chunkX = BlockPos.containing(farFrom).getX() >> 4, chunkZ = BlockPos.containing(farFrom).getZ() >> 4;
            require(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "saved terrain fixture starts unloaded");
            var states = new PalettedContainer<>(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY,
                    Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
            states.set(4, 200 & 15, 0, Blocks.STONE.defaultBlockState());
            var snapshot = new RocketTerrainCache.Snapshot(Map.of(200 >> 4, states));
            installSaved(level, chunkX, chunkZ, snapshot);
            next(level, 5); scanner.clear();
            int loaded = level.getChunkSource().getLoadedChunksCount();
            require(!sees(scanner, level, farFrom, target(UUID.randomUUID(), farTo, Set.of()), Set.of()), "saved unloaded mountain blocks radar");
            require(level.getChunkSource().getLoadedChunksCount() == loaded, "saved terrain does not install a live chunk");

            // Cold unknown terrain queues async I/O and hides the target instead of loading/generating chunks.
            Vec3 cold = farFrom.add(1024, 0, 0);
            scanner.clear(); next(level, 5);
            var coldTarget = target(UUID.randomUUID(), cold.add(8, 0, 0), Set.of());
            require(!sees(scanner, level, cold, coldTarget, Set.of()), "unknown terrain waits for confirmation");
            require(level.getChunkSource().getLoadedChunksCount() == loaded, "cold query creates zero chunks");
            boolean ready = false;
            for (int i = 0; i < 100 && !ready; i++) {
                Thread.sleep(2); next(level, 1);
                ready = sees(scanner, level, cold, coldTarget, Set.of());
            }
            require(ready && level.getChunkSource().getLoadedChunksCount() == loaded,
                    "async missing-chunk read completes without generating or installing a chunk");

            List<RadarVisibility.Target> crowd = new ArrayList<>();
            Vec3 crowdOrigin = from.add(1, 0, 0);
            for (int i = 0; i < 20_000; i++) crowd.add(target(new UUID(0, i + 1), crowdOrigin.add(2, 0, (i % 8) * .05), Set.of()));
            // Warm the code before timing; every batch still starts with an empty target cache.
            for (int i = 0; i < 12; i++) { next(level, 1); new RadarVisibility().scan(level, crowdOrigin, Set.of(), crowd); }
            long start = System.nanoTime();
            next(level, 5); scanner.clear();
            Set<UUID> first = scanner.scan(level, crowdOrigin, Set.of(), crowd);
            double milliseconds = (System.nanoTime() - start) / 1e6;
            require(!first.isEmpty() && first.size() <= RadarVisibility.MAX_RAYS_PER_SCAN, "20,000 targets obey the 64-ray batch cap");
            next(level, 1);
            Set<UUID> second = scanner.scan(level, crowdOrigin, Set.of(), crowd);
            require(second.stream().anyMatch(id -> !first.contains(id)), "round robin progresses beyond the first batch");
            require(level.getChunkSource().getLoadedChunksCount() == loaded, "mass-target queries create zero chunks");
            StabilizedTurret.LOGGER.info("RADAR OCCLUSION PASS: 20,000 candidates, {} confirmed in {}ms, 64-ray cap, 16,384 world-step cap, zero new chunks",
                    first.size(), milliseconds);

            Vec3 longOrigin = new Vec3(64_000.5, 200.75, 64_000.5);
            var empty = new RocketTerrainCache.Snapshot(Map.of());
            for (int x = 4000; x <= 4128; x++) installSaved(level, x, 4000, empty);
            List<RadarVisibility.Target> distantCrowd = new ArrayList<>();
            for (int i = 0; i < 20_000; i++) distantCrowd.add(target(new UUID(1, i + 1), longOrigin.add(2048, 0, (i % 8) * .05), Set.of()));
            next(level, 1); scanner.clear(); start = System.nanoTime();
            Set<UUID> distant = scanner.scan(level, longOrigin, Set.of(), distantCrowd);
            milliseconds = (System.nanoTime() - start) / 1e6;
            require(!distant.isEmpty() && distant.size() < RadarVisibility.MAX_RAYS_PER_SCAN,
                    "long rays also obey the shared section-work budget");
            require(level.getChunkSource().getLoadedChunksCount() == loaded, "2km mass-target scan loads zero chunks");
            StabilizedTurret.LOGGER.info("RADAR DISTANCE BUDGET PASS: 20,000 candidates at 2km, {} confirmed in {}ms, remainder deferred, zero new chunks",
                    distant.size(), milliseconds);
        } finally {
            raycastSetting.set(originalRaycastSetting);
            for (SubLevel body : bodies) SubLevelContainer.getContainer(level).removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            level.getServer().getWorldData().overworldData().setGameTime(originalTime);
        }
    }

    @SuppressWarnings("unchecked")
    static void installSaved(ServerLevel level, int x, int z, RocketTerrainCache.Snapshot snapshot) throws Exception {
        var worldsField = RadarVisibility.class.getDeclaredField("WORLDS"); worldsField.setAccessible(true);
        var worlds = (Map<ServerLevel, Object>) worldsField.get(null);
        // An earlier normal query created the real shared terrain cache.
        Object terrain = worlds.get(level);
        var savedField = terrain.getClass().getDeclaredField("saved"); savedField.setAccessible(true);
        var saved = (Map<Long, Object>) savedField.get(terrain);
        var ctor = Class.forName(RadarVisibility.class.getName() + "$Saved").getDeclaredConstructors()[0]; ctor.setAccessible(true);
        Object entry = ctor.newInstance(level.getGameTime(), CompletableFuture.completedFuture(snapshot));
        var future = entry.getClass().getDeclaredField("future"); future.setAccessible(true);
        ((CompletableFuture<?>) future.get(entry)).join(); // Deterministic fixture only; production never waits.
        saved.put(ChunkPos.asLong(x, z), entry);
    }
    private static RadarVisibility.Target target(UUID id, Vec3 center, Set<UUID> members) {
        return new RadarVisibility.Target(id, center, new AABB(center, center).inflate(.5), members);
    }
    private static boolean sees(RadarVisibility scanner, ServerLevel level, Vec3 from, RadarVisibility.Target target, Set<UUID> own) {
        return scanner.scan(level, from, own, List.of(target)).contains(target.id());
    }
    static void checkMissileLocks(ServerLevel level, Vec3 origin, SubLevel body, BlockPos wall) throws Exception {
        Computer computer = new Computer(origin, true); computer.setLevel(level);
        var track = InfraredSeeker.observe(level, body.getUniqueId(), Set.of(body.getUniqueId()), level.getGameTime());
        Vec3 forward = track.center().subtract(origin).normalize();
        Vec3 right = new Vec3(0, 0, 1), up = right.cross(forward).normalize();
        var frame = new InfraredTargeting.Frame(origin, forward, right, up, .7, 16.0 / 9, 1280, 720);
        var acquire = InfraredTargeting.class.getDeclaredMethod("acquire", StabilizerControllerBlockEntity.class,
                InfraredTargeting.Frame.class, long.class); acquire.setAccessible(true);
        next(level, 5);
        require(acquire.invoke(null, computer, frame, level.getGameTime()) != null, "IR acquires an unobstructed physical target");
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState()); next(level, 5);
        require(acquire.invoke(null, computer, frame, level.getGameTime()) == null, "IR cannot initially acquire through blocks");
        seedInfrared(computer, frame, track, false);
        require(InfraredTargeting.target(computer) == null, "IR loses a sustained lock behind blocks");

        level.setBlockAndUpdate(wall, Blocks.WATER.defaultBlockState()); next(level, 5);
        require(acquire.invoke(null, computer, frame, level.getGameTime()) != null, "IR initially acquires through water");
        seedInfrared(computer, frame, track, false);
        require(InfraredTargeting.target(computer) != null, "IR lock ignores water");
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        seedInfrared(computer, frame, track, true);
        require(InfraredTargeting.launchTarget(computer) == null,
                "IR launch rejects a newly placed same-tick wall despite an earlier clear cache entry");

        level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState()); next(level, 5);
        setField(computer, "hasPodStabilizedWorldTarget", true); setField(computer, "podStabilizedWorldTarget", track.center());
        var acquireGround = StabilizerControllerBlockEntity.class.getDeclaredMethod("findAirToGroundTargetInPodFrame");
        acquireGround.setAccessible(true);
        require(acquireGround.invoke(computer) != null, "air-to-ground acquires an unobstructed physical target");
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState()); next(level, 5);
        require(acquireGround.invoke(computer) == null, "air-to-ground cannot initially acquire through blocks");
        seedGround(computer, body);
        require(computer.getAirToGroundTargetSnapshot() == null, "air-to-ground loses a sustained lock behind blocks");
        level.setBlockAndUpdate(wall, Blocks.LAVA.defaultBlockState()); next(level, 5);
        require(acquireGround.invoke(computer) != null, "air-to-ground initially acquires through lava");
        seedGround(computer, body);
        require(computer.getAirToGroundTargetSnapshot() != null, "air-to-ground lock ignores lava");
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        require(computer.getAirToGroundLaunchTargetSnapshot() == null,
                "air-to-ground launch rechecks a newly placed same-tick wall");
        var raycast = com.hooya.stabilizedturret.config.FireControlSettings.RADAR_RAYCAST;
        boolean original = raycast.get();
        int loadedChunks = level.getChunkSource().getLoadedChunksCount();
        try {
            for (int toggle = 0; toggle < 2; toggle++) {
                // Leave the same solid wall in place and reuse the same controller/caches.
                raycast.set(false);
                require(acquire.invoke(null, computer, frame, level.getGameTime()) != null,
                        "disabled occlusion must allow IR initial acquisition through the wall");
                seedInfrared(computer, frame, track, false);
                require(InfraredTargeting.target(computer) != null && InfraredTargeting.launchTarget(computer) != null,
                        "disabled occlusion must allow IR lock retention and fresh launch through the wall");
                require(acquireGround.invoke(computer) != null, "disabled occlusion must allow AGM acquisition through the wall");
                seedGround(computer, body);
                require(computer.getAirToGroundTargetSnapshot() != null && computer.getAirToGroundLaunchTargetSnapshot() != null,
                        "disabled occlusion must allow AGM lock retention and fresh launch through the wall");
                for (var type : List.of(MianbaoAircraftCompat.WeaponType.CLOSE,
                        MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT,
                        MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_TOP_ATTACK))
                    require(computer.isAircraftWeaponGuidanceReady(type), "disabled occlusion must not prevent native launch readiness: " + type);
                require(acquire.invoke(null, computer, frame.withoutSeeker(), level.getGameTime()) == null,
                        "disabling occlusion must not bypass seeker activation");
                require(InfraredRaycast.clip(level, origin, track.center().add(forward), Set.of(body.getUniqueId())) == null,
                        "disabling occlusion must still exclude the ignored physical body");

                raycast.set(true);
                // Same-tick launch checks must not reuse the clear result obtained while disabled.
                seedInfrared(computer, frame, track, true); seedGround(computer, body);
                require(InfraredTargeting.launchTarget(computer) == null && computer.getAirToGroundLaunchTargetSnapshot() == null,
                        "re-enabling occlusion must immediately prevent both launches behind the unchanged wall");
                next(level, 5);
                require(acquire.invoke(null, computer, frame, level.getGameTime()) == null
                        && acquireGround.invoke(computer) == null, "re-enabled occlusion must block initial IR and AGM acquisition");
                seedInfrared(computer, frame, track, false); seedGround(computer, body);
                require(InfraredTargeting.target(computer) == null && computer.getAirToGroundTargetSnapshot() == null,
                        "re-enabled occlusion must end obstructed IR and AGM locks");
            }
            require(level.getChunkSource().getLoadedChunksCount() == loadedChunks,
                    "changing missile lock occlusion must not load world chunks");
        } finally { raycast.set(original); }
        level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
        InfraredTargeting.clear(computer);
        StabilizedTurret.LOGGER.info("MISSILE LOCK OCCLUSION PASS: shared toggle for IR/AGM acquisition, sustained lock and same-tick launch validation; repeated off/on with unchanged wall; both AGM types; fluid transparency; no extra chunks");
    }
    private static void seedGround(Computer computer, SubLevel body) throws Exception {
        setField(computer, "airToGroundLockedSubLevelId", body.getUniqueId());
        setField(computer, "airToGroundLockedMembers", Set.of(body.getUniqueId()));
    }
    @SuppressWarnings("unchecked")
    private static void seedInfrared(Computer computer, InfraredTargeting.Frame frame, InfraredSeeker.Track track,
                                      boolean alreadyEvaluated) throws Exception {
        var type = Class.forName(InfraredTargeting.class.getName() + "$State");
        var ctor = type.getDeclaredConstructor(); ctor.setAccessible(true); Object state = ctor.newInstance();
        setField(state, "frame", frame); setField(state, "receivedAt", computer.getLevel().getGameTime());
        setField(state, "evaluatedAt", alreadyEvaluated ? computer.getLevel().getGameTime() : Long.MIN_VALUE);
        setField(state, "track", track); setField(state, "lastPosition", track.center());
        var field = InfraredTargeting.class.getDeclaredField("STATES"); field.setAccessible(true);
        ((Map<StabilizerControllerBlockEntity, Object>) field.get(null)).put(computer, state);
    }
    private static void setField(Object owner, String name, Object value) throws Exception {
        Class<?> type = owner instanceof StabilizerControllerBlockEntity ? StabilizerControllerBlockEntity.class : owner.getClass();
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static void requireRwr(Computer receiver, RwrThreat.Type type, boolean expected, String message) throws Exception {
        var worldsField = RadarVisibility.class.getDeclaredField("WORLDS"); worldsField.setAccessible(true);
        Object terrain = ((Map<?, ?>) worldsField.get(null)).get(receiver.getLevel());
        var steps = terrain.getClass().getDeclaredField("remaining"); steps.setAccessible(true);
        int before = steps.getInt(terrain);
        boolean warned = RwrThreatCollector.collect(receiver).stream().anyMatch(threat -> threat.type() == type);
        require(warned == expected, message);
        require(steps.getInt(terrain) == before, "RWR reuses confirmed radar contacts instead of issuing extra server terrain queries");
    }
    private static void next(ServerLevel level, int ticks) {
        level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + ticks);
    }
    static SubLevel body(ServerLevel level, Vec3 position) {
        var body = SubLevelContainer.getContainer(level).allocateNewSubLevel(new Pose3d());
        // Sable can recycle a plot before the previous fixture's storage notification
        // is drained. A distinct block position ensures a real add, never a no-op set.
        BlockPos block = body.getPlot().getCenterBlock().atY(200 + fixtureBlockSequence++ % 100);
        body.getPlot().newEmptyChunk(new ChunkPos(block));
        level.setBlockAndUpdate(block, Blocks.STONE.defaultBlockState()); body.getPlot().updateBoundingBox();
        body.logicalPose().rotationPoint().set(block.getX() + .5, block.getY() + .75, block.getZ() + .5);
        body.logicalPose().position().set(position.x, position.y, position.z);
        body.updateBoundingBox(); body.forceUpdateGlobalBounds();
        return body;
    }
    private static final class Computer extends StabilizerControllerBlockEntity {
        final Vec3 origin; final boolean aircraft;
        final Set<UUID> structure;
        Computer(Vec3 origin, boolean aircraft) {
            this(origin, aircraft, Set.of());
        }
        Computer(Vec3 origin, boolean aircraft, Set<UUID> structure) {
            super(BlockPos.containing(origin), ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
            this.origin = origin; this.aircraft = aircraft; this.structure = structure;
        }
        @Override public boolean isRadarActive() { return true; }
        @Override public boolean isAircraftRadarActive() { return aircraft; }
        @Override public boolean isInfraredWeaponArmed() { return true; }
        @Override public Vec3 getInfraredAircraftOrigin() { return origin; }
        @Override public Vec3 getPodCameraPosition() { return origin; }
        @Override public Set<UUID> getOwnControlledSubLevelIds() { return structure; }
        @Override public boolean isAircraftRadarMode() { return aircraft; }
        @Override public Vec3 getRadarOrigin() { return origin; }
        @Override public Vec3 getAircraftRadarForward() { return new Vec3(1, 0, 0); }
        @Override public Vec3 getAircraftRadarUp() { return new Vec3(0, 1, 0); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private RadarVisibilityIntegrationChecks() {}
}
