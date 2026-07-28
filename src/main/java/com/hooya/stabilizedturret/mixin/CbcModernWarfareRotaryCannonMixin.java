package com.hooya.stabilizedturret.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Places CBC Modern Warfare rotary-cannon projectiles at the actual muzzle.
 *
 * <p>0.0.6v adds a 0.75-block forward clearance before publishing the
 * projectile. On a rendered multi-barrel cannon this leaves a visible
 * one-block gap between the muzzle and the first projectile/tracer frame.</p>
 */
@Mixin(
        targets = "riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedRotarycannonContraption",
        remap = false
)
public abstract class CbcModernWarfareRotaryCannonMixin {
    @ModifyConstant(
            method = "fireShot",
            constant = @Constant(doubleValue = 0.75D),
            require = 0
    )
    private double stabilizedTurret$spawnAtMuzzle(
            double original) {
        return 0.0D;
    }
}
