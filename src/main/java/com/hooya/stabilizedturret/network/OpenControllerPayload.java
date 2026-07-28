package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record OpenControllerPayload(
        BlockPos pos,
        double depression,
        double elevation,
        double yawSpeed,
        double pitchSpeed,
        boolean yawReversed,
        boolean pitchReversed,
        double thirdPersonHeight,
        double viewAimOffsetX,
        double viewAimOffsetY,
        double yawOffsetX,
        double yawOffsetY,
        double yawOffsetZ,
        double pitchOffsetX,
        double pitchOffsetY,
        double pitchOffsetZ,
        double scopeOffsetX,
        double scopeOffsetY,
        double scopeOffsetZ,
        boolean stabilizerEnabled,
        boolean headAimEnabled,
        boolean remoteHeadAimEnabled,
        boolean remoteTerminalAvailable,
        double podCannonDepression,
        double podCannonElevation,
        double radarRange,
        double radarDepression,
        double radarElevation,
        double aaProximityFuseRange,
        double radarHudScale,
        double radarHudX,
        double radarHudY,
        double aircraftRadarRange,
        double aircraftRadarHorizontalHalfAngle,
        double aircraftRadarVerticalAngle,
        double aircraftProximityFuseRange,
        double aircraftFuseSafetySeconds,
        double aircraftRadarMinimumTargetSpeed,
        double aircraftRadarHudScale,
        double aircraftRadarHudX,
        double aircraftRadarHudY,
        String scopeFirst,
        String scopeSecond,
        String thermalFirst,
        String thermalSecond,
        String opticalLockFirst,
        String opticalLockSecond,
        String missileFireFirst,
        String missileFireSecond,
        String aaMissileFireFirst,
        String aaMissileFireSecond,
        String radarLockFirst,
        String radarLockSecond,
        String aircraftMissileFireFirst,
        String aircraftMissileFireSecond,
        String aircraftWeaponCycleFirst,
        String aircraftWeaponCycleSecond,
        String aircraftTargetCycleFirst,
        String aircraftTargetCycleSecond,
        String podViewFirst,
        String podViewSecond,
        String airToGroundLockFirst,
        String airToGroundLockSecond,
        List<String> normalRemoteKeys,
        List<String> advancedRemoteKeys,
        List<BoundDevice> devices
) implements CustomPacketPayload {
    public static final Type<OpenControllerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "open_controller")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenControllerPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.pos);
                buffer.writeDouble(payload.depression);
                buffer.writeDouble(payload.elevation);
                buffer.writeDouble(payload.yawSpeed);
                buffer.writeDouble(payload.pitchSpeed);
                buffer.writeBoolean(payload.yawReversed);
                buffer.writeBoolean(payload.pitchReversed);
                buffer.writeDouble(payload.thirdPersonHeight);
                buffer.writeDouble(payload.viewAimOffsetX);
                buffer.writeDouble(payload.viewAimOffsetY);
                buffer.writeDouble(payload.yawOffsetX);
                buffer.writeDouble(payload.yawOffsetY);
                buffer.writeDouble(payload.yawOffsetZ);
                buffer.writeDouble(payload.pitchOffsetX);
                buffer.writeDouble(payload.pitchOffsetY);
                buffer.writeDouble(payload.pitchOffsetZ);
                buffer.writeDouble(payload.scopeOffsetX);
                buffer.writeDouble(payload.scopeOffsetY);
                buffer.writeDouble(payload.scopeOffsetZ);
                buffer.writeBoolean(payload.stabilizerEnabled);
                buffer.writeBoolean(payload.headAimEnabled);
                buffer.writeBoolean(payload.remoteHeadAimEnabled);
                buffer.writeBoolean(payload.remoteTerminalAvailable);
                buffer.writeDouble(payload.podCannonDepression);
                buffer.writeDouble(payload.podCannonElevation);
                buffer.writeDouble(payload.radarRange);
                buffer.writeDouble(payload.radarDepression);
                buffer.writeDouble(payload.radarElevation);
                buffer.writeDouble(payload.aaProximityFuseRange);
                buffer.writeDouble(payload.radarHudScale);
                buffer.writeDouble(payload.radarHudX);
                buffer.writeDouble(payload.radarHudY);
                buffer.writeDouble(payload.aircraftRadarRange);
                buffer.writeDouble(payload.aircraftRadarHorizontalHalfAngle);
                buffer.writeDouble(payload.aircraftRadarVerticalAngle);
                buffer.writeDouble(payload.aircraftProximityFuseRange);
                buffer.writeDouble(payload.aircraftFuseSafetySeconds);
                buffer.writeDouble(payload.aircraftRadarMinimumTargetSpeed);
                buffer.writeDouble(payload.aircraftRadarHudScale);
                buffer.writeDouble(payload.aircraftRadarHudX);
                buffer.writeDouble(payload.aircraftRadarHudY);
                buffer.writeUtf(payload.scopeFirst);
                buffer.writeUtf(payload.scopeSecond);
                buffer.writeUtf(payload.thermalFirst);
                buffer.writeUtf(payload.thermalSecond);
                buffer.writeUtf(payload.opticalLockFirst);
                buffer.writeUtf(payload.opticalLockSecond);
                buffer.writeUtf(payload.missileFireFirst);
                buffer.writeUtf(payload.missileFireSecond);
                buffer.writeUtf(payload.aaMissileFireFirst);
                buffer.writeUtf(payload.aaMissileFireSecond);
                buffer.writeUtf(payload.radarLockFirst);
                buffer.writeUtf(payload.radarLockSecond);
                buffer.writeUtf(payload.aircraftMissileFireFirst);
                buffer.writeUtf(payload.aircraftMissileFireSecond);
                buffer.writeUtf(payload.aircraftWeaponCycleFirst);
                buffer.writeUtf(payload.aircraftWeaponCycleSecond);
                buffer.writeUtf(payload.aircraftTargetCycleFirst);
                buffer.writeUtf(payload.aircraftTargetCycleSecond);
                buffer.writeUtf(payload.podViewFirst);
                buffer.writeUtf(payload.podViewSecond);
                buffer.writeUtf(payload.airToGroundLockFirst);
                buffer.writeUtf(payload.airToGroundLockSecond);
                writeKeys(buffer, payload.normalRemoteKeys);
                writeKeys(buffer, payload.advancedRemoteKeys);
                writeDevices(buffer, payload.devices);
            },
            buffer -> new OpenControllerPayload(
                    buffer.readBlockPos(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    buffer.readUtf(),
                    readKeys(buffer),
                    readKeys(buffer),
                    readDevices(buffer)
            )
    );

    private static void writeKeys(RegistryFriendlyByteBuf buffer, List<String> keys) {
        int size = Math.min(keys.size(), 11);
        buffer.writeVarInt(size);
        for (int index = 0; index < size; index++) buffer.writeUtf(keys.get(index), 64);
    }

    private static List<String> readKeys(RegistryFriendlyByteBuf buffer) {
        int encodedSize = buffer.readVarInt();
        if (encodedSize < 0 || encodedSize > 256) {
            throw new IllegalArgumentException("Invalid remote-key count: " + encodedSize);
        }
        List<String> keys = new ArrayList<>(11);
        for (int index = 0; index < encodedSize; index++) {
            String key = buffer.readUtf(64);
            if (index < 11) keys.add(key);
        }
        while (keys.size() < 11) keys.add("");
        return List.copyOf(keys);
    }

    private static void writeDevices(RegistryFriendlyByteBuf buffer, List<BoundDevice> devices) {
        int size = Math.min(devices.size(), 256);
        buffer.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            BoundDevice device = devices.get(index);
            buffer.writeUtf(device.slot);
            buffer.writeVarInt(device.index);
            buffer.writeUtf(device.translationKey);
            buffer.writeBlockPos(device.targetPos);
            buffer.writeBoolean(device.subLevel);
            buffer.writeUtf(device.podMode, 24);
            buffer.writeVarInt(device.podOrder);
            buffer.writeBoolean(device.podReversed);
        }
    }

    private static List<BoundDevice> readDevices(RegistryFriendlyByteBuf buffer) {
        int encodedSize = buffer.readVarInt();
        if (encodedSize < 0 || encodedSize > 4096) {
            throw new IllegalArgumentException("Invalid bound-device count: " + encodedSize);
        }
        List<BoundDevice> devices = new ArrayList<>(Math.min(encodedSize, 256));
        for (int index = 0; index < encodedSize; index++) {
            BoundDevice device = new BoundDevice(
                    buffer.readUtf(), buffer.readVarInt(), buffer.readUtf(),
                    buffer.readBlockPos(), buffer.readBoolean(),
                    buffer.readUtf(24), buffer.readVarInt(), buffer.readBoolean());
            if (index < 256) devices.add(device);
        }
        return List.copyOf(devices);
    }

    public record BoundDevice(
            String slot,
            int index,
            String translationKey,
            BlockPos targetPos,
            boolean subLevel,
            String podMode,
            int podOrder,
            boolean podReversed
    ) {
        public BoundDevice(
                String slot, int index, String translationKey, BlockPos targetPos, boolean subLevel
        ) {
            this(slot, index, translationKey, targetPos, subLevel, "", 0, false);
        }
    }

    public static void handle(OpenControllerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.openController(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
