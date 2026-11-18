package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Keeps client camera-mode and thermal rendering state aligned with the server camera. */
public record TelevisionGuidanceStatePayload(
        boolean active, UUID missileId, int remainingTicks
)
        implements CustomPacketPayload {
    public static final Type<TelevisionGuidanceStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "television_guidance_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TelevisionGuidanceStatePayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.active);
                buffer.writeBoolean(payload.missileId != null);
                if (payload.missileId != null) buffer.writeUUID(payload.missileId);
                buffer.writeVarInt(Math.max(0, payload.remainingTicks));
            },
            buffer -> new TelevisionGuidanceStatePayload(
                    buffer.readBoolean(),
                    buffer.readBoolean() ? buffer.readUUID() : null,
                    buffer.readVarInt()));

    public static void handle(
            TelevisionGuidanceStatePayload payload, IPayloadContext context
    ) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.handleTelevisionGuidanceState(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
