package com.hooya.stabilizedturret.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SpriteSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stretch the native smoke animation across the configured lifetime instead of restarting its sprite cycle. */
@Pseudo
@Mixin(targets = {"net.mcreator.myfirstmod.client.particle.Newsmoke2Particle",
        "net.mcreator.myfirstmod.client.particle.Newsmoke3Particle"}, remap = false)
public abstract class MianbaoSmokeAnimationMixin {
    @Unique private int createFireControl$originalLifetime;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void createFireControl$rememberLifetime(ClientLevel level, double x, double y, double z,
            double dx, double dy, double dz, SpriteSet sprites, CallbackInfo ci) {
        createFireControl$originalLifetime = ((ParticleAccessor) this).createFireControl$getLifetime();
    }
    @ModifyExpressionValue(method = "tick", at = {
            @At(value = "FIELD", target = "Lnet/mcreator/myfirstmod/client/particle/Newsmoke2Particle;age:I"),
            @At(value = "FIELD", target = "Lnet/mcreator/myfirstmod/client/particle/Newsmoke3Particle;age:I")}, require = 1)
    private int createFireControl$animationAge(int age) {
        int lifetime = ((ParticleAccessor) this).createFireControl$getLifetime();
        return lifetime <= 0 || createFireControl$originalLifetime <= 0 || lifetime == createFireControl$originalLifetime
                ? age : (int) ((long) age * createFireControl$originalLifetime / lifetime);
    }
}
