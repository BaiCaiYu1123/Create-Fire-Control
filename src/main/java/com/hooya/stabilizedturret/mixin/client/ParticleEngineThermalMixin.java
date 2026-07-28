package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalSmokeFilter;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineThermalMixin {
    @Redirect(
            method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"
            )
    )
    private void stabilizedTurret$hideCannonSmoke(
            Particle particle,
            VertexConsumer consumer,
            Camera camera,
            float partialTick
    ) {
        if (!ThermalSmokeFilter.shouldHide(particle)) {
            particle.render(consumer, camera, partialTick);
        }
    }
}
