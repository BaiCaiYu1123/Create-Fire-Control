package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetPodCannonLimitsPayload(
        BlockPos controllerPos,
        double depression,
        double elevation
) implements CustomPacketPayload {
    public static final Type<SetPodCannonLimitsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "set_pod_cannon_limits")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SetPodCannonLimitsPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.controllerPos);
                        buffer.writeDouble(payload.depression);
                        buffer.writeDouble(payload.elevation);
                    },
                    buffer -> new SetPodCannonLimitsPayload(
                            buffer.readBlockPos(), buffer.readDouble(), buffer.readDouble())
            );

    public static void handle(SetPodCannonLimitsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !Double.isFinite(payload.depression)
                    || !Double.isFinite(payload.elevation)
                    || !C2SWriteAuthorization.canEditNearby(player, payload.controllerPos)) {
                return;
            }
            if (player.level().getBlockEntity(payload.controllerPos)
                    instanceof StabilizerControllerBlockEntity controller) {
                controller.setPodCannonLimits(payload.depression, payload.elevation);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
