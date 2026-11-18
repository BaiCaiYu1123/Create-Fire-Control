package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.SableCameraZoomCompat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Removes the orbit distance/collision clamp from both Sable camera modes. */
// For cancellable HEAD injectors, applying after Sable places this callback
// before Sable's already-merged callback in the transformed method.
@Mixin(value = Camera.class, priority = 900)
public abstract class CameraCollisionMixin {
    /**
     * Sable cancels {@code Camera#getMaxZoom} from its own HEAD injector and
     * calls {@code sable$clampZoom}, so a RETURN injector cannot reliably
     * override the clipped distance. Run before Sable and return the desired
     * distance directly; no vanilla or Sable camera ray is evaluated.
     */
    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$disableSableContraptionCameraCollision(
            float requestedDistance,
            CallbackInfoReturnable<Float> cir
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!SableCameraZoomCompat.isEligible(minecraft)) return;

        float partialTick = minecraft.getTimer()
                .getGameTimeDeltaPartialTick(true);
        cir.setReturnValue(SableCameraZoomCompat.desiredDistance(
                minecraft,
                (Camera) (Object) this,
                requestedDistance,
                partialTick));
    }
}
