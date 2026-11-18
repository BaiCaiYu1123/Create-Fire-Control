package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.content.tiger.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** Two native half-shafts at the same block centre, matching the gas turbine's placement. */
public final class TigerIEngineRenderer extends KineticBlockEntityRenderer<TigerIEngineBlockEntity> {
    public TigerIEngineRenderer(BlockEntityRendererProvider.Context context) { super(context); }
    @Override protected void renderSafe(TigerIEngineBlockEntity engine, float partialTicks, PoseStack pose,
            MultiBufferSource buffers, int light, int overlay) {
        var state = engine.getBlockState();
        if(!engine.isControlPart())return;
        var left=state.getValue(TigerIEngineBlock.FACING).getCounterClockWise();
        for(var direction:new net.minecraft.core.Direction[]{left,left.getOpposite()}) {
            var axis=direction.getAxis();
            float angle=AnimationTickHolder.getRenderTime(engine.getLevel())*engine.halfShaftSpeed(direction)*.3F
                    +getRotationOffsetForPosition(engine,engine.getBlockPos(),axis);
            var shaft=CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF,state,direction);
            kineticRotationTransform(shaft,engine,axis,angle*((float)Math.PI/180),light);
            shaft.renderInto(pose,buffers.getBuffer(RenderType.solid()));
        }
    }
}
