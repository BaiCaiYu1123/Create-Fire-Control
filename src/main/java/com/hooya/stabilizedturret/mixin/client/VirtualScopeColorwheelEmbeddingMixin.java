package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.VirtualScopeVisibility;
import net.minecraft.core.Vec3i;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Colorwheel replaces Flywheel's embedding implementation while Iris shaders are active. */
@Pseudo
@Mixin(targets = "dev.djefrey.colorwheel.engine.embed.EmbeddedEnvironment", remap = false)
public abstract class VirtualScopeColorwheelEmbeddingMixin {
    @Shadow @Final private Vec3i renderOrigin;

    @ModifyVariable(method = "transforms", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Matrix4fc createFireControl$hideShaderTurretInstances(Matrix4fc pose) {
        // Parent embedding visibility propagates to all animated/model children.
        // Sable supplies a fresh pose every frame, restoring instances on scope exit.
        return VirtualScopeVisibility.hidesEmbedding(renderOrigin) ? new Matrix4f(pose).scale(0) : pose;
    }
}
