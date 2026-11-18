package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops new smoke and repeated motor sounds together; existing particles finish normally. */
@Pseudo
@Mixin(targets = {"net.mcreator.myfirstmod.procedures.RocketmissiletrailsmokeProcedure",
        "net.mcreator.myfirstmod.procedures.RocketmissiletrailsmokelargeProcedure",
        "net.mcreator.myfirstmod.procedures.GroundmissilesmokeProcedure"}, remap = false)
public abstract class MianbaoLandAttackExhaustMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void createFireControl$poweredEffects(LevelAccessor level, double x, double y, double z,
            Entity missile, CallbackInfo ci) {
        if (!LandAttackMissileFlight.emitting(missile)
                || !com.hooya.stabilizedturret.config.MissileSettings.emitting(missile)) ci.cancel();
    }
}
