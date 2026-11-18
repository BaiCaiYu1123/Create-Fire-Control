package com.hooya.stabilizedturret.mixin;

import com.cbc_terminal_ballistics.debug.InspectionSnapshot;
import com.hooya.stabilizedturret.content.armor.EquivalentArmor;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets="com.cbc_terminal_ballistics.network.ServerboundInspectBlockPacket",remap=false)
public abstract class EquivalentArmorTerminalInspectorMixin {
    @WrapOperation(method="lambda$handle$1",at=@At(value="INVOKE",target="Lcom/cbc_terminal_ballistics/debug/InspectionSnapshot;build(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;)Lcom/cbc_terminal_ballistics/debug/InspectionSnapshot;"))
    private static InspectionSnapshot fireControl$serverViewRay(ServerLevel level,BlockPos pos,BlockHitResult hit,Operation<InspectionSnapshot> original,@Local ServerPlayer player){
        try(var ignored=EquivalentArmor.inspect(level,pos,player.getEyePosition(),hit.getLocation())){return original.call(level,pos,hit);}
    }
    @WrapOperation(method="lambda$handle$1",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"))
    private static double fireControl$structureDistance(ServerPlayer player,net.minecraft.world.phys.Vec3 point,Operation<Double> original){
        if(!EquivalentArmor.enabled())return original.call(player,point);
        var body=dev.ryanhcode.sable.Sable.HELPER.getContaining(player.level(),BlockPos.containing(point));
        return original.call(player,body==null?point:body.logicalPose().transformPosition(point));
    }
}
