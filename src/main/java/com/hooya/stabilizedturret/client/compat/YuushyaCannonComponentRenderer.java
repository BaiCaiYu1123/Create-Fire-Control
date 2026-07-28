package com.hooya.stabilizedturret.client.compat;

import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonRecoilAnimation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yuushya.modelling.blockentity.itemblock.ItemBlockEntity;
import com.yuushya.modelling.blockentity.transformData.TransformItemData;
import com.yuushya.modelling.client.anvilcraft.rendering.CachedRegion;
import com.yuushya.modelling.registries.DataComponentRegistry;
import com.yuushya.modelling.utils.YuushyaUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BuiltInModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class YuushyaCannonComponentRenderer {
    private static final List<Direction> DIRECTIONS = createDirections();

    public static void render(
            ItemBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            int overlay
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        ItemRenderer itemRenderer = minecraft.getItemRenderer();
        RandomSource random = RandomSource.create(blockEntity.getBlockPos().asLong());
        float facing = blockEntity.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();

        poseStack.pushPose();
        if (blockEntity instanceof YuushyaCannonRecoilAnimation animation) {
            Direction direction = animation.stabilizedTurret$getRecoilDirection();
            float recoil = animation.stabilizedTurret$getRecoilOffset(partialTick);
            poseStack.translate(
                    -direction.getStepX() * recoil,
                    -direction.getStepY() * recoil,
                    -direction.getStepZ() * recoil
            );
        }

        for (TransformItemData transform : blockEntity.getTransformData()) {
            ItemStack stack = transform.itemStack;
            if (!transform.isShown || stack.isEmpty()) {
                continue;
            }

            poseStack.pushPose();
            poseStack.translate(0.5F, 0.5F, 0.5F);
            poseStack.mulPose(Axis.YP.rotationDegrees(-facing));
            poseStack.translate(-0.5F, -0.5F, -0.5F);
            YuushyaUtils.scale(poseStack, transform.scales);
            YuushyaUtils.translate(poseStack, transform.pos);
            YuushyaUtils.rotate(poseStack, transform.rot);

            BlockState representedState = transform.enableBlock
                    ? stack.get(DataComponentRegistry.BLOCKSTATE)
                    : null;
            if (representedState != null) {
                renderBlock(transform, representedState, poseStack, buffer, light, overlay, random);
            } else {
                renderItem(transform, stack, poseStack, buffer, light, overlay, random, itemRenderer, blockEntity);
            }
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    private static void renderBlock(
            TransformItemData transform,
            BlockState state,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            int overlay,
            RandomSource random
    ) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        for (RenderType renderType : model.getRenderTypes(state, random, ModelData.EMPTY)) {
            VertexConsumer vertices = buffer.getBuffer(renderType);
            for (Direction direction : DIRECTIONS) {
                List<BakedQuad> quads = model.getQuads(state, direction, random, ModelData.EMPTY, renderType);
                renderQuads(transform, model, quads, poseStack, vertices, light, overlay);
            }
        }
    }

    private static void renderItem(
            TransformItemData transform,
            ItemStack stack,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            int overlay,
            RandomSource random,
            ItemRenderer itemRenderer,
            ItemBlockEntity blockEntity
    ) {
        BakedModel baseModel = itemRenderer.getModel(stack, blockEntity.getLevel(), null, (int) blockEntity.getBlockPos().asLong());
        for (BakedModel model : baseModel.getRenderPasses(stack, true)) {
            if (model instanceof BuiltInModel) {
                itemRenderer.render(stack, ItemDisplayContext.NONE, false, poseStack, buffer, light, overlay, model);
                continue;
            }
            for (RenderType renderType : model.getRenderTypes(stack, true)) {
                VertexConsumer vertices = buffer.getBuffer(renderType);
                for (Direction direction : DIRECTIONS) {
                    renderQuads(
                            transform,
                            model,
                            model.getQuads(null, direction, random),
                            poseStack,
                            vertices,
                            light,
                            overlay
                    );
                }
            }
        }
    }

    private static void renderQuads(
            TransformItemData transform,
            BakedModel model,
            List<BakedQuad> quads,
            PoseStack poseStack,
            VertexConsumer vertices,
            int light,
            int overlay
    ) {
        for (BakedQuad quad : quads) {
            float[] color = CachedRegion.getColorComponents(transform, model, quad);
            vertices.putBulkData(
                    poseStack.last(),
                    quad,
                    color[0],
                    color[1],
                    color[2],
                    1.0F,
                    light,
                    overlay
            );
        }
    }

    private static List<Direction> createDirections() {
        List<Direction> directions = new ArrayList<>(Arrays.asList(Direction.values()));
        directions.add(null);
        return directions;
    }

    private YuushyaCannonComponentRenderer() {
    }
}
