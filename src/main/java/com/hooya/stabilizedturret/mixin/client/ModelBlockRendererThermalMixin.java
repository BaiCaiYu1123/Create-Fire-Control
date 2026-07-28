package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Makes thermal terrain independent of the visible-light renderer.  The
 * lightmap mixin alone cannot do this: Minecraft bakes AO and face shading
 * directly into block vertices before the lightmap is sampled.
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
    private boolean stabilizedTurret$disableThermalAmbientOcclusion(boolean original) {
        return ClientAimState.isThermal() ? false : original;
    }

    @ModifyArgs(
            method = "renderModelFaceFlat",
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
    private void stabilizedTurret$useFlatThermalBlockLight(Args args) {
        if (!ClientAimState.isThermal()) {
            return;
        }
        for (int index = 6; index <= 9; index++) {
            args.set(index, 1.0F);
        }
        for (int index = 10; index <= 13; index++) {
            args.set(index, LightTexture.FULL_BRIGHT);
        }
    }
}
