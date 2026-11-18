package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalEmissionLightmap;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Stores source-block emission in packed light coordinates. It never changes
 * color, alpha, geometry, AO, depth or render type.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererThermalMixin {
    @ModifyExpressionValue(
            method = "tesselateBlock",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;useAmbientOcclusion()Z"
            )
    )
    private boolean createFireControl$useBackedUpAmbientOcclusion(boolean original) {
        return original;
    }

    @ModifyArgs(
            method = {"renderModelFaceFlat", "renderModelFaceAO"},
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;putQuadData("
                            + "Lnet/minecraft/world/level/BlockAndTintGetter;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
                            + "Lnet/minecraft/client/renderer/block/model/BakedQuad;FFFFIIIII)V"
            )
    )
    private void createFireControl$encodeSourceEmission(Args args) {
        var level = (net.minecraft.world.level.BlockAndTintGetter) args.get(0);
        var state = (net.minecraft.world.level.block.state.BlockState) args.get(1);
        var pos = (net.minecraft.core.BlockPos) args.get(2);
        int emission = state.getLightEmission(level, pos);
        for (int index = 10; index <= 13; index++) {
            args.set(index, ThermalEmissionLightmap.encodeBlock(
                    args.get(index), emission));
        }
    }
}
