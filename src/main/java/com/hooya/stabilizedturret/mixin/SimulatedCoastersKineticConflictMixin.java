package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Composes the turbine guard with Simulated Coasters' chain-lift guard.
 *
 * <p>Simulated Coasters redirects all three kinetic-conflict destroy calls,
 * so attempting a second redirect is an exclusive-injector conflict. Hooking
 * its shared handler preserves both protections without competing for the
 * original call sites.</p>
 */
@Pseudo
@Mixin(
        targets = "dev.silvergold.simulatedcoasters.track.chainlift.ChainLiftKineticConflict",
        remap = false
)
public abstract class SimulatedCoastersKineticConflictMixin {
    @Inject(
            method = "redirectDestroyAwayFromAnchor",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            remap = false
    )
    private static void stabilizedTurret$preserveVariableRatioTurbine(
            Level level,
            BlockPos pos,
            boolean drop,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (level.getBlockEntity(pos) instanceof GasTurbineBlockEntity) {
            callback.setReturnValue(false);
        }
    }
}
