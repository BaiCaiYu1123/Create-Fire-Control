package com.hooya.stabilizedturret.mixin;
import com.hooya.stabilizedturret.content.armor.PolyArmorImpacts;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import rbasamoyai.createbigcannons.munitions.*;
import net.minecraft.world.phys.BlockHitResult;
@Mixin(targets="riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile",remap=false)
public abstract class PolyArmorNativeImpactMixin {
    @WrapMethod(method="calculateBlockPenetration")
    private AbstractCannonProjectile.ImpactResult fireControl$scopedMedium(ProjectileContext context,BlockState state,BlockHitResult hit,Operation<AbstractCannonProjectile.ImpactResult> original){
        return PolyArmorImpacts.calculate((AbstractCannonProjectile)(Object)this,context,state,hit,()->original.call(context,state,hit));
    }
    @WrapOperation(method="calculateBlockPenetration",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean fireControl$keepArmor(Level level,BlockPos pos,BlockState state,int flags,Operation<Boolean> original){
        return !PolyArmorImpacts.activeAt(level,pos)&&original.call(level,pos,state,flags);
    }
}
