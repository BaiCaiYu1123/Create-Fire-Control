package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.compat.mianbao.MountedHeavyMachineGunRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/** Renders attachments before Create clears the prepared contraption matrices. */
@Mixin(ContraptionEntityRenderer.class)
public abstract class MountedHeavyMachineGunRendererMixin {
    @Inject(
            method = "render(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target =
                    "Lcom/simibubi/create/content/contraptions/render/ContraptionMatrices;clear()V"))
    private void createFireControl$renderMountedHeavyMachineGuns(
            AbstractContraptionEntity entity,
            float yaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int packedLight,
            CallbackInfo ci
    ) {
        if (entity instanceof PitchOrientedContraptionEntity cannonEntity
                && cannonEntity.getContraption() != null) {
            MountedHeavyMachineGunRenderer.render(
                    cannonEntity,
                    cannonEntity.getContraption()
                            .getOrCreateClientContraptionLazy().getMatrices(),
                    buffers);
        }
    }
}
