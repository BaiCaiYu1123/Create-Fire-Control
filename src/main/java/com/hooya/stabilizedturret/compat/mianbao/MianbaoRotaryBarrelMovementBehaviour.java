package com.hooya.stabilizedturret.compat.mianbao;

import com.hooya.stabilizedturret.client.compat.mianbao.MianbaoRotaryBarrelActorVisual;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ActorVisual;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/** Draws moving Mianbao barrels as dynamic actors; the breech remains static. */
public final class MianbaoRotaryBarrelMovementBehaviour implements MovementBehaviour {
    @Override
    @Nullable
    public ActorVisual createVisual(
            VisualizationContext visualizationContext,
            VirtualRenderWorld simulationWorld,
            MovementContext context
    ) {
        if (!(context.contraption instanceof MianbaoMountedAutocannonContraption cannon)
                || !cannon.isBarrelPosition(context.localPos)) {
            return null;
        }
        return new MianbaoRotaryBarrelActorVisual(visualizationContext, simulationWorld, context);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void renderInContraption(
            MovementContext context,
            VirtualRenderWorld renderWorld,
            ContraptionMatrices matrices,
            MultiBufferSource buffer
    ) {
        if (!(context.contraption instanceof MianbaoMountedAutocannonContraption cannon)
                || !cannon.isBarrelPosition(context.localPos)) {
            return;
        }
        float partialTicks = AnimationTickHolder.getPartialTicks();
        Direction facing = MianbaoCannonCompat.facing(context.state);
        Direction.Axis axis = facing.getAxis();
        float recoil = cannon.getBarrelRecoil(partialTicks);
        float rotaryAngle = cannon.isRotaryBarrelPosition(context.localPos)
                ? cannon.getRotaryAngle(partialTicks)
                : 0.0F;
        SuperByteBuffer model = CachedBuffers.block(context.state);
        model.transform(matrices.getModel())
                .translate(
                        -facing.getStepX() * recoil,
                        -facing.getStepY() * recoil,
                        -facing.getStepZ() * recoil)
                .center()
                .rotateDegrees(rotaryAngle, axis)
                .uncenter()
                .light(LevelRenderer.getLightColor(renderWorld, context.localPos))
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(matrices.getViewProjection(), buffer.getBuffer(RenderType.solid()));
    }
}
