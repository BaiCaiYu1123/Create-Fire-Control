package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.turbine.GasTurbineBlock;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the gas turbine's two variable-ratio outputs reciprocal.
 *
 * <p>Create normally asks only the source block entity for a custom ratio.
 * When propagation later approaches the turbine from the attached shaft, the
 * ordinary shaft ratio of {@code 1} is used instead.  That asymmetry makes a
 * reverse or differential output look like two incompatible generators and
 * Create resolves the conflict by breaking a block.  Returning the inverse
 * ratio on the way back keeps both traversal directions mathematically
 * consistent while still allowing either side to stop independently.</p>
 */
@Mixin(value = RotationPropagator.class, remap = false)
public abstract class GasTurbineRotationPropagatorMixin {
    @Inject(
            method = "getRotationSpeedModifier",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void stabilizedTurret$gasTurbineOutputRatio(
            KineticBlockEntity from,
            KineticBlockEntity to,
            CallbackInfoReturnable<Float> callback
    ) {
        if (from instanceof GasTurbineBlockEntity turbine) {
            Direction output = directOutputDirection(turbine, to);
            if (output != null) {
                callback.setReturnValue(turbine.getOutputModifier(output));
            }
            return;
        }
        if (to instanceof GasTurbineBlockEntity turbine) {
            Direction output = directOutputDirection(turbine, from);
            if (output == null) return;
            float modifier = turbine.getOutputModifier(output);
            callback.setReturnValue(Math.abs(modifier) < 0.0001F
                    ? 0.0F : 1.0F / modifier);
        }
    }

    private static Direction directOutputDirection(
            GasTurbineBlockEntity turbine,
            KineticBlockEntity neighbour
    ) {
        BlockPos offset = neighbour.getBlockPos().subtract(turbine.getBlockPos());
        if (offset.distManhattan(BlockPos.ZERO) != 1) return null;
        Direction direction = Direction.getNearest(
                offset.getX(), offset.getY(), offset.getZ());
        return direction.getAxis() == GasTurbineBlock.outputAxis(
                turbine.getBlockState()) ? direction : null;
    }
}
