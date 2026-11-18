package com.hooya.stabilizedturret.client.compat.mianbao;

import com.hooya.stabilizedturret.compat.mianbao.MianbaoCannonCompat;
import com.hooya.stabilizedturret.compat.mianbao.MianbaoMountedAutocannonContraption;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ActorVisual;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.core.Direction;

/** Flywheel path for Mianbao barrel rotation and recoil. */
public final class MianbaoRotaryBarrelActorVisual extends ActorVisual {
    private final TransformedInstance barrel;
    private final Direction facing;
    private final boolean rotary;

    public MianbaoRotaryBarrelActorVisual(
            VisualizationContext visualizationContext,
            VirtualRenderWorld simulationWorld,
        MovementContext context
    ) {
        super(visualizationContext, simulationWorld, context);
        this.facing = MianbaoCannonCompat.facing(context.state);
        this.rotary = MianbaoCannonCompat.isRotaryBarrel(context.state);
        this.barrel = this.instancerProvider
                .instancer(InstanceTypes.TRANSFORMED, Models.block(context.state))
                .createInstance();
        this.barrel.light(localBlockLight(), 0).setChanged();
    }

    @Override
    public void beginFrame() {
        if (!(this.context.contraption instanceof MianbaoMountedAutocannonContraption cannon)) {
            this.barrel.setVisible(false);
            return;
        }
        float partialTicks = AnimationTickHolder.getPartialTicks();
        float recoil = cannon.getBarrelRecoil(partialTicks);
        float rotaryAngle = this.rotary ? cannon.getRotaryAngle(partialTicks) : 0.0F;
        this.barrel.setVisible(true);
        this.barrel.setIdentityTransform()
                .translate(this.context.localPos)
                .translate(
                        -this.facing.getStepX() * recoil,
                        -this.facing.getStepY() * recoil,
                        -this.facing.getStepZ() * recoil)
                .rotateCenteredDegrees(
                        rotaryAngle,
                        this.facing.getAxis()
                )
                .setChanged();
    }

    @Override
    protected void _delete() {
        this.barrel.delete();
    }
}
