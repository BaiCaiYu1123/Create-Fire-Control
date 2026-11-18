package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import com.simibubi.create.content.kinetics.RotationPropagator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Prevents Create's kinetic conflict handling from dropping a gas turbine
 * while its variable output ratios are being rebuilt.
 *
 * <p>This redirect is kept separate from the ratio mixin so the mixin plugin
 * can disable only the exclusive redirect when another mod owns the same
 * call sites. {@code require = 0} is intentional: redirects are exclusive,
 * and an unknown compatibility mod must never turn a recoverable conflict
 * into a game crash.</p>
 */
@Mixin(value = RotationPropagator.class, remap = false)
public abstract class GasTurbineDestroyProtectionMixin {
    @Redirect(
            method = "propagateNewSource",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z",
                    remap = true
            ),
            expect = 3,
            require = 0,
            remap = false
    )
    private static boolean stabilizedTurret$preserveVariableRatioTurbine(
            Level level, BlockPos pos, boolean drop
    ) {
        if (level.getBlockEntity(pos) instanceof GasTurbineBlockEntity) {
            return false;
        }
        return level.destroyBlock(pos, drop);
    }
}
