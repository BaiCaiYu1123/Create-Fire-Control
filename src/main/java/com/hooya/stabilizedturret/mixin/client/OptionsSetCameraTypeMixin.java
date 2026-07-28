package com.hooya.stabilizedturret.mixin.client;

import dev.ryanhcode.sable.mixinhelpers.camera.new_camera_types.SableCameraTypes;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents ShoulderSurfing from intercepting {@link Options#setCameraType}
 * calls that carry Sable's custom CameraType values.
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
 * <p>Loaded only when ShoulderSurfing is present (see
 * {@code StabilizedTurretMixinPlugin#shouldApplyMixin}).</p>
 */
@Mixin(value = Options.class, priority = 500)
public abstract class OptionsSetCameraTypeMixin {
    @Shadow
    private CameraType cameraType;

    @Inject(method = "setCameraType", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$bypassShoulderSurfingForSableCameraType(
            CameraType cameraType, CallbackInfo ci) {
        if (cameraType == SableCameraTypes.SUB_LEVEL_VIEW
                || cameraType == SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED) {
            // Write the field directly, matching what ShoulderSurfing's own
            // OptionsDuck.shouldersurfing$setCameraTypeDirect would do.
            this.cameraType = cameraType;
            ci.cancel();
        }
    }
}
