package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.remote.WirelessControllerClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MouseHandler.class, priority = 3000)
public abstract class ControllerMouseInputMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void fireControl$remoteScroll(long window,double horizontal,double vertical,CallbackInfo ci) {
        if(WirelessControllerClient.scroll(window,vertical))ci.cancel();
    }
    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void fireControl$remoteMouse(long window, int button, int action, int modifiers, CallbackInfo ci) {
        if (WirelessControllerClient.input(window, true, button, 0, action)) ci.cancel();
    }
}
