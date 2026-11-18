package com.hooya.stabilizedturret.content.controller;

import com.mojang.serialization.Codec;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Read-only collision data. Never creates chunks, tickets or ticking entities. */
final class RocketTerrainCache {
    static final int MAX_CACHE_CHUNKS = 256;
    static final int MAX_READS_PER_TICK = 2;
    static final int MAX_IN_FLIGHT_READS = 4;
    private static final Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
            PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    private static final Snapshot UNGENERATED = new Snapshot(Map.of());
    private final ServerLevel level;
    private final Map<Long, Entry> chunks = new LinkedHashMap<>(32, 0.75F, true);
    private long budgetTick = Long.MIN_VALUE;
    private int reads;

    RocketTerrainCache(ServerLevel level) { this.level = level; }

    record Contact(boolean ready, @Nullable Vec3 hit) {}

    boolean inWater(Vec3 point) {
        if (point.y < level.getMinBuildHeight() || point.y >= level.getMaxBuildHeight()) return false;
        BlockPos pos = BlockPos.containing(point);
        var loaded = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (loaded != null) return loaded.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER);
        if (!available(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        Entry entry = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        Snapshot snapshot = entry == null ? null : entry.future.getNow(null);
        return snapshot != null && snapshot.getBlockState(pos).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    /** Same heightmap predicate as the live low-altitude guidance, from saved palettes. */
    double groundHeight(double x, double z) {
        int blockX = (int) Math.floor(x), blockZ = (int) Math.floor(z);
        var loaded = level.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4);
        if (loaded != null) return loaded.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                blockX & 15, blockZ & 15) + 1.0;
        if (!available(blockX >> 4, blockZ >> 4)) return Double.NaN;
        Entry entry = chunks.get(ChunkPos.asLong(blockX >> 4, blockZ >> 4));
        Snapshot snapshot = entry == null ? null : entry.future.getNow(null);
        if (snapshot == null || snapshot == UNGENERATED) return Double.NaN;
        var opaque = net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque();
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            if (opaque.test(snapshot.getBlockState(new BlockPos(blockX, y, blockZ)))) return y + 1.0;
        }
        return level.getMinBuildHeight();
    }

    Contact sweep(Vec3 from, Vec3 to) {
        if (Math.min(from.y, to.y) >= level.getMaxBuildHeight()
                || Math.max(from.y, to.y) < level.getMinBuildHeight()) return new Contact(true, null);
        int minX = ((int) Math.floor(Math.min(from.x, to.x))) >> 4;
        int maxX = ((int) Math.floor(Math.max(from.x, to.x))) >> 4;
        int minZ = ((int) Math.floor(Math.min(from.z, to.z))) >> 4;
        int maxZ = ((int) Math.floor(Math.max(from.z, to.z))) >> 4;
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > 32) return new Contact(false, null);
        boolean ready = true;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) ready &= available(x, z);
        }
        BlockGetter view = new BlockGetter() {
            @Override public BlockState getBlockState(BlockPos pos) {
                var loaded = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                if (loaded != null) return loaded.getBlockState(pos);
                Entry entry = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
                Snapshot snapshot = entry == null ? null : entry.future.getNow(null);
                return snapshot == null ? Blocks.AIR.defaultBlockState() : snapshot.getBlockState(pos);
            }
            @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getHeight() { return level.getHeight(); }
            @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
        };
        BlockHitResult hit = view.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        Vec3 point = hit.getType() == HitResult.Type.MISS ? null : hit.getLocation();
        if (!ready && point != null) {
            // A known near wall must explode immediately even when the far end
            // of this high-speed step crosses an unavailable chunk.
            ready = true;
            minX = ((int) Math.floor(Math.min(from.x, point.x))) >> 4;
            maxX = ((int) Math.floor(Math.max(from.x, point.x))) >> 4;
            minZ = ((int) Math.floor(Math.min(from.z, point.z))) >> 4;
            maxZ = ((int) Math.floor(Math.max(from.z, point.z))) >> 4;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) ready &= available(x, z);
            }
        }
        return new Contact(ready, point);
    }

    private boolean available(int x, int z) {
        if (level.getChunkSource().getChunkNow(x, z) != null) return true;
        long now = level.getGameTime(), key = ChunkPos.asLong(x, z);
        if (budgetTick != now) { budgetTick = now; reads = 0; }
        Entry entry = chunks.get(key);
        if (entry != null && entry.future.isDone() && now - entry.requested > 100) {
            chunks.remove(key);
            entry = null;
        }
        if (entry == null) {
            long pending = chunks.values().stream().filter(value -> !value.future.isDone()).count();
            if (reads >= MAX_READS_PER_TICK || pending >= MAX_IN_FLIGHT_READS) return false;
            trim();
            if (chunks.size() >= MAX_CACHE_CHUNKS) return false;
            reads++;
            // ChunkStorage.read is asynchronous region-file I/O. Decoding only
            // block palettes off-thread never installs a LevelChunk in the world.
            CompletableFuture<Snapshot> future = level.getChunkSource().chunkMap.read(new ChunkPos(x, z))
                    .thenApplyAsync(RocketTerrainCache::decodeSaved, Util.backgroundExecutor())
                    .exceptionally(error -> null);
            entry = new Entry(now, future);
            chunks.put(key, entry);
        }
        // A chunk with no saved data has no authoritative blocks to hit. Pass
        // through without generating a speculative ground impact, then resume
        // exact collisions in the next known chunk. Corrupt/unreadable data waits.
        return entry.future.isDone() && entry.future.getNow(null) != null;
    }

    void invalidate(Vec3 position) {
        int x = ((int) Math.floor(position.x)) >> 4, z = ((int) Math.floor(position.z)) >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            chunks.remove(ChunkPos.asLong(x + dx, z + dz));
        }
    }

    private void trim() {
        Iterator<Entry> iterator = chunks.values().iterator();
        while (chunks.size() >= MAX_CACHE_CHUNKS && iterator.hasNext()) {
            if (iterator.next().future.isDone()) iterator.remove();
        }
    }

    static Snapshot decodeSaved(Optional<CompoundTag> saved) {
        return saved.isEmpty() ? UNGENERATED : decode(saved.get());
    }

    static Snapshot decode(CompoundTag tag) {
        String status = tag.getString("Status");
        if (!status.equals("minecraft:full") && !status.equals("full")) return null;
        if (!tag.contains("sections", 9)) return null;
        Map<Integer, PalettedContainer<BlockState>> sections = new HashMap<>();
        ListTag list = tag.getList("sections", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag section = list.getCompound(i);
            if (!section.contains("block_states", 10)) continue;
            if (section.getCompound("block_states").getList("palette", 10).isEmpty()) return null;
            Optional<PalettedContainer<BlockState>> states = STATES.parse(
                    NbtOps.INSTANCE, section.getCompound("block_states")).result();
            if (states.isEmpty()) return null;
            sections.put(section.getByte("Y") & 0xff, states.get());
        }
        return new Snapshot(Map.copyOf(sections));
    }

    record Snapshot(Map<Integer, PalettedContainer<BlockState>> sections) {
        BlockState getBlockState(BlockPos pos) {
            PalettedContainer<BlockState> section = sections.get((pos.getY() >> 4) & 0xff);
            return section == null ? Blocks.AIR.defaultBlockState()
                    : section.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
    }

    private record Entry(long requested, CompletableFuture<Snapshot> future) {}
}
