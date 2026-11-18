package com.hooya.stabilizedturret.mixin;

import com.cbc_terminal_ballistics.ballistics.TBImpactService;
import com.cbc_terminal_ballistics.data.MaterialManager;
import com.cbc_terminal_ballistics.data.MaterialStats;
import com.hooya.stabilizedturret.content.armor.ArmorRicochet;
import com.hooya.stabilizedturret.content.armor.PolyArmorImpacts;
import com.hooya.stabilizedturret.content.armor.PolyArmorProperties;
import com.hooya.stabilizedturret.content.armor.EquivalentArmor;
import com.hooya.stabilizedturret.content.armor.ArmorUpgrade;
import com.hooya.stabilizedturret.content.armor.CompositeArmorTerminalTraits;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retains CTB's actual solver and spall; disables its replacement armor-damage system locally. */
@Mixin(targets="com.cbc_terminal_ballistics.ballistics.TBImpactService",remap=false)
public abstract class PolyArmorTerminalImpactMixin {
    @WrapMethod(method="calculate")
    private static Object fireControl$scopedImpact(Entity projectile,Object context,BlockState state,BlockHitResult hit,boolean autocannon,Operation<Object> original){
        try(var scope=PolyArmorImpacts.begin(projectile,state,hit)){
            Object result=original.call(projectile,context,state,hit,autocannon);scope.complete(result,hit);return result;
        }
    }
    @WrapOperation(method = "calculate", at = @At(value = "INVOKE",
            target = "Lcom/cbc_terminal_ballistics/ballistics/TBImpactService;syncImpactOutcome(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;Ljava/lang/String;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;ZLnet/minecraft/core/BlockPos;)V"))
    private static void fireControl$syncRicochetDeparture(ServerLevel level, Entity projectile, String outcome,
            Vec3 position, Vec3 velocity, boolean inGround, BlockPos pos, Operation<Void> original,
            Entity shot, Object context, BlockState state, BlockHitResult hit, boolean autocannon) {
        if (outcome.equals("BOUNCE") && PolyArmorImpacts.activeAt(level, pos)) {
            position = ArmorRicochet.departure(level, hit);
        }
        original.call(level, projectile, outcome, position, velocity, inGround, pos);
    }
    @WrapOperation(method="calculate",at=@At(value="INVOKE",target="Lcom/cbc_terminal_ballistics/data/MaterialManager;get(Lnet/minecraft/world/level/block/state/BlockState;D)Lcom/cbc_terminal_ballistics/data/MaterialStats;"))
    private static MaterialStats fireControl$fullMaterialTraits(MaterialManager manager,BlockState material,double toughness,Operation<MaterialStats> original,
            Entity projectile,Object context,BlockState state,BlockHitResult hit,boolean autocannon){
        var stats = original.call(manager,material,EquivalentArmor.enabled()?EquivalentArmor.materialToughness(projectile.level(),material,hit.getBlockPos()):PolyArmorProperties.unscaledMaterialToughness(projectile.level(),hit.getBlockPos(),toughness));
        return CompositeArmorTerminalTraits.scale(stats, ArmorUpgrade.level(projectile.level(), hit.getBlockPos()));
    }
    @WrapOperation(method="calculate",at=@At(value="INVOKE",target="Lcom/cbc_terminal_ballistics/ballistics/TBImpactService;degradedToughness(DDD)D"))
    private static double fireControl$noIntegrityDegradation(double base,double saved,double threshold,Operation<Double> original){return PolyArmorImpacts.active()?base:original.call(base,saved,threshold);}
    @ModifyExpressionValue(method="calculate",at=@At(value="INVOKE",target="Lcom/cbc_terminal_ballistics/ballistics/TBImpactService;effectiveDuctility(Lnet/minecraft/world/level/block/state/BlockState;Lcom/cbc_terminal_ballistics/data/MaterialStats;D)D"))
    private static double fireControl$noMaterialShattering(double original){return PolyArmorImpacts.active()?Double.POSITIVE_INFINITY:original;}
    @ModifyExpressionValue(method="calculate",at=@At(value="INVOKE",target="Lcom/cbc_terminal_ballistics/ballistics/TBImpactService;shouldBreakImmediately(Lnet/minecraft/world/level/block/state/BlockState;Lcom/cbc_terminal_ballistics/data/MaterialStats;DDZ)Z"))
    private static boolean fireControl$noIntegrityBreak(boolean original){return !PolyArmorImpacts.active()&&original;}
    @WrapOperation(method="calculate",at=@At(value="INVOKE",target="Lcom/cbc_terminal_ballistics/ballistics/TBImpactService;triggerExplosiveBlockHit(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z"))
    private static boolean fireControl$noCopiedBlockDetonation(ServerLevel level,BlockPos pos,BlockState state,net.minecraft.core.Direction face,Operation<Boolean> original){return !PolyArmorImpacts.activeAt(level,pos)&&original.call(level,pos,state,face);}
    @WrapOperation(method="calculate",at=@At(value="INVOKE",target="Lcom/cbc_terminal_ballistics/state/TemporaryBlockPassage;phaseForThisTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private static void fireControl$neverRemoveArmor(ServerLevel level,BlockPos pos,BlockState state,Operation<Void> original){if(!PolyArmorImpacts.activeAt(level,pos))original.call(level,pos,state);}
    @Inject(method="syncMarks",at=@At("HEAD"),cancellable=true)
    private static void fireControl$noDecals(ServerLevel level,BlockPos pos,java.util.List<?> marks,CallbackInfo ci){if(level.getBlockState(pos).getBlock() instanceof com.caca.copypoly.PolyBlock)ci.cancel();}
    @Inject(method="syncIntegrityProgress(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;I)V",at=@At("HEAD"),cancellable=true)
    private static void fireControl$noCracks(ServerLevel level,BlockPos pos,int stage,CallbackInfo ci){if(level.getBlockState(pos).getBlock() instanceof com.caca.copypoly.PolyBlock)ci.cancel();}
}
