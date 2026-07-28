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

public record MissileExplosionPayload(
        double x, double y, double z, float strength, boolean nativeParticlesExpected)
        implements CustomPacketPayload {
    public static final Type<MissileExplosionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "missile_explosion"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MissileExplosionPayload> STREAM_CODEC =
            StreamCodec.of(MissileExplosionPayload::write, MissileExplosionPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, MissileExplosionPayload payload) {
        buffer.writeDouble(payload.x);
        buffer.writeDouble(payload.y);
        buffer.writeDouble(payload.z);
        buffer.writeFloat(payload.strength);
        buffer.writeBoolean(payload.nativeParticlesExpected);
    }

    private static MissileExplosionPayload read(RegistryFriendlyByteBuf buffer) {
        return new MissileExplosionPayload(
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readFloat(), buffer.readBoolean());
    }

    public static void handle(MissileExplosionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.handleMissileExplosion(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
