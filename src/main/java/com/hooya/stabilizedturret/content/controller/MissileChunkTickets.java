package com.hooya.stabilizedturret.content.controller;

import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Prefetches a bounded terrain corridor and optionally enables native entity
 * ticks along the current-to-next-tick segment. The distant prefetch chunks
 * do not receive entity-ticking tickets.
 */
public final class MissileChunkTickets {
    private static final double MAX_LOOKAHEAD_BLOCKS = 384.0;
    private static final double MIN_LOOKAHEAD_DIRECTION_SPEED = 0.25;
    private static final int MAX_PATH_CHUNK_SPAN = 32;

    private final TicketType<UUID> type;
    // addRegionTicket uses level 33 - distance; entity ticks require level 31.
    // A constant avoids bootstrapping chunk registries when this class is loaded.
    static final int ENTITY_TICKING_RADIUS = 2;
    private final boolean tickEntities;
    private ServerLevel level;
    private UUID owner;
    private Set<ChunkPos> chunks = Set.of();
    private Set<ChunkPos> tickingChunks = Set.of();

    public MissileChunkTickets(TicketType<UUID> type) {
        this(type, false);
    }

    public MissileChunkTickets(TicketType<UUID> type, boolean tickEntities) {
        this.type = type;
        this.tickEntities = tickEntities;
    }

    public void update(Entity missile, @Nullable Vec3 velocity) {
        update(missile, velocity, 0.0);
    }

    /**
     * Keeps the current movement segment plus a bounded horizontal corridor
     * loaded. The corridor gives asynchronous chunk generation time to finish
     * before a fast missile reaches the next view-distance boundary.
     */
    public void update(Entity missile, @Nullable Vec3 velocity,
                       double minimumLookAheadBlocks) {
        if (missile != null && GuidedMissileMode.mathematical(missile)) {
            release();
            return;
        }
        if (missile == null || missile.isRemoved()) {
            release();
            return;
        }
        ServerLevel nextLevel = MianbaoMissileCompat.getRootServerLevel(missile);
        Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
        if (nextLevel == null || position == null
                || !Double.isFinite(position.x) || !Double.isFinite(position.z)) {
            release();
            return;
        }
        UUID nextOwner = missile.getUUID();
        Vec3 movement = velocity == null
                ? MianbaoMissileCompat.getWorldVelocity(missile) : velocity;
        Set<ChunkPos> wanted = pathChunks(position, movement,
                minimumLookAheadBlocks);
        if (level != nextLevel || !nextOwner.equals(owner)) release();
        level = nextLevel;
        owner = nextOwner;
        // FULL (33) keeps terrain available but never enables entity ticks (31).
        // Promote only the imminent movement segment, not the entire prefetch corridor.
        Set<ChunkPos> nextTicking = tickEntities
                ? pathChunks(position, movement, 0.0) : Set.of();
        for (ChunkPos chunk : nextTicking) if (!tickingChunks.contains(chunk)) {
            level.getChunkSource().addRegionTicket(type, chunk, ENTITY_TICKING_RADIUS, owner, true);
        }
        for (ChunkPos chunk : tickingChunks) if (!nextTicking.contains(chunk)) {
            level.getChunkSource().removeRegionTicket(type, chunk, ENTITY_TICKING_RADIUS, owner, true);
        }
        tickingChunks = Set.copyOf(nextTicking);
        for (ChunkPos chunk : chunks) if (!wanted.contains(chunk)) {
            level.getChunkSource().removeRegionTicket(type, chunk, 0, owner, true);
        }
        for (ChunkPos chunk : wanted) if (!chunks.contains(chunk)) {
            level.getChunkSource().addRegionTicket(type, chunk, 0, owner, true);
        }
        chunks = Set.copyOf(wanted);
    }

    /** Read-only check used to hold a missile for one tick instead of bouncing it. */
    public boolean movementPathLoaded(Entity missile, @Nullable Vec3 movement) {
        if (missile == null || missile.isRemoved()) return false;
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
        if (root == null || position == null) return false;
        for (ChunkPos chunk : pathChunks(position, movement, 0.0)) {
            if (root.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) {
                return false;
            }
        }
        return true;
    }

    private static Set<ChunkPos> pathChunks(Vec3 position,
                                            @Nullable Vec3 movement,
                                            double minimumLookAheadBlocks) {
        Set<ChunkPos> result = new HashSet<>();
        ChunkPos current = new ChunkPos((int) Math.floor(position.x) >> 4,
                (int) Math.floor(position.z) >> 4);
        result.add(current);
        if (movement == null || !Double.isFinite(movement.x)
                || !Double.isFinite(movement.z)) return result;

        double horizontalSpeed = Math.hypot(movement.x, movement.z);
        double lookAhead = horizontalSpeed;
        if (horizontalSpeed >= MIN_LOOKAHEAD_DIRECTION_SPEED
                && Double.isFinite(minimumLookAheadBlocks)) {
            lookAhead = Math.max(horizontalSpeed, Math.max(0.0,
                    Math.min(MAX_LOOKAHEAD_BLOCKS, minimumLookAheadBlocks)));
        }
        double pathX = horizontalSpeed < 1.0e-8
                ? 0.0 : movement.x * lookAhead / horizontalSpeed;
        double pathZ = horizontalSpeed < 1.0e-8
                ? 0.0 : movement.z * lookAhead / horizontalSpeed;
        Vec3 next = position.add(pathX, 0.0, pathZ);
        ChunkPos predicted = new ChunkPos((int) Math.floor(next.x) >> 4,
                (int) Math.floor(next.z) >> 4);
        // Reject malformed third-party velocities before they can request a
        // remote corridor. Legitimate Mach-3 flight remains well inside this bound.
        if (Math.max(Math.abs(predicted.x - current.x),
                Math.abs(predicted.z - current.z)) > MAX_PATH_CHUNK_SPAN) {
            return result;
        }
        int samples = Math.max(1, (int) Math.ceil(Math.max(
                Math.abs(pathX), Math.abs(pathZ)) / 8.0));
        for (int index = 1; index <= samples; index++) {
            double fraction = (double) index / samples;
            int blockX = (int) Math.floor(position.x + pathX * fraction);
            int blockZ = (int) Math.floor(position.z + pathZ * fraction);
            result.add(new ChunkPos(blockX >> 4, blockZ >> 4));
        }
        return result;
    }

    public void release() {
        if (level != null && owner != null) {
            for (ChunkPos chunk : tickingChunks) {
                level.getChunkSource().removeRegionTicket(type, chunk, ENTITY_TICKING_RADIUS, owner, true);
            }
            for (ChunkPos chunk : chunks) {
                level.getChunkSource().removeRegionTicket(type, chunk, 0, owner, true);
            }
        }
        level = null;
        owner = null;
        chunks = Set.of();
        tickingChunks = Set.of();
    }
}
