package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoRemoteWeaponStationCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Weakens only autocannon shells spawned by a bound remote weapon station. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.procedures.AutocannonexplodeProcedure",
        remap = false, priority = 2100)
public abstract class MianbaoAutocannonExplosionProcedureMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, require = 0)
    private static void createFireControl$weakenRemoteStationRound(
            LevelAccessor world, double x, double y, double z,
            Entity source, CallbackInfo ci
    ) {
        if (MianbaoRemoteWeaponStationCompat.detonateReducedAutocannon(
                world, x, y, z, source)) {
            ci.cancel();
        }
    }
}
