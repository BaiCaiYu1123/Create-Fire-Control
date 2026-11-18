package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps a fire-control hinge in the bearing's native lifetime and handle slot. */
@Mixin(value = SwivelBearingBlockEntity.class, remap = false)
public abstract class SwivelBearingMotorMixin {
    @Inject(method = "updateServoCoefficients", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$preserveBoundServo(CallbackInfo ci) {
        if (BearingStabilizer.ownsJoint((SwivelBearingBlockEntity) (Object) this)) ci.cancel();
    }

    @Inject(method = "updateServoCoefficients", at = @At("TAIL"))
    private void stabilizedTurret$restoreLegacyLock(CallbackInfo ci) {
        BearingStabilizer.suppressMotor((SwivelBearingBlockEntity)(Object)this);
    }

    @Inject(method = "reattachConstraint", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$preserveBoundJoint(
            dev.ryanhcode.sable.sublevel.ServerSubLevel child, boolean updatePlate, CallbackInfo ci) {
        SwivelBearingBlockEntity bearing = (SwivelBearingBlockEntity) (Object) this;
        if (BearingStabilizer.preserveOwnedJoint(bearing, child)) {
            if (updatePlate) bearing.associatePlateWithParent();
            ci.cancel();
        }
    }

    @Inject(method = "assemble", at = @At("TAIL"))
    private void stabilizedTurret$captureAssemblyZero(CallbackInfo ci) {
        BearingStabilizer.captureAssemblyZero(
                (SwivelBearingBlockEntity) (Object) this);
    }

    @Inject(method = "disassemble", at = @At("HEAD"))
    private void stabilizedTurret$forgetAssemblyZero(CallbackInfo ci) {
        BearingStabilizer.forgetAssemblyZero(
                (SwivelBearingBlockEntity) (Object) this);
    }
}
