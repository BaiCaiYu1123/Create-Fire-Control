package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record RadarLockPayload(UUID target) implements CustomPacketPayload {
    public static final Type<RadarLockPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "radar_lock"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RadarLockPayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> {
                buffer.writeBoolean(payload.target != null);
                if (payload.target != null) buffer.writeUUID(payload.target);
            }, buffer -> new RadarLockPayload(buffer.readBoolean() ? buffer.readUUID() : null));

    public static void handle(RadarLockPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            StabilizerControllerBlockEntity controller =
                    ControllerTracker.findForVehicle(player.level(), player.getVehicle());
            if (controller != null) controller.toggleRadarLock(player, payload.target);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
