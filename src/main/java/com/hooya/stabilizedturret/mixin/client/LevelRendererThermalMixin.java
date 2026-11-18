package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes sun, moon, stars and clouds from the thermal sensor image. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererThermalMixin {
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$hideVisibleSpectrumSky(
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            float partialTick,
            Camera camera,
            boolean isFoggy,
            Runnable skyFogSetup,
            CallbackInfo ci
    ) {
        if (ClientAimState.isThermal()) {
            RenderSystem.clearColor(0.12F, 0.12F, 0.12F, 1.0F);
            RenderSystem.clear(GL11C.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
            ci.cancel();
        }
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$hideVisibleSpectrumClouds(
            PoseStack poseStack,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            float partialTick,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfo ci
    ) {
        if (ClientAimState.isThermal() || ClientAimState.isPodView()) {
            ci.cancel();
        }
    }
}
