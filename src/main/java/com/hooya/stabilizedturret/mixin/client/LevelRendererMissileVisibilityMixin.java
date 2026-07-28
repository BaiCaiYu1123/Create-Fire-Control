package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.MissileLongRangeEffects;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LevelRenderer has a second entity visibility gate after
 * EntityRenderDispatcher.shouldRender: entities are skipped when their client
 * chunk section has not been compiled. Guided missiles remain paired by the
 * server outside chunk view, so bypass that gate only at their cached block
 * positions. No remote chunk and no unrelated entity is made visible.
 */
@Mixin(value = LevelRenderer.class, priority = 2000)
public abstract class LevelRendererMissileVisibilityMixin {
    @Inject(method = "isSectionCompiled", at = @At("HEAD"), cancellable = true)
    private void createFireControl$renderGuidedMissileOutsideCompiledSections(
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (MissileLongRangeEffects.shouldBypassSectionCompilation(pos)) {
            cir.setReturnValue(true);
        }
    }
}
