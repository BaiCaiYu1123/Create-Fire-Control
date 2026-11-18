package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.MathematicalMissileVisuals;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.*;

/** Completed movement plus the next device command. No vanilla entity tracking or client chunks. */
public record MathematicalMissilePayload(ResourceLocation dimension, long tick, List<State> states,
                                          List<UUID> removed) implements CustomPacketPayload {
    public static final int MAX_STATES = 128;
    public static final Type<MathematicalMissilePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "mathematical_missiles"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MathematicalMissilePayload> STREAM_CODEC =
            StreamCodec.of(MathematicalMissilePayload::write, MathematicalMissilePayload::read);
    public record State(UUID id, ResourceLocation type, Vec3 previous, Vec3 position, Vec3 velocity,
                        float yaw, float pitch, float previousYaw, float previousPitch, int age,
                        double airEngineAge, double groundEngineAge, double cruiseEngineAge) {}
    public MathematicalMissilePayload {
        states = List.copyOf(states); removed = List.copyOf(removed);
        if (states.size() > MAX_STATES || removed.size() > 4096) throw new IllegalArgumentException("Missile packet too large");
    }
    private static void write(RegistryFriendlyByteBuf b, MathematicalMissilePayload p) {
        b.writeResourceLocation(p.dimension); b.writeLong(p.tick); b.writeVarInt(p.states.size());
        for (State s : p.states) {
            b.writeUUID(s.id); b.writeResourceLocation(s.type); vec(b, s.previous); vec(b, s.position); vec(b, s.velocity);
            b.writeFloat(s.yaw); b.writeFloat(s.pitch); b.writeFloat(s.previousYaw); b.writeFloat(s.previousPitch);
            b.writeVarInt(s.age); b.writeDouble(s.airEngineAge); b.writeDouble(s.groundEngineAge); b.writeDouble(s.cruiseEngineAge);
        }
        b.writeVarInt(p.removed.size()); p.removed.forEach(b::writeUUID);
    }
    private static MathematicalMissilePayload read(RegistryFriendlyByteBuf b) {
        var dimension = b.readResourceLocation(); long tick = b.readLong(); int size = count(b, MAX_STATES);
        List<State> states = new ArrayList<>(size);
        for (int i = 0; i < size; i++) states.add(new State(b.readUUID(), b.readResourceLocation(), vec(b), vec(b), vec(b),
                b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble()));
        size = count(b, 4096); List<UUID> removed = new ArrayList<>(size);
        for (int i = 0; i < size; i++) removed.add(b.readUUID());
        return new MathematicalMissilePayload(dimension, tick, states, removed);
    }
    private static int count(RegistryFriendlyByteBuf b, int max) {
        int count = b.readVarInt(); if (count < 0 || count > max) throw new IllegalArgumentException("Invalid missile count"); return count;
    }
    private static void vec(RegistryFriendlyByteBuf b, Vec3 v) { b.writeDouble(v.x); b.writeDouble(v.y); b.writeDouble(v.z); }
    private static Vec3 vec(RegistryFriendlyByteBuf b) { return new Vec3(b.readDouble(), b.readDouble(), b.readDouble()); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(MathematicalMissilePayload p, IPayloadContext c) {
        c.enqueueWork(() -> { if (FMLEnvironment.dist == Dist.CLIENT) MathematicalMissileVisuals.accept(p); });
    }
}
