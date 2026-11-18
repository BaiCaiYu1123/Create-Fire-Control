package com.hooya.stabilizedturret.mixin.client;

import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accesses the quad size declared by SingleQuadParticle. */
@Mixin(SingleQuadParticle.class)
public interface SingleQuadParticleAccessor {
    @Accessor("quadSize")
    float createFireControl$getQuadSize();
    @Accessor("quadSize")
    void createFireControl$setQuadSize(float quadSize);
}
