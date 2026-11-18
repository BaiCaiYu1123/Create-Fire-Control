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

import java.util.UUID;

/** Redundant animation start for loaders in moving Sable plots. */
public record Type10LoaderAnimationPayload(BlockPos pos, UUID subLevelId,
                                           long playUntilTick, int slots, int cycleSlot)
        implements CustomPacketPayload {
    public static final Type<Type10LoaderAnimationPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "type_10_loader_animation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Type10LoaderAnimationPayload> STREAM_CODEC =
            StreamCodec.of(Type10LoaderAnimationPayload::write, Type10LoaderAnimationPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, Type10LoaderAnimationPayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.subLevelId != null);
        if (payload.subLevelId != null) buffer.writeUUID(payload.subLevelId);
        buffer.writeVarLong(payload.playUntilTick);
        buffer.writeVarInt(payload.slots);
        buffer.writeVarInt(payload.cycleSlot);
    }

    private static Type10LoaderAnimationPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
        return new Type10LoaderAnimationPayload(pos, subLevelId,
                buffer.readVarLong(), buffer.readVarInt(), buffer.readVarInt());
    }

    public static void handle(Type10LoaderAnimationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.handleType10LoaderAnimation(payload);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
