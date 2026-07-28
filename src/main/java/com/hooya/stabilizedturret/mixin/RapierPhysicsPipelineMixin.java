package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Omits CBC/Create cannon voxels from Rapier while their pitch axis is bound. */
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class RapierPhysicsPipelineMixin {
    @Redirect(
            method = "add(Ldev/ryanhcode/sable/api/sublevel/KinematicContraption;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/sublevel/KinematicContraption;sable$shouldCollide()Z"
            )
    )
    private boolean stabilizedTurret$disableBoundCannonCollision(KinematicContraption contraption) {
        return contraption.sable$shouldCollide()
                && !BearingStabilizer.shouldDisableContraptionCollision(contraption);
    }
}
