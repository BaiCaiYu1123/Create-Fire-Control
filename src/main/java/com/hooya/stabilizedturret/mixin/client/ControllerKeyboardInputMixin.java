package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.remote.WirelessControllerClient;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KeyboardHandler.class, priority = 3000)
public abstract class ControllerKeyboardInputMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void fireControl$remoteKey(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) {
        if (WirelessControllerClient.input(window, false, key, scanCode, action)) ci.cancel();
    }
}
