package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.iff.IffTransponderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record SetIffChannelPayload(
        BlockPos pos, @Nullable UUID subLevelId, String channel)
        implements CustomPacketPayload {
    public static final Type<SetIffChannelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "set_iff_channel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetIffChannelPayload>
            STREAM_CODEC = StreamCodec.of(SetIffChannelPayload::write,
            SetIffChannelPayload::read);

    private static void write(
            RegistryFriendlyByteBuf buffer, SetIffChannelPayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.subLevelId != null);
        if (payload.subLevelId != null) buffer.writeUUID(payload.subLevelId);
        buffer.writeUtf(payload.channel, IffTransponderBlockEntity.MAX_CHANNEL_LENGTH);
    }

    private static SetIffChannelPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
        return new SetIffChannelPayload(pos, subLevelId,
                buffer.readUtf(IffTransponderBlockEntity.MAX_CHANNEL_LENGTH));
    }

    public static void handle(SetIffChannelPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !IffTransponderBlockEntity.isValidChannel(payload.channel)) return;
            IffTransponderBlockEntity transponder = IffTransponderBlockEntity.findLoaded(
                    player, payload.pos, payload.subLevelId);
            if (transponder == null
                    || !C2SWriteAuthorization.canEditNearby(player, transponder)) return;
            transponder.setChannel(payload.channel);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
