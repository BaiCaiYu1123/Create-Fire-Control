package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.armor.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class EquivalentArmorBlockStateMixin {
    @Inject(method="getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",at=@At("HEAD"),cancellable=true)
    private void fireControl$oneChargePerPenetratedContact(BlockGetter level,BlockPos pos,CollisionContext context,CallbackInfoReturnable<VoxelShape> cir){
        if(context instanceof EntityCollisionContext entity&&entity.getEntity() instanceof AbstractCannonProjectile projectile&&EquivalentArmor.enabled()&&ArmorProjectilePassage.ignores(projectile,pos))cir.setReturnValue(Shapes.empty());
    }
}
