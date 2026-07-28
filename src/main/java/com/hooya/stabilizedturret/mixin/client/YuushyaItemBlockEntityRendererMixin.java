package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.compat.YuushyaCannonComponentRenderer;
import com.hooya.stabilizedturret.client.compat.YuushyaCannonRenderContext;
import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yuushya.modelling.blockentity.itemblock.ItemBlockEntity;
import com.yuushya.modelling.blockentity.itemblock.ItemBlockEntityRender;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import riftyboi.cbcmodernwarfare.compat.SableRenderCompat;

@Mixin(ItemBlockEntityRender.class)
public abstract class YuushyaItemBlockEntityRendererMixin {
    @Inject(
            method = "render(Lcom/yuushya/modelling/blockentity/itemblock/ItemBlockEntity;"
                    + "FLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void stabilizedTurret$renderCannonComponents(
            ItemBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            int overlay,
            CallbackInfo ci
    ) {
        boolean createCannon = YuushyaCannonCompat.isBarrel(blockEntity.getBlockState())
                && YuushyaCannonRenderContext.isRenderingCreateContraption();
        boolean sableItemBlock = SableRenderCompat.isInSubLevel(blockEntity);
        if (!createCannon && !sableItemBlock) {
            return;
        }
        YuushyaCannonComponentRenderer.render(blockEntity, partialTick, poseStack, buffer, light, overlay);
        ci.cancel();
    }
}
