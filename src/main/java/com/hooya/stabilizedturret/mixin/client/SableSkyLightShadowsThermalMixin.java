package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalRenderMode;
import dev.ryanhcode.sable.render.sky_light_shadow.SableSkyLightShadows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Removes Sable's time-dependent sky shadow while thermal imaging is active. */
@Mixin(value = SableSkyLightShadows.class, remap = false)
public abstract class SableSkyLightShadowsThermalMixin {
    @Inject(method = "isEnabled", at = @At("HEAD"), cancellable = true)
    private static void createFireControl$disableThermalSkyShadow(
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (ThermalRenderMode.isFixedLightThermal()) {
            cir.setReturnValue(false);
        }
    }
}
