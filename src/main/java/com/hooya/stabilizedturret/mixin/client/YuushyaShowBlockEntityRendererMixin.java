package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.compat.YuushyaCannonComponentRenderer;
import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yuushya.modelling.blockentity.AbstractTransformBlockEntity;
import com.yuushya.modelling.blockentity.AbstractTransformBlockEntityRender;
import com.yuushya.modelling.blockentity.showblock.ShowBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractTransformBlockEntityRender.class)
public abstract class YuushyaShowBlockEntityRendererMixin {
    @Inject(
            method = "render(Lcom/yuushya/modelling/blockentity/AbstractTransformBlockEntity;"
                    + "FLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD")
    )
    private void stabilizedTurret$renderRecoilBarrel(
            AbstractTransformBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            int overlay,
            CallbackInfo ci
    ) {
        if (!(blockEntity instanceof ShowBlockEntity showBlockEntity)
                || !YuushyaCannonCompat.isBarrel(showBlockEntity.getBlockState())) return;
        YuushyaCannonComponentRenderer.render(
                showBlockEntity, partialTick, poseStack, buffer, light, overlay);
    }
}
