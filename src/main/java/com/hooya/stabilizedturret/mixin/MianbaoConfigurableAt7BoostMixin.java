package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.config.MissileSettings;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** AT-7 uses a separate procedure with both a source entity and the actual missile argument. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.procedures.Antitankmissile_7tanshe_trailProcedure", remap = false)
public abstract class MianbaoConfigurableAt7BoostMixin {
    @ModifyExpressionValue(method = "execute", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/Entity;tickCount:I", ordinal = 2), require = 1)
    private static int createFireControl$motorAge(int age, LevelAccessor world, double x, double y, double z,
                                                  Entity source, Entity missile) {
        return MissileSettings.adopted(missile) ? (int) Math.floor(age / MissileSettings.poweredScale(missile)) : age;
    }
    @ModifyConstant(method = "execute", constant = @Constant(intValue = 250), require = 1)
    private static int createFireControl$deviceOwnsExpiry(int original, LevelAccessor world, double x, double y, double z,
                                                          Entity source, Entity missile) {
        return MissileSettings.adopted(missile) && MissileSettings.lifetimeCustomized(missile)
                ? Integer.MAX_VALUE : original;
    }
    @ModifyConstant(method = "execute", constant = @Constant(intValue = 15), require = 1)
    private static int createFireControl$exhaustEnd(int original, LevelAccessor world, double x, double y, double z,
                                                    Entity source, Entity missile) {
        return MissileSettings.adopted(missile)
                ? Math.max(1, (int) Math.round(original * MissileSettings.poweredScale(missile))) : original;
    }
}
