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

/** Announces a temporary second, player-like client chunk view at impact. */
public record MissileImpactRegionPayload(
        UUID missileId, int centerChunkX, int centerChunkZ, int radius, boolean active
) implements CustomPacketPayload {
    public static final Type<MissileImpactRegionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "missile_impact_region"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MissileImpactRegionPayload>
            STREAM_CODEC = StreamCodec.of(
                    MissileImpactRegionPayload::write,
                    MissileImpactRegionPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer,
                              MissileImpactRegionPayload payload) {
        buffer.writeUUID(payload.missileId);
        buffer.writeVarInt(payload.centerChunkX);
        buffer.writeVarInt(payload.centerChunkZ);
        buffer.writeVarInt(payload.radius);
        buffer.writeBoolean(payload.active);
    }

    private static MissileImpactRegionPayload read(RegistryFriendlyByteBuf buffer) {
        return new MissileImpactRegionPayload(
                buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readBoolean());
    }

    public static void handle(MissileImpactRegionPayload payload,
                              IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.handleMissileImpactRegion(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
