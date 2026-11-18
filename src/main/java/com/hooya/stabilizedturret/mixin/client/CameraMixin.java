package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.ClientTelevisionGuidanceState;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
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

@Mixin(value = Camera.class, priority = 500)
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

    @Inject(method = "setup", at = @At("TAIL"))
    private void stabilizedTurret$useBoundScopePosition(
            net.minecraft.world.level.BlockGetter level,
            Entity cameraEntity,
            boolean detached,
            boolean mirrored,
            float partialTick,
            CallbackInfo ci
    ) {
        boolean fireControlRebuiltBase = false;
        if (ClientTelevisionGuidanceState.hasDirection()) {
            // The server remains authoritative for the projectile, while the
            // camera consumes the same virtual direction immediately. This
            // avoids a visibly locked camera between 20 Hz missile updates.
            float yaw = ClientTelevisionGuidanceState.getCameraYaw();
            float pitch = ClientTelevisionGuidanceState.getCameraPitch();
            rotation.rotationYXZ(
                    (float) Math.PI - yaw * Mth.DEG_TO_RAD,
                    -pitch * Mth.DEG_TO_RAD,
                    0.0F
            );
            forwards.set(0.0F, 0.0F, -1.0F).rotate(rotation);
            up.set(0.0F, 1.0F, 0.0F).rotate(rotation);
            left.set(-1.0F, 0.0F, 0.0F).rotate(rotation);
            yRot = yaw;
            xRot = pitch;
            fireControlRebuiltBase = true;
        } else if (ClientAimState.isOpticalView()) {
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
            fireControlRebuiltBase = true;
        } else if (ClientAimState.shouldUseHeadAimThirdPersonFollow()
                && cameraEntity instanceof LocalPlayer) {
            // The mouse target and camera are separate. The follower advances
            // once in RenderFrameEvent.Pre; Camera.setup can run repeatedly in
            // one frame and therefore only consumes that immutable result.
            // Off-frame compatibility callers may initialize, but never
            // advance, the stored camera from Sable's final world angles.
            ClientAimState.ensureHeadAimThirdPersonCamera(yRot, xRot);
            float yaw = ClientAimState.getHeadAimThirdPersonCameraYaw();
            float pitch = ClientAimState.getHeadAimThirdPersonCameraPitch();
            rotation.rotationYXZ(
                    (float) Math.PI - yaw * Mth.DEG_TO_RAD,
                    -pitch * Mth.DEG_TO_RAD,
                    0.0F
            );
            forwards.set(0.0F, 0.0F, -1.0F).rotate(rotation);
            up.set(0.0F, 1.0F, 0.0F).rotate(rotation);
            left.set(-1.0F, 0.0F, 0.0F).rotate(rotation);
            yRot = yaw;
            xRot = pitch;
            fireControlRebuiltBase = true;
        }

        // Free look is the final orientation layer. It never writes the
        // player's stable aim or the pod gimbal, and therefore both world aim
        // points stay fixed while this camera is allowed to look away.
        if (cameraEntity instanceof LocalPlayer
                && ClientAimState.prepareFreeLookCamera(
                new Quaternionf(rotation), fireControlRebuiltBase)) {
            rotation.set(ClientAimState.getFreeLookCameraRotation());
            forwards.set(0.0F, 0.0F, -1.0F).rotate(rotation);
            up.set(0.0F, 1.0F, 0.0F).rotate(rotation);
            left.set(-1.0F, 0.0F, 0.0F).rotate(rotation);
            double horizontal = Math.sqrt(
                    forwards.x * forwards.x + forwards.z * forwards.z);
            yRot = Mth.wrapDegrees((float) Math.toDegrees(
                    Math.atan2(-forwards.x, forwards.z)));
            xRot = Mth.clamp((float) Math.toDegrees(
                    Math.atan2(-forwards.y, horizontal)), -90.0F, 90.0F);
        }
    }
}
