package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.CruiseMissileGuidance;
import com.hooya.stabilizedturret.network.MianbaoDetonationVisualPayload;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Separates a remote Mianbao impact from its terrain mutation. The exact
 * audiovisual profile is sent at impact time; terrain work runs without a
 * per-tick block-count or CPU-time budget once the required chunks are ready.
 */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoDeferredDetonation {
    public static final byte PROFILE_BOMB = 0;
    public static final byte PROFILE_GROUND = 1;
    public static final byte PROFILE_LITTLE_BOMB = 2;
    public static final byte PROFILE_NUCLEAR = 3;
    public static final byte PROFILE_ROCKET = 4;
    public static final byte PROFILE_HEAVY_ROCKET = 5;
    public static final byte PROFILE_INCENDIARY_ROCKET = 6;

    private static final ExplosionDamageCalculator BLOCK_ONLY =
            new ExplosionDamageCalculator() {
                @Override
                public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
                    return false;
                }

                @Override
                public float getKnockbackMultiplier(Entity entity) {
                    return 0.0F;
                }
            };
    private static final List<Work> ACTIVE = new ArrayList<>();
    private static final List<DetachedExplosionWork> DETACHED_EXPLOSIONS =
            new ArrayList<>();
    private static final Set<ServerLevel> LOADED_LEVELS =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private static final String DATA_NAME = "create_fire_control_deferred_mianbao";
    private static final String DETACHED_DATA_NAME =
            "create_fire_control_detached_explosions";
    private static final TagKey<Block> MIANBAO_NO_BLOCK = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(
                    MianbaoMissileCompat.MOD_ID, "no_block"));
    /** Global, not per explosion: prevents a simultaneous impact ticket burst. */
    private static final int DETONATION_TICKETS_PER_TICK = 2;
    private static final RocketWorkBudget ROCKET_WORK = new RocketWorkBudget();
    private static final int MAX_PENDING_ROCKET_DETONATIONS = 64;
    private static final long ROCKET_MERGE_WINDOW_TICKS = 2L;
    private static final double ROCKET_MERGE_DISTANCE_SQR = 4.0 * 4.0;
    private static final TicketType<UUID> DETONATION_TICKET = TicketType.create(
            "create_fire_control_deferred_detonation", UUID::compareTo);
    private static final int[] EXPLOSION_RAYS = buildExplosionRays();

    /**
     * Returns true when the native all-in-one procedure must be cancelled.
     * Every managed impact uses the deferred terrain path; terrain work starts
     * as soon as its chunks are ready.
     */
    public static boolean intercept(Entity source, Vec3 position, byte profile) {
        if (source == null || source.level().isClientSide() || position == null) return false;
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(source);
        if (root == null) return false;
        ensureLoaded(root);
        UUID id = source.getUUID();
        long seed = root.random.nextLong();
        if (profile == PROFILE_NUCLEAR) {
            boolean nativeNuclearEffects =
                    MianbaoCruiseMissileCompat.playNativeNuclearEffects(
                            root, position);
            StabilizedTurret.LOGGER.info(
                    "Fire-control nuclear effects mode={} impact={}",
                    nativeNuclearEffects ? "mianbao-native" : "client-fallback",
                    position);
            MianbaoMissileEffects.broadcastDetonationVisual(
                    root, position, profile, seed, nativeNuclearEffects);
            CruiseMissileGuidance.beginImpactLifecycle(
                    id, root.getGameTime(),
                    MianbaoDetonationVisualPayload.visualDurationTicks(
                            profile, seed));
            applyNativeEntityDamage(root, source, position, profile);
            MianbaoNativeNuclearQueue.enqueue(root, id, position);
            source.discard();
            return true;
        }
        MianbaoMissileEffects.broadcastDetonationVisual(
                root, position, profile, seed, false);
        CruiseMissileGuidance.beginImpactLifecycle(id, root.getGameTime(),
                MianbaoDetonationVisualPayload.visualDurationTicks(profile, seed));
        // The native procedure is cancelled below so terrain can be processed
        // incrementally. Its direct radial entity damage is not terrain work
        // and must still happen once at impact; otherwise every display-fired
        // cruise/VLS warhead becomes harmless to mobs and players.
        applyNativeEntityDamage(root, source, position, profile);
        if (ACTIVE.stream().noneMatch(work -> work.id.equals(id))) {
            Work work = new Work(root, id, position, profile, root.getGameTime());
            ACTIVE.add(work);
            data(root).add(work);
        }
        source.discard();
        return true;
    }

    /**
     * Immediately sends the lightweight impact feedback and entity damage,
     * while terrain mutation waits for the required chunks to become ready.
     */
    public static boolean interceptRocket(
            Entity source, Vec3 position, byte profile
    ) {
        if (source == null || source.level().isClientSide() || position == null
                || !isRocketProfile(profile)) {
            return false;
        }
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(source);
        if (root == null) return false;
        if (source.getPersistentData().getBoolean("CreateFireControlRocketDetonated")) return true;
        source.getPersistentData().putBoolean(
                "CreateFireControlRocketDetonated", true);
        detonateVirtualRocket(root, source.getUUID(), position, profile);
        source.discard();
        return true;
    }

    /** Authoritative impact for an entity-free rocket; feedback never waits for terrain tickets. */
    public static void detonateVirtualRocket(ServerLevel root, UUID id, Vec3 position, byte profile) {
        if (root == null || position == null || !isRocketProfile(profile)) return;
        ensureLoaded(root);
        long now = root.getGameTime();
        MianbaoMissileEffects.broadcastVirtualRocketExplosion(root, position, explosionStrength(profile));
        applyNativeEntityDamage(root, null, position, profile);

        boolean overlapsQueuedCrater = ACTIVE.stream().anyMatch(work ->
                work.level == root && work.isRocketWork()
                        && Math.abs(now - work.requestedAt)
                        <= ROCKET_MERGE_WINDOW_TICKS
                        && work.position.distanceToSqr(position)
                        <= ROCKET_MERGE_DISTANCE_SQR);
        long pendingRockets = ACTIVE.stream().filter(Work::isRocketWork).count();
        if (!overlapsQueuedCrater
                && pendingRockets < MAX_PENDING_ROCKET_DETONATIONS) {
            Work work = new Work(root, id, position, profile, now);
            ACTIVE.add(work);
            data(root).add(work);
        }
    }

    /** Restores Mianbao's explicit, unoccluded radial damage without coupling
     * it to the paced block-destruction task. The same world-space blast is
     * projected into intersecting Sable sublevels so occupants are included. */
    private static void applyNativeEntityDamage(
            ServerLevel root, Entity source, Vec3 position, byte profile
    ) {
        double radius = switch (profile) {
            case PROFILE_BOMB -> 20.5;
            case PROFILE_GROUND -> 17.5;
            case PROFILE_LITTLE_BOMB -> 10.5;
            case PROFILE_NUCLEAR -> 100.0;
            case PROFILE_ROCKET -> 8.5;
            case PROFILE_HEAVY_ROCKET, PROFILE_INCENDIARY_ROCKET -> 10.0;
            default -> 0.0;
        };
        float damage = switch (profile) {
            case PROFILE_BOMB -> 100.0F;
            case PROFILE_GROUND -> 60.0F;
            case PROFILE_LITTLE_BOMB -> 30.0F;
            case PROFILE_NUCLEAR -> 1000.0F;
            case PROFILE_ROCKET, PROFILE_HEAVY_ROCKET -> 40.0F;
            case PROFILE_INCENDIARY_ROCKET -> 25.0F;
            default -> 0.0F;
        };
        if (radius <= 0.0 || damage <= 0.0F) return;
        for (TerrainTarget target : terrainTargets(root, position, radius, true)) {
            ServerLevel damageLevel = target.level();
            DamageSource damageSource = new DamageSource(damageLevel.registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(DamageTypes.EXPLOSION));
            AABB area = new AABB(target.center(), target.center()).inflate(radius);
            for (Entity victim : damageLevel.getEntities(
                    source != null && source.level() == damageLevel ? source : null,
                    area,
                    victim -> victim != source && victim.isAlive()
                            && !victim.isRemoved())) {
                victim.hurt(damageSource, damage);
                if (profile == PROFILE_INCENDIARY_ROCKET) {
                    victim.igniteForTicks(10);
                }
            }
        }
    }

    private static boolean isRocketProfile(byte profile) {
        return profile == PROFILE_ROCKET || profile == PROFILE_HEAVY_ROCKET
                || profile == PROFILE_INCENDIARY_ROCKET;
    }

    private static float explosionStrength(byte profile) {
        return switch (profile) {
            case PROFILE_ROCKET, PROFILE_INCENDIARY_ROCKET -> 3.0F;
            case PROFILE_HEAVY_ROCKET -> 5.0F;
            case PROFILE_BOMB -> 20.0F;
            case PROFILE_GROUND -> 18.0F;
            case PROFILE_LITTLE_BOMB -> 5.0F;
            default -> 0.0F;
        };
    }

    public static boolean isControlledClusterWarhead(Entity entity) {
        return entity != null && entity.getPersistentData()
                .getBoolean("CreateFireControlCruiseClusterWarhead");
    }

    /** Applies normal explosion damage and block destruction without spawning block loot. */
    public static void explodeWithoutDrops(Entity source, Vec3 position, float strength) {
        if (source == null || position == null || strength <= 0.0F) return;
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(source);
        if (root == null || !MianbaoMissileCompat.areRootChunksLoaded(
                source, position, strength)) return;
        root.explode(source, position.x, position.y, position.z, strength,
                net.minecraft.world.level.Level.ExplosionInteraction.NONE);
        long seed = root.random.nextLong();
        DetachedExplosionWork work = new DetachedExplosionWork(
                root, source.getUUID(), position, strength, seed);
        DETACHED_EXPLOSIONS.add(work);
        detachedData(root).add(work);
    }

    public static boolean isImpactLoadedByPlayer(ServerLevel level, Vec3 position) {
        return isImpactAreaLoadedByPlayer(level, position, 0.0);
    }

    /** Manual detonation tickets and streamed render chunks do not count here;
     * only normal player chunk tracking switches every terrain phase to fast mode. */
    public static boolean isImpactAreaLoadedByPlayer(
            ServerLevel level, Vec3 position, double radius
    ) {
        if (level == null || position == null) return false;
        int minX = ((int) Math.floor(position.x - radius)) >> 4;
        int maxX = ((int) Math.floor(position.x + radius)) >> 4;
        int minZ = ((int) Math.floor(position.z - radius)) >> 4;
        int maxZ = ((int) Math.floor(position.z + radius)) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!level.getChunkSource().chunkMap
                        .getPlayers(new ChunkPos(x, z), false).isEmpty()) return true;
            }
        }
        return false;
    }

    private static boolean chunksReady(ServerLevel level, Vec3 position, double radius) {
        int minX = ((int) Math.floor(position.x - radius - 1.0)) >> 4;
        int maxX = ((int) Math.floor(position.x + radius + 1.0)) >> 4;
        int minZ = ((int) Math.floor(position.z - radius - 1.0)) >> 4;
        int maxZ = ((int) Math.floor(position.z + radius + 1.0)) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) return false;
            }
        }
        return true;
    }

    private static double readinessRadius(byte profile) {
        return switch (profile) {
            case PROFILE_BOMB -> 21.0;
            case PROFILE_GROUND -> 19.0;
            case PROFILE_LITTLE_BOMB -> 7.0;
            case PROFILE_NUCLEAR -> 65.0;
            case PROFILE_ROCKET -> 8.0;
            case PROFILE_HEAVY_ROCKET -> 12.0;
            case PROFILE_INCENDIARY_ROCKET -> 15.0;
            default -> 8.0;
        };
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ROCKET_WORK.reset();
        for (ServerLevel level : event.getServer().getAllLevels()) ensureLoaded(level);
        int ticketBudget = DETONATION_TICKETS_PER_TICK;
        Iterator<Work> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            Work work = iterator.next();
            if (work.level.getServer() != event.getServer()) continue;
            if (ticketBudget > 0 && work.issueNextTicket()) ticketBudget--;
            // Always let the first audiovisual frame enter the client queue
            // before the deferred terrain task starts.
            if (work.level.getGameTime() <= work.requestedAt) continue;
            if (!chunksReady(work.level, work.position,
                    readinessRadius(work.profile))) continue;
            if (!work.tick()) continue;
            work.releaseTickets();
            data(work.level).remove(work.id);
            iterator.remove();
        }
        Iterator<DetachedExplosionWork> detached = DETACHED_EXPLOSIONS.iterator();
        while (detached.hasNext()) {
            DetachedExplosionWork work = detached.next();
            if (work.level.getServer() != event.getServer()) continue;
            if (work.tick()) {
                detachedData(work.level).remove(work.id);
                detached.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent event) {
        ACTIVE.forEach(Work::releaseTickets);
        ACTIVE.clear();
        DETACHED_EXPLOSIONS.forEach(work -> detachedData(work.level).update(work));
        DETACHED_EXPLOSIONS.clear();
        LOADED_LEVELS.clear();
    }

    private static boolean transformScorchedBlock(ServerLevel level, BlockPos pos,
                                                   int x, int y, int z,
                                                   RandomSource random) {
        Block scorchedWood = block("scorchedwood");
        Block scorchedLand = block("scorchedland");
        Block scorchedLandTwo = block("scorchedland_2");
        Block scorchedStone = block("scorchedstone");
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        BlockState replacement = null;
        if (state.is(BlockTags.LOGS) && scorchedWood != Blocks.AIR) {
            replacement = scorchedWood.defaultBlockState();
        } else if (state.is(BlockTags.DIRT)) {
            if (random.nextDouble() < 0.4 && scorchedLand != Blocks.AIR) {
                replacement = scorchedLand.defaultBlockState();
            } else if (random.nextDouble() < 0.8 && scorchedLandTwo != Blocks.AIR) {
                replacement = scorchedLandTwo.defaultBlockState();
            } else {
                replacement = Blocks.PACKED_MUD.defaultBlockState();
            }
        } else if (x * x + y * y + z * z <= 81
                && state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            replacement = random.nextDouble() < 0.3
                    ? Blocks.COAL_BLOCK.defaultBlockState()
                    : scorchedStone.defaultBlockState();
        } else if (state.is(Blocks.GRAVEL)) {
            replacement = Blocks.MUD.defaultBlockState();
        } else if (state.is(Blocks.SAND)) {
            replacement = random.nextBoolean()
                    ? Blocks.SANDSTONE.defaultBlockState()
                    : Blocks.PRISMARINE.defaultBlockState();
        }
        return replacement != null
                && level.setBlock(pos, replacement, Block.UPDATE_CLIENTS);
    }

    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(
                MianbaoMissileCompat.MOD_ID, path));
    }

    /**
     * Maps one world-space blast into the root world and every intersecting
     * Sable plot. The plot coordinates are where Sable actually stores its
     * blocks, so a custom paced explosion must mutate those coordinates
     * explicitly instead of relying on Sable's vanilla Explosion mixin.
     */
    static List<TerrainTarget> terrainTargets(
            ServerLevel root, Vec3 worldCenter, double radius,
            boolean includeRoot
    ) {
        List<TerrainTarget> targets = new ArrayList<>();
        if (includeRoot) targets.add(new TerrainTarget(root, worldCenter, null));
        AABB worldBounds = new AABB(worldCenter, worldCenter).inflate(radius);
        for (SubLevel subLevel : SubLevelContainer.getContainer(root)
                .getAllSubLevels()) {
            if (!(subLevel instanceof ServerSubLevel serverSubLevel)
                    || subLevel.isRemoved()) continue;
            AABB subLevelBounds = StabilizerControllerBlockEntity
                    .subLevelWorldBounds(subLevel);
            if (!subLevelBounds.intersects(worldBounds)) continue;
            Vec3 localCenter = subLevel.logicalPose()
                    .transformPositionInverse(worldCenter);
            targets.add(new TerrainTarget(
                    serverSubLevel.getLevel(), localCenter,
                    subLevel.getUniqueId()));
        }
        return List.copyOf(targets);
    }

    static record TerrainTarget(ServerLevel level, Vec3 center,
                                UUID subLevelId) {
    }

    private static final class Work {
        final ServerLevel level;
        final UUID id;
        final Vec3 position;
        final byte profile;
        final long requestedAt;
        final RandomSource random;
        final List<TerrainTarget> targets;
        final List<SilentExplosionWork> explosions;
        final List<ChunkPos> ticketPlan;
        final Set<ChunkPos> ticketedChunks = new HashSet<>();
        int nextTicket;
        int phase;
        int explosionTarget;
        int scorchTarget;
        int coreTarget;
        int fireTarget;
        int scorchX = -13;
        int scorchY = -11;
        int scorchZ = -13;
        int coreX = -3;
        int coreY = -3;
        int coreZ = -3;
        int fireX = -14;
        int fireY = -14;
        int fireZ = -14;

        Work(ServerLevel level, UUID id, Vec3 position,
             byte profile, long requestedAt) {
            this.level = level;
            this.id = id;
            this.position = position;
            this.profile = profile;
            this.requestedAt = requestedAt;
            long workSeed = id.getMostSignificantBits()
                    ^ id.getLeastSignificantBits() ^ requestedAt;
            this.random = RandomSource.create(workSeed);
            float strength = explosionStrength(profile);
            this.targets = terrainTargets(
                    level, position, readinessRadius(profile), true);
            this.explosions = strength > 0.0F
                    ? targets.stream().map(target -> new SilentExplosionWork(
                    target.level(), target.center(), strength,
                    workSeed ^ (target.subLevelId() == null
                            ? 0L : target.subLevelId().getLeastSignificantBits())))
                    .toList()
                    : List.of();
            this.ticketPlan = plannedChunks(position, readinessRadius(profile));
        }

        boolean issueNextTicket() {
            if (isRocketWork()) {
                // Loaded impacts can finish from resident chunks. Adding a
                // ticket anyway can expand chunk status/dependency work.
                // Rescan each tick: a previously resident chunk may unload
                // while this crater is waiting for the shared work budget.
                for (ChunkPos chunk : ticketPlan) {
                    if (ticketedChunks.contains(chunk)
                            || level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null) continue;
                    ticketedChunks.add(chunk);
                    level.getChunkSource().addRegionTicket(
                            DETONATION_TICKET, chunk, 0, id, true);
                    return true;
                }
                return false;
            }
            while (nextTicket < ticketPlan.size()) {
                ChunkPos chunk = ticketPlan.get(nextTicket++);
                if (ticketedChunks.add(chunk)) {
                    level.getChunkSource().addRegionTicket(
                            DETONATION_TICKET, chunk, 0, id, true);
                    return true;
                }
            }
            return false;
        }

        void releaseTickets() {
            for (ChunkPos chunk : ticketedChunks) {
                level.getChunkSource().removeRegionTicket(
                        DETONATION_TICKET, chunk, 0, id, true);
            }
            ticketedChunks.clear();
        }

        boolean tick() {
            if (profile == PROFILE_NUCLEAR) {
                MianbaoNuclearDetonation.detonate(level, id, position);
                return true;
            }
            if (explosions.isEmpty()) {
                CruiseMissileGuidance.completeImpactDestruction(id);
                return true;
            }
            boolean playerLoaded = isImpactAreaLoadedByPlayer(
                    level, position, readinessRadius(profile));
            Set<ChunkPos> dirty = new HashSet<>();

            if (phase == 0) {
                while (explosionTarget < explosions.size()) {
                    if (!explosions.get(explosionTarget)
                            .tick(dirty, isRocketWork() ? ROCKET_WORK : null)) break;
                    explosionTarget++;
                }
                if (explosionTarget >= explosions.size()) phase = 1;
            }
            if (phase == 1 && (profile == PROFILE_LITTLE_BOMB
                    || profile == PROFILE_ROCKET
                    || profile == PROFILE_HEAVY_ROCKET)) {
                phase = 3;
            } else if (phase == 1 && profile == PROFILE_INCENDIARY_ROCKET) {
                phase = 4;
            }
            if (phase == 1) processScorch(dirty);
            if (phase == 2) processGroundCore(dirty);
            if (phase == 4) processIncendiary(dirty);
            if (!dirty.isEmpty()) {
                CruiseMissileGuidance.markImpactChunksDirty(id, dirty);
            }
            if (phase != 3) return false;
            CruiseMissileGuidance.completeImpactDestruction(id);
            StabilizedTurret.LOGGER.info(
                    "Completed silent Mianbao terrain detonation profile={} id={} at {} playerLoaded={}",
                    profile, id, position, playerLoaded);
            return true;
        }

        boolean isRocketWork() {
            return isRocketProfile(profile);
        }

        private void processScorch(Set<ChunkPos> dirty) {
            while (scorchTarget < targets.size()) {
                if (scorchY > 11) {
                    scorchTarget++;
                    scorchX = -13;
                    scorchY = -11;
                    scorchZ = -13;
                    continue;
                }
                int x = scorchX;
                int y = scorchY;
                int z = scorchZ;
                advanceScorch();
                double distance = (double) (x * x) / 169.0
                        + (double) (y * y) / 121.0
                        + (double) (z * z) / 169.0;
                if (distance > 1.0) continue;
                TerrainTarget target = targets.get(scorchTarget);
                BlockPos pos = BlockPos.containing(
                        target.center().x + x,
                        target.center().y + y,
                        target.center().z + z);
                if (transformScorchedBlock(
                        target.level(), pos, x, y, z, random)) {
                    dirty.add(new ChunkPos(pos));
                }
            }
            if (scorchTarget >= targets.size()) {
                phase = profile == PROFILE_GROUND ? 2 : 3;
            }
        }

        private void advanceScorch() {
            if (++scorchZ <= 13) return;
            scorchZ = -13;
            if (++scorchX <= 13) return;
            scorchX = -13;
            scorchY++;
        }

        private void processGroundCore(Set<ChunkPos> dirty) {
            while (coreTarget < targets.size()) {
                if (coreY > 3) {
                    coreTarget++;
                    coreX = -3;
                    coreY = -3;
                    coreZ = -3;
                    continue;
                }
                int x = coreX;
                int y = coreY;
                int z = coreZ;
                advanceCore();
                if (x * x + y * y + z * z > 9) continue;
                TerrainTarget target = targets.get(coreTarget);
                BlockPos pos = BlockPos.containing(
                        target.center().x + x,
                        target.center().y + y,
                        target.center().z + z);
                BlockState state = target.level().getBlockState(pos);
                if (!state.isAir()
                        && state.getDestroySpeed(target.level(), pos) > 0.0F
                        && !state.is(MIANBAO_NO_BLOCK)
                        && !(state.getBlock() instanceof LiquidBlock)
                        && !state.getFluidState().isSource()
                        && target.level().setBlock(
                        pos, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS)) {
                    dirty.add(new ChunkPos(pos));
                }
            }
            if (coreTarget >= targets.size()) phase = 3;
        }

        private void advanceCore() {
            if (++coreZ <= 3) return;
            coreZ = -3;
            if (++coreX <= 3) return;
            coreX = -3;
            coreY++;
        }

        private void processIncendiary(Set<ChunkPos> dirty) {
            while (fireTarget < targets.size()) {
                if (!ROCKET_WORK.block()) return;
                if (fireY > 14) {
                    fireTarget++;
                    fireX = -14;
                    fireY = -14;
                    fireZ = -14;
                    continue;
                }
                int x = fireX;
                int y = fireY;
                int z = fireZ;
                advanceFire();
                if (x * x + y * y + z * z > 14 * 14) continue;
                TerrainTarget target = targets.get(fireTarget);
                BlockPos pos = BlockPos.containing(
                        target.center().x + x,
                        target.center().y + y,
                        target.center().z + z);
                BlockState fire = Blocks.FIRE.defaultBlockState();
                if (target.level().getBlockState(pos).isAir()
                        && fire.canSurvive(target.level(), pos)
                        && target.level().setBlock(
                        pos, fire, Block.UPDATE_CLIENTS)) {
                    dirty.add(new ChunkPos(pos));
                }
            }
            if (fireTarget >= targets.size()) phase = 3;
        }

        private void advanceFire() {
            if (++fireZ <= 14) return;
            fireZ = -14;
            if (++fireX <= 14) return;
            fireX = -14;
            fireY++;
        }
    }

    /** One-shot entity damage stays immediate; only the no-loot block pass is paced. */
    private static final class DetachedExplosionWork {
        final ServerLevel level;
        final UUID id;
        final Vec3 position;
        final float strength;
        final long seed;
        final List<SilentExplosionWork> explosions;
        int target;

        DetachedExplosionWork(ServerLevel level, UUID id, Vec3 position,
                              float strength, long seed) {
            this.level = level;
            this.id = id;
            this.position = position;
            this.strength = strength;
            this.seed = seed;
            this.explosions = terrainTargets(
                    level, position, strength * 2.0 + 1.0, true).stream()
                    .map(terrain -> new SilentExplosionWork(
                            terrain.level(), terrain.center(), strength,
                            seed ^ (terrain.subLevelId() == null
                                    ? 0L : terrain.subLevelId().getLeastSignificantBits())))
                    .toList();
        }

        DetachedExplosionWork(ServerLevel level, StoredDetached stored) {
            this(level, stored.id, stored.position, stored.strength, stored.seed);
        }

        boolean tick() {
            Set<ChunkPos> dirty = new HashSet<>();
            while (target < explosions.size()) {
                if (!explosions.get(target).tick(dirty)) break;
                target++;
            }
            if (!dirty.isEmpty()) CruiseMissileGuidance.markImpactChunksDirty(id, dirty);
            return target >= explosions.size();
        }
    }

    /** Incrementally calculates and applies a vanilla-shaped block-only blast. */
    static final class SilentExplosionWork {
        private final ServerLevel level;
        private final Vec3 center;
        private final float strength;
        private final RandomSource random;
        private final Explosion context;
        private final Set<BlockPos> affected = new LinkedHashSet<>();
        private List<BlockPos> ordered;
        private int nextRay;
        private int nextBlock;

        SilentExplosionWork(ServerLevel level, Vec3 center,
                            float strength, long seed) {
            this.level = level;
            this.center = center;
            this.strength = strength;
            this.random = RandomSource.create(seed);
            Explosion.BlockInteraction interaction = level.getGameRules().getBoolean(
                    GameRules.RULE_BLOCK_EXPLOSION_DROP_DECAY)
                    ? Explosion.BlockInteraction.DESTROY_WITH_DECAY
                    : Explosion.BlockInteraction.DESTROY;
            this.context = new Explosion(level, null, null, BLOCK_ONLY,
                    center.x, center.y, center.z, strength, false,
                    interaction,
                    ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER,
                    SoundEvents.GENERIC_EXPLODE);
        }

        boolean tick(Set<ChunkPos> dirty) {
            return tick(dirty, null);
        }

        boolean tick(Set<ChunkPos> dirty, RocketWorkBudget budget) {
            while (nextRay < EXPLOSION_RAYS.length) {
                if (budget != null && !budget.ray()) return false;
                traceRay(EXPLOSION_RAYS[nextRay++]);
            }
            if (ordered == null) {
                ordered = new ArrayList<>(affected);
                affected.clear();
                Util.shuffle(ordered, random);
            }
            while (nextBlock < ordered.size()) {
                if (budget != null && !budget.block()) return false;
                BlockPos pos = ordered.get(nextBlock++);
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) continue;
                // Display-controlled missiles destroy terrain without
                // generating block loot, including blocks stored in Sable
                // sublevels.
                if (level.destroyBlock(pos, false)
                        || level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS)) {
                    dirty.add(new ChunkPos(pos));
                }
            }
            return true;
        }

        private void traceRay(int packed) {
            int ix = (packed >>> 8) & 0xFF;
            int iy = (packed >>> 4) & 0xF;
            int iz = packed & 0xF;
            double dx = (double) ix / 15.0 * 2.0 - 1.0;
            double dy = (double) iy / 15.0 * 2.0 - 1.0;
            double dz = (double) iz / 15.0 * 2.0 - 1.0;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= length;
            dy /= length;
            dz /= length;
            float power = strength * (0.7F + random.nextFloat() * 0.6F);
            double x = center.x;
            double y = center.y;
            double z = center.z;
            while (power > 0.0F) {
                BlockPos pos = BlockPos.containing(x, y, z);
                if (!level.isInWorldBounds(pos)) break;
                BlockState state = level.getBlockState(pos);
                var resistance = BLOCK_ONLY.getBlockExplosionResistance(
                        context, level, pos, state, level.getFluidState(pos));
                if (resistance.isPresent()) {
                    power -= (resistance.get() + 0.3F) * 0.3F;
                }
                if (power > 0.0F && BLOCK_ONLY.shouldBlockExplode(
                        context, level, pos, state, power)) {
                    affected.add(pos.immutable());
                }
                power -= 0.22500001F;
                x += dx * 0.3F;
                y += dy * 0.3F;
                z += dz * 0.3F;
            }
        }
    }

    /** Shared by all rocket impacts, so a salvo cannot multiply the per-tick budget. */
    static final class RocketWorkBudget {
        private int rays, blocks;
        private long deadline;
        void reset() { rays = 384; blocks = 256; deadline = 0; }
        private boolean withinTime() {
            long now = System.nanoTime();
            if (deadline == 0) deadline = now + 2_000_000L;
            return now < deadline;
        }
        boolean ray() { return rays-- > 0 && withinTime(); }
        boolean block() { return blocks-- > 0 && withinTime(); }
    }

    private static int[] buildExplosionRays() {
        List<Integer> rays = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    if (x == 0 || x == 15 || y == 0 || y == 15
                            || z == 0 || z == 15) {
                        rays.add((x << 8) | (y << 4) | z);
                    }
                }
            }
        }
        return rays.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void ensureLoaded(ServerLevel level) {
        if (!LOADED_LEVELS.add(level)) return;
        PendingData saved = data(level);
        for (StoredWork stored : saved.entries) {
            if (ACTIVE.stream().noneMatch(work -> work.id.equals(stored.id))) {
                ACTIVE.add(new Work(level, stored.id, stored.position,
                        stored.profile, stored.requestedAt));
            }
        }
        for (StoredDetached stored : detachedData(level).entries) {
            if (DETACHED_EXPLOSIONS.stream().noneMatch(work ->
                    work.level == level && work.id.equals(stored.id))) {
                DETACHED_EXPLOSIONS.add(new DetachedExplosionWork(level, stored));
            }
        }
    }

    private static PendingData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PendingData::new, PendingData::load), DATA_NAME);
    }

    private static DetachedPendingData detachedData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(DetachedPendingData::new,
                        DetachedPendingData::load), DETACHED_DATA_NAME);
    }

    private record StoredDetached(
            UUID id, Vec3 position, float strength, long seed
    ) {}

    private static final class DetachedPendingData extends SavedData {
        private final List<StoredDetached> entries = new ArrayList<>();

        private void add(DetachedExplosionWork work) {
            if (entries.stream().anyMatch(entry -> entry.id.equals(work.id))) return;
            entries.add(new StoredDetached(work.id, work.position,
                    work.strength, work.seed));
            setDirty();
        }

        private void update(DetachedExplosionWork work) {
            remove(work.id);
            entries.add(new StoredDetached(work.id, work.position,
                    work.strength, work.seed));
            setDirty();
        }

        private void remove(UUID id) {
            if (entries.removeIf(entry -> entry.id.equals(id))) setDirty();
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            for (StoredDetached entry : entries) {
                CompoundTag item = new CompoundTag();
                item.putUUID("Id", entry.id);
                item.putDouble("X", entry.position.x);
                item.putDouble("Y", entry.position.y);
                item.putDouble("Z", entry.position.z);
                item.putFloat("Strength", entry.strength);
                item.putLong("Seed", entry.seed);
                list.add(item);
            }
            tag.put("Pending", list);
            return tag;
        }

        private static DetachedPendingData load(
                CompoundTag tag, HolderLookup.Provider registries
        ) {
            DetachedPendingData data = new DetachedPendingData();
            ListTag list = tag.getList("Pending", CompoundTag.TAG_COMPOUND);
            for (int index = 0; index < list.size(); index++) {
                CompoundTag item = list.getCompound(index);
                if (!item.hasUUID("Id")) continue;
                data.entries.add(new StoredDetached(item.getUUID("Id"),
                        new Vec3(item.getDouble("X"), item.getDouble("Y"),
                                item.getDouble("Z")),
                        item.getFloat("Strength"), item.getLong("Seed")));
            }
            return data;
        }
    }

    private record StoredWork(UUID id, Vec3 position, byte profile, long requestedAt) {}

    private static final class PendingData extends SavedData {
        private final List<StoredWork> entries = new ArrayList<>();

        private void add(Work work) {
            if (entries.stream().anyMatch(entry -> entry.id.equals(work.id))) return;
            entries.add(new StoredWork(work.id, work.position,
                    work.profile, work.requestedAt));
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
                item.putDouble("X", entry.position.x);
                item.putDouble("Y", entry.position.y);
                item.putDouble("Z", entry.position.z);
                item.putByte("Profile", entry.profile);
                item.putLong("RequestedAt", entry.requestedAt);
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
                data.entries.add(new StoredWork(item.getUUID("Id"),
                        new Vec3(item.getDouble("X"), item.getDouble("Y"),
                                item.getDouble("Z")),
                        item.getByte("Profile"), item.getLong("RequestedAt")));
            }
            return data;
        }
    }

    private static Set<ChunkPos> dirtyChunks(Vec3 position, double radius) {
        Set<ChunkPos> chunks = new HashSet<>();
        int minX = ((int) Math.floor(position.x - radius - 1.0)) >> 4;
        int maxX = ((int) Math.floor(position.x + radius + 1.0)) >> 4;
        int minZ = ((int) Math.floor(position.z - radius - 1.0)) >> 4;
        int maxZ = ((int) Math.floor(position.z + radius + 1.0)) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) chunks.add(new ChunkPos(x, z));
        }
        return chunks;
    }

    private static List<ChunkPos> plannedChunks(Vec3 position, double radius) {
        ChunkPos center = new ChunkPos(BlockPos.containing(position));
        List<ChunkPos> chunks = new ArrayList<>(dirtyChunks(position, radius));
        chunks.sort(Comparator.comparingInt(chunk -> Math.max(
                Math.abs(chunk.x - center.x), Math.abs(chunk.z - center.z))));
        return List.copyOf(chunks);
    }

    private MianbaoDeferredDetonation() {}
}
