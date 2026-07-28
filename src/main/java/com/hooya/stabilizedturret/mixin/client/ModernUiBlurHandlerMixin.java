package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.StabilizerControllerScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents ModernUI's screen-change hook from blurring the fire-control UI. */
@Pseudo
@Mixin(targets = "icyllis.modernui.mc.BlurHandler", remap = false)
public abstract class ModernUiBlurHandlerMixin {
    @Inject(method = "blur", at = @At("HEAD"), cancellable = true, require = 0)
    private void stabilizedTurret$keepFireControlBackgroundSharp(Screen screen, CallbackInfo ci) {
        if (screen instanceof StabilizerControllerScreen) {
            ci.cancel();
        }
    }
}
