package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.CountermeasureSalvoPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps native countermeasures ticking and visible without installing more
 * than one force-ticking ticket for a chunk shared by several flares.
 */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoCountermeasureManager {
    public static final double MAX_DECOY_RELATIVE_SPEED_METERS_PER_SECOND = 30.0;
    public static final double DECOY_CHANCE = 0.35;
    private static final int REMOTE_SYNC_INTERVAL_TICKS = 4;
    private static final int ENTITY_TICKING_RADIUS = MissileChunkTickets.ENTITY_TICKING_RADIUS;

    private static final TicketType<Long> TICKET = TicketType.create(
            "create_fire_control_countermeasure", Long::compareTo);
    private static final ThreadLocal<LaunchContext> ACTIVE_LAUNCH = new ThreadLocal<>();
    private static final Map<Entity, TrackedCountermeasure> TRACKED = new IdentityHashMap<>();
    private static final Set<UUID> TRACKED_IDS = new HashSet<>();
    private static final Map<ServerLevel, List<UUID>> REMOVED = new IdentityHashMap<>();
    private static final List<PendingLaunch> PENDING_LAUNCHES = new ArrayList<>();
    private static final Map<ServerLevel, Map<Long, Integer>> CHUNK_REFERENCES =
            new IdentityHashMap<>();

    static LaunchScope expectLaunch(Set<UUID> carrierIds) {
        LaunchContext previous = ACTIVE_LAUNCH.get();
        ACTIVE_LAUNCH.set(new LaunchContext(
                carrierIds == null ? Set.of() : Set.copyOf(carrierIds)));
        return new LaunchScope(previous);
    }

    static Entity capturedLaunch() {
        LaunchContext launch = ACTIVE_LAUNCH.get();
        return launch == null ? null : launch.captured();
    }

    static void registerLaunched(Entity countermeasure, Set<UUID> carrierIds) {
        if (countermeasure == null || countermeasure.level().isClientSide()
                || !MianbaoCountermeasureCompat.isCountermeasure(countermeasure)) {
            return;
        }
        countermeasure.getPersistentData().putBoolean(
                MianbaoCountermeasureCompat.TRACKED_TAG, true);
        TrackedCountermeasure state = new TrackedCountermeasure(
                carrierIds == null ? Set.of() : Set.copyOf(carrierIds));
        synchronized (TRACKED) {
            TrackedCountermeasure replaced = TRACKED.put(countermeasure, state);
            TRACKED_IDS.add(countermeasure.getUUID());
            if (replaced != null) replaced.release();
            state.update(countermeasure);
            long now = state.level == null ? countermeasure.level().getGameTime() : state.level.getGameTime();
            state.launchedAt = now - Math.clamp((long) countermeasure.getPersistentData().getDouble("time"),
                    0L, MianbaoCountermeasureCompat.LIFETIME_TICKS);
        }
    }

    static boolean isTracked(Entity countermeasure) {
        synchronized (TRACKED) {
            return TRACKED.containsKey(countermeasure);
        }
    }

    /** Returns whether the UUID belongs to a flare explicitly launched by fire control. */
    public static boolean isTrackedId(UUID id) {
        if (id == null) return false;
        synchronized (TRACKED) {
            return TRACKED_IDS.contains(id);
        }
    }

    /**
     * Retains a launch association for procedures that enqueue entity creation
     * until the next server tick (the self-loading launcher does this on some
     * Mianbao builds). The follow-up lookup is restricted to the launcher's
     * small muzzle area; it is not a passive world-wide entity scan.
     */
    static void deferLaunch(Level level, Vec3 origin, Set<UUID> carrierIds) {
        if (level == null || level.isClientSide() || origin == null) return;
        long expiry = level.getGameTime() + 6L;
        synchronized (PENDING_LAUNCHES) {
            PENDING_LAUNCHES.add(new PendingLaunch(
                    level, origin, carrierIds == null ? Set.of() : Set.copyOf(carrierIds), expiry));
        }
    }

    static boolean hasPendingLaunch(Level level, BlockPos origin) {
        if (level == null || origin == null) return false;
        Vec3 center = Vec3.atCenterOf(origin);
        synchronized (PENDING_LAUNCHES) {
            return PENDING_LAUNCHES.stream()
                    .anyMatch(pending -> pending.level() == level
                            && pending.origin().distanceToSqr(center) <= 36.0);
        }
    }

    static List<TrackedFlare> snapshotTracked() {
        synchronized (TRACKED) {
            if (TRACKED.isEmpty()) return List.of();
            List<TrackedFlare> result = new ArrayList<>();
            for (Map.Entry<Entity, TrackedCountermeasure> entry : TRACKED.entrySet()) {
                Entity entity = entry.getKey();
                if (entity != null && entity.isAlive() && !entity.isRemoved()) {
                    result.add(new TrackedFlare(entity, entry.getValue().carrierIds));
                }
            }
            return List.copyOf(result);
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        Entity countermeasure = event.getEntity();
        LaunchContext launch = ACTIVE_LAUNCH.get();
        if (event.getLevel().isClientSide()
                || !MianbaoCountermeasureCompat.isCountermeasure(countermeasure)) {
            return;
        }
        if (event.loadedFromDisk()) {
            if (countermeasure.getPersistentData().getBoolean(MianbaoCountermeasureCompat.TRACKED_TAG)) {
                registerLaunched(countermeasure, Set.of());
            }
            return;
        }
        if (launch == null) return;
        // The active fire call captures the exact entity. Registration is
        // performed by fireOne after the native procedure returns, so this
        // event is no longer a passive global discovery mechanism.
        // Pairing can happen immediately after this event, before fireOne
        // returns. Mark now so no duplicate native client arrow is paired.
        countermeasure.getPersistentData().putBoolean(MianbaoCountermeasureCompat.TRACKED_TAG, true);
        launch.capture(countermeasure);
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        // NeoForge also posts this when an alive entity merely stops being
        // tracked as its chunk becomes hidden. Keep its fallback simulation
        // and decoy association until it really dies or reaches its lifetime.
        if (!event.getEntity().isRemoved()) return;
        synchronized (TRACKED) {
            TrackedCountermeasure state = TRACKED.remove(event.getEntity());
            if (state != null) {
                removeVisual(event.getEntity(), state);
                state.release();
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (TRACKED.isEmpty() && PENDING_LAUNCHES.isEmpty() && REMOVED.isEmpty()) return;
        resolvePendingLaunches(event.getServer().getAllLevels());
        List<TrackedFlare> activeFlares;
        synchronized (TRACKED) {
            Iterator<Map.Entry<Entity, TrackedCountermeasure>> iterator =
                    TRACKED.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Entity, TrackedCountermeasure> entry = iterator.next();
                Entity countermeasure = entry.getKey();
                TrackedCountermeasure state = entry.getValue();
                if (countermeasure.isRemoved()
                        || !countermeasure.isAlive()) {
                    removeVisual(countermeasure, state);
                    state.release();
                    iterator.remove();
                    continue;
                }
                // The native AbstractArrow tick is not guaranteed to run when
                // a remote chunk is outside the player's tracking range. The
                // shared marker lets us advance exactly once in that case.
                if (MianbaoMissileCompat.advanceIfNativeTickMissed(countermeasure, false)) {
                    // A missed whole native tick also misses arrow drag and
                    // the flare's age/drop procedure. Apply both exactly once.
                    MianbaoCountermeasureCompat.applySkippedArrowPhysics(countermeasure);
                    MianbaoCountermeasureCompat.runManagedTrail(countermeasure, false);
                }
                state.update(countermeasure);
                ServerLevel root = MianbaoMissileCompat.getRootServerLevel(countermeasure);
                if (countermeasure.getPersistentData().getDouble("time") >= MianbaoCountermeasureCompat.LIFETIME_TICKS
                        || root != null && root.getGameTime() - state.launchedAt >= MianbaoCountermeasureCompat.LIFETIME_TICKS) {
                    // Remove from the iterator before discard posts EntityLeaveLevelEvent.
                    removeVisual(countermeasure, state);
                    state.release();
                    iterator.remove();
                    countermeasure.discard();
                }
            }
            activeFlares = snapshotTracked();
        }
        for (ServerLevel level : event.getServer().getAllLevels()) sync(level);
        // Decoy checks are driven by the explicitly registered flare list.
        // No world-wide entity scan is needed after the launch event.
        for (TrackedFlare flare : activeFlares) {
            MianbaoAircraftGuidanceHandler.tryCountermeasureDecoy(flare);
            MianbaoAirDefenseGuidanceHandler.tryCountermeasureDecoy(flare);
        }
    }

    private static void removeVisual(Entity entity, TrackedCountermeasure state) {
        TRACKED_IDS.remove(entity.getUUID());
        if (state.level != null) REMOVED.computeIfAbsent(state.level, ignored -> new ArrayList<>()).add(entity.getUUID());
    }

    private static void sync(ServerLevel level) {
        if (TRACKED.isEmpty() && !REMOVED.containsKey(level)) return;
        long now = level.getGameTime();
        List<CountermeasureSalvoPayload.State> states = new ArrayList<>();
        synchronized (TRACKED) {
            for (var entry : TRACKED.entrySet()) {
                Entity entity = entry.getKey();
                TrackedCountermeasure state = entry.getValue();
                if (state.level != level || entity.isRemoved() || !entity.isAlive()
                        || !state.unsent && now % REMOTE_SYNC_INTERVAL_TICKS != 0) continue;
                states.add(new CountermeasureSalvoPayload.State(entity.getUUID(),
                        MianbaoMissileCompat.getWorldPosition(entity), MianbaoMissileCompat.getWorldVelocity(entity)));
                state.unsent = false;
            }
        }
        List<UUID> removed = REMOVED.remove(level);
        if (removed == null) removed = List.of();
        if (states.isEmpty() && removed.isEmpty()) return;
        for (var player : level.getServer().getPlayerList().getPlayers()) {
            if (MianbaoMissileCompat.getRootServerLevel(player) != level) continue;
            Vec3 viewer = MianbaoMissileCompat.getWorldPosition(player);
            List<CountermeasureSalvoPayload.State> visible = states.stream().filter(state ->
                    state.position().distanceToSqr(viewer) <= 20000.0 * 20000.0).toList();
            int batchSize = CountermeasureSalvoPayload.MAX_FLARES;
            for (int offset = 0; offset < Math.max(visible.size(), removed.size()); offset += batchSize) {
                PacketDistributor.sendToPlayer(player, new CountermeasureSalvoPayload(level.dimension().location(), now,
                        visible.subList(Math.min(offset, visible.size()), Math.min(offset + batchSize, visible.size())),
                        removed.subList(Math.min(offset, removed.size()), Math.min(offset + batchSize, removed.size()))));
            }
        }
    }

    private static void resolvePendingLaunches(Iterable<ServerLevel> levels) {
        synchronized (PENDING_LAUNCHES) {
            Iterator<PendingLaunch> iterator = PENDING_LAUNCHES.iterator();
            while (iterator.hasNext()) {
                PendingLaunch pending = iterator.next();
                if (pending.level().isClientSide() || pending.level().getGameTime() > pending.expiry()) {
                    iterator.remove();
                    continue;
                }
                AABB area = new AABB(pending.origin(), pending.origin()).inflate(6.0);
                List<Entity> candidates = pending.level().getEntitiesOfClass(
                        Entity.class, area,
                        entity -> MianbaoCountermeasureCompat.isCountermeasure(entity)
                                && !isTracked(entity)
                                && entity.getPersistentData().getDouble("jilu1") <= 3.0);
                Entity nearest = null;
                double nearestDistance = Double.POSITIVE_INFINITY;
                for (Entity candidate : candidates) {
                    double distance = candidate.position().distanceToSqr(pending.origin());
                    if (distance < nearestDistance) {
                        nearest = candidate;
                        nearestDistance = distance;
                    }
                }
                if (nearest != null) {
                    registerLaunched(nearest, pending.carrierIds());
                    iterator.remove();
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (TRACKED) {
            for (TrackedCountermeasure state : TRACKED.values()) state.release();
            TRACKED.clear();
            TRACKED_IDS.clear();
            REMOVED.clear();
            // release() normally empties this map. The explicit fallback keeps
            // shutdown safe even if a third-party level disappeared first.
            for (Map.Entry<ServerLevel, Map<Long, Integer>> levelEntry
                    : CHUNK_REFERENCES.entrySet()) {
                ServerLevel level = levelEntry.getKey();
                for (long chunk : levelEntry.getValue().keySet()) {
                    level.getChunkSource().removeRegionTicket(
                            TICKET, new ChunkPos(chunk), ENTITY_TICKING_RADIUS, chunk, true);
                }
            }
            CHUNK_REFERENCES.clear();
        }
        synchronized (PENDING_LAUNCHES) {
            PENDING_LAUNCHES.clear();
        }
    }

    static double approachSpeedMismatchMetersPerSecond(
            Vec3 missilePosition,
            Vec3 missileVelocity,
            Vec3 targetPosition,
            Vec3 targetVelocity
    ) {
        if (missilePosition == null || missileVelocity == null
                || targetPosition == null || targetVelocity == null) {
            return Double.POSITIVE_INFINITY;
        }
        Vec3 lineOfSight = targetPosition.subtract(missilePosition);
        if (lineOfSight.lengthSqr() < 1.0e-8) return Double.POSITIVE_INFINITY;
        lineOfSight = lineOfSight.normalize();
        double closingRate = missileVelocity.subtract(targetVelocity)
                .dot(lineOfSight) * 20.0;
        double missileSpeed = missileVelocity.length() * 20.0;
        return closingRate - missileSpeed;
    }

    /** Retained for the lightweight regression check and older callers. */
    static double relativeSpeedMetersPerSecond(
            Vec3 missileVelocity, Vec3 targetVelocity
    ) {
        if (missileVelocity == null || targetVelocity == null) {
            return Double.POSITIVE_INFINITY;
        }
        return missileVelocity.subtract(targetVelocity).length() * 20.0;
    }

    static Vec3 divertedVelocity(
            Entity missile,
            Vec3 missilePosition,
            Vec3 targetPosition,
            Vec3 currentVelocity
    ) {
        double speed = Math.max(0.1, currentVelocity.length());
        Vec3 away = missilePosition.subtract(targetPosition);
        if (away.lengthSqr() < 1.0e-8) away = currentVelocity.scale(-1.0);
        if (away.lengthSqr() < 1.0e-8) away = new Vec3(0.0, 1.0, 0.0);
        away = away.normalize();

        Vec3 lateral = away.cross(new Vec3(0.0, 1.0, 0.0));
        if (lateral.lengthSqr() < 1.0e-8) {
            lateral = away.cross(new Vec3(1.0, 0.0, 0.0));
        }
        double sign = (missile.getUUID().getLeastSignificantBits() & 1L) == 0L
                ? 1.0 : -1.0;
        Vec3 direction = away.scale(1.25).add(lateral.normalize().scale(sign));
        return direction.normalize().scale(speed);
    }

    private static Set<Long> requiredChunks(Entity countermeasure) {
        Vec3 position = MianbaoMissileCompat.getWorldPosition(countermeasure);
        Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(countermeasure);
        Vec3 lookAhead = position.add(velocity.scale(2.0));
        int samples = Math.max(1, Mth.ceil(Math.max(
                Math.abs(lookAhead.x - position.x),
                Math.abs(lookAhead.z - position.z)) / 8.0));
        Set<Long> result = new HashSet<>();
        for (int index = 0; index <= samples; index++) {
            double fraction = (double) index / samples;
            int blockX = Mth.floor(Mth.lerp(fraction, position.x, lookAhead.x));
            int blockZ = Mth.floor(Mth.lerp(fraction, position.z, lookAhead.z));
            result.add(ChunkPos.asLong(blockX >> 4, blockZ >> 4));
        }
        return result;
    }

    private static void retain(ServerLevel level, long chunk) {
        Map<Long, Integer> references = CHUNK_REFERENCES.computeIfAbsent(
                level, ignored -> new HashMap<>());
        int count = references.getOrDefault(chunk, 0);
        if (count == 0) {
            level.getChunkSource().addRegionTicket(
                    TICKET, new ChunkPos(chunk), ENTITY_TICKING_RADIUS, chunk, true);
        }
        references.put(chunk, count + 1);
    }

    private static void release(ServerLevel level, long chunk) {
        Map<Long, Integer> references = CHUNK_REFERENCES.get(level);
        if (references == null) return;
        int count = references.getOrDefault(chunk, 0);
        if (count <= 1) {
            references.remove(chunk);
            level.getChunkSource().removeRegionTicket(
                    TICKET, new ChunkPos(chunk), ENTITY_TICKING_RADIUS, chunk, true);
        } else {
            references.put(chunk, count - 1);
        }
        if (references.isEmpty()) CHUNK_REFERENCES.remove(level);
    }

    static record TrackedFlare(Entity entity, Set<UUID> carrierIds) {
    }

    private record PendingLaunch(
            Level level,
            Vec3 origin,
            Set<UUID> carrierIds,
            long expiry
    ) {
    }

    private static final class LaunchContext {
        private final Set<UUID> carrierIds;
        private Entity captured;

        private LaunchContext(Set<UUID> carrierIds) {
            this.carrierIds = carrierIds;
        }

        private Set<UUID> carrierIds() {
            return carrierIds;
        }

        private Entity captured() {
            return captured;
        }

        private void capture(Entity entity) {
            if (captured == null) captured = entity;
        }
    }

    static final class LaunchScope implements AutoCloseable {
        private final LaunchContext previous;
        private boolean closed;

        private LaunchScope(LaunchContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) ACTIVE_LAUNCH.remove();
            else ACTIVE_LAUNCH.set(previous);
        }
    }

    private static final class TrackedCountermeasure {
        private final Set<UUID> carrierIds;
        private ServerLevel level;
        private Set<Long> chunks = Set.of();
        private long launchedAt;
        private boolean unsent = true;

        private TrackedCountermeasure(Set<UUID> carrierIds) {
            this.carrierIds = carrierIds;
        }

        void update(Entity countermeasure) {
            ServerLevel nextLevel = MianbaoMissileCompat.getRootServerLevel(countermeasure);
            if (nextLevel == null) return;
            Set<Long> desired = new HashSet<>();
            for (long chunk : requiredChunks(countermeasure)) {
                // Player tracking already requests and keeps these chunks.
                // As a player leaves, the next tick restores the flare ticket.
                if (nextLevel.getChunkSource().chunkMap.getPlayers(new ChunkPos(chunk), false).isEmpty()) desired.add(chunk);
            }
            if (level != nextLevel) {
                release();
                level = nextLevel;
            }
            for (long chunk : desired) {
                if (!chunks.contains(chunk)) retain(nextLevel, chunk);
            }
            for (long chunk : chunks) {
                if (!desired.contains(chunk)) {
                    MianbaoCountermeasureManager.release(nextLevel, chunk);
                }
            }
            chunks = Set.copyOf(desired);
        }

        void release() {
            if (level != null) {
                for (long chunk : chunks) {
                    MianbaoCountermeasureManager.release(level, chunk);
                }
            }
            chunks = Set.of();
            level = null;
        }
    }

    private MianbaoCountermeasureManager() {
    }
}
