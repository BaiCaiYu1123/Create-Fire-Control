package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Replaces Minecraft's time-dependent light map with a fixed thermal exposure.
 * The sky axis is deliberately ignored, so day, night, weather and dimensions
 * cannot change the image. Propagated block light is ignored. Heat markers
 * live in packed-coordinate low bits, so no light-map texel is recolored and
 * full-bright HUD/text rendering remains neutral.
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
    @Accessor("lightPixels")
    protected abstract NativeImage stabilizedTurret$getLightPixels();

    @Accessor("lightTexture")
    protected abstract DynamicTexture stabilizedTurret$getLightTexture();

    @Inject(method = "updateLightTexture", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$useThermalLightMap(float partialTick, CallbackInfo ci) {
        if (!ClientAimState.isThermal()) {
            return;
        }
        NativeImage lightPixels = stabilizedTurret$getLightPixels();
        int channel = Math.round(0.40F * 255.0F);
        int fixedGray = 0xFF000000
                | channel << 16
                | channel << 8
                | channel;
        int fullBrightWhite = 0xFFFFFFFF;
        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                // GUI text, HUD icons and intentionally full-bright particles
                // use the vanilla (15, 15) coordinate. World mesh encoders
                // demote propagated full light, so restoring this cell cannot
                // make neighbouring terrain inherit an emitter's heat.
                lightPixels.setPixelRGBA(
                        block,
                        sky,
                        block == 15 && sky == 15 ? fullBrightWhite : fixedGray
                );
            }
        }
        stabilizedTurret$getLightTexture().upload();
        ci.cancel();
    }
}
