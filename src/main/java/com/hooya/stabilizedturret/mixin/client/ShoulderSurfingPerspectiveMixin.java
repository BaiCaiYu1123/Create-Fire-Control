package com.hooya.stabilizedturret.mixin.client;

import dev.ryanhcode.sable.mixinhelpers.camera.new_camera_types.SableCameraTypes;
import net.minecraft.client.CameraType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fallback for Shoulder Surfing AERO builds whose Sable compatibility mixins
 * are skipped because the fork uses {@code shouldersurfingaero} as its mod id.
 */
@Pseudo
@Mixin(
        targets = "com.github.exopandora.shouldersurfing.api.model.Perspective",
        priority = 500,
        remap = false
)
public abstract class ShoulderSurfingPerspectiveMixin {
    @Unique
    private static Object stabilizedTurret$thirdPersonBack;

    /**
     * AERO's Java 21 switch covers only the three vanilla CameraType values.
     * Treat Sable's physical views as ordinary third person for AERO itself;
     * Sable and fire control remain the sole owners of the actual camera.
     */
    @Inject(method = "of", at = @At("HEAD"), cancellable = true, remap = false)
    private static void stabilizedTurret$mapSableCameraType(
            CameraType cameraType,
            boolean shoulderSurfing,
            CallbackInfoReturnable<Object> cir
    ) {
        if (cameraType != SableCameraTypes.SUB_LEVEL_VIEW
                && cameraType != SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED) {
            return;
        }
        Object thirdPersonBack = stabilizedTurret$thirdPersonBack;
        if (thirdPersonBack == null) {
            try {
                Class<?> perspective = Class.forName(
                        "com.github.exopandora.shouldersurfing.api.model.Perspective",
                        false,
                        ShoulderSurfingPerspectiveMixin.class.getClassLoader()
                );
                thirdPersonBack = perspective.getField("THIRD_PERSON_BACK").get(null);
                stabilizedTurret$thirdPersonBack = thirdPersonBack;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return;
            }
        }
        cir.setReturnValue(thirdPersonBack);
    }
}
