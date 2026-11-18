package com.hooya.stabilizedturret.mixin;
import com.caca.copypoly.PolyBlock;
import com.cbc_terminal_ballistics.state.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(targets="com.cbc_terminal_ballistics.state.ArmorIntegritySavedData",remap=false)
public abstract class PolyArmorTerminalIntegrityMixin {
    @Inject(method="addDamage",at=@At("HEAD"),cancellable=true)
    private void fireControl$noWear(ServerLevel level,BlockPos pos,BlockState state,double amount,CallbackInfo ci){if(state.getBlock() instanceof PolyBlock)ci.cancel();}
    @Inject(method="damage",at=@At("HEAD"),cancellable=true)
    private void fireControl$noAccumulatedDamage(ServerLevel level,BlockPos pos,BlockState state,CallbackInfoReturnable<Double> cir){if(state.getBlock() instanceof PolyBlock)cir.setReturnValue(0.0);}
    @Inject(method="addMark",at=@At("HEAD"),cancellable=true)
    private void fireControl$noMarks(ServerLevel level,BlockPos pos,BlockState state,ImpactMark mark,CallbackInfo ci){if(state.getBlock() instanceof PolyBlock)ci.cancel();}
    @Inject(method="getEntry",at=@At("HEAD"),cancellable=true)
    private void fireControl$ignoreOldIntegrity(ServerLevel level,BlockPos pos,CallbackInfoReturnable<ArmorIntegritySavedData.Entry> cir){if(level.getBlockState(pos).getBlock() instanceof PolyBlock)cir.setReturnValue(null);}
}
