package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.network.CountermeasureSalvoPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CountermeasureSalvoChecks {
    public static void main(String[] args) {
        var dimension = ResourceLocation.parse("minecraft:overworld");
        List<CountermeasureSalvoPayload.State> states = new ArrayList<>();
        List<UUID> removed = new ArrayList<>();
        for (int i = 0; i < CountermeasureSalvoPayload.MAX_FLARES; i++) {
            states.add(new CountermeasureSalvoPayload.State(new UUID(0, i),
                    new Vec3(-2000 + i, 300, i * 10), new Vec3(0.4, -0.2, 0.7)));
            removed.add(new UUID(1, i));
        }
        var original = new CountermeasureSalvoPayload(dimension, 200, states, removed);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        CountermeasureSalvoPayload.STREAM_CODEC.encode(buffer, original);
        require(buffer.readableBytes() < 16384, "192 flares and removals exceeded 16 KiB");
        require(original.equals(CountermeasureSalvoPayload.STREAM_CODEC.decode(buffer)), "salvo round trip failed");
        require(buffer.readableBytes() == 0, "trailing bytes");
        buffer.release();
        buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buffer.writeResourceLocation(dimension);
        buffer.writeLong(0);
        buffer.writeVarInt(Integer.MAX_VALUE);
        boolean rejected = false;
        try { CountermeasureSalvoPayload.STREAM_CODEC.decode(buffer); }
        catch (IllegalArgumentException expected) { rejected = true; }
        finally { buffer.release(); }
        require(rejected, "unbounded incoming list was accepted");
        double matching = MianbaoCountermeasureManager.approachSpeedMismatchMetersPerSecond(
                Vec3.ZERO, new Vec3(0, 0, 5), new Vec3(0, 0, 100), Vec3.ZERO);
        require(Math.abs(matching) < 1e-8, "matching approach failed");
        double mismatch = MianbaoCountermeasureManager.approachSpeedMismatchMetersPerSecond(
                Vec3.ZERO, new Vec3(0, 0, 5), new Vec3(0, 0, 100), new Vec3(0, 0, 2));
        require(Math.abs(mismatch) > MianbaoCountermeasureManager.MAX_DECOY_RELATIVE_SPEED_METERS_PER_SECOND,
                "40 m/s mismatch bypassed decoy limit");
        System.out.println("PASS: 192-flare batch/removals, bounded decoding, unchanged approach-speed rule");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private CountermeasureSalvoChecks() {}
}
