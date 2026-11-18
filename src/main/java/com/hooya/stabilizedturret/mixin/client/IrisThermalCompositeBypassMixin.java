package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalRenderMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips every shader-pack composite/compute pass during a thermal frame. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer", remap = false)
public abstract class IrisThermalCompositeBypassMixin {
    @Inject(method = "renderAll", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$skipShaderPackComposite(CallbackInfo ci) {
        if (ThermalRenderMode.isShaderPackThermal()) {
            ci.cancel();
        }
    }
}
