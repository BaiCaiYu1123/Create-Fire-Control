package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalRenderMode;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Disables Sable's visible-spectrum lighting and uniformly raises physical
 * sub-levels before the monochrome pass.
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
        if (ThermalRenderMode.isFixedLightThermal()) {
            setFloat(shader, "SableEnableNormalLighting", 0.0F, upload);
            setFloat(shader, "SableShadowsEnabled", 0.0F, upload);
            setFloat(shader, "SableSkyLightScale", 1.0F, upload);
        }
        if (shader.COLOR_MODULATOR != null) {
            if (onSubLevel && ThermalRenderMode.isFixedLightThermal()) {
                shader.COLOR_MODULATOR.set(4.0F, 4.0F, 4.0F, 1.0F);
            } else {
                shader.COLOR_MODULATOR.set(1.0F, 1.0F, 1.0F, 1.0F);
            }
            if (upload) {
                shader.COLOR_MODULATOR.upload();
            }
        }
    }

    private static void setFloat(
            ShaderInstance shader,
            String name,
            float value,
            boolean upload
    ) {
        var uniform = shader.getUniform(name);
        if (uniform == null) {
            return;
        }
        uniform.set(value);
        if (upload) {
            uniform.upload();
        }
    }
}
