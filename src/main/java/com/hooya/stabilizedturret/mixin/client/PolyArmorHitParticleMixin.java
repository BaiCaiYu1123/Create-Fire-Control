package com.hooya.stabilizedturret.mixin.client;

import com.caca.copypoly.PolyBlock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Direct callers such as Effortless bypass Create's normal custom hit-particle handler. */
@Mixin(ParticleEngine.class)
public abstract class PolyArmorHitParticleMixin {
    @Shadow protected ClientLevel level;

    @Inject(method="crack",at=@At("HEAD"),cancellable=true)
    private void fireControl$skipEmptyArmorHit(BlockPos pos,Direction face,CallbackInfo ci){
        var state=level.getBlockState(pos);
        if(state.getBlock() instanceof PolyBlock&&state.getShape(level,pos).isEmpty())ci.cancel();
    }
}
