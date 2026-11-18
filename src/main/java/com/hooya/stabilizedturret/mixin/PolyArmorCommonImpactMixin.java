package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.armor.PolyArmorImpacts;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import rbasamoyai.createbigcannons.munitions.*;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;

@Mixin(value={AbstractBigCannonProjectile.class,AbstractAutocannonProjectile.class},remap=false)
public abstract class PolyArmorCommonImpactMixin {
    @WrapMethod(method="calculateBlockPenetration")
    private AbstractCannonProjectile.ImpactResult fireControl$scopedRound(ProjectileContext context,BlockState state,BlockHitResult hit,Operation<AbstractCannonProjectile.ImpactResult> original){
        return PolyArmorImpacts.calculate((AbstractCannonProjectile)(Object)this,context,state,hit,()->original.call(context,state,hit));
    }
}
