package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.RemoteImpactRenderManager;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererRemoteImpactMixin {
    @Shadow @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Inject(method = "setupRender", at = @At("RETURN"))
    private void stabilizedTurret$appendRemoteImpactSections(
            Camera camera, Frustum frustum, boolean capturedFrustum,
            boolean spectator, CallbackInfo ci) {
        RemoteImpactRenderManager.appendVisibleSections(visibleSections, camera, frustum);
    }
}
