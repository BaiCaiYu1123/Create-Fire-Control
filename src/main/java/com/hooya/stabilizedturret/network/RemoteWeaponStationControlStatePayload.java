package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.RemoteWeaponStationControlClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RemoteWeaponStationControlStatePayload(boolean active)
        implements CustomPacketPayload {
    public static final Type<RemoteWeaponStationControlStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "remote_weapon_station_control_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            RemoteWeaponStationControlStatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBoolean(payload.active),
            buffer -> new RemoteWeaponStationControlStatePayload(buffer.readBoolean()));

    public static void handle(
            RemoteWeaponStationControlStatePayload payload, IPayloadContext context
    ) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                RemoteWeaponStationControlClient.accept(payload.active);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
