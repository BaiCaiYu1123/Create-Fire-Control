package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.tiger.TigerIEngineBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Resolve a neighbour's source to the correct side of a Tiger I engine, including validation. */
@Mixin(value=KineticBlockEntity.class,remap=false)
public abstract class TigerIKineticPortMixin {
    @Inject(method="setSource",at=@At("HEAD"),cancellable=true)
    private void tigerI$sourcePort(BlockPos source,CallbackInfo ci) {
        var node=(KineticBlockEntity)(Object)this;
        if(node.getLevel()==null || node.getLevel().isClientSide)return;
        var resolved=TigerIEngineBlockEntity.resolvePortSource(node.getLevel(),source,node.getBlockPos());
        if(!(resolved instanceof TigerIEngineBlockEntity.Port port))return;
        node.source=source;node.setNetwork(port.network);node.sequenceContext=port.sequenceContext;ci.cancel();
    }
    @Inject(method="validateKinetics",at=@At("HEAD"),cancellable=true)
    private void tigerI$validatePort(CallbackInfo ci) {
        var node=(KineticBlockEntity)(Object)this;
        if(!node.hasSource() || !node.hasNetwork() || !node.getLevel().isLoaded(node.source))return;
        var resolved=TigerIEngineBlockEntity.resolvePortSource(node.getLevel(),node.source,node.getBlockPos());
        if(resolved instanceof TigerIEngineBlockEntity.Port port && port.getTheoreticalSpeed()!=0)ci.cancel();
    }
}
