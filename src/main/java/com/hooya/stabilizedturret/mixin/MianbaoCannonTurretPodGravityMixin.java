package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoCannonTurretPodCompat;
import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gives only fire-control cannon-pod shells the requested slightly flatter arc. */
@Mixin(AbstractArrow.class)
public abstract class MianbaoCannonTurretPodGravityMixin {
    @Inject(method = "getDefaultGravity", at = @At("RETURN"), cancellable = true)
    private void createFireControl$cannonPodGravity(
            CallbackInfoReturnable<Double> callback
    ) {
        AbstractArrow arrow = (AbstractArrow) (Object) this;
        if (arrow.getPersistentData().getBoolean(MianbaoCannonTurretPodCompat.PROJECTILE_TAG)) {
            callback.setReturnValue(MianbaoCannonTurretPodCompat.GRAVITY_PER_TICK);
        }
    }
}
