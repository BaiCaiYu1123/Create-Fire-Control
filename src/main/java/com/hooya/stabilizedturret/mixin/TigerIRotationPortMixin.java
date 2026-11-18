package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.tiger.TigerIEngineBlockEntity;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

/** Each embedded node can reach only its own output; the controller has no kinetic edges. */
@Mixin(value=RotationPropagator.class,remap=false)
public abstract class TigerIRotationPortMixin {
    @Inject(method="getPotentialNeighbourLocations",at=@At("HEAD"),cancellable=true)
    private static void tigerI$oneSide(KineticBlockEntity node,CallbackInfoReturnable<List<BlockPos>> ci) {
        if(node instanceof TigerIEngineBlockEntity.Port port)
            ci.setReturnValue(List.of(port.getBlockPos().relative(port.output)));
        else if(node instanceof TigerIEngineBlockEntity) ci.setReturnValue(List.of());
    }
    @Inject(method="findConnectedNeighbour",at=@At("HEAD"),cancellable=true)
    private static void tigerI$neighbourPort(KineticBlockEntity node,BlockPos pos,
            CallbackInfoReturnable<KineticBlockEntity> ci) {
        if(!(node.getLevel().getBlockEntity(pos) instanceof TigerIEngineBlockEntity engine) || !engine.isControlPart())return;
        var resolved=TigerIEngineBlockEntity.resolvePortSource(node.getLevel(),pos,node.getBlockPos());
        if(resolved instanceof TigerIEngineBlockEntity.Port port &&
                (RotationPropagator.isConnected(node,port)||RotationPropagator.isConnected(port,node)))ci.setReturnValue(port);
        else ci.setReturnValue(null);
    }
}
