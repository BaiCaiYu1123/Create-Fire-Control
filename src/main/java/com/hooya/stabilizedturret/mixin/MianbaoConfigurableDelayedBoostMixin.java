package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.config.MissileSettings;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;

/** The ten-tick ignition delay is retained while only the following boost interval scales. */
@Pseudo
@Mixin(targets = {"net.mcreator.myfirstmod.procedures.Antitankemissile2trailProcedure",
        "net.mcreator.myfirstmod.procedures.Antitankmissile5trailProcedure"}, remap = false)
public abstract class MianbaoConfigurableDelayedBoostMixin {
    @ModifyConstant(method = "execute", constant = @Constant(intValue = 20),
            slice = @Slice(to = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V", ordinal = 0)),
            require = 1)
    private static int createFireControl$boostEnd(int original, LevelAccessor world, double x, double y, double z, Entity missile) {
        return MissileSettings.adopted(missile)
                ? 10 + Math.max(1, (int) Math.round((original - 10) * MissileSettings.poweredScale(missile))) : original;
    }
}
