package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.config.MissileSettings;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;

/** Native 2.7.1 timeout constants are after the first motor write; early particle limits stay intact. */
@Pseudo
@Mixin(targets = {
        "net.mcreator.myfirstmod.procedures.Antitankmissile1trailProcedure",
        "net.mcreator.myfirstmod.procedures.Antitankemissile2trailProcedure",
        "net.mcreator.myfirstmod.procedures.Antitankmissile5trailProcedure",
        "net.mcreator.myfirstmod.procedures.Antitankmissile6trailProcedure",
        "net.mcreator.myfirstmod.procedures.AntiairmissiletrailProcedure",
        "net.mcreator.myfirstmod.procedures.PortableantiairmissiletrailProcedure",
        "net.mcreator.myfirstmod.procedures.Portableantiairmissile2trailProcedure",
        "net.mcreator.myfirstmod.procedures.Missile3trailProcedure",
        "net.mcreator.myfirstmod.procedures.Agm1trailProcedure",
        "net.mcreator.myfirstmod.procedures.Agm2trailProcedure"
}, remap = false)
public abstract class MianbaoConfigurableEngineMixin {
    @ModifyConstant(method = "execute", constant = {@Constant(intValue = 150), @Constant(intValue = 200),
            @Constant(intValue = 220), @Constant(intValue = 300), @Constant(intValue = 320), @Constant(intValue = 420)},
            slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V", ordinal = 0)), require = 0)
    private static int createFireControl$deviceOwnsExpiry(int original,
            LevelAccessor world, double x, double y, double z, Entity missile) {
        return MissileSettings.adopted(missile) && MissileSettings.lifetimeCustomized(missile)
                ? Integer.MAX_VALUE : original;
    }

    @ModifyConstant(method = "execute", constant = {@Constant(intValue = 15), @Constant(intValue = 50)},
            slice = @Slice(to = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V", ordinal = 0)), require = 0)
    private static int createFireControl$exhaustEnd(int original,
            LevelAccessor world, double x, double y, double z, Entity missile) {
        return MissileSettings.adopted(missile)
                ? Math.max(1, (int) Math.round(original * MissileSettings.poweredScale(missile))) : original;
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/LevelAccessor;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"), require = 0)
    private static void createFireControl$exhaustWindow(LevelAccessor world, ParticleOptions options,
            double x, double y, double z, double dx, double dy, double dz, Operation<Void> original,
            LevelAccessor procedureWorld, double px, double py, double pz, Entity missile) {
        if (MissileSettings.emitting(missile)) original.call(world, options, x, y, z, dx, dy, dz);
    }
}
