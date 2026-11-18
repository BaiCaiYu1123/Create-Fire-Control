package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalRenderMode;
import com.hooya.stabilizedturret.client.VoxyThermalShaderPatcher;
import com.hooya.stabilizedturret.client.compat.PhotonVoxyThermalFramebufferBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runtime-only adjustments for Photon; switching optics never rebuilds Voxy. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.IrisVoxyRenderPipeline", remap = false)
public abstract class VoxyPhotonThermalPipelineMixin {
    @Inject(method = "getRenderScalingFactor", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$nativePhotonThermalResolution(CallbackInfoReturnable<float[]> cir) {
        if (ThermalRenderMode.isPhotonThermal()) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "taaFunction(ILjava/lang/String;)Ljava/lang/String;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void createFireControl$photonThermalTaa(int bindingPoint, String functionName,
                                                   CallbackInfoReturnable<String> cir) {
        // Compile both branches even when the optic is off; only the uniform
        // changes during play. The same function also runs in Voxy's culler.
        if (ThermalRenderMode.isPhotonShaderPackInUseNow()) {
            cir.setReturnValue(VoxyThermalShaderPatcher.patchTaa(cir.getReturnValue(), functionName));
        }
    }

    @Inject(method = "free", at = @At("HEAD"), require = 0)
    private void createFireControl$releasePhotonThermalFramebuffer(CallbackInfo ci) {
        PhotonVoxyThermalFramebufferBridge.release();
    }
}
