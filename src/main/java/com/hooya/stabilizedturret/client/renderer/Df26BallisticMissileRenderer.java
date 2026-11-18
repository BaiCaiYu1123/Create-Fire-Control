package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.content.missile.Df26BallisticMissileBlockEntity;
import com.hooya.stabilizedturret.content.missile.Df26BallisticMissileBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Renders the complete 42-block DF-26 outside the compressed chunk mesh.
 * Sodium-style chunk vertex formats cannot represent vertices this far from
 * their owning block without wrapping them into the wrong positions.
 */
public final class Df26BallisticMissileRenderer
        implements BlockEntityRenderer<Df26BallisticMissileBlockEntity> {
    private static final int VIEW_DISTANCE = 256;
    private static final ModelResourceLocation MODEL = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath("create_fire_control", "block/df_26"));

    public Df26BallisticMissileRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(
            Df26BallisticMissileBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            int packedOverlay
    ) {
        BlockState state = blockEntity.getBlockState();
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        BakedModel model = Minecraft.getInstance().getModelManager().getModel(MODEL);
        RandomSource random = RandomSource.create(blockEntity.getBlockPos().asLong());

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        rotateToFacing(poseStack, state.getValue(Df26BallisticMissileBlock.FACING));
        poseStack.translate(-0.5, -0.5, -0.5);
        for (RenderType blockRenderType
                : model.getRenderTypes(state, random, ModelData.EMPTY)) {
            VertexConsumer vertices = buffer.getBuffer(
                    RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
            dispatcher.getModelRenderer().renderModel(
                    poseStack.last(),
                    vertices,
                    state,
                    model,
                    1.0F,
                    1.0F,
                    1.0F,
                    packedLight,
                    packedOverlay,
                    ModelData.EMPTY,
                    blockRenderType
            );
        }
        poseStack.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(Df26BallisticMissileBlockEntity blockEntity) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(Df26BallisticMissileBlockEntity blockEntity) {
        BlockPos root = blockEntity.getBlockPos();
        Direction facing = blockEntity.getBlockState()
                .getValue(Df26BallisticMissileBlock.FACING);
        BlockPos tip = root.relative(facing, 42);
        return new AABB(
                Math.min(root.getX(), tip.getX()),
                Math.min(root.getY(), tip.getY()),
                Math.min(root.getZ(), tip.getZ()),
                Math.max(root.getX(), tip.getX()) + 1.0,
                Math.max(root.getY(), tip.getY()) + 1.0,
                Math.max(root.getZ(), tip.getZ()) + 1.0
        ).inflate(3.25);
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    @Override
    public boolean shouldRender(
            Df26BallisticMissileBlockEntity blockEntity,
            Vec3 cameraPosition
    ) {
        return Vec3.atCenterOf(blockEntity.getBlockPos())
                .closerThan(cameraPosition, VIEW_DISTANCE);
    }

    private static void rotateToFacing(PoseStack poseStack, Direction facing) {
        switch (facing) {
            case DOWN -> poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
            case NORTH -> poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
            case SOUTH -> poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            case WEST -> poseStack.mulPose(Axis.ZP.rotationDegrees(90.0F));
            case EAST -> poseStack.mulPose(Axis.ZP.rotationDegrees(-90.0F));
            case UP -> {
            }
        }
    }
}
