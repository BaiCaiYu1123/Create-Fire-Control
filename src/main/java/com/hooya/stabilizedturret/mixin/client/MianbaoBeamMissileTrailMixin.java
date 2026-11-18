package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.BeamMissileParticles;
import com.hooya.stabilizedturret.client.MissileLongRangeEffects;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps native near-range exhaust consistent with the computer's remote visuals. */
@Pseudo
@Mixin(targets = {
        "net.mcreator.myfirstmod.procedures.PortableantiairmissiletrailProcedure",
        "net.mcreator.myfirstmod.procedures.Antitankmissile1trailProcedure",
        "net.mcreator.myfirstmod.procedures.Antitankemissile2trailProcedure",
        "net.mcreator.myfirstmod.procedures.Antitankmissile5trailProcedure",
        "net.mcreator.myfirstmod.procedures.Antitankmissile6trailProcedure"
}, remap = false)
public abstract class MianbaoBeamMissileTrailMixin {
    @WrapOperation(method = "execute", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/LevelAccessor;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"),
            require = 1)
    private static void createFireControl$compactExhaust(LevelAccessor world, ParticleOptions options,
            double x, double y, double z, double dx, double dy, double dz,
            Operation<Void> original, @Local(argsOnly = true) Entity missile) {
        if (world instanceof ClientLevel level && BeamMissileParticles.isManaged(missile)
                && BeamMissileParticles.isExhaust(options)) {
            if (!com.hooya.stabilizedturret.config.MissileSettings.emitting(missile)) return;
            // The authoritative proxy emits this trail once, including nearby views.
            if (!missile.getPersistentData().getBoolean("CreateFireControlRemoteVisual")
                    && !MissileLongRangeEffects.shouldSuppressNativeMissile(missile)) {
                BeamMissileParticles.spawn(level, options, x, y, z, dx, dy, dz);
            }
            return;
        }
        original.call(world, options, x, y, z, dx, dy, dz);
    }
}
