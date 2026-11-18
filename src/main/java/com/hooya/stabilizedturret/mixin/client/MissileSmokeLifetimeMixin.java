package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientVisualConfig;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shared factory catches native network packets, math-flight wrappers and observer/remote particles once. */
@Mixin(ParticleEngine.class)
public abstract class MissileSmokeLifetimeMixin {
    @Inject(method = "makeParticle", at = @At("RETURN"))
    private <T extends ParticleOptions> void createFireControl$smokeLifetime(T options,
            double x, double y, double z, double dx, double dy, double dz, CallbackInfoReturnable<Particle> cir) {
        Particle particle = cir.getReturnValue();
        if (particle == null) return;
        var id = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        if (id == null || !id.getNamespace().equals("mianbaos_modernwarfare")) return;
        if (!id.getPath().equals("newsmoke_2") && !id.getPath().equals("newsmoke_3")
                && !id.getPath().equals("ballistic_flight_smoke")) return;
        var state = (ParticleAccessor) particle;
        state.createFireControl$setLifetime(ClientVisualConfig.smokeLifetime(state.createFireControl$getLifetime()));
    }
}
