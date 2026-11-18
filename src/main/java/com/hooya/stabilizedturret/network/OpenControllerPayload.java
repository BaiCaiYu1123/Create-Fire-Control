package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.content.bearing.YawLimits;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record OpenControllerPayload(
        BlockPos pos,
        UUID controllerSubLevelId,
        double depression,
        double elevation,
        double yawSpeed,
        YawLimits yawLimits,
        double pitchSpeed,
        boolean yawReversed,
        boolean pitchReversed,
        boolean yawBearingHeadHidden,
        boolean pitchBearingHeadHidden,
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
        int scopePreset,
        boolean stabilizerEnabled,
        boolean stabilizerReactionEnabled,
        double cannonRecoilMagnitude,
        boolean mobileShotCompensationEnabled,
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
        double rwrRange,
        double rwrScale,
        boolean aircraftRocketSalvoMode,
        Component scopeFirst,
        Component scopeSecond,
        Component thermalFirst,
        Component thermalSecond,
        Component opticalLockFirst,
        Component opticalLockSecond,
        Component missileFireFirst,
        Component missileFireSecond,
        Component aaMissileFireFirst,
        Component aaMissileFireSecond,
        Component radarLockFirst,
        Component radarLockSecond,
        Component aircraftMissileFireFirst,
        Component aircraftMissileFireSecond,
        Component aircraftWeaponCycleFirst,
        Component aircraftWeaponCycleSecond,
        Component aircraftTargetCycleFirst,
        Component aircraftTargetCycleSecond,
        Component podViewFirst,
        Component podViewSecond,
        Component airToGroundLockFirst,
        Component airToGroundLockSecond,
        Component countermeasureFireFirst,
        Component countermeasureFireSecond,
        Component freeLookFirst,
        Component freeLookSecond,
        Component televisionGuidanceFirst,
        Component televisionGuidanceSecond,
        Component laserRangeFirst,
        Component laserRangeSecond,
        List<String> normalRemoteKeys,
        List<String> advancedRemoteKeys,
        List<BoundDevice> devices,
        net.minecraft.nbt.CompoundTag inputState,
        boolean virtualScopeEnabled,
        String virtualScopeUnavailableReason
) implements CustomPacketPayload {
    public static final Type<OpenControllerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "open_controller")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenControllerPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.pos);
                buffer.writeBoolean(payload.controllerSubLevelId != null);
                if (payload.controllerSubLevelId != null) {
                    buffer.writeUUID(payload.controllerSubLevelId);
                }
                buffer.writeDouble(payload.depression);
                buffer.writeDouble(payload.elevation);
                buffer.writeDouble(payload.yawSpeed);
                writeYawLimits(buffer, payload.yawLimits);
                buffer.writeDouble(payload.pitchSpeed);
                buffer.writeBoolean(payload.yawReversed);
                buffer.writeBoolean(payload.pitchReversed);
                buffer.writeBoolean(payload.yawBearingHeadHidden);
                buffer.writeBoolean(payload.pitchBearingHeadHidden);
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
                buffer.writeVarInt(payload.scopePreset);
                buffer.writeBoolean(payload.stabilizerEnabled);
                buffer.writeBoolean(payload.stabilizerReactionEnabled);
                buffer.writeDouble(payload.cannonRecoilMagnitude);
                buffer.writeBoolean(payload.mobileShotCompensationEnabled);
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
                buffer.writeDouble(payload.rwrRange);
                buffer.writeDouble(payload.rwrScale);
                buffer.writeBoolean(payload.aircraftRocketSalvoMode);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.scopeFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.scopeSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.thermalFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.thermalSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.opticalLockFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.opticalLockSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.missileFireFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.missileFireSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.aaMissileFireFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.aaMissileFireSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.radarLockFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.radarLockSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.aircraftMissileFireFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.aircraftMissileFireSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.aircraftWeaponCycleFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.aircraftWeaponCycleSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.aircraftTargetCycleFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.aircraftTargetCycleSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.podViewFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.podViewSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.airToGroundLockFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.airToGroundLockSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.countermeasureFireFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.countermeasureFireSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.freeLookFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.freeLookSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.televisionGuidanceFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.televisionGuidanceSecond);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.laserRangeFirst);
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.laserRangeSecond);
                writeKeys(buffer, payload.normalRemoteKeys);
                writeKeys(buffer, payload.advancedRemoteKeys);
                writeDevices(buffer, payload.devices);
                buffer.writeNbt(payload.inputState);
                buffer.writeBoolean(payload.virtualScopeEnabled);
                buffer.writeUtf(payload.virtualScopeUnavailableReason);
            },
            buffer -> new OpenControllerPayload(
                    buffer.readBlockPos(), // pos
                    buffer.readBoolean() ? buffer.readUUID() : null, // controllerSubLevelId
                    buffer.readDouble(), // depression
                    buffer.readDouble(), // elevation
                    buffer.readDouble(), // yawSpeed
                    readYawLimits(buffer),
                    buffer.readDouble(), // pitchSpeed
                    buffer.readBoolean(), // yawReversed
                    buffer.readBoolean(), // pitchReversed
                    buffer.readBoolean(), // yawBearingHeadHidden
                    buffer.readBoolean(), // pitchBearingHeadHidden
                    buffer.readDouble(), // thirdPersonHeight
                    buffer.readDouble(), // viewAimOffsetX
                    buffer.readDouble(), // viewAimOffsetY
                    buffer.readDouble(), // yawOffsetX
                    buffer.readDouble(), // yawOffsetY
                    buffer.readDouble(), // yawOffsetZ
                    buffer.readDouble(), // pitchOffsetX
                    buffer.readDouble(), // pitchOffsetY
                    buffer.readDouble(), // pitchOffsetZ
                    buffer.readDouble(), // scopeOffsetX
                    buffer.readDouble(), // scopeOffsetY
                    buffer.readDouble(), // scopeOffsetZ
                    buffer.readVarInt(), // scopePreset
                    buffer.readBoolean(), // stabilizerEnabled
                    buffer.readBoolean(), // stabilizerReactionEnabled
                    buffer.readDouble(), // cannonRecoilMagnitude
                    buffer.readBoolean(), // mobileShotCompensationEnabled
                    buffer.readBoolean(), // headAimEnabled
                    buffer.readBoolean(), // remoteHeadAimEnabled
                    buffer.readBoolean(), // remoteTerminalAvailable
                    buffer.readDouble(), // podCannonDepression
                    buffer.readDouble(), // podCannonElevation
                    buffer.readDouble(), // radarRange
                    buffer.readDouble(), // radarDepression
                    buffer.readDouble(), // radarElevation
                    buffer.readDouble(), // aaProximityFuseRange
                    buffer.readDouble(), // radarHudScale
                    buffer.readDouble(), // radarHudX
                    buffer.readDouble(), // radarHudY
                    buffer.readDouble(), // aircraftRadarRange
                    buffer.readDouble(), // aircraftRadarHorizontalHalfAngle
                    buffer.readDouble(), // aircraftRadarVerticalAngle
                    buffer.readDouble(), // aircraftProximityFuseRange
                    buffer.readDouble(), // aircraftFuseSafetySeconds
                    buffer.readDouble(), // aircraftRadarMinimumTargetSpeed
                    buffer.readDouble(), // aircraftRadarHudScale
                    buffer.readDouble(), // aircraftRadarHudX
                    buffer.readDouble(), // aircraftRadarHudY
                    buffer.readDouble(), // rwrRange
                    buffer.readDouble(), // rwrScale
                    buffer.readBoolean(), // aircraftRocketSalvoMode
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer), // laserRangeFirst
                    ComponentSerialization.STREAM_CODEC.decode(buffer), // laserRangeSecond
                    readKeys(buffer),
                    readKeys(buffer),
                    readDevices(buffer),
                    java.util.Objects.requireNonNull(buffer.readNbt()),
                    buffer.readBoolean(),
                    buffer.readUtf()
            )
    );

    private static void writeKeys(RegistryFriendlyByteBuf buffer, List<String> keys) {
        int size = Math.min(keys.size(), StabilizerControllerBlockEntity.INPUT_ACTION_COUNT);
        buffer.writeVarInt(size);
        for (int index = 0; index < size; index++) buffer.writeUtf(keys.get(index), 64);
    }

    private static List<String> readKeys(RegistryFriendlyByteBuf buffer) {
        int encodedSize = buffer.readVarInt();
        if (encodedSize < 0 || encodedSize > 256) {
            throw new IllegalArgumentException("Invalid remote-key count: " + encodedSize);
        }
        List<String> keys = new ArrayList<>(StabilizerControllerBlockEntity.INPUT_ACTION_COUNT);
        for (int index = 0; index < encodedSize; index++) {
            String key = buffer.readUtf(64);
            if (index < StabilizerControllerBlockEntity.INPUT_ACTION_COUNT) keys.add(key);
        }
        while (keys.size() < StabilizerControllerBlockEntity.INPUT_ACTION_COUNT) keys.add("");
        return List.copyOf(keys);
    }

    private static void writeYawLimits(RegistryFriendlyByteBuf buffer, YawLimits limits) {
        buffer.writeDouble(limits.left()); buffer.writeDouble(limits.right());
        buffer.writeVarInt(limits.centerDirection());
    }

    private static YawLimits readYawLimits(RegistryFriendlyByteBuf buffer) {
        return new YawLimits(buffer.readDouble(), buffer.readDouble(), buffer.readVarInt());
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
                buffer.writeVarInt(device.bearingGroup);
                buffer.writeDouble(device.depression);
                buffer.writeDouble(device.elevation);
                buffer.writeDouble(device.speed);
                buffer.writeBoolean(device.reversed);
                buffer.writeBoolean(device.headHidden);
                buffer.writeDouble(device.offsetX);
                buffer.writeDouble(device.offsetY);
                buffer.writeDouble(device.offsetZ);
                writeYawLimits(buffer, device.yawLimits);
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
            device = new BoundDevice(
                    device.slot(), device.index(), device.translationKey(), device.targetPos(),
                    device.subLevel(), device.podMode(), device.podOrder(), device.podReversed(),
                    buffer.readVarInt());
            device = new BoundDevice(device.slot(), device.index(), device.translationKey(), device.targetPos(),
                    device.subLevel(), device.podMode(), device.podOrder(), device.podReversed(), device.bearingGroup(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), readYawLimits(buffer));
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
            boolean podReversed,
            int bearingGroup,
            double depression,
            double elevation,
            double speed,
            boolean reversed,
            boolean headHidden,
            double offsetX,
            double offsetY,
            double offsetZ,
            YawLimits yawLimits
    ) {
        public BoundDevice(String slot, int index, String translationKey, BlockPos targetPos,
                           boolean subLevel, String podMode, int podOrder, boolean podReversed,
                           int bearingGroup, double depression, double elevation, double speed,
                           boolean reversed, boolean headHidden, double offsetX, double offsetY, double offsetZ) {
            this(slot, index, translationKey, targetPos, subLevel, podMode, podOrder, podReversed,
                    bearingGroup, depression, elevation, speed, reversed, headHidden, offsetX, offsetY, offsetZ, YawLimits.UNLIMITED);
        }
        public BoundDevice(
                String slot, int index, String translationKey, BlockPos targetPos, boolean subLevel
        ) {
            this(slot, index, translationKey, targetPos, subLevel, "", 0, false, -1,
                    Double.NaN, Double.NaN, Double.NaN, false, false, 0.0, 0.0, 0.0);
        }

        public BoundDevice(
                String slot, int index, String translationKey, BlockPos targetPos,
                boolean subLevel, String podMode, int podOrder, boolean podReversed
        ) {
            this(slot, index, translationKey, targetPos, subLevel,
                    podMode, podOrder, podReversed, -1,
                    Double.NaN, Double.NaN, Double.NaN, false, false, 0.0, 0.0, 0.0);
        }

        public BoundDevice(String slot, int index, String translationKey, BlockPos targetPos,
                           boolean subLevel, String podMode, int podOrder, boolean podReversed,
                           int bearingGroup) {
            this(slot, index, translationKey, targetPos, subLevel, podMode, podOrder,
                    podReversed, bearingGroup, Double.NaN, Double.NaN, Double.NaN,
                    false, false, 0.0, 0.0, 0.0);
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
