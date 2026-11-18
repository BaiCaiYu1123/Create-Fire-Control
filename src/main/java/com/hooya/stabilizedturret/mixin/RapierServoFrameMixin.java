package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.bearing.SableServoConstraints;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.physics.impl.rapier.constraint.generic.RapierGenericConstraintHandle;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Copies successful native generic-joint mutations without changing the joint. */
@Mixin(value = RapierGenericConstraintHandle.class, remap = false)
public abstract class RapierServoFrameMixin {
    @Inject(method = "setFrame1", at = @At("RETURN"))
    private void createFireControl$rememberFirstFrame(Vector3dc position, Quaterniondc orientation,
                                                     CallbackInfo ci) {
        SableServoConstraints.frame(this, true, position, orientation);
    }

    @Inject(method = "setFrame2", at = @At("RETURN"))
    private void createFireControl$rememberSecondFrame(Vector3dc position, Quaterniondc orientation,
                                                      CallbackInfo ci) {
        SableServoConstraints.frame(this, false, position, orientation);
    }

    @Inject(method = "lockAxes", at = @At("RETURN"))
    private void createFireControl$rememberLockedAxes(ConstraintJointAxis[] axes, CallbackInfo ci) {
        SableServoConstraints.lockedAxes(this, axes);
    }

    @Inject(method = "setLimit", at = @At("RETURN"))
    private void createFireControl$rememberLimit(ConstraintJointAxis axis, double min, double max,
                                                CallbackInfo ci) {
        SableServoConstraints.limit(this, axis, min, max);
    }
}
