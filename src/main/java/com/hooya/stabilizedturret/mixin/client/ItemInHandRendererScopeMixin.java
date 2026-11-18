package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.ClientTelevisionGuidanceState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the scoped hotbar visible without drawing the held item or hands. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererScopeMixin {
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$hideScopedHands(
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource.BufferSource buffer,
            LocalPlayer player,
            int combinedLight,
            CallbackInfo ci
    ) {
        if (ClientAimState.isOpticalView()
                || ClientTelevisionGuidanceState.isActive()
                || com.hooya.stabilizedturret.client.ClientWeaponViewState.isActive()) {
            ci.cancel();
        }
    }
}
