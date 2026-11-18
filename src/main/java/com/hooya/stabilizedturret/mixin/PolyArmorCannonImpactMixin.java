package com.hooya.stabilizedturret.mixin;

import com.caca.copypoly.PolyBlock;
import com.hooya.stabilizedturret.content.armor.ArmorRicochet;
import com.hooya.stabilizedturret.content.armor.EquivalentArmorStopAccess;
import com.hooya.stabilizedturret.content.armor.PolyArmorImpacts;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import rbasamoyai.createbigcannons.munitions.*;

/** The virtual call also covers addon projectiles overriding CBC's stock impact methods. */
@Mixin(value=AbstractCannonProjectile.class,remap=false)
public abstract class PolyArmorCannonImpactMixin implements ArmorRicochet.ClientPrediction {
    @Shadow protected Vec3 nextVelocity;
    @Shadow protected boolean removeNextTick;
    @Unique private BlockHitResult fireControl$ricochetContact;

    @WrapOperation(method="clipAndDamage",at=@At(value="INVOKE",target="Lrbasamoyai/createbigcannons/munitions/AbstractCannonProjectile;calculateBlockPenetration(Lrbasamoyai/createbigcannons/munitions/ProjectileContext;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/BlockHitResult;)Lrbasamoyai/createbigcannons/munitions/AbstractCannonProjectile$ImpactResult;"))
    private AbstractCannonProjectile.ImpactResult fireControl$anyCannonRound(AbstractCannonProjectile projectile,ProjectileContext context,BlockState state,BlockHitResult hit,Operation<AbstractCannonProjectile.ImpactResult> original){
        var result = PolyArmorImpacts.calculate(projectile, context, state, hit,
                () -> original.call(projectile, context, state, hit));
        if (state.getBlock() instanceof PolyBlock && !result.shouldRemove()
                && result.kinematics() == AbstractCannonProjectile.ImpactResult.KinematicOutcome.BOUNCE) {
            fireControl$ricochetContact = hit;
        }
        return result;
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lrbasamoyai/createbigcannons/munitions/AbstractCannonProjectile;setPos(Lnet/minecraft/world/phys/Vec3;)V",
            ordinal = 0))
    private void fireControl$departRicochet(AbstractCannonProjectile projectile, Vec3 position, Operation<Void> original) {
        var hit = fireControl$ricochetContact;
        fireControl$ricochetContact = null;
        if (hit == null || nextVelocity == null || nextVelocity.lengthSqr() < 1e-4) {
            original.call(projectile, position);
            return;
        }
        // CBC's incoming movement has already been clipped. Publish the outward
        // position and reflected velocity together before projectile tracking runs.
        original.call(projectile, ArmorRicochet.departure(projectile.level(), hit));
        projectile.setDeltaMovement(nextVelocity);
        projectile.setOrientation(nextVelocity);
    }

    @Override
    public void fireControl$clearPredictedImpact() {
        nextVelocity = null;
        removeNextTick = false;
        fireControl$ricochetContact = null;
        ((EquivalentArmorStopAccess) this).fireControl$clearStop();
    }
}
