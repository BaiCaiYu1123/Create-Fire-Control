package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.VirtualRocketVisuals;
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

/** Batched launches/corrections/removals. Clients simulate every intervening frame. */
public record RocketSalvoPayload(ResourceLocation dimension, long serverTick,
                                 List<State> rockets, List<UUID> removed) implements CustomPacketPayload {
    public static final int MAX_ROCKETS = 192;
    public static final Type<RocketSalvoPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "rocket_salvo"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RocketSalvoPayload> STREAM_CODEC =
            StreamCodec.of(RocketSalvoPayload::write, RocketSalvoPayload::read);

    public RocketSalvoPayload {
        rockets = List.copyOf(rockets);
        removed = List.copyOf(removed);
        if (rockets.size() > MAX_ROCKETS || removed.size() > MAX_ROCKETS) {
            throw new IllegalArgumentException("Rocket salvo exceeds simulation limit");
        }
    }

    public record State(UUID id, ResourceLocation entityType, Vec3 position, Vec3 velocity,
                        int age, double firstDrag, double drop) {}

    private static void write(RegistryFriendlyByteBuf buffer, RocketSalvoPayload payload) {
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeLong(payload.serverTick);
        buffer.writeVarInt(payload.rockets.size());
        for (State rocket : payload.rockets) {
            buffer.writeUUID(rocket.id);
            buffer.writeResourceLocation(rocket.entityType);
            buffer.writeDouble(rocket.position.x); buffer.writeDouble(rocket.position.y); buffer.writeDouble(rocket.position.z);
            buffer.writeDouble(rocket.velocity.x); buffer.writeDouble(rocket.velocity.y); buffer.writeDouble(rocket.velocity.z);
            buffer.writeVarInt(rocket.age);
            buffer.writeDouble(rocket.firstDrag); buffer.writeDouble(rocket.drop);
        }
        buffer.writeVarInt(payload.removed.size());
        payload.removed.forEach(buffer::writeUUID);
    }

    private static RocketSalvoPayload read(RegistryFriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        long tick = buffer.readLong();
        int size = boundedSize(buffer);
        List<State> states = new ArrayList<>(size);
        for (int i = 0; i < size; i++) states.add(new State(buffer.readUUID(), buffer.readResourceLocation(),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readVarInt(), buffer.readDouble(), buffer.readDouble()));
        size = boundedSize(buffer);
        List<UUID> removed = new ArrayList<>(size);
        for (int i = 0; i < size; i++) removed.add(buffer.readUUID());
        return new RocketSalvoPayload(dimension, tick, states, removed);
    }

    private static int boundedSize(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ROCKETS) throw new IllegalArgumentException("Invalid rocket salvo size");
        return size;
    }

    public static void handle(RocketSalvoPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) VirtualRocketVisuals.accept(payload);
        });
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
