package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalRenderMode;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drives Sodium's white-hot branch independently of chunk mesh rebuilds. */
@Pseudo
@Mixin(
        targets = "net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface",
        remap = false
)
public abstract class SodiumThermalUniformMixin {
    @Unique
    private int createFireControl$thermalProgram = -1;
    @Unique
    private int createFireControl$thermalUniform = -2;

    @Inject(method = "setupState", at = @At("TAIL"), require = 0)
    private void createFireControl$uploadThermalState(CallbackInfo ci) {
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program != createFireControl$thermalProgram) {
            createFireControl$thermalProgram = program;
            createFireControl$thermalUniform = program > 0
                    ? GL20C.glGetUniformLocation(
                    program,
                    "createFireControlThermalEnabled"
            )
                    : -1;
        }
        if (createFireControl$thermalUniform >= 0) {
            GL20C.glUniform1f(
                    createFireControl$thermalUniform,
                    ThermalRenderMode.isFixedLightThermal() ? 1.0F : 0.0F
            );
        }
    }
}
