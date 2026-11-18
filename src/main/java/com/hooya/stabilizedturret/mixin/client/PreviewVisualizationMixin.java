package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.preview.PreviewCaptureScope;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Preview snapshots need the ordinary renderer, since the world instancing pass is not drawn. */
@Mixin(VisualizationManager.class)
public interface PreviewVisualizationMixin {
    @ModifyReturnValue(method="supportsVisualization",at=@At("RETURN"))
    private static boolean createFireControl$previewFallback(boolean supported) {
        return supported&&!PreviewCaptureScope.active();
    }
}
