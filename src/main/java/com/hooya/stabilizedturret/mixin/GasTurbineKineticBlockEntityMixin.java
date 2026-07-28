package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents intentional gas-turbine speed ramping from tripping Create's
 * kinetic-network flicker failsafe.
 *
 * <p>Changing either output requires the turbine to propagate a new ratio.
 * Create normally counts those rebuilds as network flicker and destroys the
 * source after the score exceeds 128.  Capping only this block entity's
 * reported score keeps the normal decay behaviour and leaves real speed
 * conflicts, overspeed checks, and every other kinetic block untouched.</p>
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class GasTurbineKineticBlockEntityMixin {
    private static final int GAS_TURBINE_FLICKER_LIMIT = 120;

    @Inject(
            method = "getFlickerScore",
            at = @At("RETURN"),
            cancellable = true,
            remap = false
    )
    private void stabilizedTurret$capGasTurbineFlickerScore(
            CallbackInfoReturnable<Integer> callback
    ) {
        if ((Object) this instanceof GasTurbineBlockEntity) {
            callback.setReturnValue(Math.min(
                    callback.getReturnValue(),
                    GAS_TURBINE_FLICKER_LIMIT
            ));
        }
    }
}
