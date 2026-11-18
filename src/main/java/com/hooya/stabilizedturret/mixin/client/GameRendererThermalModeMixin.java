package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalRenderMode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Samples Iris once before any world renderer chooses its thermal path. */
@Mixin(GameRenderer.class)
public abstract class GameRendererThermalModeMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void createFireControl$selectThermalRenderer(
            DeltaTracker deltaTracker,
            CallbackInfo ci
    ) {
        ThermalRenderMode.refresh();
    }
}
