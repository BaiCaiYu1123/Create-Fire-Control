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

import java.util.UUID;
import java.util.List;

public record AimSessionPayload(
        boolean active,
        boolean remoteControl,
        boolean seatedAtBoundController,
        boolean headAim,
        boolean freeLookHeld,
        boolean aircraftEquipment,
        boolean scoped,
        int scopePreset,
        boolean podView,
        boolean thermal,
        double thirdPersonHeight,
        double viewAimOffsetX,
        double viewAimOffsetY,
        boolean cannonAimAvailable,
        double cannonAimX,
        double cannonAimY,
        double cannonAimZ,
        double cannonOriginX,
        double cannonOriginY,
        double cannonOriginZ,
        double scopeX,
        double scopeY,
        double scopeZ,
        boolean scopePoseRelative,
        int cannonEntityId,
        UUID cannonEntityUuid,
        UUID cannonSubLevelId,
        UUID pitchSubLevelId,
        BlockPos cannonMountPos,
        UUID cannonMountSubLevelId,
        boolean opticalLockActive,
        boolean opticalLockSearching,
        boolean opticalLockObscured,
        double opticalLockTargetX,
        double opticalLockTargetY,
        double opticalLockTargetZ,
        boolean opticalLockBoundsAvailable,
        double opticalLockMinX,
        double opticalLockMinY,
        double opticalLockMinZ,
        double opticalLockMaxX,
        double opticalLockMaxY,
        double opticalLockMaxZ,
        double podAimX,
        double podAimY,
        double podAimZ,
        boolean airToGroundLockSearching,
        boolean airToGroundLockActive,
        boolean airToGroundLockBoundsAvailable,
        double airToGroundLockMinX,
        double airToGroundLockMinY,
        double airToGroundLockMinZ,
        double airToGroundLockMaxX,
        double airToGroundLockMaxY,
        double airToGroundLockMaxZ,
        boolean rocketImpactAvailable,
        double rocketImpactX,
        double rocketImpactY,
        double rocketImpactZ,
        boolean rocketTrajectoryAvailable,
        double rocketOriginX,
        double rocketOriginY,
        double rocketOriginZ,
        double rocketVelocityX,
        double rocketVelocityY,
        double rocketVelocityZ,
        double rocketFirstDrag,
        double rocketFlightDrop,
        double rocketFirstGravity,
        List<UUID> cameraIgnoredSubLevelIds,
        TargetLockTelemetry opticalLockTelemetry,
        TargetLockTelemetry airToGroundLockTelemetry,
        List<UUID> virtualScopeHiddenSubLevels,
        List<UUID> virtualScopeHiddenEntities
)
        implements CustomPacketPayload {
    public static final Type<AimSessionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "aim_session")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, AimSessionPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.active);
                buffer.writeBoolean(payload.remoteControl);
                buffer.writeBoolean(payload.seatedAtBoundController);
                buffer.writeBoolean(payload.headAim);
                buffer.writeBoolean(payload.freeLookHeld);
                buffer.writeBoolean(payload.aircraftEquipment);
                buffer.writeBoolean(payload.scoped);
                buffer.writeVarInt(payload.scopePreset);
                buffer.writeBoolean(payload.podView);
                buffer.writeBoolean(payload.thermal);
                buffer.writeDouble(payload.thirdPersonHeight);
                buffer.writeDouble(payload.viewAimOffsetX);
                buffer.writeDouble(payload.viewAimOffsetY);
                buffer.writeBoolean(payload.cannonAimAvailable);
                buffer.writeDouble(payload.cannonAimX);
                buffer.writeDouble(payload.cannonAimY);
                buffer.writeDouble(payload.cannonAimZ);
                buffer.writeDouble(payload.cannonOriginX);
                buffer.writeDouble(payload.cannonOriginY);
                buffer.writeDouble(payload.cannonOriginZ);
                buffer.writeDouble(payload.scopeX);
                buffer.writeDouble(payload.scopeY);
                buffer.writeDouble(payload.scopeZ);
                buffer.writeBoolean(payload.scopePoseRelative);
                buffer.writeVarInt(payload.cannonEntityId);
                buffer.writeBoolean(payload.cannonEntityUuid != null);
                if (payload.cannonEntityUuid != null) {
                    buffer.writeUUID(payload.cannonEntityUuid);
                }
                buffer.writeBoolean(payload.cannonSubLevelId != null);
                if (payload.cannonSubLevelId != null) {
                    buffer.writeUUID(payload.cannonSubLevelId);
                }
                buffer.writeBoolean(payload.pitchSubLevelId != null);
                if (payload.pitchSubLevelId != null) {
                    buffer.writeUUID(payload.pitchSubLevelId);
                }
                buffer.writeBoolean(payload.cannonMountPos != null);
                if (payload.cannonMountPos != null) {
                    buffer.writeBlockPos(payload.cannonMountPos);
                }
                buffer.writeBoolean(payload.cannonMountSubLevelId != null);
                if (payload.cannonMountSubLevelId != null) {
                    buffer.writeUUID(payload.cannonMountSubLevelId);
                }
                buffer.writeBoolean(payload.opticalLockActive);
                buffer.writeBoolean(payload.opticalLockSearching);
                buffer.writeBoolean(payload.opticalLockObscured);
                buffer.writeDouble(payload.opticalLockTargetX);
                buffer.writeDouble(payload.opticalLockTargetY);
                buffer.writeDouble(payload.opticalLockTargetZ);
                buffer.writeBoolean(payload.opticalLockBoundsAvailable);
                buffer.writeDouble(payload.opticalLockMinX);
                buffer.writeDouble(payload.opticalLockMinY);
                buffer.writeDouble(payload.opticalLockMinZ);
                buffer.writeDouble(payload.opticalLockMaxX);
                buffer.writeDouble(payload.opticalLockMaxY);
                buffer.writeDouble(payload.opticalLockMaxZ);
                buffer.writeDouble(payload.podAimX);
                buffer.writeDouble(payload.podAimY);
                buffer.writeDouble(payload.podAimZ);
                buffer.writeBoolean(payload.airToGroundLockSearching);
                buffer.writeBoolean(payload.airToGroundLockActive);
                buffer.writeBoolean(payload.airToGroundLockBoundsAvailable);
                buffer.writeDouble(payload.airToGroundLockMinX);
                buffer.writeDouble(payload.airToGroundLockMinY);
                buffer.writeDouble(payload.airToGroundLockMinZ);
                buffer.writeDouble(payload.airToGroundLockMaxX);
                buffer.writeDouble(payload.airToGroundLockMaxY);
                buffer.writeDouble(payload.airToGroundLockMaxZ);
                buffer.writeBoolean(payload.rocketImpactAvailable);
                buffer.writeDouble(payload.rocketImpactX);
                buffer.writeDouble(payload.rocketImpactY);
                buffer.writeDouble(payload.rocketImpactZ);
                buffer.writeBoolean(payload.rocketTrajectoryAvailable);
                buffer.writeDouble(payload.rocketOriginX);
                buffer.writeDouble(payload.rocketOriginY);
                buffer.writeDouble(payload.rocketOriginZ);
                buffer.writeDouble(payload.rocketVelocityX);
                buffer.writeDouble(payload.rocketVelocityY);
                buffer.writeDouble(payload.rocketVelocityZ);
                buffer.writeDouble(payload.rocketFirstDrag);
                buffer.writeDouble(payload.rocketFlightDrop);
                buffer.writeDouble(payload.rocketFirstGravity);
                buffer.writeVarInt(payload.cameraIgnoredSubLevelIds.size());
                for (UUID id : payload.cameraIgnoredSubLevelIds) buffer.writeUUID(id);
                TargetLockTelemetry.write(buffer, payload.opticalLockTelemetry);
                TargetLockTelemetry.write(buffer, payload.airToGroundLockTelemetry);
                writeUuidList(buffer, payload.virtualScopeHiddenSubLevels);
                writeUuidList(buffer, payload.virtualScopeHiddenEntities);
            },
            buffer -> new AimSessionPayload(
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
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
                    buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readBoolean() ? buffer.readUUID() : null,
                    buffer.readBoolean() ? buffer.readUUID() : null,
                    buffer.readBoolean() ? buffer.readUUID() : null,
                    buffer.readBoolean() ? buffer.readBlockPos() : null,
                    buffer.readBoolean() ? buffer.readUUID() : null,
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
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
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readBoolean(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
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
                    readUuidList(buffer),
                    TargetLockTelemetry.read(buffer),
                    TargetLockTelemetry.read(buffer),
                    readUuidList(buffer),
                    readUuidList(buffer)
            )
    );

    public AimSessionPayload {
        cameraIgnoredSubLevelIds = cameraIgnoredSubLevelIds == null
                ? List.of() : List.copyOf(cameraIgnoredSubLevelIds);
        virtualScopeHiddenSubLevels = List.copyOf(virtualScopeHiddenSubLevels);
        virtualScopeHiddenEntities = List.copyOf(virtualScopeHiddenEntities);
    }

    private static void writeUuidList(RegistryFriendlyByteBuf buffer, List<UUID> ids) {
        buffer.writeVarInt(ids.size());
        for (UUID id : ids) buffer.writeUUID(id);
    }

    private static List<UUID> readUuidList(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > 4096) {
            throw new IllegalArgumentException("Invalid camera sub-level group size: " + size);
        }
        java.util.ArrayList<UUID> ids = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) ids.add(buffer.readUUID());
        return List.copyOf(ids);
    }

    public static void handle(AimSessionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.handleAimSession(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
