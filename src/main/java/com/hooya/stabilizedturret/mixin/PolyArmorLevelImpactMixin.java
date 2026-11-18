package com.hooya.stabilizedturret.mixin;

import com.caca.copypoly.PolyBlock;
import com.hooya.stabilizedturret.content.armor.PolyArmorImpacts;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Addon impact implementations may remove their block directly instead of queuing damage. */
@Mixin(Level.class)
public abstract class PolyArmorLevelImpactMixin {
    @Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",at=@At("HEAD"),cancellable=true)
    private void fireControl$keepImpactedSolid(BlockPos pos,BlockState state,int flags,int depth,CallbackInfoReturnable<Boolean> cir){
        var level=(Level)(Object)this;
        if(state.isAir()&&PolyArmorImpacts.activeAt(level,pos)&&level.getBlockState(pos).getBlock() instanceof PolyBlock)cir.setReturnValue(false);
    }
}
