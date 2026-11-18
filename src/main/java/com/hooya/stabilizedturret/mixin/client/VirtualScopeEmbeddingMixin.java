package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.VirtualScopeVisibility;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import net.minecraft.core.Vec3i;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Collapse only the visual matrix; lighting normals and physical poses stay valid. */
@Mixin(value = EmbeddedEnvironment.class, remap = false)
public abstract class VirtualScopeEmbeddingMixin {
    @Shadow @Final private Vec3i renderOrigin;

    @ModifyVariable(method = "transforms", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Matrix4fc createFireControl$hideTurretInstances(Matrix4fc pose) {
        return VirtualScopeVisibility.hidesEmbedding(renderOrigin) ? new Matrix4f(pose).scale(0) : pose;
    }
}
