package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.client.Type10CannonBreechClient;
import com.hooya.stabilizedturret.content.cannon.Type10CannonBreechBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlock;

/** The source group slides down five model pixels; the whole breech follows normal recoil. */
public final class Type10CannonBreechRenderer extends SmartBlockEntityRenderer<Type10CannonBreechBlockEntity> {
    public static final float OPEN_TRAVEL = 5.0F / 16.0F;

    public Type10CannonBreechRenderer(BlockEntityRendererProvider.Context context) { super(context); }

    @Override
    public boolean shouldRenderOffScreen(Type10CannonBreechBlockEntity be) { return true; }

    @Override
    protected void renderSafe(Type10CannonBreechBlockEntity be, float partialTick, PoseStack poses,
                              MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        Direction recoilDirection = be.stabilizedTurret$getRecoilDirection();
        float recoil = be.stabilizedTurret$getRecoilOffset(partialTick);
        VertexConsumer vertices = buffers.getBuffer(RenderType.solid());
        poses.pushPose();
        poses.translate(-recoilDirection.getStepX() * recoil,
                -recoilDirection.getStepY() * recoil, -recoilDirection.getStepZ() * recoil);
        orientModel(poses, state.getValue(MediumcannonBreechBlock.FACING));
        render(Type10CannonBreechClient.BODY, state, poses, vertices, light);
        poses.translate(0, -OPEN_TRAVEL * Mth.clamp(be.getOpenProgress(partialTick), 0, 1), 0);
        render(Type10CannonBreechClient.SLIDING_BLOCK, state, poses, vertices, light);
        poses.popPose();
    }

    private static void render(PartialModel model, BlockState state, PoseStack poses,
                               VertexConsumer vertices, int light) {
        CachedBuffers.partial(model, state).light(light).renderInto(poses, vertices);
    }

    private static void orientModel(PoseStack poses, Direction facing) {
        poses.translate(0.5, 0.5, 0.5);
        switch (facing) {
            case SOUTH -> poses.mulPose(Axis.YP.rotationDegrees(180));
            case EAST -> poses.mulPose(Axis.YP.rotationDegrees(-90));
            case WEST -> poses.mulPose(Axis.YP.rotationDegrees(90));
            case UP -> {
                poses.mulPose(Axis.XP.rotationDegrees(-90));
                poses.mulPose(Axis.YP.rotationDegrees(180));
            }
            case DOWN -> {
                poses.mulPose(Axis.XP.rotationDegrees(90));
                poses.mulPose(Axis.YP.rotationDegrees(180));
            }
            default -> { }
        }
        poses.translate(-0.5, -0.5, -0.5);
    }
}
