package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Only these two procedures use 620 as their expiry, rather than as a guidance transition. */
@Pseudo
@Mixin(targets = {"net.mcreator.myfirstmod.procedures.GroundMissile1trailProcedure",
        "net.mcreator.myfirstmod.procedures.GroundMissile2trailProcedure"}, remap = false)
public abstract class MianbaoGroundMissileLifetimeMixin {
    @ModifyConstant(method = "execute", constant = @Constant(doubleValue = 620))
    private static double createFireControl$deviceLifetime(double value,
            LevelAccessor level, double x, double y, double z, Entity missile) {
        return LandAttackMissileFlight.managed(missile) ? Double.MAX_VALUE : value;
    }
}
