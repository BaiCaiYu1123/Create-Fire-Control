package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import dev.ryanhcode.sable.mixinhelpers.camera.new_camera_types.SableCameraTypes;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Owns camera-type changes while the local player is using a bound fire-
 * control seat and prevents camera mods from intercepting Sable camera types.
 *
 * <p>ShoulderSurfing's {@code OptionsMixin} HEAD-injects {@code setCameraType}
 * and calls {@code shouldersurfing.Perspective.of(CameraType, boolean)}, whose
 * Java 21 switch expression only covers the three vanilla CameraType entries.
 * Receiving a Sable CameraType throws {@code MatchException} and crashes the
 * client.</p>
 *
 * <p>This mixin runs at HEAD with a higher priority than ShoulderSurfing's
 * mixin, so it gets the first chance to handle the call. When the incoming
 * CameraType is one of Sable's custom values we write it directly into the
 * {@code cameraType} field and cancel the call. Cancelling only short-circuits
 * the method body; it does <em>not</em> prevent Sable's own
 * {@code sable$preCycleCameraType} / {@code sable$postCycleCameraType} hooks
 * (which are {@code INVOKE} BEFORE/AFTER injections in
 * {@code Minecraft.handleKeybinds}) from running, so Sable's local-to-world
 * and world-to-local look conversion continues to work and dual-axis
 * stabilisation stays intact.</p>
 *
 * <p>The mixin is always loaded so the bound-seat guard applies equally to
 * other perspective mods. Outside a guarded seat it only special-cases Sable
 * custom values.</p>
 */
@Mixin(value = Options.class, priority = 500)
public abstract class OptionsSetCameraTypeMixin {
    @Shadow
    private CameraType cameraType;

    @Inject(method = "setCameraType", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$bypassShoulderSurfingForSableCameraType(
            CameraType cameraType, CallbackInfo ci) {
        boolean sableType = cameraType == SableCameraTypes.SUB_LEVEL_VIEW
                || cameraType == SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED;
        // While seated at a bound fire-control computer, no third-party
        // perspective implementation may own the camera cycle. Translate all
        // requested values into one of the two fire-control-supported modes
        // and return before lower-priority camera-mod callbacks can run.
        boolean guardedSeat = ClientAimState.isSeatedAtBoundController();
        if (guardedSeat) {
            if (cameraType == CameraType.THIRD_PERSON_BACK
                    || cameraType == SableCameraTypes.SUB_LEVEL_VIEW) {
                this.cameraType = SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED;
            } else if (cameraType == SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED) {
                this.cameraType = cameraType;
            } else {
                this.cameraType = CameraType.FIRST_PERSON;
            }
            ci.cancel();
            return;
        }
        if (sableType) {
            // Write the field directly, matching what ShoulderSurfing's own
            // OptionsDuck.shouldersurfing$setCameraTypeDirect would do.
            this.cameraType = cameraType;
            ci.cancel();
        }
    }
}
