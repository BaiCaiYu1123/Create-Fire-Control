package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.content.loader.Type10LoaderBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public final class Type10LoaderRenderer extends GeoBlockRenderer<Type10LoaderBlockEntity> {
    public Type10LoaderRenderer(BlockEntityRendererProvider.Context context) {
        super(new Type10LoaderModel());
    }

    @Override
    public boolean shouldRenderOffScreen(Type10LoaderBlockEntity loader) { return true; }

    @Override
    protected void rotateBlock(Direction facing, PoseStack poseStack) { }

    @Override
    public void preRender(PoseStack poseStack, Type10LoaderBlockEntity loader, BakedGeoModel model,
                          MultiBufferSource buffers, VertexConsumer vertices, boolean reRender,
                          float partialTick, int light, int overlay, int colour) {
        super.preRender(poseStack, loader, model, buffers, vertices, reRender,
                partialTick, light, overlay, colour);
        Direction facing = loader.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        poseStack.mulPose(Axis.YP.rotationDegrees(switch (facing) {
            case NORTH -> 180.0F;
            case WEST -> -90.0F;
            case EAST -> 90.0F;
            default -> 0.0F;
        }));
    }

    @Override
    public void renderRecursively(PoseStack poseStack, Type10LoaderBlockEntity loader, GeoBone bone,
                                  RenderType renderType, MultiBufferSource buffers, VertexConsumer vertices,
                                  boolean reRender, float partialTick, int light, int overlay, int colour) {
        for (int i = 0; i < Type10LoaderModel.SHELL_BONES.length; i++) {
            if (Type10LoaderModel.SHELL_BONES[i].equals(bone.getName())) {
                bone.setHidden((loader.getSlots() & (1 << i)) == 0);
                break;
            }
        }
        super.renderRecursively(poseStack, loader, bone, renderType, buffers, vertices,
                reRender, partialTick, light, overlay, colour);
    }
}
