package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import com.hooya.stabilizedturret.content.bearing.SableServoConstraints;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.object.rope.RopeHandle;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class RapierServoPlanningMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "addConstraint", at = @At("RETURN"))
    private void createFireControl$rememberConstraint(PhysicsPipelineBody parent, PhysicsPipelineBody child,
            PhysicsConstraintConfiguration<?> configuration, CallbackInfoReturnable<PhysicsConstraintHandle> cir) {
        SableServoConstraints.created(level, parent, child, configuration, cir.getReturnValue());
    }

    @Inject(method = "remove(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V", at = @At("HEAD"))
    private void createFireControl$checkAutomaticJointRemoval(ServerSubLevel body, CallbackInfo ci) {
        SableServoConstraints.bodyRemoving(level, body);
    }

    @Inject(method = "addRope", at = @At("HEAD"))
    private void createFireControl$checkInternalJoints(RopePhysicsObject rope,
                                                      CallbackInfoReturnable<RopeHandle> cir) {
        SableServoConstraints.internalJointsCreated(level);
    }

    @Inject(method = "physicsTick", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;step(JD)V"))
    private void createFireControl$planTogether(double dt, CallbackInfo ci) {
        BearingStabilizer.planArticulatedServos(SubLevelPhysicsSystem.get(level), dt);
    }

    @Inject(method = "dispose", at = @At("HEAD"))
    private void createFireControl$forgetLevel(CallbackInfo ci) {
        SableServoConstraints.clear(level);
    }
}
