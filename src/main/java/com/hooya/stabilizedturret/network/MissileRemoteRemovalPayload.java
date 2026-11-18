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

/** Explicitly retires a distant visual when an ordinary AA round expires or impacts. */
public record MissileRemoteRemovalPayload(ResourceLocation dimension, UUID uuid) implements CustomPacketPayload {
    public static final Type<MissileRemoteRemovalPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "missile_remote_removal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MissileRemoteRemovalPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> { buffer.writeResourceLocation(payload.dimension); buffer.writeUUID(payload.uuid); },
            buffer -> new MissileRemoteRemovalPayload(buffer.readResourceLocation(), buffer.readUUID()));

    public static void handle(MissileRemoteRemovalPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.handleMissileRemoteRemoval(payload);
        });
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
