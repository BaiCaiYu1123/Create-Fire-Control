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
        double velocityZ,
        float rotationX,
        float rotationY,
        float rotationZ,
        float rotationW,
        boolean df26,
        long serverGameTime,
        int airPoweredAge,
        int landPoweredAge
) implements CustomPacketPayload {
    public MissileRemoteStatePayload(UUID uuid, ResourceLocation entityType, double x, double y, double z,
                                     double vx, double vy, double vz, float qx, float qy, float qz, float qw,
                                     boolean df26, long serverGameTime) {
        this(uuid, entityType, x, y, z, vx, vy, vz, qx, qy, qz, qw, df26, serverGameTime, -2, -2);
    }
    public MissileRemoteStatePayload(UUID uuid, ResourceLocation entityType, double x, double y, double z,
                                     double vx, double vy, double vz, float qx, float qy, float qz, float qw,
                                     boolean df26, long serverGameTime, int airPoweredAge) {
        this(uuid, entityType, x, y, z, vx, vy, vz, qx, qy, qz, qw, df26, serverGameTime, airPoweredAge, -2);
    }
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
        buffer.writeFloat(payload.rotationX);
        buffer.writeFloat(payload.rotationY);
        buffer.writeFloat(payload.rotationZ);
        buffer.writeFloat(payload.rotationW);
        buffer.writeBoolean(payload.df26);
        buffer.writeLong(payload.serverGameTime);
        buffer.writeVarInt(payload.airPoweredAge);
        buffer.writeVarInt(payload.landPoweredAge);
    }

    private static MissileRemoteStatePayload read(RegistryFriendlyByteBuf buffer) {
        return new MissileRemoteStatePayload(
                buffer.readUUID(), buffer.readResourceLocation(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readBoolean(),
                buffer.readLong(), buffer.readVarInt(), buffer.readVarInt());
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
