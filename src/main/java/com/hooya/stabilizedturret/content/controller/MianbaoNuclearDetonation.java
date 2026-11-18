package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.CruiseMissileGuidance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Terrain-only equivalent of Mianbao's nuclear procedures. Visuals and
 * sounds are played independently at missile impact; this class never emits
 * explosion packets, particles, sound, entity damage, or a native NukeBlock.
 */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoNuclearDetonation {
    /** Current Mianbao 2.5.0 Nuke_dynamite crater dimensions. */
    private static final int CRATER_HORIZONTAL_RADIUS = 34;
    private static final int CRATER_VERTICAL_RADIUS = 25;
    private static final int SCORCH_RADIUS = 79;
    /** Bounds expensive block reads/writes, not merely successful mutations. */
    private static final int CRATER_POSITIONS_PER_TICK = 2_048;
    private static final int SCORCH_POSITIONS_PER_TICK = 8_192;
    private static final double WAVE_BLAST_TARGET_RADIUS = 24.0;
    private static final int WAVE_INTERVAL_TICKS = 11;
    private static final String DATA_NAME = "create_fire_control_nuclear_detonation";
    /** Covers the outermost radius-30 shockwave plus its strength-10 blasts. */
    private static final int MAX_WORK_RADIUS = 168;
    private static final int DETONATION_TICKETS_PER_TICK = 4;
    private static final TicketType<UUID> DETONATION_TICKET = TicketType.create(
            "create_fire_control_nuclear_detonation", UUID::compareTo);
    private static final int[] CRATER_OFFSETS = buildCraterOffsets();
    private static final int[] SABLE_CORE_OFFSETS = buildSableCoreOffsets();
    private static final List<Work> ACTIVE = new ArrayList<>();
    private static final Set<ServerLevel> LOADED_LEVELS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final TagKey<Block> VINES = tag("vines");
    private static final TagKey<Block> WATER_PLANTS = tag("water_plants");
    private static final TagKey<Block> KELP = tag("kelp");

    public static void detonate(ServerLevel level, UUID missileId, Vec3 position) {
        if (level == null || missileId == null || position == null) return;
        ensureLoaded(level);
        BlockPos center = BlockPos.containing(position);
        // Fire-control nuclear terrain work always uses the paced wave path.
        // Visibility only controls who receives visuals; it must not change
        // terrain scheduling or allow off-screen detonations to burst a ring.
        data(level).add(missileId, center, true);
        if (ACTIVE.stream().anyMatch(work -> work.level == level
                && work.missileId.equals(missileId))) return;
        long queuedAhead = ACTIVE.stream()
                .filter(work -> work.level.getServer() == level.getServer())
                .count();
        ACTIVE.add(new Work(level, missileId, center, true));
        StabilizedTurret.LOGGER.info(
                "Queued nuclear terrain detonation missile={} center={} queuedAhead={}",
                missileId, center, queuedAhead);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) ensureLoaded(level);
        if (ACTIVE.isEmpty()) return;
        Iterator<Work> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            Work work = iterator.next();
            if (work.level.getServer() != event.getServer()) continue;
            boolean complete = work.tick();
            if (!complete) data(work.level).update(work);
            if (complete) {
                work.releaseTickets();
                data(work.level).remove(work.missileId);
                iterator.remove();
            }
            // Nuclear visuals, sound and entity damage are emitted immediately
            // by the caller. Terrain mutation is deliberately global FIFO:
            // even when this work completes, the next detonation starts next tick.
            break;
        }
    }

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent event) {
        ACTIVE.forEach(work -> {
            data(work.level).update(work);
            work.releaseTickets();
        });
        ACTIVE.clear();
        LOADED_LEVELS.clear();
    }

    private static int[] buildCraterOffsets() {
        List<Integer> offsets = new ArrayList<>();
        for (int y = -CRATER_VERTICAL_RADIUS + 1; y <= 0; y++) {
            for (int x = -CRATER_HORIZONTAL_RADIUS;
                 x <= CRATER_HORIZONTAL_RADIUS; x++) {
                for (int z = -CRATER_HORIZONTAL_RADIUS;
                     z <= CRATER_HORIZONTAL_RADIUS; z++) {
                    double distance = (double) (x * x)
                            / (CRATER_HORIZONTAL_RADIUS * CRATER_HORIZONTAL_RADIUS)
                            + (double) (y * y)
                            / (CRATER_VERTICAL_RADIUS * CRATER_VERTICAL_RADIUS)
                            + (double) (z * z)
                            / (CRATER_HORIZONTAL_RADIUS * CRATER_HORIZONTAL_RADIUS);
                    if (distance <= 1.0) offsets.add(packOffset(x, y, z));
                }
            }
        }
        return offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] buildSableCoreOffsets() {
        List<Integer> offsets = new ArrayList<>();
        for (int y = -CRATER_VERTICAL_RADIUS + 1;
             y <= CRATER_VERTICAL_RADIUS - 1; y++) {
            for (int x = -CRATER_HORIZONTAL_RADIUS;
                 x <= CRATER_HORIZONTAL_RADIUS; x++) {
                for (int z = -CRATER_HORIZONTAL_RADIUS;
                     z <= CRATER_HORIZONTAL_RADIUS; z++) {
                    double distance = (double) (x * x)
                            / (CRATER_HORIZONTAL_RADIUS * CRATER_HORIZONTAL_RADIUS)
                            + (double) (y * y)
                            / (CRATER_VERTICAL_RADIUS * CRATER_VERTICAL_RADIUS)
                            + (double) (z * z)
                            / (CRATER_HORIZONTAL_RADIUS * CRATER_HORIZONTAL_RADIUS);
                    if (distance <= 1.0) offsets.add(packOffset(x, y, z));
                }
            }
        }
        return offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int packOffset(int x, int y, int z) {
        return ((y + CRATER_VERTICAL_RADIUS - 1) << 14)
                | ((x + CRATER_HORIZONTAL_RADIUS) << 7)
                | (z + CRATER_HORIZONTAL_RADIUS);
    }

    private static int unpackX(int packed) {
        return ((packed >>> 7) & 0x7F) - CRATER_HORIZONTAL_RADIUS;
    }

    private static int unpackY(int packed) {
        return (packed >>> 14) - CRATER_VERTICAL_RADIUS + 1;
    }

    private static int unpackZ(int packed) {
        return (packed & 0x7F) - CRATER_HORIZONTAL_RADIUS;
    }

    private static final class Work {
        final ServerLevel level;
        final UUID missileId;
        final BlockPos center;
        final List<MianbaoDeferredDetonation.TerrainTarget> craterTargets;
        final List<MianbaoDeferredDetonation.TerrainTarget> scorchTargets;
        final Set<ChunkPos> dirty = new HashSet<>();
        final Set<UUID> loggedDamagedSubLevels = new HashSet<>();
        final List<ChunkPos> ticketPlan;
        final Set<ChunkPos> ticketedChunks = new HashSet<>();
        int nextTicket;
        int nextCraterOffset;
        int craterTarget;
        int scorchX = -SCORCH_RADIUS;
        int scorchY = -SCORCH_RADIUS;
        int scorchZ = -SCORCH_RADIUS;
        int scorchTarget;
        int waveRadius = 1;
        int activeWaveRadius;
        long nextWaveAt = Long.MIN_VALUE;
        int activeWaveSliceTicks;
        int completedWaveBlasts;
        boolean craterFinished;
        boolean scorchFinished;
        final boolean spreadVisibleWaves;
        final ArrayDeque<MianbaoDeferredDetonation.SilentExplosionWork> waveBlasts =
                new ArrayDeque<>();
        boolean processingStarted;
        boolean completionReported;

        Work(ServerLevel level, UUID missileId, BlockPos center,
             boolean spreadVisibleWaves) {
            this.level = level;
            this.missileId = missileId;
            this.center = center;
            // Keep the persisted field for save compatibility, but ignore old
            // false values now that paced waves apply at every viewing range.
            this.spreadVisibleWaves = true;
            this.craterTargets = MianbaoDeferredDetonation.terrainTargets(
                    level, Vec3.atCenterOf(center), CRATER_HORIZONTAL_RADIUS, true);
            this.scorchTargets = MianbaoDeferredDetonation.terrainTargets(
                    level, Vec3.atCenterOf(center), SCORCH_RADIUS, true);
            this.ticketPlan = plannedChunks(center, MAX_WORK_RADIUS);
            StabilizedTurret.LOGGER.info(
                    "Created nuclear terrain work missile={} center={} spreadVisibleWaves={} craterTargets={} craterSubLevels={} scorchTargets={} scorchSubLevels={}",
                    missileId, center,
                    this.spreadVisibleWaves,
                    craterTargets.size(), countSubLevels(craterTargets),
                    scorchTargets.size(), countSubLevels(scorchTargets));
        }

        Work(ServerLevel level, StoredWork stored) {
            this(level, stored.id, stored.center, true);
            nextCraterOffset = stored.nextCraterOffset;
            craterTarget = Math.min(stored.craterTarget, craterTargets.size());
            scorchX = stored.scorchX;
            scorchY = stored.scorchY;
            scorchZ = stored.scorchZ;
            scorchTarget = Math.min(stored.scorchTarget, scorchTargets.size());
            waveRadius = Math.max(1, stored.waveRadius);
            activeWaveRadius = Math.max(0, stored.activeWaveRadius);
            nextWaveAt = stored.nextWaveAt;
            craterFinished = stored.craterFinished;
            scorchFinished = stored.scorchFinished;
            if (activeWaveRadius > 0) {
                queueWave(activeWaveRadius);
                int restoredCompleted = Math.min(
                        stored.completedWaveBlasts, waveBlasts.size());
                for (int index = 0; index < restoredCompleted; index++) {
                    waveBlasts.removeFirst();
                }
                completedWaveBlasts = restoredCompleted;
                activeWaveSliceTicks = Math.max(0, Math.min(
                        WAVE_INTERVAL_TICKS, stored.activeWaveSliceTicks));
            }
        }

        boolean tick() {
            if (!processingStarted) {
                processingStarted = true;
                StabilizedTurret.LOGGER.info(
                        "Started queued nuclear terrain detonation missile={} center={}",
                        missileId, center);
            }
            issueTickets();
            boolean playerLoaded = MianbaoDeferredDetonation
                    .isImpactAreaLoadedByPlayer(
                            level, Vec3.atCenterOf(center), 12.0 * 16.0);
            if (!craterFinished) processCrater();
            if (craterFinished && !scorchFinished) {
                processScorch();
            }
            if (scorchFinished && !waveBlasts.isEmpty()) {
                processWaveBlasts();
            }
            if (waveBlasts.isEmpty() && activeWaveRadius != 0) {
                activeWaveRadius = 0;
                activeWaveSliceTicks = 0;
                completedWaveBlasts = 0;
            }
            if (scorchFinished && waveBlasts.isEmpty() && waveRadius <= 30
                    && level.getGameTime() >= nextWaveAt
                    && waveChunksReady(waveRadius)) {
                int queuedRadius = waveRadius++;
                queueWave(queuedRadius);
                nextWaveAt = level.getGameTime() + WAVE_INTERVAL_TICKS;
            }
            if (!dirty.isEmpty()) {
                CruiseMissileGuidance.markImpactChunksDirty(missileId, Set.copyOf(dirty));
                dirty.clear();
            }
            boolean complete = scorchFinished && waveRadius > 30
                    && waveBlasts.isEmpty();
            if (complete && !completionReported) {
                completionReported = true;
                CruiseMissileGuidance.completeImpactDestruction(missileId);
                StabilizedTurret.LOGGER.info(
                        "Completed silent nuclear terrain detonation missile={} playerLoaded={}",
                        missileId, playerLoaded);
            }
            return complete;
        }

        private void issueTickets() {
            int budget = DETONATION_TICKETS_PER_TICK;
            while (budget-- > 0 && nextTicket < ticketPlan.size()) {
                ChunkPos chunk = ticketPlan.get(nextTicket++);
                if (ticketedChunks.add(chunk)) {
                    level.getChunkSource().addRegionTicket(
                            DETONATION_TICKET, chunk, 0, missileId, true);
                }
            }
        }

        private void releaseTickets() {
            for (ChunkPos chunk : ticketedChunks) {
                level.getChunkSource().removeRegionTicket(
                        DETONATION_TICKET, chunk, 0, missileId, true);
            }
            ticketedChunks.clear();
        }

        private void processCrater() {
            int remainingPositions = CRATER_POSITIONS_PER_TICK;
            while (craterTarget < craterTargets.size()
                    && remainingPositions > 0) {
                MianbaoDeferredDetonation.TerrainTarget target = craterTargets.get(craterTarget);
                int[] offsets = target.subLevelId() == null
                        ? CRATER_OFFSETS : SABLE_CORE_OFFSETS;
                if (nextCraterOffset >= offsets.length) {
                    craterTarget++;
                    nextCraterOffset = 0;
                    continue;
                }
                int packed = offsets[nextCraterOffset];
                remainingPositions--;
                BlockPos pos = BlockPos.containing(
                        target.center().x + unpackX(packed),
                        target.center().y + unpackY(packed),
                        target.center().z + unpackZ(packed));
                if (!loaded(target, pos)) {
                    // Sable allocates only plot chunks that contain structure
                    // data. Empty surrounding plot chunks never become
                    // loaded, so waiting on one would permanently stall the
                    // paced crater cursor. Root chunks still wait for the
                    // detonation tickets issued above.
                    if (target.subLevelId() != null) {
                        nextCraterOffset++;
                        continue;
                    }
                    return;
                }
                nextCraterOffset++;
                BlockState state = target.level().getBlockState(pos);
                if (!state.is(Blocks.BEDROCK) && !state.isAir()) {
                    set(target, pos, Blocks.AIR.defaultBlockState());
                }
                if (Blocks.FIRE.defaultBlockState().canSurvive(target.level(), pos)
                        && randomUnit(1L, target.subLevelId(), packed) < 0.25) {
                    set(target, pos, Blocks.FIRE.defaultBlockState());
                }
            }
            if (craterTarget >= craterTargets.size()) {
                craterFinished = true;
                StabilizedTurret.LOGGER.info(
                        "Completed silent nuclear crater missile={}", missileId);
            }
        }

        private void processScorch() {
            int remainingPositions = SCORCH_POSITIONS_PER_TICK;
            while (!scorchFinished && remainingPositions-- > 0) {
                MianbaoDeferredDetonation.TerrainTarget target =
                        scorchTargets.get(scorchTarget);
                int x = scorchX;
                int y = scorchY;
                int z = scorchZ;
                advanceScorchCursor();
                double distance = (double) (x * x + y * y + z * z)
                        / (SCORCH_RADIUS * SCORCH_RADIUS);
                if (distance > 1.0) continue;
                BlockPos pos = BlockPos.containing(
                        target.center().x + x,
                        target.center().y + y,
                        target.center().z + z);
                if (!loaded(target, pos)) {
                    if (target.subLevelId() != null) continue;
                    retreatScorchCursor(x, y, z);
                    return;
                }
                transformScorchedBlock(target, pos);
            }
            if (scorchFinished) {
                nextWaveAt = level.getGameTime() + WAVE_INTERVAL_TICKS;
                StabilizedTurret.LOGGER.info(
                        "Completed silent nuclear scorch pass missile={}", missileId);
            }
        }

        private void advanceScorchCursor() {
            scorchZ++;
            if (scorchZ <= SCORCH_RADIUS) return;
            scorchZ = -SCORCH_RADIUS;
            scorchX++;
            if (scorchX <= SCORCH_RADIUS) return;
            scorchX = -SCORCH_RADIUS;
            scorchY++;
            if (scorchY <= SCORCH_RADIUS) return;
            scorchTarget++;
            if (scorchTarget >= scorchTargets.size()) {
                scorchFinished = true;
                return;
            }
            scorchX = -SCORCH_RADIUS;
            scorchY = -SCORCH_RADIUS;
            scorchZ = -SCORCH_RADIUS;
        }

        private void retreatScorchCursor(int x, int y, int z) {
            scorchX = x;
            scorchY = y;
            scorchZ = z;
            scorchFinished = false;
        }

        private void transformScorchedBlock(
                MianbaoDeferredDetonation.TerrainTarget target,
                BlockPos pos
        ) {
            ServerLevel targetLevel = target.level();
            BlockState state = targetLevel.getBlockState(pos);
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.SAPLINGS)
                    || state.is(BlockTags.FLOWERS) || state.is(VINES)
                    || state.is(BlockTags.CROPS) || state.is(WATER_PLANTS)
                    || state.is(KELP) || state.is(Blocks.SEAGRASS)
                    || state.is(Blocks.TALL_SEAGRASS) || state.is(Blocks.SHORT_GRASS)
                    || state.is(Blocks.FERN) || state.is(Blocks.TALL_GRASS)) {
                set(target, pos, Blocks.AIR.defaultBlockState());
            } else if (state.getBlock() instanceof LiquidBlock
                    || state.getFluidState().isSource()) {
                set(target, pos, Blocks.LAVA.defaultBlockState());
            } else if (state.is(Blocks.GRAVEL)) {
                set(target, pos, Blocks.MUD.defaultBlockState());
            } else if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
                double roll = randomUnit(2L, target.subLevelId(), pos.asLong());
                if (roll < 0.35) set(target, pos, Blocks.COAL_BLOCK.defaultBlockState());
                else if (roll < 0.805) set(target, pos, blockState("scorchedstone"));
                else if (roll < 0.97075) set(target, pos, Blocks.MAGMA_BLOCK.defaultBlockState());
                else set(target, pos, blockState("scorchedstone_2"));
            } else if (state.is(BlockTags.DIRT)) {
                double roll = randomUnit(3L, target.subLevelId(), pos.asLong());
                if (roll < 0.3) set(target, pos, blockState("scorchedland"));
                else if (roll < 0.79) set(target, pos, blockState("scorchedland_2"));
                else set(target, pos, Blocks.PACKED_MUD.defaultBlockState());
            } else if (state.is(BlockTags.LOGS)) {
                set(target, pos, blockState("scorchedwood"));
            } else if (state.is(BlockTags.SAND)) {
                set(target, pos, randomUnit(4L, target.subLevelId(), pos.asLong()) < 0.5
                        ? Blocks.PRISMARINE.defaultBlockState()
                        : Blocks.SANDSTONE.defaultBlockState());
            }
        }

        private void queueWave(int radius) {
            activeWaveRadius = radius;
            activeWaveSliceTicks = 0;
            completedWaveBlasts = 0;
            double ring = radius * 5.0;
            for (int index = 0; index < 60; index++) {
                double angle = index * Math.PI * 2.0 / 60.0;
                Vec3 pos = new Vec3(
                        center.getX() + Math.cos(angle) * ring,
                        center.getY() + 5 + (int) Math.floor(
                                randomUnit(5L + radius, null, index) * 6.0),
                        center.getZ() + Math.sin(angle) * ring);
                queueWaveBlast(pos, radius, index);
            }
            Vec3 middle = new Vec3(center.getX(), center.getY() + radius, center.getZ());
            queueWaveBlast(middle, radius, 60);
        }

        private void queueWaveBlast(Vec3 worldPosition, int radius, int index) {
            long seed = mixedSeed(6L + radius, null, index);
            for (MianbaoDeferredDetonation.TerrainTarget target
                    : MianbaoDeferredDetonation.terrainTargets(
                    level, worldPosition, WAVE_BLAST_TARGET_RADIUS, true)) {
                waveBlasts.add(new MianbaoDeferredDetonation.SilentExplosionWork(
                        target.level(), target.center(), 10.0F,
                        seed ^ (target.subLevelId() == null
                                ? 0L : target.subLevelId().getLeastSignificantBits())));
            }
        }

        private void processWaveBlasts() {
            int budget = visibleWaveBlastBudget(
                    waveBlasts.size(), activeWaveSliceTicks);
            int processed = 0;
            while (!waveBlasts.isEmpty() && processed < budget) {
                if (!waveBlasts.peekFirst().tick(dirty)) return;
                waveBlasts.removeFirst();
                completedWaveBlasts++;
                processed++;
            }
            if (activeWaveSliceTicks < WAVE_INTERVAL_TICKS) {
                activeWaveSliceTicks++;
            }
        }

        private boolean waveChunksReady(int radius) {
            double extent = radius * 5.0 + 12.0;
            int minX = ((int) Math.floor(center.getX() - extent)) >> 4;
            int maxX = ((int) Math.floor(center.getX() + extent)) >> 4;
            int minZ = ((int) Math.floor(center.getZ() - extent)) >> 4;
            int maxZ = ((int) Math.floor(center.getZ() + extent)) >> 4;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (level.getChunkSource().getChunkNow(x, z) == null) return false;
                }
            }
            return true;
        }

        private boolean loaded(BlockPos pos) {
            return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
        }

        private boolean loaded(MianbaoDeferredDetonation.TerrainTarget target,
                               BlockPos pos) {
            return target.level().getChunkSource().getChunkNow(
                    pos.getX() >> 4, pos.getZ() >> 4) != null;
        }

        private void set(BlockPos pos, BlockState state) {
            if (state == null) return;
            if (level.setBlock(pos, state, Block.UPDATE_CLIENTS)) dirty.add(new ChunkPos(pos));
        }

        private void set(MianbaoDeferredDetonation.TerrainTarget target,
                         BlockPos pos, BlockState state) {
            if (state == null) return;
            if (target.level().setBlock(pos, state, Block.UPDATE_CLIENTS)) {
                dirty.add(new ChunkPos(pos));
                if (target.subLevelId() != null
                        && loggedDamagedSubLevels.add(target.subLevelId())) {
                    StabilizedTurret.LOGGER.info(
                            "Nuclear terrain work began damaging Sable sublevel missile={} subLevel={} localPos={}",
                            missileId, target.subLevelId(), pos);
                }
            }
        }

        private static long countSubLevels(
                List<MianbaoDeferredDetonation.TerrainTarget> targets
        ) {
            return targets.stream().filter(target -> target.subLevelId() != null).count();
        }

        private double randomUnit(long salt, UUID subLevelId, long value) {
            return (mixedSeed(salt, subLevelId, value) >>> 11)
                    * 0x1.0p-53;
        }

        private long mixedSeed(long salt, UUID subLevelId, long value) {
            long seed = missileId.getMostSignificantBits()
                    ^ Long.rotateLeft(missileId.getLeastSignificantBits(), 23)
                    ^ Long.rotateLeft(value, 11) ^ salt * 0x9E3779B97F4A7C15L;
            if (subLevelId != null) {
                seed ^= subLevelId.getMostSignificantBits()
                        ^ Long.rotateLeft(subLevelId.getLeastSignificantBits(), 37);
            }
            seed ^= seed >>> 30;
            seed *= 0xBF58476D1CE4E5B9L;
            seed ^= seed >>> 27;
            seed *= 0x94D049BB133111EBL;
            return seed ^ seed >>> 31;
        }

    }

    private static BlockState blockState(String path) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(
                MianbaoMissileCompat.MOD_ID, path));
        return block == Blocks.AIR ? Blocks.STONE.defaultBlockState() : block.defaultBlockState();
    }

    private static List<ChunkPos> plannedChunks(BlockPos center, int radius) {
        int minX = (center.getX() - radius - 1) >> 4;
        int maxX = (center.getX() + radius + 1) >> 4;
        int minZ = (center.getZ() - radius - 1) >> 4;
        int maxZ = (center.getZ() + radius + 1) >> 4;
        ChunkPos centerChunk = new ChunkPos(center);
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) chunks.add(new ChunkPos(x, z));
        }
        chunks.sort(Comparator.comparingInt(chunk -> Math.max(
                Math.abs(chunk.x - centerChunk.x),
                Math.abs(chunk.z - centerChunk.z))));
        return List.copyOf(chunks);
    }

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath("minecraft", path));
    }

    static int visibleWaveBlastBudget(int remainingBlasts, int elapsedTicks) {
        if (remainingBlasts <= 0) return 0;
        int remainingTicks = Math.max(
                1, WAVE_INTERVAL_TICKS - Math.max(0, elapsedTicks));
        return (remainingBlasts + remainingTicks - 1) / remainingTicks;
    }

    /**
     * Nuclear visuals and entity damage are emitted by the caller. Persisting
     * only the unfinished terrain job lets a restart safely resume it without
     * replaying either of those one-shot effects. An older world simply has no
     * entry under this new SavedData key.
     */
    private static void ensureLoaded(ServerLevel level) {
        if (!LOADED_LEVELS.add(level)) return;
        for (StoredWork stored : data(level).entries) {
            if (ACTIVE.stream().noneMatch(work -> work.level == level
                    && work.missileId.equals(stored.id))) {
                ACTIVE.add(new Work(level, stored));
            }
        }
    }

    private static PendingData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PendingData::new, PendingData::load), DATA_NAME);
    }

    private record StoredWork(
            UUID id, BlockPos center,
            int nextCraterOffset, int craterTarget,
            int scorchX, int scorchY, int scorchZ, int scorchTarget,
            int waveRadius, int activeWaveRadius, long nextWaveAt,
            int activeWaveSliceTicks, int completedWaveBlasts,
            boolean spreadVisibleWaves,
            boolean craterFinished, boolean scorchFinished
    ) {
        static StoredWork initial(UUID id, BlockPos center,
                                  boolean spreadVisibleWaves) {
            return new StoredWork(id, center, 0, 0,
                    -SCORCH_RADIUS, -SCORCH_RADIUS, -SCORCH_RADIUS, 0,
                    1, 0, Long.MIN_VALUE, 0, 0, spreadVisibleWaves,
                    false, false);
        }

        static StoredWork from(Work work) {
            return new StoredWork(work.missileId, work.center,
                    work.nextCraterOffset, work.craterTarget,
                    work.scorchX, work.scorchY, work.scorchZ, work.scorchTarget,
                    work.waveRadius, work.activeWaveRadius, work.nextWaveAt,
                    work.activeWaveSliceTicks, work.completedWaveBlasts,
                    work.spreadVisibleWaves,
                    work.craterFinished, work.scorchFinished);
        }
    }

    private static final class PendingData extends SavedData {
        private final List<StoredWork> entries = new ArrayList<>();

        private void add(UUID id, BlockPos center, boolean spreadVisibleWaves) {
            if (entries.stream().anyMatch(entry -> entry.id.equals(id))) return;
            entries.add(StoredWork.initial(
                    id, center.immutable(), spreadVisibleWaves));
            setDirty();
        }

        private void update(Work work) {
            StoredWork replacement = StoredWork.from(work);
            for (int index = 0; index < entries.size(); index++) {
                if (!entries.get(index).id.equals(work.missileId)) continue;
                if (!entries.get(index).equals(replacement)) {
                    entries.set(index, replacement);
                    setDirty();
                }
                return;
            }
            entries.add(replacement);
            setDirty();
        }

        private void remove(UUID id) {
            if (entries.removeIf(entry -> entry.id.equals(id))) setDirty();
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            for (StoredWork entry : entries) {
                CompoundTag item = new CompoundTag();
                item.putUUID("Id", entry.id);
                item.putInt("X", entry.center.getX());
                item.putInt("Y", entry.center.getY());
                item.putInt("Z", entry.center.getZ());
                item.putInt("NextCraterOffset", entry.nextCraterOffset);
                item.putInt("CraterTarget", entry.craterTarget);
                item.putInt("ScorchX", entry.scorchX);
                item.putInt("ScorchY", entry.scorchY);
                item.putInt("ScorchZ", entry.scorchZ);
                item.putInt("ScorchTarget", entry.scorchTarget);
                item.putInt("WaveRadius", entry.waveRadius);
                item.putInt("ActiveWaveRadius", entry.activeWaveRadius);
                item.putLong("NextWaveAt", entry.nextWaveAt);
                item.putInt("ActiveWaveSliceTicks", entry.activeWaveSliceTicks);
                item.putInt("CompletedWaveBlasts", entry.completedWaveBlasts);
                item.putBoolean("SpreadVisibleWaves", entry.spreadVisibleWaves);
                item.putBoolean("CraterFinished", entry.craterFinished);
                item.putBoolean("ScorchFinished", entry.scorchFinished);
                list.add(item);
            }
            tag.put("Pending", list);
            return tag;
        }

        private static PendingData load(CompoundTag tag,
                                        HolderLookup.Provider registries) {
            PendingData data = new PendingData();
            ListTag list = tag.getList("Pending", CompoundTag.TAG_COMPOUND);
            for (int index = 0; index < list.size(); index++) {
                CompoundTag item = list.getCompound(index);
                if (!item.hasUUID("Id")) continue;
                data.entries.add(new StoredWork(item.getUUID("Id"), new BlockPos(
                        item.getInt("X"), item.getInt("Y"), item.getInt("Z")),
                        item.getInt("NextCraterOffset"), item.getInt("CraterTarget"),
                        item.contains("ScorchX") ? item.getInt("ScorchX") : -SCORCH_RADIUS,
                        item.contains("ScorchY") ? item.getInt("ScorchY") : -SCORCH_RADIUS,
                        item.contains("ScorchZ") ? item.getInt("ScorchZ") : -SCORCH_RADIUS,
                        item.getInt("ScorchTarget"),
                        item.contains("WaveRadius") ? item.getInt("WaveRadius") : 1,
                        item.getInt("ActiveWaveRadius"), item.getLong("NextWaveAt"),
                        item.getInt("ActiveWaveSliceTicks"),
                        item.getInt("CompletedWaveBlasts"),
                        item.getBoolean("SpreadVisibleWaves"),
                        item.getBoolean("CraterFinished"),
                        item.getBoolean("ScorchFinished")));
            }
            return data;
        }
    }

    private MianbaoNuclearDetonation() {}
}
