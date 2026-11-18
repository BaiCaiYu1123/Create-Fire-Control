package com.hooya.stabilizedturret.mixin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Preserve impact events, deflection and native warheads, without executing a flight tick. */
@Mixin(Projectile.class)
public interface MathematicalMissileImpactAccess {
    @Invoker("hitTargetOrDeflectSelf") ProjectileDeflection createFireControl$impact(HitResult hit);
    @Invoker("canHitEntity") boolean createFireControl$canHit(Entity entity);
}
