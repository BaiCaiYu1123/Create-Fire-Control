package com.hooya.stabilizedturret.mixin.client;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CBC particles include sound/flash controllers, not just ambient factory decoration. */
@Pseudo
@Mixin(targets="dev.noxora.createfpsoptimizer.OptimizationController", remap=false)
public abstract class CbcFpsOptimizerParticleMixin {
    @Inject(method="allowParticle", at=@At("HEAD"), cancellable=true)
    private static void stabilizedTurret$preserveCannonEffects(ParticleOptions options,
            double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        ResourceLocation id=BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        if (id!=null && id.getNamespace().equals("createbigcannons")) {
            // The optimizer's distance, back-of-camera and per-frame limits
            // otherwise also delete cannon_blast_wave, which plays the shot audio.
            // CBC and vanilla still apply their own range and particle-quality settings.
            cir.setReturnValue(true);
        }
    }
}
