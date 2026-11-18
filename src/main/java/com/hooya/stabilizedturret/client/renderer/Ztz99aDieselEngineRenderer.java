package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.content.diesel.*;
import com.hooya.stabilizedturret.client.Ztz99aDieselAnimationModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;

public final class Ztz99aDieselEngineRenderer extends KineticBlockEntityRenderer<Ztz99aDieselEngineBlockEntity> {
    public Ztz99aDieselEngineRenderer(BlockEntityRendererProvider.Context context) { super(context); }
    @Override public boolean shouldRenderOffScreen(Ztz99aDieselEngineBlockEntity engine) {
        return engine.isControlPart();
    }
    @Override protected void renderSafe(Ztz99aDieselEngineBlockEntity engine, float partialTicks,
            PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var state = engine.getBlockState();
        if (engine.isControlPart()) renderModelDetails(engine, partialTicks, pose, buffers, light, overlay);
        var direction = Ztz99aDieselEngineBlock.outputDirection(state);
        if (direction == null) return;
        var axis = direction.getAxis();
        float angle = AnimationTickHolder.getRenderTime(engine.getLevel()) * engine.getSpeed() * 0.3F % 360F
                + getRotationOffsetForPosition(engine, engine.getBlockPos(), axis);
        var shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, direction);
        kineticRotationTransform(shaft, engine, axis, angle * ((float)Math.PI/180F), light);
        shaft.renderInto(pose, buffers.getBuffer(RenderType.solid()));
    }
    private void renderModelDetails(Ztz99aDieselEngineBlockEntity engine, float partialTicks,
            PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        pose.translate(.5, .5, .5);
        float yaw = switch(engine.getBlockState().getValue(Ztz99aDieselEngineBlock.FACING)) {
            case EAST -> -90; case SOUTH -> 180; case WEST -> 90; default -> 0;
        };
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-.5, -.5, -.5);
        var minecraft = Minecraft.getInstance();
        // These are visual geometry, never hidden world blocks that reserve surrounding space.
        for (var part : Ztz99aDieselEnginePart.values()) {
            if (part.isOccupied()) continue;
            pose.pushPose();
            pose.translate(part.x, part.y, part.z);
            minecraft.getBlockRenderer().getModelRenderer().renderModel(pose.last(),
                    buffers.getBuffer(RenderType.solid()), null,
                    minecraft.getModelManager().getModel(Ztz99aDieselAnimationModels.SLICES[part.ordinal()]),
                    1, 1, 1, light, overlay);
            pose.popPose();
        }
        for (int i = 0; i < Ztz99aDieselAnimationModels.FANS.length; i++) {
            float[] pivot = Ztz99aDieselModelAnimation.PIVOTS[i], axis = Ztz99aDieselModelAnimation.AXES[i];
            float angle = Ztz99aDieselModelAnimation.angle(i, engine.getModelAnimationSeconds(partialTicks));
            pose.pushPose();
            pose.translate(pivot[0], pivot[1], pivot[2]);
            pose.mulPose(new Quaternionf().rotationAxis((float)Math.toRadians(angle), axis[0], axis[1], axis[2]));
            minecraft.getBlockRenderer().getModelRenderer().renderModel(pose.last(),
                    buffers.getBuffer(RenderType.cutout()), null,
                    minecraft.getModelManager().getModel(Ztz99aDieselAnimationModels.FANS[i]),
                    1, 1, 1, light, overlay);
            pose.popPose();
        }
        pose.popPose();
    }
}
