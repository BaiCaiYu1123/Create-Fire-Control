package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.VoxyThermalShaderPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds a runtime thermal direct-color branch to Photon-patched Voxy shaders. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.IrisVoxyRenderPipeline", remap = false)
public abstract class VoxyThermalShaderMixin {
    @Inject(
            method = "patchOpaqueShader",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private void createFireControl$patchOpaque(
            @Coerce Object renderer,
            String source,
            CallbackInfoReturnable<String> cir
    ) {
        cir.setReturnValue(VoxyThermalShaderPatcher.patch(cir.getReturnValue(), true));
    }

    @Inject(
            method = "patchTranslucentShader",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private void createFireControl$patchTranslucent(
            @Coerce Object renderer,
            String source,
            CallbackInfoReturnable<String> cir
    ) {
        String patched = cir.getReturnValue();
        if (patched != null) {
            cir.setReturnValue(VoxyThermalShaderPatcher.patch(patched, false));
        }
    }
}
