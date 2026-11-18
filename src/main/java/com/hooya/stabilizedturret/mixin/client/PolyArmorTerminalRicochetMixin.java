package com.hooya.stabilizedturret.mixin.client;

import com.caca.copypoly.PolyBlock;
import com.cbc_terminal_ballistics.network.ClientboundImpactOutcomePacket;
import com.hooya.stabilizedturret.content.armor.ArmorRicochet;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

@Mixin(targets = "com.cbc_terminal_ballistics.client.ClientPacketHandlers", remap = false)
public abstract class PolyArmorTerminalRicochetMixin {
    @Inject(method = "handleImpactOutcome", at = @At("TAIL"))
    private static void fireControl$discardStalePrediction(ClientboundImpactOutcomePacket packet, CallbackInfo ci) {
        if (!packet.outcome().equals("BOUNCE")) return;
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getBlockState(packet.blockPos()).getBlock() instanceof PolyBlock)
                || !(level.getEntity(packet.entityId()) instanceof AbstractCannonProjectile projectile)) return;

        ((ArmorRicochet.ClientPrediction) projectile).fireControl$clearPredictedImpact();
        var position = packet.position();
        projectile.syncPacketPositionCodec(position.x, position.y, position.z);
        projectile.setOrientation(packet.velocity());
    }
}
