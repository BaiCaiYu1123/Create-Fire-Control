package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoCountermeasureCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Managed flares use compact motion/lifetime, bounded client effects and fire-control decoy rules. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.procedures.CountermeasureTtrailProcedure",
        remap = false)
public abstract class MianbaoCountermeasureTrailProcedureMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void createFireControl$begin(
            LevelAccessor level,
            double x,
            double y,
            double z,
            Entity entity,
            CallbackInfo ci
    ) {
        if (entity == null || (!entity.getPersistentData().getBoolean(MianbaoCountermeasureCompat.TRACKED_TAG)
                && !entity.getPersistentData().getBoolean("CreateFireControlRemoteVisual"))) return;
        MianbaoCountermeasureCompat.runManagedTrail(entity);
        ci.cancel();
    }
}
