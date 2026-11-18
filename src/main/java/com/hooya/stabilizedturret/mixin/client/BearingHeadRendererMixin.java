package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.content.bearing.BearingHeadVisibility;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KineticBlockEntityRenderer.class, remap = false)
public abstract class BearingHeadRendererMixin {
    @Inject(method = "renderSafe", at = @At("HEAD"), cancellable = true, remap = false)
    private void stabilizedTurret$hideBearingHead(
            KineticBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            int packedOverlay,
            CallbackInfo ci
    ) {
        if (blockEntity instanceof BearingHeadVisibility visibility
                && visibility.stabilizedTurret$isBearingHeadHidden()) {
            ci.cancel();
        }
    }
}
