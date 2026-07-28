package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Restores the fire-control servo after Simulated updates the same joint. */
@Mixin(value = SwivelBearingBlockEntity.class, remap = false)
public abstract class SwivelBearingMotorMixin {
    @Inject(method = "updateServoCoefficients", at = @At("TAIL"))
    private void stabilizedTurret$restoreBoundServo(CallbackInfo ci) {
        BearingStabilizer.suppressMotor((SwivelBearingBlockEntity) (Object) this);
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
