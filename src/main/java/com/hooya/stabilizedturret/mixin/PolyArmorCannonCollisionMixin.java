package com.hooya.stabilizedturret.mixin;

import com.caca.copypoly.PolyBlock;
import com.caca.copypoly.PolyBlockEntity;
import com.hooya.stabilizedturret.content.armor.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/** Exact projectile rays; ordinary entity contacts retain their existing box decomposition. */
@Mixin(PolyBlock.class)
public abstract class PolyArmorCannonCollisionMixin {
    @Inject(method="getCollisionShape",at=@At("HEAD"),cancellable=true)
    private void fireControl$exactUnpiercedSlope(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context,CallbackInfoReturnable<VoxelShape> cir){
        if(context instanceof EntityCollisionContext entity&&entity.getEntity() instanceof AbstractCannonProjectile projectile
                &&!ArmorProjectilePassage.ignores(projectile,pos)&&level.getBlockEntity(pos) instanceof PolyBlockEntity be)
            cir.setReturnValue(((PolyArmorAccess)be).fireControl$armorDamage().shape(be.poly()));
    }
}
