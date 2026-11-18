package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.bearing.SableServoConstraints;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.physics.impl.rapier.constraint.RapierConstraintHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RapierConstraintHandle.class, remap = false)
public abstract class RapierServoConstraintMixin {
    @Inject(method = "setMotor", at = @At("RETURN"))
    private void createFireControl$rememberMotor(ConstraintJointAxis axis, double target, double stiffness,
                                                double damping, boolean limited, double cap, CallbackInfo ci) {
        SableServoConstraints.motor(this, axis, target, stiffness, damping, limited, cap);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void createFireControl$forgetConstraint(CallbackInfo ci) {
        SableServoConstraints.removed(this);
    }
}
