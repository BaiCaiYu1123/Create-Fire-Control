package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.VirtualScopeVisibility;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CBC's assembled barrel and all its child visuals bypass EntityRenderDispatcher with Flywheel. */
@Mixin(value = ContraptionVisual.class, remap = false)
public abstract class VirtualScopeContraptionVisualMixin extends AbstractEntityVisual<AbstractContraptionEntity> {
    @Shadow @Final protected VisualEmbedding embedding;

    protected VirtualScopeContraptionVisualMixin(VisualizationContext context, AbstractContraptionEntity entity, float partialTick) {
        super(context, entity, partialTick);
    }

    @Inject(method = "beginFrame", at = @At("TAIL"))
    private void createFireControl$hideAssembledBarrel(DynamicVisual.Context context, CallbackInfo ci) {
        if (VirtualScopeVisibility.hides(entity)) {
            embedding.transforms(new Matrix4f().scale(0), new Matrix3f());
        }
    }
}
