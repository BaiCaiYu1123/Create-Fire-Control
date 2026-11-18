package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.effects.CBCScreenShakeHandler;

/** Stops cannon camera shake while the local player occupies a bound seat. */
@Mixin(value = CBCScreenShakeHandler.class, remap = false)
public abstract class CbcScreenShakeHandlerMixin {
    @Inject(method = "addEffect", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$suppressBoundSeatCannonShake(CallbackInfo ci) {
        if (ClientAimState.isSeatedAtBoundController()) ci.cancel();
    }
}
