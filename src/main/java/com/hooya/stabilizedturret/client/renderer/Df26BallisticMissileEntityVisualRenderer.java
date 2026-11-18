package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Quaternionf;

/** Draws the complete DF-26 mesh before Mianbao can submit its five native parts. */
public final class Df26BallisticMissileEntityVisualRenderer {
    private static final ModelResourceLocation MODEL = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath("create_fire_control", "block/df_26"));

    public static boolean render(
            Entity missile,
            double x,
            double y,
            double z,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight
    ) {
        Quaternionf rotation = MianbaoBallisticMissileCompat.renderRotation(missile);
        if (rotation == null) return false;
        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
        BakedModel model = minecraft.getModelManager().getModel(MODEL);
        BlockState state = ModBlocks.DF_26_BALLISTIC_MISSILE.get().defaultBlockState();
        RandomSource random = RandomSource.create(missile.getUUID().getLeastSignificantBits());

        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.mulPose(rotation);
        poseStack.translate(-0.5D, -0.5D, -0.5D);
        for (RenderType blockRenderType
                : model.getRenderTypes(state, random, ModelData.EMPTY)) {
            // The nozzle is visible from below during launch. Do not cull the
            // OBJ back faces or the camera can see through the exhaust port.
            VertexConsumer vertices = buffer.getBuffer(
                    RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
            dispatcher.getModelRenderer().renderModel(
                    poseStack.last(), vertices, state, model,
                    1.0F, 1.0F, 1.0F, packedLight, OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY, blockRenderType);
        }
        poseStack.popPose();
        return true;
    }

    private Df26BallisticMissileEntityVisualRenderer() {
    }
}
