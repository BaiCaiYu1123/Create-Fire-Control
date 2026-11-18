package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.compat.QModelingThermalRenderBridge;
import com.hooya.stabilizedturret.client.compat.QModelingThermalGeometryCache;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** qModeling's ordinary renderer already preserves its transforms, animation and ModelData. */
@Pseudo
@Mixin(targets = "qwxon.qmodeling.client.render.WorkspaceBlockEntityRenderer", remap = false)
public abstract class QModelingThermalRendererMixin {
    @ModifyExpressionValue(method = "render(Lqwxon/qmodeling/block/entity/WorkspaceBlockEntity;"
            + "FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE",
                    target = "Ldev/engine_room/flywheel/api/backend/BackendManager;isBackendOn()Z"),
            require = 0)
    private boolean createFireControl$enableThermalGeometry(boolean backendOn) {
        return backendOn && !QModelingThermalRenderBridge.useNativeGeometry()
                && !com.hooya.stabilizedturret.client.preview.PreviewCaptureScope.active();
    }

    @ModifyExpressionValue(method = "render(Lqwxon/qmodeling/block/entity/WorkspaceBlockEntity;"
            + "FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value="INVOKE",target="Lqwxon/qmodeling/client/render/WorkspaceFlywheelVisual;shouldRenderNativeGeometry(Lqwxon/qmodeling/block/entity/WorkspaceBlockEntity;)Z"),require=0)
    private boolean createFireControl$previewNativeGeometry(boolean nativeGeometry) {
        return nativeGeometry || com.hooya.stabilizedturret.client.preview.PreviewCaptureScope.active();
    }

    @WrapMethod(method = "render(Lqwxon/qmodeling/block/entity/WorkspaceBlockEntity;"
            + "FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V")
    private void createFireControl$drawThermalGeometry(@Coerce Object workspace, float partialTick,
                                                       PoseStack pose, MultiBufferSource buffers,
                                                       int light, int overlay, Operation<Void> original) {
        if (!QModelingThermalRenderBridge.useNativeGeometry()
                || com.hooya.stabilizedturret.client.preview.PreviewCaptureScope.active()) {
            original.call(workspace, partialTick, pose, buffers, light, overlay);
            return;
        }
        QModelingThermalRenderBridge.render(buffers,
                thermalBuffers -> original.call(workspace, partialTick, pose, thermalBuffers, light, overlay));
    }

    @WrapMethod(method = "renderNativeGeometry(Lqwxon/qmodeling/block/entity/WorkspaceBlockEntity;"
            + "FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V")
    private void createFireControl$cacheThermalGeometry(@Coerce Object workspace, float partialTick,
                                                       PoseStack pose, MultiBufferSource buffers,
                                                       int light, int overlay, Operation<Void> original) {
        QModelingThermalGeometryCache.render(workspace, pose, buffers, light, overlay,
                (localPose, source) -> original.call(workspace, partialTick, localPose, source, light, overlay));
    }
}
