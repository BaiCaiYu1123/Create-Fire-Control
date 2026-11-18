package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.loader.MzLoaderDetonation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;

/** Detonates a loaded MZ rack before any native CBC impact path can remove its block. */
@Mixin(value = AbstractCannonProjectile.class, remap = false)
public abstract class CbcProjectileMzLoaderDetonationMixin {
    @Inject(method = "onImpact", at = @At("HEAD"))
    private void stabilizedTurret$detonateLoadedMzRack(
            HitResult hit,
            AbstractCannonProjectile.ImpactResult impactResult,
            ProjectileContext context,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (hit instanceof BlockHitResult blockHit) {
            AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
            MzLoaderDetonation.tryDetonate(projectile.level(), blockHit.getBlockPos());
        }
    }
}
