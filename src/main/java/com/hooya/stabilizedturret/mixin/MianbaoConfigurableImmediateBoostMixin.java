package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.config.MissileSettings;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Only the native motor age read changes; particle counts and guidance delays stay intact. */
@Pseudo
@Mixin(targets = {"net.mcreator.myfirstmod.procedures.Antitankmissile1trailProcedure",
        "net.mcreator.myfirstmod.procedures.Antitankmissile6trailProcedure"}, remap = false)
public abstract class MianbaoConfigurableImmediateBoostMixin {
    @ModifyExpressionValue(method = "execute", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/Entity;tickCount:I", ordinal = 2), require = 1)
    private static int createFireControl$motorAge(int age, LevelAccessor world, double x, double y, double z, Entity missile) {
        return MissileSettings.adopted(missile) ? (int) Math.floor(age / MissileSettings.poweredScale(missile)) : age;
    }
}
