package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.compat.IrisThermalFramebufferBridge;
import com.hooya.stabilizedturret.client.ThermalRenderMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Iris' Sodium redirect use Sodium's already cached native program for
 * a thermal frame. Iris still binds the matching base framebuffer first, so
 * Voxy and the rest of the world continue to share a valid full-resolution
 * scene target.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.SodiumPrograms", remap = false)
public abstract class IrisThermalSodiumProgramsMixin {
    @Inject(method = "getFramebuffer", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$useThermalBaseFramebuffer(
            CallbackInfoReturnable<Object> cir
    ) {
        if (!ThermalRenderMode.isShaderPackThermal()) {
            return;
        }
        Object framebuffer = IrisThermalFramebufferBridge.get();
        if (framebuffer != null) {
            cir.setReturnValue(framebuffer);
        }
    }

    @Inject(method = "getProgram", at = @At("HEAD"), cancellable = true, require = 0)
    private void createFireControl$useNativeSodiumProgram(
            CallbackInfoReturnable<Object> cir
    ) {
        if (ThermalRenderMode.isShaderPackThermal()) {
            cir.setReturnValue(null);
        }
    }
}
