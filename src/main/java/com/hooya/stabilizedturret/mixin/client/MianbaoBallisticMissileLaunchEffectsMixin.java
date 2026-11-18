package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.Df26BallisticMissileVisuals;
import com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat;
import com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scales particles created by the native launch-effects routine only for DF-26. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.entity.BallisticMissileLaunchEffects",
        remap = false, priority = 2000)
public abstract class MianbaoBallisticMissileLaunchEffectsMixin {
    @WrapMethod(method = "tick")
    private static void createFireControl$poweredEffects(@Coerce Object missile, Operation<Void> original) {
        if (!(missile instanceof Entity entity) || LandAttackMissileFlight.emitting(entity)) original.call(missile);
    }

    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = 3.6D), require = 1)
    private static double createFireControl$lengthenDf26Flame(
            double nativeLength, @Coerce Object missile
    ) {
        return missile instanceof Entity entity
                && MianbaoBallisticMissileCompat.isDf26(entity)
                ? Df26BallisticMissileVisuals.FLAME_LENGTH : nativeLength;
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private static void createFireControl$reliableDf26GroundSmoke(
            @Coerce Object missile, CallbackInfo ci
    ) {
        if (!(missile instanceof Entity entity)
                || !MianbaoBallisticMissileCompat.isDf26(entity)
                || !(entity.level() instanceof ClientLevel level)) return;
        int launchAge = MianbaoBallisticMissileCompat.launchAge(entity);
        if (launchAge < 0 || launchAge > 180) return;
        var rotation = MianbaoBallisticMissileCompat.renderRotation(entity);
        if (rotation == null) return;
        var forwardVector = rotation.transform(new org.joml.Vector3f(0.0F, 1.0F, 0.0F));
        Vec3 forward = new Vec3(forwardVector.x, forwardVector.y, forwardVector.z).normalize();
        var nozzleOffset = rotation.transform(
                new org.joml.Vector3f(0.0F, -0.5F, -3.0F / 16.0F));
        Vec3 nozzle = entity.position().add(
                nozzleOffset.x, nozzleOffset.y, nozzleOffset.z);
        Vec3 exhaustEnd = nozzle.subtract(forward.scale(
                Math.min(24.0, (launchAge + 1) * 1.35)));
        Df26BallisticMissileVisuals.beginScaledParticles(entity);
        try {
            Df26BallisticMissileVisuals.emitReliableGroundSmoke(
                    entity, level, nozzle, exhaustEnd, forward, launchAge);
        } finally {
            Df26BallisticMissileVisuals.endScaledParticles();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private static void createFireControl$beginDf26Particles(
            @Coerce Object missile, CallbackInfo ci
    ) {
        if (missile instanceof Entity entity
                && MianbaoBallisticMissileCompat.isDf26(entity)) {
            Df26BallisticMissileVisuals.beginScaledParticles(entity);
        }
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private static void createFireControl$endDf26Particles(
            @Coerce Object missile, CallbackInfo ci
    ) {
        if (missile instanceof Entity entity
                && MianbaoBallisticMissileCompat.isDf26(entity)) {
            Df26BallisticMissileVisuals.endScaledParticles();
        }
    }

    @org.spongepowered.asm.mixin.injection.Redirect(
            method = "emit",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;addParticle("
                            + "Lnet/minecraft/core/particles/ParticleOptions;"
                            + "DDDDDD)V"),
            require = 0
    )
    private static void createFireControl$roundDf26Tail(
            Level level, ParticleOptions type,
            double x, double y, double z,
            double velocityX, double velocityY, double velocityZ
    ) {
        Vec3 position = Df26BallisticMissileVisuals.circularTailPosition(
                type, new Vec3(x, y, z));
        if (Df26BallisticMissileVisuals.scalingParticles()
                && level instanceof ClientLevel clientLevel) {
            clientLevel.addAlwaysVisibleParticle(type, true,
                    position.x, position.y, position.z,
                    velocityX, velocityY, velocityZ);
        } else {
            level.addParticle(type, position.x, position.y, position.z,
                    velocityX, velocityY, velocityZ);
        }
    }
}
