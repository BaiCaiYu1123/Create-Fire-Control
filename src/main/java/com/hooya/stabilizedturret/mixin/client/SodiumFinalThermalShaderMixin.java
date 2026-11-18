package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ThermalHighlightShaderPatcher;
import com.hooya.stabilizedturret.client.ThermalRenderMode;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Patches the exact expanded source passed to Sodium's OpenGL compiler.
 * Veil's processor is kept as an earlier compatibility hook, while this final
 * hook guarantees that Sodium's root terrain programs receive both halves of
 * the coordinate-marker varying.
 */
@Pseudo
@Mixin(
        targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlShader",
        remap = false
)
public abstract class SodiumFinalThermalShaderMixin {
    @Unique
    private static final AtomicBoolean createFireControl$loggedVertex =
            new AtomicBoolean();
    @Unique
    private static final AtomicBoolean createFireControl$loggedFragment =
            new AtomicBoolean();
    @Unique
    private static final AtomicBoolean createFireControl$loggedUnmatchedVertex =
            new AtomicBoolean();
    @Unique
    private static final AtomicBoolean createFireControl$loggedUnmatchedFragment =
            new AtomicBoolean();
    @ModifyExpressionValue(
            method = "<init>",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/"
                            + "ShaderParser$ParsedShader;src()Ljava/lang/String;",
                    remap = false
            )
    )
    private String createFireControl$patchFinalSource(
            String source,
            @Coerce Object shaderType,
            ResourceLocation name,
            @Coerce Object parsedShader
    ) {
        // Iris generates a shader-pack-specific vertex/fragment pair and Voxy
        // patches that pair again. Injecting Sodium's private light-coordinate
        // varying into only one generated stage invalidates the whole Iris
        // pipeline and leaves Voxy without a viewport. Keep external Iris
        // programs byte-for-byte intact; the scene-level thermal post pass and
        // entity/Sable highlights remain independent of this terrain hook.
        if (ThermalRenderMode.isShaderPackInUseNow()
                || createFireControl$isExternalIrisProgram(source, name)) {
            return source;
        }
        String patched = ThermalHighlightShaderPatcher.patch(source);
        if (patched == null || patched.equals(source)) {
            createFireControl$logUnmatchedCandidate(source);
            return source;
        }

        if (patched.contains("flat out vec2 createFireControlThermalHeat")
                && createFireControl$loggedVertex.compareAndSet(false, true)) {
            StabilizedTurret.LOGGER.info(
                    "Applied final Sodium thermal marker patch to terrain vertex shader"
            );
        }
        if (patched.contains("flat in vec2 createFireControlThermalHeat")
                && createFireControl$loggedFragment.compareAndSet(false, true)) {
            StabilizedTurret.LOGGER.info(
                    "Applied final Sodium thermal white-hot patch to terrain fragment shader"
            );
        }
        return patched;
    }

    @Unique
    private static boolean createFireControl$isExternalIrisProgram(
            String source,
            ResourceLocation name
    ) {
        if (name != null && "iris".equals(name.getNamespace())) {
            return true;
        }
        if (source == null) {
            return false;
        }
        // Fail closed for generated shader-pack sources even if Iris is in
        // the middle of a reload and its public active-state briefly lags.
        return source.contains("iris_")
                || source.contains("gl_MultiTexCoord1")
                || source.contains("gbuffer_data_")
                || source.contains("colortex")
                || source.contains("out vec2 light_levels;")
                || source.contains("in vec2 light_levels;");
    }

    @Unique
    private static void createFireControl$logUnmatchedCandidate(String source) {
        if (source == null
                || (!source.contains("u_LightTex")
                && !source.contains("light_levels")
                && !source.contains("gl_MultiTexCoord1"))) {
            return;
        }
        if (source.contains("gl_Position")
                && createFireControl$loggedUnmatchedVertex.compareAndSet(false, true)) {
            StabilizedTurret.LOGGER.warn(
                    "Final Sodium thermal vertex candidate was not matched "
                            + "(vColor={}, lightLevels={}, multiTex={}, length={})",
                    source.contains("v_Color"),
                    source.contains("light_levels"),
                    source.contains("gl_MultiTexCoord1"),
                    source.length()
            );
        } else if ((source.contains("fragColor") || source.contains("base_color"))
                && createFireControl$loggedUnmatchedFragment.compareAndSet(false, true)) {
            StabilizedTurret.LOGGER.warn(
                    "Final Sodium thermal fragment candidate was not matched "
                            + "(vColor={}, lightLevels={}, baseColor={}, length={})",
                    source.contains("v_Color"),
                    source.contains("light_levels"),
                    source.contains("base_color"),
                    source.length()
            );
        }
    }
}
