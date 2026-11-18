package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Extends the original short cruise plume to the shared visual-only powered phase. */
@Pseudo
@Mixin(targets = {"net.mcreator.myfirstmod.procedures.CruisemissiletrailProcedure",
        "net.mcreator.myfirstmod.procedures.CruisemissilelowtrailProcedure",
        "net.mcreator.myfirstmod.procedures.NuclearcruisemissiletrailProcedure"}, remap = false)
public abstract class MianbaoCruiseExhaustWindowMixin {
    // The first 75 is the smoke limit; later occurrences are terrain-guidance distances.
    @ModifyConstant(method = "execute", constant = @Constant(doubleValue = 75, ordinal = 0))
    private static double createFireControl$exhaustWindow(double value,
            LevelAccessor level, double x, double y, double z, Entity missile) {
        return LandAttackMissileFlight.managed(missile) ? Double.MAX_VALUE : value;
    }
}
