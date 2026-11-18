package com.hooya.stabilizedturret.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface LevelRendererRemoteImpactAccessor {
    @Accessor("sectionRenderDispatcher")
    SectionRenderDispatcher stabilizedTurret$getSectionRenderDispatcher();
}
