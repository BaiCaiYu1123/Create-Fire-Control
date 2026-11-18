package com.hooya.stabilizedturret.mixin.client;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Construct exactly the installed particle provider, without starting its clock. */
@Mixin(ParticleEngine.class)
public interface NativeMissileParticleFactory {
    @Invoker("makeParticle")
    <T extends ParticleOptions> Particle createFireControl$makeParticle(T options,
            double x, double y, double z, double dx, double dy, double dz);
}
