package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.compat.YuushyaCannonRenderContext;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.render.BlockEntityRenderHelper;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;
import java.util.List;

@Mixin(BlockEntityRenderHelper.class)
public abstract class YuushyaBlockEntityRenderHelperMixin {
    @Inject(method = "renderBlockEntities", at = @At("HEAD"))
    private static void stabilizedTurret$enterCreateContraptionRender(
            List<BlockEntity> blockEntities,
            BitSet shouldRender,
            BitSet errored,
            VirtualRenderWorld renderLevel,
            Level realLevel,
            PoseStack poseStack,
            Matrix4f lightTransform,
            MultiBufferSource buffer,
            float partialTick,
            CallbackInfo ci
    ) {
        YuushyaCannonRenderContext.enterCreateContraption();
    }

    @Inject(method = "renderBlockEntities", at = @At("RETURN"))
    private static void stabilizedTurret$exitCreateContraptionRender(
            List<BlockEntity> blockEntities,
            BitSet shouldRender,
            BitSet errored,
            VirtualRenderWorld renderLevel,
            Level realLevel,
            PoseStack poseStack,
            Matrix4f lightTransform,
            MultiBufferSource buffer,
            float partialTick,
            CallbackInfo ci
    ) {
        YuushyaCannonRenderContext.exitCreateContraption();
    }
}
