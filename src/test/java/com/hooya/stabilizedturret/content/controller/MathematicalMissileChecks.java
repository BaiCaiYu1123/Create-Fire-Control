package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.network.MathematicalMissilePayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Network boundaries and sub-tick smoke placement; real native tick parity is an integration check. */
public final class MathematicalMissileChecks {
    public static void main(String[] args) {
        GuidedMissileModeChecks.main(args);
        for (double speed : new double[] { 0, .2, 2.5, 17.015, 34.03, 51.045 }) {
            Vec3 velocity = new Vec3(.8, .2, .4).normalize().scale(speed);
            Vec3 end = new Vec3(-100.5, 240, 12.3);
            int count = GuidedMissileFlight.smokeCount(velocity);
            require(count == Math.max(1, Math.round(speed * .75)), "native smoke density");
            double last = 2;
            for (int i = 0; i < count; i++) {
                Vec3 sample = GuidedMissileFlight.smokePosition(end, velocity, i);
                double fraction = GuidedMissileFlight.smokeTimeFraction(velocity, i);
                require(fraction >= 0 && fraction <= 1 && fraction <= last, "ordered next-frame display times");
                if (speed > 0) near(end.distanceTo(sample), i, "one native smoke point per block");
                require(end.subtract(velocity.scale(1 - fraction)).distanceToSqr(sample) < 1e-20,
                        "smoke becomes visible when interpolated missile passes its position");
                last = fraction;
            }
        }
        for (boolean water : new boolean[] { false, true }) {
            Vec3 origin = new Vec3(-1, 200, -16), velocity = new Vec3(17.015, -.3, 4);
            var step = GuidedMissileFlight.step(origin, velocity, true, water);
            require(step.to().equals(origin.add(velocity)), "movement precedes drag and gravity");
            near(step.afterArrow().y, velocity.y * (water ? (double) .6F : (double) .99F) - .05,
                    "native float inertia and double gravity");
        }
        network();
        System.out.println("PASS: mathematical missile packet boundaries, ordered future smoke visibility, native density, gravity/drag ordering");
    }
    private static void network() {
        List<MathematicalMissilePayload.State> states = new ArrayList<>();
        for (int i = 0; i < MathematicalMissilePayload.MAX_STATES; i++) states.add(new MathematicalMissilePayload.State(
                UUID.randomUUID(), ResourceLocation.parse("mianbaos_modernwarfare:farmissile_1tanshe"),
                new Vec3(-i, 200, i), new Vec3(34.03 - i, 200, i), new Vec3(34.03, 0, 0),
                179, 10, 178, 9, 100, 100, 0, 0));
        var packet = new MathematicalMissilePayload(ResourceLocation.parse("minecraft:overworld"), 5000, states, List.of(UUID.randomUUID()));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            MathematicalMissilePayload.STREAM_CODEC.encode(buffer, packet);
            require(MathematicalMissilePayload.STREAM_CODEC.decode(buffer).equals(packet), "complete batch round trip");
        } finally { buffer.release(); }
        buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeResourceLocation(ResourceLocation.parse("minecraft:overworld")); buffer.writeLong(0); buffer.writeVarInt(129);
            boolean rejected = false;
            try { MathematicalMissilePayload.STREAM_CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "oversized batch rejected before allocation");
        } finally { buffer.release(); }
    }
    private static void near(double a, double b, String message) { require(Math.abs(a - b) < 1e-10, message); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
