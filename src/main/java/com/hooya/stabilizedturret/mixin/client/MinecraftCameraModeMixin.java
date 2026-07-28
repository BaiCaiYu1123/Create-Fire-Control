package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import dev.ryanhcode.sable.mixinhelpers.camera.new_camera_types.SableCameraTypes;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bound gunners cycle only between first person and Sable's unlocked view. */
@Mixin(Minecraft.class)
public abstract class MinecraftCameraModeMixin {
    @Inject(method = "handleKeybinds", at = @At("TAIL"))
    private void stabilizedTurret$restrictBoundGunnerCameraModes(CallbackInfo ci) {
        if (!ClientAimState.isActive()) {
            return;
        }
        Minecraft minecraft = (Minecraft) (Object) this;
        CameraType current = minecraft.options.getCameraType();
        if (ClientAimState.isOpticalView()) {
            minecraft.options.hideGui = false;
            if (current != CameraType.FIRST_PERSON) {
                if (!ClientAimState.isPodView()) {
                    ClientAimState.restoreObservedWorldLook(minecraft.player);
                }
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            }
            return;
        }
        if (ClientAimState.isRemoteControl()) {
            return;
        }
        if (ClientAimState.isAllowedCameraType(current)) {
            return;
        }

        ClientAimState.restoreObservedWorldLook(minecraft.player);
        if (current == CameraType.THIRD_PERSON_BACK || current == SableCameraTypes.SUB_LEVEL_VIEW) {
            minecraft.options.setCameraType(SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED);
        } else {
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        }
    }
}
