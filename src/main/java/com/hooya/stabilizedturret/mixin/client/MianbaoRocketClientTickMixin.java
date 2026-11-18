package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.MissileLongRangeEffects;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops duplicate native rocket simulation and its unbounded client trails. */
@Pseudo
@Mixin(targets = {
        "net.mcreator.myfirstmod.entity.RocketstanshewuEntity",
        "net.mcreator.myfirstmod.entity.RocketHETansheEntity",
        "net.mcreator.myfirstmod.entity.RocketVisualGuidedTansheEntity",
        "net.mcreator.myfirstmod.entity.HeavyrocketexplosiontansheEntity",
        "net.mcreator.myfirstmod.entity.HeavyrocketfiretansheEntity",
        "net.mcreator.myfirstmod.entity.HeavyrocketlaserguidedtansheEntity"
}, remap = false, priority = 2200)
public abstract class MianbaoRocketClientTickMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$useBudgetedRocketVisual(CallbackInfo ci) {
        Entity rocket = (Entity) (Object) this;
        if (rocket.getPersistentData().getBoolean(
                "CreateFireControlRemoteVisual")
                || MissileLongRangeEffects.shouldSuppressNativeMissile(rocket)) {
            ci.cancel();
        }
    }
}
