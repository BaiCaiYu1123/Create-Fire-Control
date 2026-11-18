package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalHighlightShaderPatcher;
import com.hooya.stabilizedturret.client.ThermalRenderMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the established native Sable thermal patch at Veil's preprocessing
 * stage. Shader-pack sources are left untouched here because Iris has not yet
 * transformed them into their final gbuffer programs.
 */
@Pseudo
@Mixin(
        targets = "foundry.veil.impl.client.render.shader.processor.VanillaShaderProcessor",
        remap = false
)
public abstract class VeilThermalHighlightShaderMixin {
    @Inject(method = "modify", at = @At("RETURN"), cancellable = true, require = 0)
    private static void createFireControl$patchSableWhiteHot(
            CallbackInfoReturnable<String> cir
    ) {
        String source = cir.getReturnValue();
        if (ThermalRenderMode.isShaderPackInUseNow()) {
            cir.setReturnValue(source);
            return;
        }
        cir.setReturnValue(ThermalHighlightShaderPatcher.patch(source));
    }
}
