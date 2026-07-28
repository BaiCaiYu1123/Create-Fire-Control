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

public record AimSessionPayload(
        boolean active,
        boolean remoteControl,
        boolean headAim,
        boolean aircraftEquipment,
        boolean scoped,
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
        double airToGroundLockMaxZ
)
        implements CustomPacketPayload {
    public static final Type<AimSessionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "aim_session")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, AimSessionPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.active);
                buffer.writeBoolean(payload.remoteControl);
                buffer.writeBoolean(payload.headAim);
                buffer.writeBoolean(payload.aircraftEquipment);
                buffer.writeBoolean(payload.scoped);
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
            },
            buffer -> new AimSessionPayload(
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
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
                    buffer.readDouble()
            )
    );

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
