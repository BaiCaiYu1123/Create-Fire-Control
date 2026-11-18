package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.tiger.TigerIEngineBlockEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Owned ports are live members even though the placed block entity is their controller. */
@Mixin(value = KineticNetwork.class, remap = false)
public abstract class TigerIKineticNetworkMixin {
    @WrapOperation(method = {"calculateCapacity", "calculateStress"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity tigerI$ownedPort(Level level, BlockPos pos, Operation<BlockEntity> original,
                                        @Local KineticBlockEntity member) {
        if (member instanceof TigerIEngineBlockEntity.Port port && port.isLivePort()) return port;
        return original.call(level, pos);
    }
}
