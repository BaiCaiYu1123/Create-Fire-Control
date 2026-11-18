package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoRemoteWeaponStationDirectAimCompat;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Fire-control-owned mouse attitude for native Mianbao station sessions. */
public record RemoteWeaponStationAimPayload(float worldYaw, float worldPitch)
        implements CustomPacketPayload {
    public static final Type<RemoteWeaponStationAimPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "remote_weapon_station_aim"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            RemoteWeaponStationAimPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeFloat(payload.worldYaw);
                buffer.writeFloat(payload.worldPitch);
            },
            buffer -> new RemoteWeaponStationAimPayload(
                    buffer.readFloat(), buffer.readFloat()));

    public static void handle(
            RemoteWeaponStationAimPayload payload, IPayloadContext context
    ) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MianbaoRemoteWeaponStationDirectAimCompat.recordIndependentView(
                        player, payload.worldYaw, payload.worldPitch);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
