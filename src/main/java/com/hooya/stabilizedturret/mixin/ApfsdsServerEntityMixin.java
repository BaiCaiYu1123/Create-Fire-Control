package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsProjectile;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.ritchiesprojectilelib.network.ClientboundPreciseMotionSyncPacket;
import rbasamoyai.ritchiesprojectilelib.network.neoforge.NeoForgePacket;

import java.util.function.Consumer;

/** Owns APFSDS flight synchronization before Sable temporarily transforms tracker coordinates. */
@Mixin(ServerEntity.class)
public abstract class ApfsdsServerEntityMixin {
    @Shadow @Final private Entity entity;
    @Shadow @Final private Consumer<Packet<?>> broadcast;

    @Inject(method = "sendChanges", at = @At("HEAD"))
    private void stabilizedTurret$sendApfsdsMotion(CallbackInfo ci) {
        if (stabilizedTurret$preciseFlight()) {
            // Some NeoForge RPL distributions omit their ServerEntity mixin
            // registration entirely. Send explicitly through its registered
            // payload channel instead of depending on the tag alone.
            broadcast.accept(new ClientboundCustomPayloadPacket(stabilizedTurret$motionPayload()));
            entity.hasImpulse = false;
        }
    }

    @WrapOperation(method = {"sendChanges", "broadcastAndSend"}, at = @At(
            value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private void stabilizedTurret$filterApfsdsVanillaMotion(
            Consumer<Object> sink, Object packet, Operation<Void> original) {
        // Select by packet type, not invocation ordinal: 1.21 added a projectile
        // power bundle and shifted the old RPL position-packet redirect.
        if (!stabilizedTurret$preciseFlight() || !stabilizedTurret$vanillaMotion(packet)) {
            original.call(sink, packet);
        }
    }

    @WrapOperation(method = "sendPairingData", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/network/bundle/PacketAndPayloadAcceptor;accept(Lnet/minecraft/network/protocol/Packet;)Lnet/neoforged/neoforge/network/bundle/PacketAndPayloadAcceptor;"))
    private PacketAndPayloadAcceptor<ClientGamePacketListener> stabilizedTurret$filterApfsdsPairingMotion(
            PacketAndPayloadAcceptor<ClientGamePacketListener> sink,
            Packet<? super ClientGamePacketListener> packet,
            Operation<PacketAndPayloadAcceptor<ClientGamePacketListener>> original) {
        return stabilizedTurret$preciseFlight() && packet instanceof ClientboundSetEntityMotionPacket
                ? sink : original.call(sink, packet);
    }

    @Inject(method = "sendPairingData", at = @At("TAIL"))
    private void stabilizedTurret$pairApfsdsMotion(ServerPlayer player,
            PacketAndPayloadAcceptor<ClientGamePacketListener> sink, CallbackInfo ci) {
        if (stabilizedTurret$preciseFlight()) {
            // This must follow entity creation and CBC's complex spawn data.
            sink.accept(stabilizedTurret$motionPayload());
        }
    }

    @Unique
    private boolean stabilizedTurret$preciseFlight() {
        return entity instanceof StabilizedApfsdsProjectile projectile && !projectile.isInGround();
    }

    @Unique
    private NeoForgePacket stabilizedTurret$motionPayload() {
        Vec3 position = entity.position(), velocity = entity.getDeltaMovement();
        return new NeoForgePacket(new ClientboundPreciseMotionSyncPacket(entity.getId(),
                position.x, position.y, position.z, velocity.x, velocity.y, velocity.z,
                entity.getYRot(), entity.getXRot(), entity.onGround(), 3));
    }

    @Unique
    private static boolean stabilizedTurret$vanillaMotion(Object packet) {
        return packet instanceof ClientboundMoveEntityPacket || packet instanceof ClientboundTeleportEntityPacket
                || packet instanceof ClientboundSetEntityMotionPacket;
    }
}
