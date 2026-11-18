package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.VirtualBombVisuals;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import com.hooya.stabilizedturret.content.controller.MianbaoBombCompat;
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

/** Batched flight state; models extrapolate between corrections without native entity tracking. */
public record BombFlightPayload(ResourceLocation dimension, long serverTick, List<State> bombs, List<UUID> removed) implements CustomPacketPayload {
    public static final int MAX_BOMBS = 256;
    public static final Type<BombFlightPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "bomb_flights"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BombFlightPayload> STREAM_CODEC = StreamCodec.of(BombFlightPayload::write, BombFlightPayload::read);
    public BombFlightPayload {
        bombs = List.copyOf(bombs); removed = List.copyOf(removed);
        if (bombs.size() > MAX_BOMBS || removed.size() > MAX_BOMBS) throw new IllegalArgumentException("Too many bomb flights");
    }
    public record State(UUID id, ResourceLocation entityType, Vec3 position, Vec3 velocity, int age,
                        boolean coordinates, Vec3 target, List<Vec3> lasers, boolean water) {
        public State { lasers = List.copyOf(lasers); if (lasers.size() > 1) throw new IllegalArgumentException("Expected selected laser only"); }
        public WeaponType weapon() { return MianbaoBombCompat.entityWeapon(entityType); }
    }
    private static void write(RegistryFriendlyByteBuf b, BombFlightPayload p) {
        b.writeResourceLocation(p.dimension); b.writeLong(p.serverTick); b.writeVarInt(p.bombs.size());
        for (State s : p.bombs) {
            b.writeUUID(s.id); b.writeResourceLocation(s.entityType); vec(b, s.position); vec(b, s.velocity); b.writeVarInt(s.age);
            b.writeBoolean(s.coordinates); vec(b, s.target); b.writeBoolean(!s.lasers.isEmpty());
            if (!s.lasers.isEmpty()) vec(b, s.lasers.getFirst());
            b.writeBoolean(s.water);
        }
        b.writeVarInt(p.removed.size()); p.removed.forEach(b::writeUUID);
    }
    private static BombFlightPayload read(RegistryFriendlyByteBuf b) {
        var dimension = b.readResourceLocation(); long tick = b.readLong(); int size = size(b);
        List<State> states = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            UUID id = b.readUUID(); var type = b.readResourceLocation(); Vec3 position = vec(b), velocity = vec(b); int age = b.readVarInt();
            boolean coordinates = b.readBoolean(); Vec3 target = vec(b); List<Vec3> lasers = b.readBoolean() ? List.of(vec(b)) : List.of();
            states.add(new State(id, type, position, velocity, age, coordinates, target, lasers, b.readBoolean()));
        }
        size = size(b); List<UUID> removed = new ArrayList<>(size);
        for (int i = 0; i < size; i++) removed.add(b.readUUID());
        return new BombFlightPayload(dimension, tick, states, removed);
    }
    private static int size(RegistryFriendlyByteBuf b) {
        int size = b.readVarInt(); if (size < 0 || size > MAX_BOMBS) throw new IllegalArgumentException("Invalid bomb flight count"); return size;
    }
    private static void vec(RegistryFriendlyByteBuf b, Vec3 v) { b.writeDouble(v.x); b.writeDouble(v.y); b.writeDouble(v.z); }
    private static Vec3 vec(RegistryFriendlyByteBuf b) { return new Vec3(b.readDouble(), b.readDouble(), b.readDouble()); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(BombFlightPayload p, IPayloadContext c) {
        c.enqueueWork(() -> { if (FMLEnvironment.dist == Dist.CLIENT) VirtualBombVisuals.accept(p); });
    }
}
