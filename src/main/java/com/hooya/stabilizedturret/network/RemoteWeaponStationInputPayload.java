package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoRemoteWeaponStationControlHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RemoteWeaponStationInputPayload(boolean firing)
        implements CustomPacketPayload {
    public static final Type<RemoteWeaponStationInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "remote_weapon_station_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            RemoteWeaponStationInputPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBoolean(payload.firing),
            buffer -> new RemoteWeaponStationInputPayload(buffer.readBoolean()));

    public static void handle(RemoteWeaponStationInputPayload payload,
                              IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MianbaoRemoteWeaponStationControlHandler.updateTrigger(
                        player, payload.firing);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
