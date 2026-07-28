package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Gives living heat targets a textured white-hot body instead of only a thin outline. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @ModifyExpressionValue(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getWhiteOverlayProgress(Lnet/minecraft/world/entity/LivingEntity;F)F"
            )
    )
    private float stabilizedTurret$whiteHotBody(float original) {
        return ClientAimState.isThermal() ? Math.max(original, 0.62F) : original;
    }
}
