package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ShaderPackSableMaskPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Patches the final Iris shader-pack source at the exact call that submits it
 * to OpenGL. At this point Veil/Sable and Iris gbuffer transformations are
 * both complete, so base_color and the real attachment packing are visible.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.shader.GlShader", remap = false)
public abstract class IrisFinalThermalShaderMixin {
    @Unique
    private static final AtomicBoolean createFireControl$loggedSableMask =
            new AtomicBoolean();

    @ModifyArg(
            method = "createShader",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/gl/shader/ShaderWorkarounds;"
                            + "safeShaderSource(ILjava/lang/CharSequence;)V",
                    remap = false
            ),
            index = 1
    )
    private static CharSequence createFireControl$patchFinalIrisSource(
            CharSequence source
    ) {
        if (source == null) {
            return null;
        }
        String original = source.toString();
        String patched = ShaderPackSableMaskPatcher.patch(original);
        if (patched != null
                && !patched.equals(original)
                && createFireControl$loggedSableMask.compareAndSet(false, true)) {
            StabilizedTurret.LOGGER.info(
                    "Applied shader-pack-only Sable thermal mask to final Iris GlShader"
            );
        }
        return patched;
    }
}
