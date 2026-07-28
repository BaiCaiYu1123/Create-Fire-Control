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

/** Lightweight visual state for guided missiles outside normal chunk tracking. */
public record MissileRemoteStatePayload(
        UUID uuid,
        ResourceLocation entityType,
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ
) implements CustomPacketPayload {
    public static final Type<MissileRemoteStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "missile_remote_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MissileRemoteStatePayload> STREAM_CODEC =
            StreamCodec.of(MissileRemoteStatePayload::write, MissileRemoteStatePayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, MissileRemoteStatePayload payload) {
        buffer.writeUUID(payload.uuid);
        buffer.writeResourceLocation(payload.entityType);
        buffer.writeDouble(payload.x);
        buffer.writeDouble(payload.y);
        buffer.writeDouble(payload.z);
        buffer.writeDouble(payload.velocityX);
        buffer.writeDouble(payload.velocityY);
        buffer.writeDouble(payload.velocityZ);
    }

    private static MissileRemoteStatePayload read(RegistryFriendlyByteBuf buffer) {
        return new MissileRemoteStatePayload(
                buffer.readUUID(), buffer.readResourceLocation(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    public static void handle(MissileRemoteStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.handleMissileRemoteState(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}