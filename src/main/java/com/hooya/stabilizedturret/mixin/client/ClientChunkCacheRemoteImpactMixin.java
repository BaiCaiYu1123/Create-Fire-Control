package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.RemoteImpactRenderManager;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheRemoteImpactMixin {
    @Shadow @Final ClientLevel level;

    @Inject(method = "getChunk", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$getRemoteImpactChunk(
            int chunkX, int chunkZ, ChunkStatus status, boolean create,
            CallbackInfoReturnable<LevelChunk> cir) {
        LevelChunk remote = RemoteImpactRenderManager.getChunk(chunkX, chunkZ);
        if (remote != null) cir.setReturnValue(remote);
    }

    @Inject(method = "replaceWithPacketData", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$acceptRemoteImpactChunk(
            int chunkX,
            int chunkZ,
            FriendlyByteBuf data,
            CompoundTag heightmaps,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities,
            CallbackInfoReturnable<LevelChunk> cir) {
        if (!RemoteImpactRenderManager.shouldCaptureChunk(chunkX, chunkZ)) return;
        LevelChunk chunk = RemoteImpactRenderManager.replaceChunk(
                level, chunkX, chunkZ, data, heightmaps, blockEntities);
        if (chunk != null) cir.setReturnValue(chunk);
    }

    @Inject(method = "replaceBiomes", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$replaceRemoteImpactBiomes(
            int chunkX, int chunkZ, FriendlyByteBuf data, CallbackInfo ci) {
        if (RemoteImpactRenderManager.replaceBiomes(chunkX, chunkZ, data)) ci.cancel();
    }

    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$dropRemoteImpactChunk(
            ChunkPos position, CallbackInfo ci) {
        if (RemoteImpactRenderManager.dropChunk(position)) ci.cancel();
    }

    @Inject(method = "getLoadedChunksCount", at = @At("RETURN"), cancellable = true)
    private void stabilizedTurret$countRemoteImpactChunks(
            CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(cir.getReturnValue()
                + RemoteImpactRenderManager.remoteChunkCount());
    }
}
