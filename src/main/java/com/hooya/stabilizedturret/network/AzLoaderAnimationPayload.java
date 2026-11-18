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

/** Reliable visual timeline for AZ loaders placed inside Sable sublevels. */
public record AzLoaderAnimationPayload(
        BlockPos pos,
        UUID subLevelId,
        long playUntilTick,
        int slots,
        int casingSlots,
        int wheelIndex,
        boolean consumePending
) implements CustomPacketPayload {
    public static final Type<AzLoaderAnimationPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "az_loader_animation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AzLoaderAnimationPayload> STREAM_CODEC =
            StreamCodec.of(AzLoaderAnimationPayload::write, AzLoaderAnimationPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, AzLoaderAnimationPayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.subLevelId != null);
        if (payload.subLevelId != null) buffer.writeUUID(payload.subLevelId);
        buffer.writeVarLong(payload.playUntilTick);
        buffer.writeVarInt(payload.slots);
        buffer.writeVarInt(payload.casingSlots);
        buffer.writeVarInt(payload.wheelIndex);
        buffer.writeBoolean(payload.consumePending);
    }

    private static AzLoaderAnimationPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
        return new AzLoaderAnimationPayload(pos, subLevelId, buffer.readVarLong(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readBoolean());
    }

    public static void handle(AzLoaderAnimationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.handleAzLoaderAnimation(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
