package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.Df26BallisticMissileVisuals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Makes the native DF-26 flame plume wider while retaining its artwork. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.client.particle.Rocketmissiletrail3Particle",
        remap = false, priority = 2000)
public abstract class MianbaoBallisticFlameParticleMixin {
    @ModifyConstant(method = "<init>", constant = @Constant(floatValue = 7.0F), require = 1)
    private float createFireControl$thickenDf26Flame(float nativeScale) {
        return Df26BallisticMissileVisuals.scalingParticles()
                ? nativeScale * Df26BallisticMissileVisuals.FLAME_PARTICLE_SCALE
                : nativeScale;
    }
}
