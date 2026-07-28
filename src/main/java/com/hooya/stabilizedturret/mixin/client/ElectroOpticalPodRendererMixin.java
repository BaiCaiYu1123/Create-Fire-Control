package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides only Mianbao's dynamic pod head while its camera is active. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.client.renderer.ElectroopticalpodRenderer", remap = false)
public abstract class ElectroOpticalPodRendererMixin {
    @Inject(
            method = "render(Lnet/mcreator/myfirstmod/block/entity/ElectroopticalpodBlockEntity;"
                    + "FLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void stabilizedTurret$hidePodHeadInPodView(
            @Coerce Object pod,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            int packedOverlay,
            CallbackInfo ci
    ) {
        if (pod instanceof BlockEntity blockEntity
                && ClientAimState.shouldHidePodHead(blockEntity)) {
            ci.cancel();
        }
    }
}
