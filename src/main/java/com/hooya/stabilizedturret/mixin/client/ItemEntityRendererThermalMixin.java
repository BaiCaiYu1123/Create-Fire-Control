package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalEmissionLightmap;
import com.hooya.stabilizedturret.client.ThermalRenderMode;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Highlights dropped items that represent a block with its own emission. */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererThermalMixin {
    @Unique
    private int createFireControl$itemEmission;

    @Inject(method = "render", at = @At("HEAD"))
    private void createFireControl$beginItem(
            ItemEntity entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo ci
    ) {
        createFireControl$itemEmission =
                ThermalEmissionLightmap.getItemEmission(entity.getItem());
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void createFireControl$endItem(
            ItemEntity entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo ci
    ) {
        createFireControl$itemEmission = 0;
    }

    @ModifyArg(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;"
                            + "renderMultipleFromCount("
                            + "Lnet/minecraft/client/renderer/entity/ItemRenderer;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource;I"
                            + "Lnet/minecraft/world/item/ItemStack;"
                            + "Lnet/minecraft/client/resources/model/BakedModel;Z"
                            + "Lnet/minecraft/util/RandomSource;)V"
            ),
            index = 3
    )
    private int createFireControl$encodeItemEmission(int packedLight) {
        if (!ThermalRenderMode.isFixedLightThermal()) {
            return packedLight;
        }
        return ThermalEmissionLightmap.encodeBlock(
                packedLight,
                createFireControl$itemEmission
        );
    }
}
