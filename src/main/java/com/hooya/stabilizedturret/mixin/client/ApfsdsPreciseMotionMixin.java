package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsProjectile;
import com.hooya.stabilizedturret.content.armor.EquivalentArmor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.ritchiesprojectilelib.network.ClientboundPreciseMotionSyncPacket;
import rbasamoyai.ritchiesprojectilelib.network.RPLClientHandlers;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/** Keeps the precise-flight and ordinary embedded-projectile position bases in agreement. */
@Mixin(value=RPLClientHandlers.class,remap=false)
public abstract class ApfsdsPreciseMotionMixin {
    @Inject(method="syncPreciseMotion",at=@At("TAIL"))
    private static void fireControl$syncPositionBase(ClientboundPreciseMotionSyncPacket packet,CallbackInfo ci){
        var level=Minecraft.getInstance().level;
        if(level==null||!(level.getEntity(packet.entityId()) instanceof AbstractCannonProjectile projectile))return;
        if(!(projectile instanceof StabilizedApfsdsProjectile)&&!EquivalentArmor.enabled())return;
        // RPL moves the rendered entity without advancing vanilla's relative-packet base.
        // Once CBC embeds it, vanilla deltas would otherwise use the old launch position.
        projectile.syncPacketPositionCodec(packet.x(),packet.y(),packet.z());
        if(projectile.tickCount<2){
            // CBC skips lerpTo in the first two client ticks. A fast shot can already
            // stop during that window, leaving no later movement packet to correct it.
            projectile.setPos(packet.x(),packet.y(),packet.z());
            projectile.setYRot(packet.yRot());projectile.setXRot(packet.xRot());
        }
    }
}
