package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.content.turbine.GasTurbineBlock;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Uses Create's half-shaft model, but renders through the regular block-entity
 * path so the two outputs remain visible inside Sable sublevels as well.
 */
public final class GasTurbineRenderer
        extends KineticBlockEntityRenderer<GasTurbineBlockEntity> {
    public GasTurbineRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(
            GasTurbineBlockEntity blockEntity,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay
    ) {
        BlockState state = blockEntity.getBlockState();
        Direction.Axis axis = GasTurbineBlock.outputAxis(state);
        float renderTime = AnimationTickHolder.getRenderTime(blockEntity.getLevel());
        float baseAngle = renderTime * blockEntity.getSpeed() * 3.0F / 10.0F % 360.0F;
        float offset = getRotationOffsetForPosition(
                blockEntity,
                blockEntity.getBlockPos(),
                axis
        );

        Direction first = GasTurbineBlock.counterClockwiseOutput(state);
        renderHalf(
                blockEntity,
                state,
                first,
                axis,
                baseAngle * blockEntity.getOutputModifier(first) + offset,
                poseStack,
                bufferSource,
                light
        );
        Direction second = first.getOpposite();
        renderHalf(
                blockEntity,
                state,
                second,
                axis,
                baseAngle * blockEntity.getOutputModifier(second) + offset,
                poseStack,
                bufferSource,
                light
        );
    }

    private static void renderHalf(
            GasTurbineBlockEntity blockEntity,
            BlockState state,
            Direction direction,
            Direction.Axis axis,
            float angleDegrees,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light
    ) {
        SuperByteBuffer shaft = CachedBuffers.partialFacing(
                AllPartialModels.SHAFT_HALF,
                state,
                direction
        );
        kineticRotationTransform(
                shaft,
                blockEntity,
                axis,
                angleDegrees * ((float) Math.PI / 180.0F),
                light
        );
        shaft.renderInto(poseStack, bufferSource.getBuffer(RenderType.solid()));
    }
}
