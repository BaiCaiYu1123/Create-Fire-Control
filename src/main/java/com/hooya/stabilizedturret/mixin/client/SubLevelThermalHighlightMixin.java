package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brightens Sable physical structures before the thermal grayscale pass.
 * Textures and directional lighting remain intact, so the result retains
 * surface detail instead of becoming a flat white silhouette.
 */
@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
public abstract class SubLevelThermalHighlightMixin {
    @Inject(method = "setupDynamicEffects", at = @At("TAIL"))
    private static void stabilizedTurret$markPhysicalStructures(
            ShaderInstance shader,
            boolean onSubLevel,
            boolean upload,
            CallbackInfo ci
    ) {
        if (shader.COLOR_MODULATOR == null) {
            return;
        }
        float brightness = onSubLevel && ClientAimState.isThermal() ? 2.0F : 1.0F;
        shader.COLOR_MODULATOR.set(brightness, brightness, brightness, 1.0F);
        if (upload) {
            shader.COLOR_MODULATOR.upload();
        }
    }
}
