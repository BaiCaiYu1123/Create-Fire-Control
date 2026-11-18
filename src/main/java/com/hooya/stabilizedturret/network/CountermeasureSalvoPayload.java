package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.MissileLongRangeEffects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One client task for a flare salvo instead of one task per flare per tick. */
public record CountermeasureSalvoPayload(ResourceLocation dimension, long serverTick,
                                         List<State> flares, List<UUID> removed) implements CustomPacketPayload {
    public static final int MAX_FLARES = 192;
    public static final Type<CountermeasureSalvoPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "countermeasure_salvo"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CountermeasureSalvoPayload> STREAM_CODEC =
            StreamCodec.of(CountermeasureSalvoPayload::write, CountermeasureSalvoPayload::read);

    public CountermeasureSalvoPayload {
        flares = List.copyOf(flares);
        removed = List.copyOf(removed);
        if (flares.size() > MAX_FLARES || removed.size() > MAX_FLARES) {
            throw new IllegalArgumentException("Countermeasure batch too large");
        }
    }

    public record State(UUID id, Vec3 position, Vec3 velocity) {}

    private static void write(RegistryFriendlyByteBuf buffer, CountermeasureSalvoPayload payload) {
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeLong(payload.serverTick);
        buffer.writeVarInt(payload.flares.size());
        for (State flare : payload.flares) {
            buffer.writeUUID(flare.id);
            buffer.writeDouble(flare.position.x); buffer.writeDouble(flare.position.y); buffer.writeDouble(flare.position.z);
            buffer.writeDouble(flare.velocity.x); buffer.writeDouble(flare.velocity.y); buffer.writeDouble(flare.velocity.z);
        }
        buffer.writeVarInt(payload.removed.size());
        payload.removed.forEach(buffer::writeUUID);
    }

    private static CountermeasureSalvoPayload read(RegistryFriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        long tick = buffer.readLong();
        int count = boundedSize(buffer);
        List<State> flares = new ArrayList<>(count);
        for (int i = 0; i < count; i++) flares.add(new State(buffer.readUUID(),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble())));
        count = boundedSize(buffer);
        List<UUID> removed = new ArrayList<>(count);
        for (int i = 0; i < count; i++) removed.add(buffer.readUUID());
        return new CountermeasureSalvoPayload(dimension, tick, flares, removed);
    }

    private static int boundedSize(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_FLARES) throw new IllegalArgumentException("Invalid countermeasure batch size");
        return count;
    }

    public static void handle(CountermeasureSalvoPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) MissileLongRangeEffects.acceptCountermeasureSalvo(payload);
        });
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
