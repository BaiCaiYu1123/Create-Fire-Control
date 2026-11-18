package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.client.MzCannonBreechClient;
import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonRecoilAnimation;
import com.hooya.stabilizedturret.content.cannon.MzCannonBreechBlockEntity;
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
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlock;

/** Renders the fixed opening linkage and the recoiling MZ/group/group + co assemblies. */
public final class MzCannonBreechRenderer
        extends SmartBlockEntityRenderer<MzCannonBreechBlockEntity> {
    public MzCannonBreechRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRenderOffScreen(MzCannonBreechBlockEntity blockEntity) {
        return true;
    }

    @Override
    protected void renderSafe(
            MzCannonBreechBlockEntity blockEntity,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            int overlay
    ) {
        BlockState state = blockEntity.getBlockState();
        float openProgress = Mth.clamp(blockEntity.getOpenProgress(partialTicks), 0.0F, 1.0F);
        int frame = Mth.clamp(
                Math.round(openProgress * (MzCannonBreechClient.FRAME_COUNT - 1)),
                0,
                MzCannonBreechClient.FRAME_COUNT - 1
        );

        VertexConsumer vertices = buffer.getBuffer(RenderType.solid());

        // Group 11 only performs the breech-opening animation and stays fixed while
        // the cannon recoils.
        poseStack.pushPose();
        orientModel(poseStack, state.getValue(MediumcannonBreechBlock.FACING));
        SuperByteBuffer moving = CachedBuffers.partial(
                MzCannonBreechClient.OPENING_FRAMES[frame], state);
        moving.light(light).renderInto(poseStack, vertices);
        poseStack.popPose();

        // Blockbench MZ/group/group and MZ/co travel rearward together during firing.
        // The co frame also carries its independent breech-opening translation.
        poseStack.pushPose();
        YuushyaCannonRecoilAnimation recoilAnimation = blockEntity;
        Direction recoilDirection = recoilAnimation.stabilizedTurret$getRecoilDirection();
        float recoil = recoilAnimation.stabilizedTurret$getRecoilOffset(partialTicks);
        poseStack.translate(
                -recoilDirection.getStepX() * recoil,
                -recoilDirection.getStepY() * recoil,
                -recoilDirection.getStepZ() * recoil
        );
        orientModel(poseStack, state.getValue(MediumcannonBreechBlock.FACING));
        SuperByteBuffer recoilBody = CachedBuffers.partial(MzCannonBreechClient.RECOIL_BODY, state);
        recoilBody.light(light).renderInto(poseStack, vertices);
        SuperByteBuffer co = CachedBuffers.partial(MzCannonBreechClient.CO_FRAMES[frame], state);
        co.light(light).renderInto(poseStack, vertices);
        poseStack.popPose();
    }

    private static void orientModel(PoseStack poseStack, Direction facing) {
        poseStack.translate(0.5, 0.5, 0.5);
        switch (facing) {
            case SOUTH -> poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
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
