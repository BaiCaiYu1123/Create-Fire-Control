package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.merging_glue.MergingGlueBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Prevents Simulated's merging glue from deleting a fire-control bearing's
 * bound child body. Simulated normally disassembles the lighter body into the
 * heavier one; deleting the child invalidates the bearing and controller UUID.
 * Modifies comparison values after redirects so Let it Walk!'s walker
 * protection can supply the original mass values.
 */
@Mixin(value = MergingGlueBlockEntity.class, remap = false)
public abstract class SimulatedMergingGlueSafetyMixin {
    private static final double CREATE_FIRE_CONTROL$PREFERRED_MASS = Double.MAX_VALUE / 4.0;

    /** First comparison operand is the partner glue's containing body. */
    @ModifyExpressionValue(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/mass/MassData;getMass()D",
                    ordinal = 0
            )
    )
    private double createFireControl$preferBoundPartner(double mass) {
        boolean currentBound = createFireControl$isBoundContaining(
                (MergingGlueBlockEntity) (Object) this);
        MergingGlueBlockEntity partner =
                ((MergingGlueBlockEntity) (Object) this).getPartnerGlue();
        boolean partnerBound = createFireControl$isBoundContaining(partner);

        // If both sides are independently bound, preserve the controller
        // side deterministically. One physical body must still be merged into
        // the other, but this avoids making both operands artificially equal.
        return partnerBound && !currentBound
                ? Math.max(mass, CREATE_FIRE_CONTROL$PREFERRED_MASS)
                : mass;
    }

    /** Second comparison operand is the controller glue's containing body. */
    @ModifyExpressionValue(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/mass/MassData;getMass()D",
                    ordinal = 1
            )
    )
    private double createFireControl$preferBoundController(double mass) {
        return createFireControl$isBoundContaining((MergingGlueBlockEntity) (Object) this)
                ? Math.max(mass, CREATE_FIRE_CONTROL$PREFERRED_MASS)
                : mass;
    }

    private static boolean createFireControl$isBoundContaining(
            MergingGlueBlockEntity glue
    ) {
        if (glue == null) return false;
        SubLevel containing = Sable.HELPER.getContaining(glue);
        return containing instanceof ServerSubLevel body
                && BearingStabilizer.isBoundChild(body);
    }
}
