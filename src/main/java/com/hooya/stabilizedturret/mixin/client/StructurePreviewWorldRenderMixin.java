package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.StructurePreviewScreen;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The opaque preview draws its own geometry; world ticking and networking continue. */
@Mixin(GameRenderer.class)
public abstract class StructurePreviewWorldRenderMixin {
    @WrapWithCondition(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private boolean createFireControl$skipPreviewBackground(GameRenderer renderer,DeltaTracker tracker) {
        return !(Minecraft.getInstance().screen instanceof StructurePreviewScreen);
    }
}
