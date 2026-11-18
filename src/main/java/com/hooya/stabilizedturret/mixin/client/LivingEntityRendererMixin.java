package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalEmissionLightmap;
import com.hooya.stabilizedturret.client.ThermalRenderMode;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Gives living heat targets a textured white-hot body instead of only a thin outline. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private int stabilizedTurret$useFixedHotBodyLight(int packedLight) {
        return ThermalRenderMode.isFixedLightThermal()
                ? ThermalEmissionLightmap.LIVING_MARKER
                : packedLight;
    }

    @ModifyExpressionValue(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getWhiteOverlayProgress(Lnet/minecraft/world/entity/LivingEntity;F)F"
            )
    )
    private float stabilizedTurret$whiteHotBody(float original) {
        if (ThermalRenderMode.isFixedLightThermal()) {
            return 1.0F;
        }
        return original;
    }
}
