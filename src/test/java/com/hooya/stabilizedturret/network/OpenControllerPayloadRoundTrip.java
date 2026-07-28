package com.hooya.stabilizedturret.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.List;
import java.util.stream.IntStream;

public final class OpenControllerPayloadRoundTrip {
    public static void main(String[] args) {
        List<String> normalKeys = IntStream.range(0, 11)
                .mapToObj(index -> "normal_" + index)
                .toList();
        List<String> advancedKeys = IntStream.range(0, 11)
                .mapToObj(index -> "advanced_" + index)
                .toList();
        List<OpenControllerPayload.BoundDevice> devices = List.of(
                new OpenControllerPayload.BoundDevice(
                        "YAW", 0, "block.test.yaw", new BlockPos(4, 5, 6),
                        true, "SERIAL", 2, true),
                new OpenControllerPayload.BoundDevice(
                        "CANNON_MOUNT", 1, "block.test.mount", new BlockPos(7, 8, 9),
                        false, "POD_CONTROL", 3, false)
        );
        OpenControllerPayload original = new OpenControllerPayload(
                new BlockPos(1, 2, 3),
                1.0, 2.0, 3.0, 4.0,
                true, false,
                5.0, 6.0, 7.0,
                8.0, 9.0, 10.0,
                11.0, 12.0, 13.0,
                14.0, 15.0, 16.0,
                true, false, true, false,
                17.0, 18.0,
                19.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0,
                26.0, 27.0, 28.0, 29.0, 30.0, 31.0, 32.0, 33.0, 34.0,
                "scope_a", "scope_b",
                "thermal_a", "thermal_b",
                "optical_a", "optical_b",
                "missile_a", "missile_b",
                "aa_a", "aa_b",
                "radar_a", "radar_b",
                "air_fire_a", "air_fire_b",
                "weapon_a", "weapon_b",
                "target_a", "target_b",
                "pod_a", "pod_b",
                "ground_a", "ground_b",
                normalKeys, advancedKeys, devices
        );

        RegistryFriendlyByteBuf buffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        OpenControllerPayload.STREAM_CODEC.encode(buffer, original);
        OpenControllerPayload decoded = OpenControllerPayload.STREAM_CODEC.decode(buffer);
        if (!original.equals(decoded)) {
            throw new AssertionError("OpenControllerPayload round trip changed data");
        }
        if (buffer.readableBytes() != 0) {
            throw new AssertionError(
                    "OpenControllerPayload decoder left " + buffer.readableBytes() + " unread bytes");
        }
    }

    private OpenControllerPayloadRoundTrip() {
    }
}
