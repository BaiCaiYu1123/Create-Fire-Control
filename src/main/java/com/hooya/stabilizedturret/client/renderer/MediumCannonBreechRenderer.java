package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.client.MediumCannonBreechClient;
import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonRecoilAnimation;
import com.hooya.stabilizedturret.content.cannon.MediumCannonBreechBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlock;

public final class MediumCannonBreechRenderer extends SmartBlockEntityRenderer<MediumCannonBreechBlockEntity> {
    private static final float OPEN_TRAVEL = 5.0F / 16.0F;

    public MediumCannonBreechRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRenderOffScreen(MediumCannonBreechBlockEntity blockEntity) {
        return true;
    }

    @Override
    protected void renderSafe(
            MediumCannonBreechBlockEntity blockEntity,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            int overlay
    ) {
        BlockState state = blockEntity.getBlockState();

        // Only the Blockbench group named "group" remains fixed. The dark recoil
        // body and middle grey breech share recoil, but only the middle section opens.
        poseStack.pushPose();
        YuushyaCannonRecoilAnimation animation = blockEntity;
        Direction direction = animation.stabilizedTurret$getRecoilDirection();
        float recoil = animation.stabilizedTurret$getRecoilOffset(partialTicks);
        poseStack.translate(
                -direction.getStepX() * recoil,
                -direction.getStepY() * recoil,
                -direction.getStepZ() * recoil
        );
        orientModel(poseStack, state.getValue(MediumcannonBreechBlock.FACING));

        VertexConsumer vertices = buffer.getBuffer(RenderType.solid());
        SuperByteBuffer recoilBody = CachedBuffers.partial(
                MediumCannonBreechClient.RECOIL_BODY, state);
        recoilBody.light(light).renderInto(poseStack, vertices);

        poseStack.translate(0.0, -OPEN_TRAVEL * blockEntity.getOpenProgress(partialTicks), 0.0);
        SuperByteBuffer moving = CachedBuffers.partial(MediumCannonBreechClient.MOVING_BREECH, state);
        moving.light(light).renderInto(poseStack, vertices);
        poseStack.popPose();
    }

    private static void orientModel(PoseStack poseStack, Direction facing) {
        poseStack.translate(0.5, 0.5, 0.5);
        switch (facing) {
            case SOUTH -> poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            // Blockstate Y rotations use the opposite sign from PoseStack.
            case EAST -> poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));
            case WEST -> poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
            case UP -> {
                poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            }
            case DOWN -> {
                poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            }
            case NORTH -> {
            }
        }
        poseStack.translate(-0.5, -0.5, -0.5);
    }
}
