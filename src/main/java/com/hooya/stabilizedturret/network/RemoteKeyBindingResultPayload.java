package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server acknowledgement for an input-binding edit made in the fire-control UI. */
public record RemoteKeyBindingResultPayload(
        BlockPos pos, int action, boolean advanced, String key, Result result
) implements CustomPacketPayload {
    public static final Type<RemoteKeyBindingResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "remote_key_binding_result")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteKeyBindingResultPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.pos);
                        buffer.writeVarInt(payload.action);
                        buffer.writeBoolean(payload.advanced);
                        buffer.writeUtf(payload.key, 64);
                        buffer.writeVarInt(payload.result.ordinal());
                    },
                    buffer -> new RemoteKeyBindingResultPayload(
                            buffer.readBlockPos(),
                            buffer.readVarInt(),
                            buffer.readBoolean(),
                            buffer.readUtf(64),
                            Result.byId(buffer.readVarInt())
                    )
            );

    public enum Result {
        SAVED,
        INVALID_ACTION,
        TOO_FAR,
        CONTROLLER_MISSING,
        TERMINAL_MISSING,
        INVALID_KEY;

        public static Result byId(int id) {
            Result[] values = values();
            return id >= 0 && id < values.length ? values[id] : INVALID_KEY;
        }

        public String translationKey() {
            return "message.create_fire_control.remote_key_binding." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static void handle(RemoteKeyBindingResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.handleRemoteKeyBindingResult(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
