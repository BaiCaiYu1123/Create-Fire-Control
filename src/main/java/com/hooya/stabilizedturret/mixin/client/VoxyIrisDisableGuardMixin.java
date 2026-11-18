package com.hooya.stabilizedturret.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy disables Iris globally when its optional Iris LOD pipeline cannot be
 * constructed. Keep that failure local to Voxy so the selected shader pack
 * remains usable and Voxy can fall back to its normal renderer.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.util.IrisUtil", remap = false)
public abstract class VoxyIrisDisableGuardMixin {
    @Inject(method = "disableIrisShaders", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createFireControl$keepIrisEnabled(CallbackInfo ci) {
        ci.cancel();
    }
}
