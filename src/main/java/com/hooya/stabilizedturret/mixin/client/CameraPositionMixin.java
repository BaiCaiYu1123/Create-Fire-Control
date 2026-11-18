package com.hooya.stabilizedturret.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.ClientWeaponViewState;
import com.hooya.stabilizedturret.client.SableCameraZoomCompat;
import dev.ryanhcode.sable.mixinhelpers.camera.new_camera_types.SableCameraTypes;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Collision-free orbit position and sole fire-control camera-height writer.
 *
 * <p>The aim snapshot is captured after the complete setup method returns.
 * TAIL callbacks from separate mixins can run after another TAIL callback;
 * priority alone does not make that callback a final camera snapshot.</p>
 */
@Mixin(value = Camera.class, priority = 400)
public abstract class CameraPositionMixin {
    @Shadow private org.joml.Quaternionf rotation;
    @Shadow private Vector3f forwards;
    @Shadow private Vector3f up;
    @Shadow private Vector3f left;
    @Shadow private float yRot;
    @Shadow private float xRot;
    @Shadow private float roll;
    @Shadow private boolean detached;
    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Unique
    private Vec3 stabilizedTurret$unclippedAnchor;

    @Unique
    private boolean stabilizedTurret$restoreCollisionFreePosition;

    @WrapMethod(method = "setup")
    private void stabilizedTurret$captureCompletedAimFrame(
            BlockGetter level,
            Entity cameraEntity,
            boolean detached,
            boolean mirrored,
            float partialTick,
            Operation<Void> original
    ) {
        try {
            original.call(level,cameraEntity,detached,mirrored,partialTick);
            if (ClientWeaponViewState.isActive()) {
                // Apply after every setup writer, without changing the player's
                // camera entity, rotation, position, or server chunk subscriptions.
                yRot = ClientWeaponViewState.yaw();
                xRot = ClientWeaponViewState.pitch();
                roll = 0.0F;
                rotation.rotationYXZ((float) Math.PI - yRot * net.minecraft.util.Mth.DEG_TO_RAD,
                        -xRot * net.minecraft.util.Mth.DEG_TO_RAD, 0);
                forwards.set(0, 0, -1).rotate(rotation);
                up.set(0, 1, 0).rotate(rotation);
                left.set(-1, 0, 0).rotate(rotation);
                this.detached = true;
                setPosition(ClientWeaponViewState.cameraPosition(partialTick));
                return;
            }
            ClientAimState.captureThirdPersonAimCamera((Camera)(Object)this,cameraEntity,
                    partialTick);
        } finally {
            stabilizedTurret$unclippedAnchor=null;
            stabilizedTurret$restoreCollisionFreePosition=false;
        }
    }

    @Inject(method = "setup", at = @At("HEAD"))
    private void stabilizedTurret$resetCollisionFreeCapture(
            BlockGetter level,
            Entity cameraEntity,
            boolean detached,
            boolean mirrored,
            float partialTick,
            CallbackInfo ci
    ) {
        stabilizedTurret$unclippedAnchor = null;
        stabilizedTurret$restoreCollisionFreePosition = false;
    }

    @Inject(
            method = "setup",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F",
                    shift = At.Shift.BEFORE
            ),
            require = 1
    )
    private void stabilizedTurret$captureUnclippedAnchor(
            BlockGetter level,
            Entity cameraEntity,
            boolean detached,
            boolean mirrored,
            float partialTick,
            CallbackInfo ci
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!detached
                || !SableCameraZoomCompat.isEligible(minecraft)
                || ClientAimState.isOpticalView()) {
            return;
        }

        stabilizedTurret$unclippedAnchor =
                ((Camera) (Object) this).getPosition();
        stabilizedTurret$restoreCollisionFreePosition = true;
    }

    @Inject(method = "setup", at = @At("TAIL"), require = 1)
    private void stabilizedTurret$applyCollisionFreePositionLast(
            BlockGetter level,
            Entity cameraEntity,
            boolean detached,
            boolean mirrored,
            float partialTick,
            CallbackInfo ci
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = (Camera) (Object) this;
        if (stabilizedTurret$restoreCollisionFreePosition
                && stabilizedTurret$unclippedAnchor != null
                && SableCameraZoomCompat.isEligible(minecraft)
                && !ClientAimState.isOpticalView()) {
            float distance = SableCameraZoomCompat.desiredDistance(
                    minecraft, camera, 4.0F, partialTick);
            Vector3f look = camera.getLookVector();
            Vec3 finalPosition = stabilizedTurret$unclippedAnchor.add(
                    -look.x * distance,
                    -look.y * distance + ClientAimState.getThirdPersonHeight(),
                    -look.z * distance
            );
            setPosition(finalPosition);
        } else if (!ClientAimState.isOpticalView() && ClientAimState.isActive()
                && minecraft.options.getCameraType()==SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED
                && ClientAimState.getThirdPersonHeight()!=0.0) {
            // Preserve the unlocked-camera height during seat synchronization,
            // when a collision-free orbit anchor is not available yet.
            setPosition(camera.getPosition().add(0.0,ClientAimState.getThirdPersonHeight(),0.0));
        }
    }
}
