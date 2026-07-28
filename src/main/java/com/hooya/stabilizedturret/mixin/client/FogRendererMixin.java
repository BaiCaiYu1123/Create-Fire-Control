package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the thermal background/fog exposure independent of weather and time. */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    private static final float THERMAL_BACKGROUND = 0.12F;

    @Shadow
    private static float fogRed;

    @Shadow
    private static float fogGreen;

    @Shadow
    private static float fogBlue;

    @Inject(method = "setupColor", at = @At("RETURN"))
    private static void stabilizedTurret$useThermalFogColor(
            Camera camera,
            float partialTick,
            ClientLevel level,
            int renderDistanceChunks,
            float bossColorModifier,
            CallbackInfo ci
    ) {
        if (!ClientAimState.isThermal()) {
            return;
        }
        fogRed = THERMAL_BACKGROUND;
        fogGreen = THERMAL_BACKGROUND;
        fogBlue = THERMAL_BACKGROUND;
        RenderSystem.clearColor(
                THERMAL_BACKGROUND,
                THERMAL_BACKGROUND,
                THERMAL_BACKGROUND,
                0.0F
        );
    }
}
