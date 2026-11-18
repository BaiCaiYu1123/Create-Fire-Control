package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.Df26BallisticMissileVisuals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.SingleQuadParticle;

/** Applies DF-26-specific sizes to Mianbao's ballistic launch particles. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.client.particle.BallisticLaunchSmokeParticle",
        remap = false, priority = 2000)
public abstract class MianbaoBallisticLaunchSmokeParticleMixin {
    @Shadow @Final @Mutable private float initialSize;
    @Shadow @Final private int kind;
    @org.spongepowered.asm.mixin.Unique
    private boolean createFireControl$df26Particle;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void createFireControl$scaleDf26Particle(
            ClientLevel level, double x, double y, double z,
            double velocityX, double velocityY, double velocityZ,
            SpriteSet sprites, int kind, CallbackInfo ci
    ) {
        createFireControl$df26Particle = Df26BallisticMissileVisuals.scalingParticles();
        if (createFireControl$df26Particle) {
            if (kind == 2) {
                // SingleQuadParticle extends this value in both directions;
                // 1.5 radius + 1.5 radial distribution = a 6-block plume.
                float targetRadius = Df26BallisticMissileVisuals.FLIGHT_SMOKE_PARTICLE_RADIUS;
                float scale = initialSize <= 1.0e-6F ? 1.0F : targetRadius / initialSize;
                ((SingleQuadParticle) (Object) this).scale(scale);
                initialSize = targetRadius;
            } else if (kind == 0) {
                // Ground launch smoke is now half of its previous DF-26 size.
                float scale = Df26BallisticMissileVisuals.PARTICLE_SCALE * 0.5F;
                ((SingleQuadParticle) (Object) this).scale(scale);
                initialSize *= scale;
            } else {
                ((SingleQuadParticle) (Object) this).scale(
                        Df26BallisticMissileVisuals.EXHAUST_PARTICLE_SCALE);
                initialSize *= Df26BallisticMissileVisuals.EXHAUST_PARTICLE_SCALE;
            }
        }
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 1)
    private void createFireControl$growFlightSmoke(CallbackInfo ci) {
        if (!createFireControl$df26Particle || kind != 2) return;
        // Growth starts when this particle is created: 1.5x after 20 ticks,
        // then reaches and holds at 3x after 80 ticks.
        ParticleAccessor particle = (ParticleAccessor) (Object) this;
        SingleQuadParticleAccessor quad = (SingleQuadParticleAccessor) (Object) this;
        int age = particle.createFireControl$getAge();
        int lifetime = particle.createFireControl$getLifetime();
        // quadSize is inherited from Particle and is not declared by the target
        // class. The accessor targets Particle directly, so this remains valid
        // for Mianbao's final subclass while preserving its native growth.
        float lifetimeProgress = lifetime <= 0 ? 1.0F : age / (float) lifetime;
        float nativeSize = initialSize * (1.0F + 0.7F * lifetimeProgress);
        float desiredSize = initialSize * Math.min(3.0F, 1.0F + age / 40.0F);
        if (nativeSize > 1.0e-6F) quad.createFireControl$setQuadSize(desiredSize);
    }

    /**
     * Native ground smoke grows from 1.0x to 2.6x initial size. A 4.2 growth
     * coefficient keeps the same linear lifetime curve and ends at 5.2x,
     * exactly twice the native maximum. Exhaust and flight smoke use other
     * constants and remain unchanged.
     */
    @ModifyConstant(method = "tick", constant = @Constant(floatValue = 1.6F), require = 0)
    private float createFireControl$doubleGroundCloudMaximum(float nativeGrowth) {
        return createFireControl$df26Particle ? 4.2F : nativeGrowth;
    }
}
