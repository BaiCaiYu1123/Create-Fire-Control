package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.content.remote.ControllerPulseQueue;
import com.hooya.stabilizedturret.network.RemoteInputStatePayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RemoteInputRouteChecks {
    public static void main(String[] args) {
        UUID token = UUID.randomUUID();
        var route = new RemoteInputRoute();
        var down = new NativeInputSnapshot(token, Map.of("buttonA", 15), Set.of("buttonA"), Set.of());
        var up = new NativeInputSnapshot(token, Map.of(), Set.of("buttonA"), Set.of());
        require(route.select(true, Map.of(), up).isEmpty() && !route.usesNativeRoute(), "idle is not a fault");
        require(route.select(true, Map.of("buttonA", 16), down).get("buttonA") == 16
                && !route.usesNativeRoute(), "healthy wire strength preserved");
        require(route.select(true, Map.of(), down).get("buttonA") == 15 && route.usesNativeRoute(),
                "lost press switches immediately");
        for (int i = 0; i < 20; i++) require(route.select(true, Map.of("buttonA", 16), down).get("buttonA") == 15,
                "holding does not flap back to wire");
        require(route.select(true, Map.of("buttonA", 16), up).getOrDefault("buttonA", 0) == 0,
                "stuck wire cannot hide release");
        require(route.select(true, Map.of("buttonA", 16), NativeInputSnapshot.EMPTY).getOrDefault("buttonA", 0) == 0,
                "stop/timeout releases stale wire");
        var second = new NativeInputSnapshot(UUID.randomUUID(), Map.of(), Set.of("buttonA"), Set.of());
        route.select(true, Map.of(), second);
        require(!route.usesNativeRoute(), "new session retries a healthy route");
        require(route.select(true, Map.of("buttonA", 16), second).getOrDefault("buttonA", 0) == 0
                && route.usesNativeRoute(), "stuck-high fault alone triggers fallback");
        route = new RemoteInputRoute();
        require(route.select(false, Map.of(), down).get("buttonA") == 15, "missing or throwing API uses native");
        var analog = route.select(true, Map.of("axisLeftX+", 7), down);
        require(analog.get("axisLeftX+") == 7, "unobserved gamepad controls retain wire input");
        var remapped = new NativeInputSnapshot(token, Map.of(), Set.of(), Set.of());
        require(route.select(true, Map.of("buttonA", 7), remapped).get("buttonA") == 7,
                "remapping an observed button to a gamepad keeps the existing wire control");
        route = new RemoteInputRoute();
        var wheel = new ControllerPulseQueue(); wheel.add(Set.of("buttonA"), 3);
        int edges = 0; boolean previous = false;
        for (int tick = 0; tick < 6; tick++) {
            boolean pressed = !wheel.active(tick).isEmpty();
            var pulse = new NativeInputSnapshot(token, pressed ? Map.of("buttonA", 15) : Map.of(),
                    Set.of("buttonA"), Set.of("buttonA"));
            boolean active = route.select(true, Map.of("buttonA", 16), pulse).getOrDefault("buttonA", 0) > 0;
            if (active && !previous) edges++;
            previous = active;
        }
        require(edges == 3 && !route.usesNativeRoute(), "three notches retain three edges without false fault");
        roundTrip(new RemoteInputStatePayload(true, new BlockPos(20000000, 80, -20000000), Set.of("buttonA"), null,
                List.of(new ControllerPulseQueue.Pulse(Set.of("buttonB"), 3)), Set.of("buttonA", "buttonB")));
        roundTrip(new RemoteInputStatePayload(false, BlockPos.ZERO, Set.of(), null, List.of(), Set.of("keyUp")));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeBoolean(true); buffer.writeBlockPos(BlockPos.ZERO); buffer.writeVarInt(0);
            buffer.writeBoolean(false); buffer.writeVarInt(0); buffer.writeVarInt(27);
            boolean rejected = false;
            try { RemoteInputStatePayload.STREAM_CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "observed input count remains bounded");
        } finally { buffer.release(); }
        System.out.println("PASS: native input self-check, press/release faults, timeout, no flapping, wheel edges, gamepad isolation and protocol");
    }

    private static void roundTrip(RemoteInputStatePayload payload) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            RemoteInputStatePayload.STREAM_CODEC.encode(buffer, payload);
            require(payload.equals(RemoteInputStatePayload.STREAM_CODEC.decode(buffer)) && !buffer.isReadable(), "input protocol round trip");
        } finally { buffer.release(); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
