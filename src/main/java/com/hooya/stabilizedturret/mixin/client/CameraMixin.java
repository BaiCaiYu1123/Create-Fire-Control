package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.SableCameraZoomCompat;
import net.minecraft.client.Camera;
import dev.ryanhcode.sable.mixinhelpers.camera.new_camera_types.SableCameraTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    private Quaternionf rotation;

    @Shadow
    private Vector3f forwards;

    @Shadow
    private Vector3f up;

    @Shadow
    private Vector3f left;

    @Shadow
    private float yRot;

    @Shadow
    private float xRot;

    @Inject(method = "setup", at = @At("HEAD"))
    private void stabilizedTurret$resetExtendedZoomOutsideBoundSeat(
            net.minecraft.world.level.BlockGetter level,
            Entity cameraEntity,
            boolean detached,
            boolean mirrored,
            float partialTick,
            CallbackInfo ci
    ) {
        SableCameraZoomCompat.resetExtendedZoomWhenIneligible(
                Minecraft.getInstance(), (Camera) (Object) this);
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void stabilizedTurret$useBoundScopePosition(
            net.minecraft.world.level.BlockGetter level,
            Entity cameraEntity,
            boolean detached,
            boolean mirrored,
            float partialTick,
            CallbackInfo ci
    ) {
        if (ClientAimState.isOpticalView()) {
            Vec3 opticalPosition = ClientAimState.getScopePosition(partialTick);
            setPosition(opticalPosition);
            if (ClientAimState.isPodView()) {
                ClientAimState.recordPodRenderedCameraOrigin(opticalPosition);
            }
            // Setting only the position leaves Sable free to keep the camera
            // basis inherited from the seat/parent sub-level. Vestalihy's
            // working scope replaces the full camera basis at setup TAIL.
            float yaw = ClientAimState.getStableWorldYaw(partialTick);
            float pitch = ClientAimState.getStableWorldPitch(partialTick);
            if (ClientAimState.isPodView()) {
                // The pod can traverse through the nadir and beyond 180°.
                // A yaw/pitch pair loses roll at that singularity, so use the
                // complete gyro-stabilized camera basis retained by the pod.
                rotation.set(ClientAimState.getPodCameraRotation(partialTick));
            } else {
                rotation.rotationYXZ(
                        (float) Math.PI - yaw * Mth.DEG_TO_RAD,
                        -pitch * Mth.DEG_TO_RAD,
                        0.0F
                );
            }
            forwards.set(0.0F, 0.0F, -1.0F).rotate(rotation);
            up.set(0.0F, 1.0F, 0.0F).rotate(rotation);
            left.set(-1.0F, 0.0F, 0.0F).rotate(rotation);
            yRot = yaw;
            xRot = pitch;
        } else if (ClientAimState.isActive()
                && Minecraft.getInstance().options.getCameraType() == SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED
                && ClientAimState.getThirdPersonHeight() != 0.0) {
            setPosition(((Camera) (Object) this).getPosition().add(
                    0.0,
                    ClientAimState.getThirdPersonHeight(),
                    0.0
            ));
        }
    }
}
