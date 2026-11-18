package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.compat.QModelingThermalRenderBridge;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Hides only qModeling's accelerated instances while its thermal fallback draws them. */
@Pseudo
@Mixin(targets = "qwxon.qmodeling.client.render.WorkspaceFlywheelVisual", remap = false)
public abstract class QModelingThermalVisualMixin {
    @Shadow @Final private Map<?, ?> nativeVisuals;
    @Shadow private int appliedWorldLight;
    @Unique private boolean createFireControl$wasThermalFallback;

    @ModifyExpressionValue(method = "shouldRenderNativeGeometry", at = @At(value = "INVOKE",
            target = "Ldev/engine_room/flywheel/api/backend/BackendManager;isBackendOn()Z"), require = 0)
    private static boolean createFireControl$useThermalFallback(boolean backendOn) {
        // Keep qModeling's editor/preview conditions and static-item fallback.
        return backendOn && !QModelingThermalRenderBridge.useNativeGeometry();
    }

    @ModifyExpressionValue(method = "shouldDisplaySnapshot", at = @At(value = "INVOKE",
            target = "Ldev/engine_room/flywheel/api/backend/BackendManager;isBackendOn()Z"), require = 0)
    private boolean createFireControl$hideThermalSnapshot(boolean backendOn) {
        return backendOn && !QModelingThermalRenderBridge.useNativeGeometry();
    }

    @Inject(method = "beginFrame", at = @At("HEAD"), require = 0)
    private void createFireControl$refreshAnimationOnThermalSwitch(CallbackInfo ci) {
        boolean fallback = QModelingThermalRenderBridge.useNativeGeometry();
        if (fallback != createFireControl$wasThermalFallback) {
            // Paused animations also need a visibility refresh after the optic
            // closes. Leave mesh revisions, build queues and the backend alone.
            appliedWorldLight = Integer.MIN_VALUE;
            createFireControl$wasThermalFallback = fallback;
        }
    }

    @Inject(method = "beginFrame", at = @At("RETURN"), require = 0)
    private void createFireControl$hideThermalNativeChildren(CallbackInfo ci) {
        if (QModelingThermalRenderBridge.useNativeGeometry()) {
            // qModeling's animated-child branch skips its normal visibility loop
            // when an animation slot is selected; cover that branch explicitly.
            for (Object child : nativeVisuals.values()) {
                if (child instanceof QModelingNativeVisualThermalAccess access) {
                    access.createFireControl$setThermalVisible(false);
                }
            }
        }
    }
}
