package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Device lifetime and exhaust window, without changing native motion or impact callbacks. */
@Pseudo
@Mixin(targets = {"net.mcreator.myfirstmod.procedures.CruisemissiletrailProcedure",
        "net.mcreator.myfirstmod.procedures.CruisemissilelowtrailProcedure",
        "net.mcreator.myfirstmod.procedures.NuclearcruisemissiletrailProcedure",
        "net.mcreator.myfirstmod.procedures.Ground_nuclear_cruise_missile_tanshe_trailProcedure",
        "net.mcreator.myfirstmod.procedures.GroundMissile1trailProcedure",
        "net.mcreator.myfirstmod.procedures.GroundMissile2trailProcedure",
        "net.mcreator.myfirstmod.procedures.GroundMissile3trailProcedure",
        "net.mcreator.myfirstmod.procedures.GroundMissile4trailProcedure"}, remap = false)
public abstract class MianbaoLandAttackTrailMixin {
    @ModifyConstant(method = "execute", constant = @Constant(doubleValue = 1200), require = 0)
    private static double createFireControl$deviceLifetime(double value,
            LevelAccessor level, double x, double y, double z, Entity missile) {
        return LandAttackMissileFlight.managed(missile) ? Double.MAX_VALUE : value;
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/LevelAccessor;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"), require = 0)
    private static void createFireControl$flame(LevelAccessor level, ParticleOptions options,
            double x, double y, double z, double dx, double dy, double dz, Operation<Void> original,
            LevelAccessor procedureLevel, double px, double py, double pz, Entity missile) {
        if (LandAttackMissileFlight.emitting(missile)) original.call(level, options, x, y, z, dx, dy, dz);
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/Commands;performPrefixedCommand(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)V"), require = 0)
    private static void createFireControl$flameCommand(Commands commands, CommandSourceStack source,
            String command, Operation<Void> original,
            LevelAccessor level, double x, double y, double z, Entity missile) {
        if (!command.startsWith("particle ") || LandAttackMissileFlight.emitting(missile))
            original.call(commands, source, command);
    }
}
