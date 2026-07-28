package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent Sable's normal local-to-world dismount conversion from applying twice. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @Unique
    private Vec3 stabilizedTurret$savedDismountLook;

    @Inject(method = "removeVehicle", at = @At("HEAD"))
    private void stabilizedTurret$captureWorldLookBeforeDismount(CallbackInfo ci) {
        if (ClientAimState.shouldSuppressSableCameraRotation()) {
            stabilizedTurret$savedDismountLook = ((LocalPlayer) (Object) this).getLookAngle();
        }
    }

    @Inject(method = "removeVehicle", at = @At("TAIL"))
    private void stabilizedTurret$restoreWorldLookAfterDismount(CallbackInfo ci) {
        if (stabilizedTurret$savedDismountLook != null) {
            ClientAimState.applyWorldLook((LocalPlayer) (Object) this, stabilizedTurret$savedDismountLook);
            stabilizedTurret$savedDismountLook = null;
        }
    }
}
