package com.hooya.stabilizedturret.mixin;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;
import net.minecraft.world.phys.HitResult;
@Mixin(value=AbstractCannonProjectile.class,remap=false)
public interface PolyArmorProjectileAccessor {
    @Invoker("getForces") Vec3 fireControl$forces(Vec3 position,Vec3 velocity);
    @Invoker("getBallisticProperties") BallisticPropertiesComponent fireControl$ballistics();
    @Invoker("canHitSurface") boolean fireControl$canHitSurface();
    @Invoker("onImpact") boolean fireControl$onImpact(HitResult hit,AbstractCannonProjectile.ImpactResult result,ProjectileContext context);
}
