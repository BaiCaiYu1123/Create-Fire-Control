package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import dev.ryanhcode.sable.mixinhelpers.camera.camera_rotation.EntitySubLevelRotationHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * First-person head aim is expressed in world space. Sable's unlocked
 * sub-level camera type already performs its own suppression and is left
 * completely untouched here.
 */
@Mixin(value = EntitySubLevelRotationHelper.class, remap = false)
public abstract class EntitySubLevelRotationHelperMixin {
    @Inject(method = "shouldCameraRotate", at = @At("HEAD"), cancellable = true)
    private static void stabilizedTurret$keepGunnerViewWorldStable(CallbackInfoReturnable<Boolean> cir) {
        if (ClientAimState.shouldSuppressSableCameraRotation()) {
            cir.setReturnValue(false);
        }
    }
}
