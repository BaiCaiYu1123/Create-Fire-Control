package com.hooya.stabilizedturret.compat.sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Guards block-entity update packets while a Sable plot chunk is being rebuilt. */
public final class SableBlockEntitySyncCompat {
    /**
     * Sable can expose a plot {@code LevelChunk} before its packet-facing
     * {@code PlotChunkHolder} exists. Vanilla/Create block-entity lookups work
     * during that window, but {@code ServerChunkCache.blockChanged()} throws.
     */
    public static boolean canSendBlockEntityUpdate(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || pos == null) {
            return true;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
        if (container == null) {
            return true;
        }
        ChunkPos chunkPos = new ChunkPos(pos);
        return !container.inBounds(chunkPos) || container.getChunkHolder(chunkPos) != null;
    }

    private SableBlockEntitySyncCompat() {
    }
}
