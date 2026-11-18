package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.FireControlMissileCommandGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Native ignition/effects stay active, without duplicate steering or blocking terrain reads. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.procedures.CruisemissilelowtrailProcedure", remap = false)
public abstract class MianbaoLowAltitudeGuidanceMixin {
    @WrapMethod(method = "execute")
    private static void createFireControl$nativeEffectsOnce(LevelAccessor world,
            double x, double y, double z, Entity missile, Operation<Void> original) {
        if (missile == null || !missile.getPersistentData().getBoolean("CreateFireControlCruiseGuided")) {
            original.call(world, x, y, z, missile);
            return;
        }
        var data = missile.getPersistentData();
        long tick = missile.level().getGameTime();
        String marker = "CreateFireControlLowTrailTick";
        if (data.contains(marker) && data.getLong(marker) == tick) return;
        data.putLong(marker, tick);
        Vec3 movement = missile.getDeltaMovement();
        try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) {
            original.call(world, x, y, z, missile);
        } finally {
            // Both native setDeltaMovement and push would otherwise compete
            // with the independent yaw/pitch controller on the next tick.
            missile.setDeltaMovement(movement);
        }
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/LevelAccessor;isEmptyBlock(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean createFireControl$nonLoadingProbe(LevelAccessor world,
            BlockPos pos, Operation<Boolean> original,
            LevelAccessor procedureWorld, double x, double y, double z, Entity missile) {
        if (missile != null && missile.getPersistentData().getBoolean("CreateFireControlCruiseGuided")
                && world instanceof ServerLevel server
                && server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return false;
        }
        return original.call(world, pos);
    }
}
