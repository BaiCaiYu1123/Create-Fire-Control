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

/** One delayed Mianbao nuclear fire-ring and mushroom-cloud step. */
public record NuclearShockwavePayload(
        double x, double y, double z, int radius, long seed
) implements CustomPacketPayload {
    public static final Type<NuclearShockwavePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "nuclear_shockwave"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NuclearShockwavePayload>
            STREAM_CODEC = StreamCodec.of(
                    NuclearShockwavePayload::write, NuclearShockwavePayload::read);

    private static void write(RegistryFriendlyByteBuf buffer,
                              NuclearShockwavePayload payload) {
        buffer.writeDouble(payload.x);
        buffer.writeDouble(payload.y);
        buffer.writeDouble(payload.z);
        buffer.writeVarInt(payload.radius);
        buffer.writeLong(payload.seed);
    }

    private static NuclearShockwavePayload read(RegistryFriendlyByteBuf buffer) {
        return new NuclearShockwavePayload(
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readVarInt(), buffer.readLong());
    }

    public static void handle(NuclearShockwavePayload payload,
                              IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.handleNuclearShockwave(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
