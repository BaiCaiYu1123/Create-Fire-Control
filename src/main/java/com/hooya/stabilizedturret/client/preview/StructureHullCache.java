package com.hooya.stabilizedturret.client.preview;

import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import java.util.*;

/** Loaded-chunk-only model snapshots. Shape, orientation and material all invalidate geometry. */
public final class StructureHullCache {
    public record SectionKey(UUID body, int x, int y, int z) {
        public BlockPos origin() { return new BlockPos(x << 4, y << 4, z << 4); }
    }
    public record ModelBlock(BlockPos pos, BlockState state, boolean opaque) {}
    public record Section(SectionKey key, long fingerprint, List<ModelBlock> blocks,
                          BlockState[] states, byte[] materials) {
        public int weight() { return blocks.size(); }
        public BlockAndTintGetter view(Level level, boolean opaque) { return new ModelView(level, this, opaque); }
    }
    private static final Map<Level, LinkedHashMap<SectionKey, Section>> WORLDS = new WeakHashMap<>();
    private StructureHullCache() {}
    public static void clear() { WORLDS.clear(); }
    public static List<SectionKey> sections(SubLevel body) {
        List<SectionKey> result = new ArrayList<>();
        for (var holder : body.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            var sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) if (sections[i] != null && !sections[i].hasOnlyAir()) {
                result.add(new SectionKey(body.getUniqueId(), chunk.getPos().x, chunk.getSectionYFromSectionIndex(i), chunk.getPos().z));
            }
        }
        return result;
    }
    public static Section previous(Level level, SectionKey key) { return cache(level).get(key); }
    private static LinkedHashMap<SectionKey, Section> cache(Level level) {
        return WORLDS.computeIfAbsent(level, ignored -> new LinkedHashMap<>(64, .75f, true));
    }
    public static Section sample(Level level, SubLevel body, SectionKey key, StructurePreviewMaterials relevance) {
        byte[] materials = new byte[18 * 18 * 18];
        BlockState[] states = new BlockState[materials.length];
        List<ModelBlock> blocks = new ArrayList<>();
        Map<ChunkPos, LevelChunk> chunks = new HashMap<>();
        var pos = new BlockPos.MutableBlockPos();
        long fingerprint = 0xcbf29ce484222325L;
        for (int x = 0; x < 18; x++) for (int z = 0; z < 18; z++) {
            int bx = (key.x << 4) + x - 1, bz = (key.z << 4) + z - 1;
            ChunkPos cp = new ChunkPos(bx >> 4, bz >> 4);
            if (!chunks.containsKey(cp)) chunks.put(cp, body.getPlot().getChunk(body.getPlot().toLocal(cp)));
            LevelChunk chunk = chunks.get(cp);
            for (int y = 0; y < 18; y++) {
                pos.set(bx, (key.y << 4) + y - 1, bz);
                int index = VoxelPreviewMesher.index(x, y, z);
                BlockState state = chunk == null ? null : chunk.getBlockState(pos);
                byte material = state == null ? -1 : state.isAir() ? (byte)0 : (byte)(relevance.opaque(key.body(), pos)
                        || StructurePreviewMaterials.fireControlBlock(BuiltInRegistries.BLOCK.getKey(state.getBlock())) ? 2 : 1);
                materials[index] = material; states[index] = state;
                fingerprint = (fingerprint ^ (state == null ? -1 : Block.getId(state))) * 0x100000001b3L;
                fingerprint = (fingerprint ^ material) * 0x100000001b3L;
                if (material > 0 && x > 0 && x < 17 && y > 0 && y < 17 && z > 0 && z < 17)
                    blocks.add(new ModelBlock(pos.immutable(), state, material == 2));
            }
        }
        var cache = cache(level);
        Section old = cache.get(key);
        if (old != null && old.fingerprint == fingerprint) return old;
        Section section = new Section(key, fingerprint, List.copyOf(blocks), states, materials);
        cache.put(key, section);
        return section;
    }
    /** Expose equipment faces inside translucent armor while preserving contextual face culling. */
    private record ModelView(Level level, Section section, boolean opaque) implements BlockAndTintGetter {
        @Override public BlockState getBlockState(BlockPos pos) {
            int x = pos.getX() - (section.key.x << 4) + 1;
            int y = pos.getY() - (section.key.y << 4) + 1;
            int z = pos.getZ() - (section.key.z << 4) + 1;
            if (x < 0 || x >= 18 || y < 0 || y >= 18 || z < 0 || z >= 18) return Blocks.AIR.defaultBlockState();
            int i = VoxelPreviewMesher.index(x,y,z);
            if (section.states[i] == null) return Blocks.BARRIER.defaultBlockState();
            return opaque && section.materials[i] == 1 ? Blocks.AIR.defaultBlockState() : section.states[i];
        }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return level.getBlockEntity(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public float getShade(Direction direction, boolean shade) { return 1; }
        @Override public LevelLightEngine getLightEngine() { return level.getLightEngine(); }
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return level.getBlockTint(pos,resolver); }
        @Override public int getHeight() { return level.getHeight(); }
        @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
    }
}
