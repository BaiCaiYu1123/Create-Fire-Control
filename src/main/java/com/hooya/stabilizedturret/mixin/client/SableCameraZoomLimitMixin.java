package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.SableCameraZoomCompat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Direct fallback around Sable's mixin-added 0..4 zoom setter. */
@Mixin(value = Camera.class, priority = 900)
public abstract class SableCameraZoomLimitMixin {
    @Dynamic("Added to Camera by Sable's camera_zoom.CameraMixin")
    @Inject(
            method = "sable$setZoomAmount",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void stabilizedTurret$removeSableZoomAmountLimit(
            float requestedZoom,
            CallbackInfo ci
    ) {
        if (SableCameraZoomCompat.setZoomUnclamped(
                Minecraft.getInstance(),
                (Camera) (Object) this,
                requestedZoom
        )) {
            ci.cancel();
        }
    }
}
