package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record MaidLoadingAnimationPayload(UUID maidId, boolean active, int durationTicks)
        implements CustomPacketPayload {
    public static final Type<MaidLoadingAnimationPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,
                    "maid_loading_animation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MaidLoadingAnimationPayload>
            STREAM_CODEC = StreamCodec.of(MaidLoadingAnimationPayload::encode,
            MaidLoadingAnimationPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, MaidLoadingAnimationPayload value) {
        buffer.writeUUID(value.maidId);
        buffer.writeBoolean(value.active);
        buffer.writeVarInt(value.durationTicks);
    }

    private static MaidLoadingAnimationPayload decode(RegistryFriendlyByteBuf buffer) {
        return new MaidLoadingAnimationPayload(buffer.readUUID(), buffer.readBoolean(),
                buffer.readVarInt());
    }

    public static void handle(MaidLoadingAnimationPayload payload, IPayloadContext context) {
        if (context.flow().isClientbound()) {
            context.enqueueWork(() -> ClientPayloadHandlers.handleMaidLoadingAnimation(payload));
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
