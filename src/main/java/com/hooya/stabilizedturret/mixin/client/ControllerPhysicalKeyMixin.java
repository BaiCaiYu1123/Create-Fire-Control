package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.remote.WirelessControllerClient;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InputConstants.class)
public abstract class ControllerPhysicalKeyMixin {
    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void fireControl$ownedKey(long window, int code, CallbackInfoReturnable<Boolean> cir) {
        if (WirelessControllerClient.blocks(false, code)) cir.setReturnValue(false);
    }
}
