package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.RemoteControlTracker;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RemoteControlStatePayload(boolean active, boolean advanced, BlockPos hubPos)
        implements CustomPacketPayload {
    public static final Type<RemoteControlStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "remote_control_state")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteControlStatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.active);
                buffer.writeBoolean(payload.advanced);
                buffer.writeBlockPos(payload.hubPos);
            },
            buffer -> new RemoteControlStatePayload(
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBlockPos()
            )
    );

    public static void handle(RemoteControlStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RemoteControlTracker.update(
                        player,
                        payload.active,
                        payload.advanced
                                ? RemoteControllerCompat.Type.ADVANCED
                                : RemoteControllerCompat.Type.NORMAL,
                        payload.hubPos
                );
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
