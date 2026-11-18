package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Omits only the active pod from its section mesh. Visibility transitions
 * invalidate both the previous and next pod sections in ClientAimState.
 */
@Mixin(BlockRenderDispatcher.class)
public abstract class BlockRenderDispatcherMixin {
    @Inject(method = "renderBatched(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            + "ZLnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;"
            + "Lnet/minecraft/client/renderer/RenderType;)V", at = @At("HEAD"),
            cancellable = true, require = 0)
    private void stabilizedTurret$hideActivePodModel(
            BlockState state, BlockPos pos, BlockAndTintGetter level, PoseStack poseStack,
            VertexConsumer vertexConsumer, boolean checkSides, RandomSource random,
            ModelData modelData, RenderType renderType, CallbackInfo ci) {
        if (ClientAimState.shouldHidePod(state, level, pos)) ci.cancel();
    }
}
