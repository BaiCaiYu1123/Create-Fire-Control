package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientSableMotionHistory;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import dev.ryanhcode.sable.network.packets.PacketReceiveMode;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=ClientboundSableSnapshotDualPacket.class,remap=false)
public abstract class SableSnapshotMotionMixin {
    @Shadow @Final private int interpolationTick;
    @Inject(method="handleClient(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/network/packets/PacketReceiveMode;)V",
            at=@At("HEAD"),remap=false)
    private void createFireControl$captureMotion(Level level,PacketReceiveMode mode,CallbackInfo ci) {
        ClientSableMotionHistory.capture(level,interpolationTick,
                ((ClientboundSableSnapshotDualPacket)(Object)this).entries());
    }
}
