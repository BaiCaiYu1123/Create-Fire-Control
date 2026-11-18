package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat;
import com.hooya.stabilizedturret.content.missile.client.BallisticMissileWingRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces only the engine part of Mianbao's entity renderer with hinged wings. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.client.renderer.BallisticMissileEntityRenderer",
        remap = false)
public abstract class MianbaoBallisticMissileRendererMixin {
    @Unique private static final ModelResourceLocation createFireControl$df26Model =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(
                    "create_fire_control", "block/df_26"));
    @Shadow @Final private BlockRenderDispatcher blockRenderer;

    @Unique private Entity createFireControl$currentMissile;
    @Unique private float createFireControl$partialTick;

    @Inject(
            method = "render(Lnet/mcreator/myfirstmod/entity/BallisticMissileEntity;"
                    + "FFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void createFireControl$captureMissile(
            @Coerce Object missile,
            float yaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            CallbackInfo ci
    ) {
        createFireControl$currentMissile = missile instanceof Entity entity ? entity : null;
        createFireControl$partialTick = partialTick;
        if (createFireControl$currentMissile == null
                || !MianbaoBallisticMissileCompat.isDf26(
                createFireControl$currentMissile)) return;
        Quaternionf rotation = MianbaoBallisticMissileCompat.renderRotation(
                createFireControl$currentMissile);
        if (rotation == null) return;
        BakedModel model = Minecraft.getInstance().getModelManager()
                .getModel(createFireControl$df26Model);
        poseStack.pushPose();
        poseStack.mulPose(rotation);
        poseStack.translate(-0.5D, -0.5D, -0.5D);
        blockRenderer.getModelRenderer().renderModel(
                poseStack.last(), buffer.getBuffer(RenderType.cutout()), null,
                model, 1.0F, 1.0F, 1.0F, light, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
        createFireControl$currentMissile = null;
        createFireControl$partialTick = 0.0F;
        ci.cancel();
    }

    @Inject(
            method = "renderModel(Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;"
                    + "Lnet/minecraft/client/resources/model/BakedModel;I)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void createFireControl$renderHingedEngine(
            PoseStack poseStack,
            MultiBufferSource buffer,
            BakedModel model,
            int light,
            CallbackInfo ci
    ) {
        if (!BallisticMissileWingRenderer.isOriginalEngineModel(model)) return;
        BallisticMissileWingRenderer.render(
                poseStack, buffer, blockRenderer, light,
                createFireControl$currentMissile, createFireControl$partialTick);
        ci.cancel();
    }

    @Inject(
            method = "render(Lnet/mcreator/myfirstmod/entity/BallisticMissileEntity;"
                    + "FFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"),
            require = 0
    )
    private void createFireControl$clearMissile(
            @Coerce Object missile,
            float yaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            CallbackInfo ci
    ) {
        createFireControl$currentMissile = null;
        createFireControl$partialTick = 0.0F;
    }
}
