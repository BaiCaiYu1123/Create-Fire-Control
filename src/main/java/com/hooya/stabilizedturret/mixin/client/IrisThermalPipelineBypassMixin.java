package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ThermalRenderMode;
import com.hooya.stabilizedturret.client.compat.IrisThermalFramebufferBridge;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Keeps Iris loaded but turns the current frame into a zero-reload thermal
 * path. Shader-pack shadow/composite/final stages are bypassed, while the
 * already rendered Iris/Voxy base color is copied to Minecraft's main target.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisThermalPipelineBypassMixin {
    @Unique
    private static Field createFireControl$renderTargets;
    @Unique
    private static Field createFireControl$depthSourceFramebuffer;
    @Unique
    private static Field createFireControl$isRenderingWorld;
    @Unique
    private static Method createFireControl$removePhase;
    @Unique
    private static Method createFireControl$getFramebufferId;
    @Unique
    private static Method createFireControl$bindFramebuffer;
    @Unique
    private static Method createFireControl$getRenderTarget;
    @Unique
    private static Method createFireControl$getMainTexture;
    @Unique
    private static boolean createFireControl$reflectionReady;
    @Unique
    private static boolean createFireControl$failureLogged;
    @Unique
    private static Object createFireControl$nonePhase;

    /**
     * Iris shader packs may route gbuffer outputs to arbitrary attachments.
     * For thermal frames, bind Iris' stable colortex0 + current-depth target
     * after setup/clears so every native renderer shares one scene buffer.
     */
    @Inject(method = "beginLevelRendering", at = @At("TAIL"), require = 0)
    private void createFireControl$bindThermalBaseFramebuffer(CallbackInfo ci) {
        if (!ThermalRenderMode.isShaderPackThermal()) {
            IrisThermalFramebufferBridge.clear();
            return;
        }
        try {
            createFireControl$resolveReflection();
            Object targets = createFireControl$renderTargets.get(this);
            Object framebuffer = createFireControl$depthSourceFramebuffer.get(targets);
            createFireControl$bindFramebuffer.invoke(framebuffer);
            IrisThermalFramebufferBridge.set(framebuffer);
            IrisThermalFramebufferBridge.setFramebufferId(
                    (int) createFireControl$getFramebufferId.invoke(framebuffer));
            Object colorTarget = createFireControl$getRenderTarget.invoke(targets, 0);
            int colorTexture = (int) createFireControl$getMainTexture.invoke(colorTarget);
            IrisThermalFramebufferBridge.setColorTexture(colorTexture);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IrisThermalFramebufferBridge.clear();
            createFireControl$logFailure(failure);
        }
    }

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$skipShaderPackShadows(
            @Coerce Object levelRendererAccessor,
            net.minecraft.client.Camera camera,
            CallbackInfo ci
    ) {
        if (ThermalRenderMode.isShaderPackThermal()) {
            ci.cancel();
        }
    }

    @Inject(method = "shouldOverrideShaders", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$useVanillaEntityPrograms(
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (ThermalRenderMode.isShaderPackThermal()) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Iris selects several entity, block-entity, sky, and weather programs
     * from the current world phase even when shouldOverrideShaders() is
     * false. Exposing NONE for this frame keeps those callers on Minecraft's
     * original programs without disturbing Iris' internally tracked phase.
     */
    @Inject(method = "getPhase", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$hideShaderPackPhase(
            CallbackInfoReturnable<Object> cir
    ) {
        if (!ThermalRenderMode.isShaderPackThermal()) {
            return;
        }
        try {
            if (createFireControl$nonePhase == null) {
                Class<?> phaseClass = Class.forName(
                        "net.irisshaders.iris.pipeline.WorldRenderingPhase"
                );
                createFireControl$nonePhase = phaseClass.getField("NONE").get(null);
            }
            cir.setReturnValue(createFireControl$nonePhase);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            createFireControl$logFailure(failure);
        }
    }

    @Inject(method = "finalizeLevelRendering", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$finishThermalWithoutShaderPack(CallbackInfo ci) {
        if (!ThermalRenderMode.isShaderPackThermal()) {
            return;
        }
        try {
            createFireControl$resolveReflection();
            createFireControl$isRenderingWorld.setBoolean(this, false);
            createFireControl$removePhase.invoke(this);

            Object sourceFramebuffer = IrisThermalFramebufferBridge.get();
            if (sourceFramebuffer == null) {
                throw new IllegalStateException("Iris thermal base framebuffer is unavailable");
            }
            int sourceId = (int) createFireControl$getFramebufferId.invoke(sourceFramebuffer);
            RenderTarget destination = Minecraft.getInstance().getMainRenderTarget();

            createFireControl$blitBaseColor(sourceId, destination);
            ci.cancel();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            createFireControl$logFailure(failure);
            // Fall through to Iris' original final pass rather than leaving
            // the player with a black frame when an unknown Iris build changes.
        }
    }

    @Inject(method = "finalizeGameRendering", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$skipShaderPackColorSpace(CallbackInfo ci) {
        if (ThermalRenderMode.isShaderPackThermal()) {
            ci.cancel();
        }
    }

    @Unique
    private void createFireControl$resolveReflection() throws ReflectiveOperationException {
        if (createFireControl$reflectionReady) {
            return;
        }
        Class<?> pipelineClass = this.getClass();
        createFireControl$renderTargets = pipelineClass.getDeclaredField("renderTargets");
        createFireControl$isRenderingWorld =
                pipelineClass.getDeclaredField("isRenderingWorld");
        createFireControl$removePhase =
                pipelineClass.getDeclaredMethod("removePhaseIfNeeded");
        createFireControl$renderTargets.setAccessible(true);
        createFireControl$isRenderingWorld.setAccessible(true);
        createFireControl$removePhase.setAccessible(true);

        Class<?> renderTargetsClass = Class.forName(
                "net.irisshaders.iris.targets.RenderTargets"
        );
        createFireControl$depthSourceFramebuffer =
                renderTargetsClass.getDeclaredField("depthSourceFb");
        createFireControl$depthSourceFramebuffer.setAccessible(true);
        createFireControl$getRenderTarget = renderTargetsClass.getMethod("get", int.class);

        Class<?> framebufferClass = Class.forName(
                "net.irisshaders.iris.gl.framebuffer.GlFramebuffer"
        );
        createFireControl$getFramebufferId = framebufferClass.getMethod("getId");
        createFireControl$bindFramebuffer = framebufferClass.getMethod("bind");
        Class<?> renderTargetClass = Class.forName("net.irisshaders.iris.targets.RenderTarget");
        createFireControl$getMainTexture = renderTargetClass.getMethod("getMainTexture");
        createFireControl$reflectionReady = true;
    }

    @Unique
    private static void createFireControl$blitBaseColor(
            int sourceId,
            RenderTarget destination
    ) {
        int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11C.glGetInteger(GL11C.GL_READ_BUFFER);
        boolean previousDepthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        boolean previousBlend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        boolean copied = false;
        try {
            RenderSystem.disableBlend();
            RenderSystem.depthMask(false);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sourceId);
            GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            GL30C.glBindFramebuffer(
                    GL30C.GL_DRAW_FRAMEBUFFER,
                    destination.frameBufferId
            );
            GL30C.glBlitFramebuffer(
                    0,
                    0,
                    destination.width,
                    destination.height,
                    0,
                    0,
                    destination.width,
                    destination.height,
                    GL11C.GL_COLOR_BUFFER_BIT,
                    GL11C.GL_NEAREST
            );
            copied = true;
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL11C.glReadBuffer(previousReadBuffer);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            RenderSystem.depthMask(previousDepthMask);
            if (previousBlend) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
            if (copied) {
                // This is where Iris' final pass normally leaves rendering.
                destination.bindWrite(false);
            }
        }
    }

    @Unique
    private static void createFireControl$logFailure(Throwable failure) {
        if (!createFireControl$failureLogged) {
            createFireControl$failureLogged = true;
            StabilizedTurret.LOGGER.warn(
                    "Unable to bypass Iris shader-pack passes for thermal imaging",
                    failure
            );
        }
    }
}
