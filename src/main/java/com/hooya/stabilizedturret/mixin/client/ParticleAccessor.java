package com.hooya.stabilizedturret.mixin.client;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accesses the particle state inherited by Mianbao's particle subclasses. */
@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("xo") void createFireControl$setXo(double value);
    @Accessor("yo") void createFireControl$setYo(double value);
    @Accessor("zo") void createFireControl$setZo(double value);
    @Accessor("age")
    int createFireControl$getAge();

    @Accessor("lifetime")
    int createFireControl$getLifetime();

    @Accessor("lifetime")
    void createFireControl$setLifetime(int lifetime);

    @Accessor("alpha")
    float createFireControl$getAlpha();

    @Accessor("alpha")
    void createFireControl$setAlpha(float alpha);

}
