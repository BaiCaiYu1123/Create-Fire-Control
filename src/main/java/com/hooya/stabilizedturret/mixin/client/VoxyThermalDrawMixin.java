package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ThermalRenderMode;
import com.hooya.stabilizedturret.client.compat.IrisThermalFramebufferBridge;
import com.hooya.stabilizedturret.client.compat.PhotonVoxyThermalFramebufferBridge;
import com.hooya.stabilizedturret.client.compat.VoxyThermalFramebufferBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Enables the Voxy thermal uniform and routes its draw target per frame. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer", remap = false)
public abstract class VoxyThermalDrawMixin {
    @Unique
    private static boolean createFireControl$photonFailureLogged;
    @Unique
    private static boolean createFireControl$photonRouteLogged;

    @Inject(
            method = "renderTerrain",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/cortex/voxy/client/core/gl/shader/Shader;bind()V",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void createFireControl$opaqueUniform(
            @Coerce Object viewport,
            long drawAddress,
            long countAddress,
            int maxDraws,
            CallbackInfo ci
    ) {
        createFireControl$setThermalUniform();
    }

    @Inject(
            method = "renderTranslucent",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/cortex/voxy/client/core/gl/shader/Shader;bind()V",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void createFireControl$translucentUniform(
            @Coerce Object viewport,
            CallbackInfo ci
    ) {
        createFireControl$setThermalUniform();
    }

    @Inject(method = "buildDrawCalls", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/gl/shader/Shader;bind()V", shift = At.Shift.AFTER),
            require = 0)
    private void createFireControl$photonCullingUniform(CallbackInfo ci) {
        if (ThermalRenderMode.isPhotonShaderPackActive()) {
            createFireControl$setThermalUniform();
        }
    }

    /**
     * renderTerrain serves both the cached opaque draw and temporal redraw.
     * Wrap the actual draw after all Voxy/Photon bindings, with exception-safe
     * restoration. renderOpaque itself contains no setupAndBindOpaque call.
     */
    @WrapOperation(method = "renderTerrain", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/ARBIndirectParameters;glMultiDrawElementsIndirectCountARB(IIJJII)V"),
            require = 0)
    private void createFireControl$drawPhotonOpaque(int mode, int type, long indirect,
                                                  long drawCount, int maxDraws, int stride,
                                                  Operation<Void> original) {
        createFireControl$drawPhotonThermal(mode, type, indirect, drawCount, maxDraws, stride,
                original, false);
    }

    @WrapOperation(method = "renderTranslucent", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/ARBIndirectParameters;glMultiDrawElementsIndirectCountARB(IIJJII)V"),
            require = 0)
    private void createFireControl$drawPhotonTranslucent(int mode, int type, long indirect,
                                                       long drawCount, int maxDraws, int stride,
                                                       Operation<Void> original) {
        createFireControl$drawPhotonThermal(mode, type, indirect, drawCount, maxDraws, stride,
                original, true);
    }

    @Unique
    private static void createFireControl$drawPhotonThermal(int mode, int type, long indirect,
                                                           long drawCount, int maxDraws, int stride,
                                                           Operation<Void> original,
                                                           boolean translucent) {
        if (!ThermalRenderMode.isPhotonThermal()) {
            original.call(mode, type, indirect, drawCount, maxDraws, stride);
            return;
        }
        int program = GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int location = program == 0 ? -1
                : GL20C.glGetUniformLocation(program, "createFireControlThermalEnabled");
        boolean routed = location >= 0 && PhotonVoxyThermalFramebufferBridge.begin(
                IrisThermalFramebufferBridge.getColorTexture(), translucent);
        if (location >= 0) {
            GL20C.glUniform1i(location, routed ? 1 : 0);
        }
        try {
            if (routed && !createFireControl$photonRouteLogged) {
                createFireControl$photonRouteLogged = true;
                StabilizedTurret.LOGGER.info("Enabled Photon/Voxy thermal direct-color route");
            }
            if (!routed && !createFireControl$photonFailureLogged) {
                createFireControl$photonFailureLogged = true;
                StabilizedTurret.LOGGER.warn("Photon thermal Voxy route unavailable: program={}, color={}",
                        program, IrisThermalFramebufferBridge.getColorTexture());
            }
            original.call(mode, type, indirect, drawCount, maxDraws, stride);
        } finally {
            if (routed) {
                PhotonVoxyThermalFramebufferBridge.end();
            }
        }
    }

    @Inject(
            method = "renderTranslucent",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/cortex/voxy/client/core/AbstractRenderPipeline;setupAndBindTranslucent(Lme/cortex/voxy/client/core/rendering/Viewport;)V",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void createFireControl$beginTranslucent(@Coerce Object viewport, CallbackInfo ci) {
        if (!ThermalRenderMode.isPhotonThermal()) {
            VoxyThermalFramebufferBridge.begin();
        }
    }

    @Inject(method = "renderTranslucent", at = @At("TAIL"), require = 0)
    private void createFireControl$endTranslucent(@Coerce Object viewport, CallbackInfo ci) {
        VoxyThermalFramebufferBridge.end();
    }

    @Unique
    private static void createFireControl$setThermalUniform() {
        int program = GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program == 0) {
            return;
        }
        int location = GL20C.glGetUniformLocation(program, "createFireControlThermalEnabled");
        if (location >= 0) {
            GL20C.glUniform1i(location, ClientAimState.isThermal() ? 1 : 0);
        }
    }
}
