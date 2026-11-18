package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.controller.ScopePreset;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record ControllerSettingsPayload(
        BlockPos pos,
        UUID controllerSubLevelId,
        double depression,
        double elevation,
        double yawSpeed,
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
        boolean virtualScopeEnabled
) implements CustomPacketPayload {
    public static final Type<ControllerSettingsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "controller_settings")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ControllerSettingsPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.pos);
                buffer.writeBoolean(payload.controllerSubLevelId != null);
                if (payload.controllerSubLevelId != null) {
                    buffer.writeUUID(payload.controllerSubLevelId);
                }
                buffer.writeDouble(payload.depression);
                buffer.writeDouble(payload.elevation);
                buffer.writeDouble(payload.yawSpeed);
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
                buffer.writeBoolean(payload.virtualScopeEnabled);
            },
            buffer -> new ControllerSettingsPayload(
                    buffer.readBlockPos(), // pos
                    buffer.readBoolean() ? buffer.readUUID() : null, // controllerSubLevelId
                    buffer.readDouble(), // depression
                    buffer.readDouble(), // elevation
                    buffer.readDouble(), // yawSpeed
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
                    buffer.readBoolean() // virtualScopeEnabled
            )
    );

    public static void handle(ControllerSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!Double.isFinite(payload.depression)
                    || !Double.isFinite(payload.cannonRecoilMagnitude)
                    || !Double.isFinite(payload.elevation)
                    || !Double.isFinite(payload.yawSpeed)
                    || !Double.isFinite(payload.pitchSpeed)
                    || !Double.isFinite(payload.thirdPersonHeight)
                    || !Double.isFinite(payload.viewAimOffsetX)
                    || !Double.isFinite(payload.viewAimOffsetY)
                    || !Double.isFinite(payload.yawOffsetX)
                    || !Double.isFinite(payload.yawOffsetY)
                    || !Double.isFinite(payload.yawOffsetZ)
                    || !Double.isFinite(payload.pitchOffsetX)
                    || !Double.isFinite(payload.pitchOffsetY)
                    || !Double.isFinite(payload.pitchOffsetZ)
                    || !Double.isFinite(payload.scopeOffsetX)
                    || !Double.isFinite(payload.scopeOffsetY)
                    || !Double.isFinite(payload.scopeOffsetZ)
                    || !Double.isFinite(payload.radarRange)
                    || !Double.isFinite(payload.radarDepression)
                    || !Double.isFinite(payload.radarElevation)
                    || !Double.isFinite(payload.aaProximityFuseRange)
                    || !Double.isFinite(payload.radarHudScale)
                    || !Double.isFinite(payload.radarHudX)
                    || !Double.isFinite(payload.radarHudY)
                    || !Double.isFinite(payload.aircraftRadarRange)
                    || !Double.isFinite(payload.aircraftRadarHorizontalHalfAngle)
                    || !Double.isFinite(payload.aircraftRadarVerticalAngle)
                    || !Double.isFinite(payload.aircraftProximityFuseRange)
                    || !Double.isFinite(payload.aircraftFuseSafetySeconds)
                    || !Double.isFinite(payload.aircraftRadarMinimumTargetSpeed)
                    || !Double.isFinite(payload.aircraftRadarHudScale)
                    || !Double.isFinite(payload.aircraftRadarHudX)
                    || !Double.isFinite(payload.aircraftRadarHudY)
                    || !Double.isFinite(payload.rwrRange)
                    || !Double.isFinite(payload.rwrScale)) {
                return;
            }
            StabilizerControllerBlockEntity controller =
                    com.hooya.stabilizedturret.content.controller.ControllerTracker
                            .findLoadedForSettings(
                                    player, payload.pos, payload.controllerSubLevelId);
            if (controller == null || !C2SWriteAuthorization.canEditNearby(player, controller)) {
                return;
            }
            {
                boolean remoteHeadAim = payload.remoteHeadAimEnabled && controller.hasRemoteTerminal();
                if (payload.remoteHeadAimEnabled && !remoteHeadAim) {
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable(
                                    "message.create_fire_control.remote_terminal_missing"
                            ),
                            true
                    );
                }
                controller.applySettings(
                        payload.depression,
                        payload.elevation,
                        payload.yawSpeed,
                        payload.pitchSpeed,
                        payload.yawReversed,
                        payload.pitchReversed,
                        payload.yawBearingHeadHidden,
                        payload.pitchBearingHeadHidden,
                        payload.thirdPersonHeight,
                        payload.viewAimOffsetX,
                        payload.viewAimOffsetY,
                        new Vec3(payload.yawOffsetX, payload.yawOffsetY, payload.yawOffsetZ),
                        new Vec3(payload.pitchOffsetX, payload.pitchOffsetY, payload.pitchOffsetZ),
                        new Vec3(payload.scopeOffsetX, payload.scopeOffsetY, payload.scopeOffsetZ),
                        ScopePreset.fromNetworkId(payload.scopePreset),
                        payload.stabilizerEnabled,
                        payload.stabilizerReactionEnabled,
                        payload.cannonRecoilMagnitude,
                        payload.mobileShotCompensationEnabled,
                        payload.headAimEnabled,
                        remoteHeadAim,
                        payload.radarRange,
                        payload.radarDepression,
                        payload.radarElevation,
                        payload.aaProximityFuseRange,
                        payload.radarHudScale,
                        payload.radarHudX,
                        payload.radarHudY,
                        payload.aircraftRadarRange,
                        payload.aircraftRadarHorizontalHalfAngle,
                        payload.aircraftRadarVerticalAngle,
                        payload.aircraftProximityFuseRange,
                        payload.aircraftFuseSafetySeconds,
                        payload.aircraftRadarMinimumTargetSpeed,
                        payload.aircraftRadarHudScale,
                        payload.aircraftRadarHudX,
                        payload.aircraftRadarHudY,
                        payload.rwrRange,
                        payload.rwrScale,
                        payload.aircraftRocketSalvoMode
                );
                if (!controller.setVirtualScopeEnabled(payload.virtualScopeEnabled)) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "message.create_fire_control." + controller.virtualScopeUnavailableReason()), true);
                }
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
