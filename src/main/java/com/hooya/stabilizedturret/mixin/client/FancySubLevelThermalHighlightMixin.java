package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalRenderMode;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.FancySubLevelRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the same fixed, uniformly hot response to Sable's fancy backend. */
@Mixin(value = FancySubLevelRenderDispatcher.class, remap = false)
public abstract class FancySubLevelThermalHighlightMixin {
    @Unique
    private float createFireControl$previousRed;
    @Unique
    private float createFireControl$previousGreen;
    @Unique
    private float createFireControl$previousBlue;
    @Unique
    private float createFireControl$previousAlpha;
    @Unique
    private boolean createFireControl$thermalColorActive;
    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void createFireControl$beginHotSubLevel(CallbackInfo ci) {
        if (!ThermalRenderMode.isFixedLightThermal()) {
            createFireControl$thermalColorActive = false;
            return;
        }
        float[] color = RenderSystem.getShaderColor();
        createFireControl$previousRed = color[0];
        createFireControl$previousGreen = color[1];
        createFireControl$previousBlue = color[2];
        createFireControl$previousAlpha = color[3];
        createFireControl$thermalColorActive = true;
        RenderSystem.setShaderColor(4.0F, 4.0F, 4.0F, 1.0F);
    }

    @Inject(method = "renderSectionLayer", at = @At("RETURN"))
    private void createFireControl$endHotSubLevel(CallbackInfo ci) {
        if (!createFireControl$thermalColorActive) {
            return;
        }
        RenderSystem.setShaderColor(
                createFireControl$previousRed,
                createFireControl$previousGreen,
                createFireControl$previousBlue,
                createFireControl$previousAlpha
        );
        createFireControl$thermalColorActive = false;
    }

    @ModifyExpressionValue(
            method = "renderSectionLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;getLatestSkyLightScale()I"
            )
    )
    private int createFireControl$useFixedSubLevelSkyLight(int original) {
        return ThermalRenderMode.isFixedLightThermal() ? 15 : original;
    }
}
