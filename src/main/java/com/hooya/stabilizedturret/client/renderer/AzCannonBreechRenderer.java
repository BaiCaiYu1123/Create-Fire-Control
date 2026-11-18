package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.client.AzCannonBreechClient;
import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonRecoilAnimation;
import com.hooya.stabilizedturret.content.cannon.AzCannonBreechBlockEntity;
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

/** Separates fixed opening parts from the group/group + co recoil assembly. */
public final class AzCannonBreechRenderer
        extends SmartBlockEntityRenderer<AzCannonBreechBlockEntity> {
    public AzCannonBreechRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRenderOffScreen(AzCannonBreechBlockEntity blockEntity) {
        return true;
    }

    @Override
    protected void renderSafe(AzCannonBreechBlockEntity blockEntity, float partialTicks,
                              PoseStack poseStack, MultiBufferSource buffer,
                              int light, int overlay) {
        BlockState state = blockEntity.getBlockState();
        float openProgress = Mth.clamp(blockEntity.getOpenProgress(partialTicks), 0.0F, 1.0F);
        int frame = Mth.clamp(Math.round(openProgress * (AzCannonBreechClient.FRAME_COUNT - 1)),
                0, AzCannonBreechClient.FRAME_COUNT - 1);
        VertexConsumer vertices = buffer.getBuffer(RenderType.solid());
        Direction facing = state.getValue(MediumcannonBreechBlock.FACING);

        // group/11 lifts 30 degrees around its Blockbench axis at
        // [0.00801, 6.54375, 12.55789]; f rotates +90 degrees around its own
        // Z hinge at [1.5, 5.85, 27.75].
        poseStack.pushPose();
        orientModel(poseStack, facing);
        render(AzCannonBreechClient.OPENING_FRAMES[frame], state,
                poseStack, vertices, light);
        poseStack.popPose();

        // group/group and co recoil together; co also slides left by eight pixels.
        poseStack.pushPose();
        YuushyaCannonRecoilAnimation recoilAnimation = blockEntity;
        Direction recoilDirection = recoilAnimation.stabilizedTurret$getRecoilDirection();
        float recoil = recoilAnimation.stabilizedTurret$getRecoilOffset(partialTicks);
        poseStack.translate(-recoilDirection.getStepX() * recoil,
                -recoilDirection.getStepY() * recoil,
                -recoilDirection.getStepZ() * recoil);
        orientModel(poseStack, facing);
        render(AzCannonBreechClient.RECOIL_BODY, state, poseStack, vertices, light);
        render(AzCannonBreechClient.CO_FRAMES[frame], state, poseStack, vertices, light);
        poseStack.popPose();
    }

    private static void render(dev.engine_room.flywheel.lib.model.baked.PartialModel model,
                               BlockState state, PoseStack poseStack,
                               VertexConsumer vertices, int light) {
        SuperByteBuffer buffer = CachedBuffers.partial(model, state);
        buffer.light(light).renderInto(poseStack, vertices);
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
