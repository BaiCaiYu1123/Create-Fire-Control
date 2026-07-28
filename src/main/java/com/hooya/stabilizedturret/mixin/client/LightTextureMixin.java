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
 * Replaces Minecraft's time- and block-light-dependent light map with one
 * neutral exposure while the thermal sight is active. Every packed sky/block
 * light coordinate therefore samples the same value: torches cannot brighten
 * nearby terrain, and daylight cannot change the image between day and night.
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
        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                lightPixels.setPixelRGBA(block, sky, 0xFFFFFFFF);
            }
        }
        stabilizedTurret$getLightTexture().upload();
        ci.cancel();
    }
}
