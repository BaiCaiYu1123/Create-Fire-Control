package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import com.hooya.stabilizedturret.content.iff.IffTransponderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record OpenIffTransponderPayload(
        BlockPos pos, @Nullable UUID subLevelId, String channel)
        implements CustomPacketPayload {
    public static final Type<OpenIffTransponderPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "open_iff_transponder"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenIffTransponderPayload>
            STREAM_CODEC = StreamCodec.of(OpenIffTransponderPayload::write,
            OpenIffTransponderPayload::read);

    private static void write(
            RegistryFriendlyByteBuf buffer, OpenIffTransponderPayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.subLevelId != null);
        if (payload.subLevelId != null) buffer.writeUUID(payload.subLevelId);
        buffer.writeUtf(payload.channel, IffTransponderBlockEntity.MAX_CHANNEL_LENGTH);
    }

    private static OpenIffTransponderPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
        return new OpenIffTransponderPayload(pos, subLevelId,
                buffer.readUtf(IffTransponderBlockEntity.MAX_CHANNEL_LENGTH));
    }

    public static void handle(OpenIffTransponderPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.openIffTransponder(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
