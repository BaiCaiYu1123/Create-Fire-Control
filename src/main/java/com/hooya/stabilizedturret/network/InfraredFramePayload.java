package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.InfraredTargeting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Epoch rejects delayed camera packets from an earlier weapon selection. */
public record InfraredFramePayload(InfraredTargeting.Frame frame, int epoch) implements CustomPacketPayload {
    public static final Type<InfraredFramePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "infrared_frame"));
    public static final StreamCodec<RegistryFriendlyByteBuf, InfraredFramePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                write(buffer, payload.frame.origin()); write(buffer, payload.frame.forward());
                write(buffer, payload.frame.right()); write(buffer, payload.frame.up());
                buffer.writeDouble(payload.frame.halfTanY()); buffer.writeDouble(payload.frame.aspect());
                buffer.writeDouble(payload.frame.seekerX()); buffer.writeDouble(payload.frame.seekerY());
                write(buffer, payload.frame.aircraftOrigin()); write(buffer, payload.frame.aircraftForward());
                buffer.writeBoolean(payload.frame.seekerActive());
                buffer.writeVarInt(payload.epoch);
            }, buffer -> new InfraredFramePayload(new InfraredTargeting.Frame(
                    read(buffer), read(buffer), read(buffer), read(buffer), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), read(buffer), read(buffer), buffer.readBoolean()), buffer.readVarInt()));
    private static void write(RegistryFriendlyByteBuf buffer, Vec3 v) {
        buffer.writeDouble(v.x); buffer.writeDouble(v.y); buffer.writeDouble(v.z);
    }
    private static Vec3 read(RegistryFriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
    public static void handle(InfraredFramePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var controller = ControllerTracker.findForVehicle(player.level(), player.getVehicle());
            if (controller != null) InfraredTargeting.updateFrame(controller, player, payload.frame, payload.epoch);
        });
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
