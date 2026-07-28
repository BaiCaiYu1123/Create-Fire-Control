package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionVisual;
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

/** Hides the bound CBC/Create cannon even when Flywheel bypasses entity rendering. */
@Mixin(value = ContraptionVisual.class, priority = 1100)
public abstract class ContraptionVisualMixin extends AbstractEntityVisual<AbstractContraptionEntity> {
    @Shadow
    @Final
    protected VisualEmbedding embedding;

    public ContraptionVisualMixin(
            VisualizationContext context,
            AbstractContraptionEntity entity,
            float partialTick
    ) {
        super(context, entity, partialTick);
    }

    @Inject(method = "setEmbeddingMatrices", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$hideScopedCannonVisual(float partialTick, CallbackInfo ci) {
        if (!ClientAimState.shouldHideScopedEntity(entity)) {
            return;
        }
        embedding.transforms(new Matrix4f().scaling(0.0F), new Matrix3f().scaling(0.0F));
        ci.cancel();
    }
}
