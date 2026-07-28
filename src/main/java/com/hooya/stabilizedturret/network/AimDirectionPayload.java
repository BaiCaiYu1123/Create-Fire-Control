package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;

public record AimDirectionPayload(
        double originX,
        double originY,
        double originZ,
        double directionX,
        double directionY,
        double directionZ,
        double targetX,
        double targetY,
        double targetZ,
        boolean hit,
        UUID hitSubLevelId,
        double hitSubLevelX,
        double hitSubLevelY,
        double hitSubLevelZ,
        double opticalHalfTanX,
        double opticalHalfTanY,
        boolean opticalLockReferenceAvailable,
        double opticalLockReferenceX,
        double opticalLockReferenceY,
        double opticalLockReferenceZ
) implements CustomPacketPayload {
    private static final double NORMAL_AIM_MAX_DISTANCE = 8192.0;
    private static final double POD_LOD_AIM_MAX_DISTANCE = 65_536.0;
    public static final Type<AimDirectionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "aim_direction")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, AimDirectionPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeDouble(payload.originX);
                buffer.writeDouble(payload.originY);
                buffer.writeDouble(payload.originZ);
                buffer.writeDouble(payload.directionX);
                buffer.writeDouble(payload.directionY);
                buffer.writeDouble(payload.directionZ);
                buffer.writeDouble(payload.targetX);
                buffer.writeDouble(payload.targetY);
                buffer.writeDouble(payload.targetZ);
                buffer.writeBoolean(payload.hit);
                buffer.writeBoolean(payload.hitSubLevelId != null);
                if (payload.hitSubLevelId != null) {
                    buffer.writeUUID(payload.hitSubLevelId);
                    buffer.writeDouble(payload.hitSubLevelX);
                    buffer.writeDouble(payload.hitSubLevelY);
                    buffer.writeDouble(payload.hitSubLevelZ);
                }
                buffer.writeDouble(payload.opticalHalfTanX);
                buffer.writeDouble(payload.opticalHalfTanY);
                buffer.writeBoolean(payload.opticalLockReferenceAvailable);
                if (payload.opticalLockReferenceAvailable) {
                    buffer.writeDouble(payload.opticalLockReferenceX);
                    buffer.writeDouble(payload.opticalLockReferenceY);
                    buffer.writeDouble(payload.opticalLockReferenceZ);
                }
            },
            buffer -> {
                double originX = buffer.readDouble();
                double originY = buffer.readDouble();
                double originZ = buffer.readDouble();
                double directionX = buffer.readDouble();
                double directionY = buffer.readDouble();
                double directionZ = buffer.readDouble();
                double targetX = buffer.readDouble();
                double targetY = buffer.readDouble();
                double targetZ = buffer.readDouble();
                boolean hit = buffer.readBoolean();
                boolean hasSubLevel = buffer.readBoolean();
                UUID subLevelId = hasSubLevel ? buffer.readUUID() : null;
                double localX = hasSubLevel ? buffer.readDouble() : 0.0;
                double localY = hasSubLevel ? buffer.readDouble() : 0.0;
                double localZ = hasSubLevel ? buffer.readDouble() : 0.0;
                double opticalHalfTanX = buffer.readDouble();
                double opticalHalfTanY = buffer.readDouble();
                boolean hasLockReference = buffer.readBoolean();
                double lockReferenceX = hasLockReference ? buffer.readDouble() : 0.0;
                double lockReferenceY = hasLockReference ? buffer.readDouble() : 0.0;
                double lockReferenceZ = hasLockReference ? buffer.readDouble() : 0.0;
                return new AimDirectionPayload(
                        originX, originY, originZ,
                        directionX, directionY, directionZ,
                        targetX, targetY, targetZ, hit,
                        subLevelId, localX, localY, localZ,
                        opticalHalfTanX, opticalHalfTanY,
                        hasLockReference,
                        lockReferenceX, lockReferenceY, lockReferenceZ
                );
            }
    );

    public static void handle(AimDirectionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!finite(payload.originX, payload.originY, payload.originZ)
                    || !finite(payload.directionX, payload.directionY, payload.directionZ)
                    || !finite(payload.targetX, payload.targetY, payload.targetZ)
                    || !Double.isFinite(payload.opticalHalfTanX)
                    || !Double.isFinite(payload.opticalHalfTanY)
                    || (payload.opticalLockReferenceAvailable
                    && !finite(
                    payload.opticalLockReferenceX,
                    payload.opticalLockReferenceY,
                    payload.opticalLockReferenceZ
            ))) {
                return;
            }
            Vec3 origin = new Vec3(payload.originX, payload.originY, payload.originZ);
            Vec3 direction = new Vec3(payload.directionX, payload.directionY, payload.directionZ);
            Vec3 target = new Vec3(payload.targetX, payload.targetY, payload.targetZ);
            Vec3 lockReference = payload.opticalLockReferenceAvailable
                    ? new Vec3(
                    payload.opticalLockReferenceX,
                    payload.opticalLockReferenceY,
                    payload.opticalLockReferenceZ
            )
                    : null;
            if (direction.lengthSqr() < 1.0e-8
                    || target.distanceToSqr(origin)
                    > POD_LOD_AIM_MAX_DISTANCE * POD_LOD_AIM_MAX_DISTANCE
                    || (lockReference != null
                    && lockReference.distanceToSqr(origin)
                    > NORMAL_AIM_MAX_DISTANCE * NORMAL_AIM_MAX_DISTANCE)) {
                return;
            }
            direction = direction.normalize();
            if (target.subtract(origin).dot(direction) <= 0.0) {
                return;
            }
            StabilizerControllerBlockEntity controller = ControllerTracker.findForPlayerAim(player);
            StabilizerControllerBlockEntity inputController =
                    com.hooya.stabilizedturret.content.controller.RemoteControlTracker
                            .findInputController(player);
            if (inputController != null && inputController.isPodViewEnabled(player)) {
                controller = inputController;
            }
            if (controller == null) {
                StabilizerControllerBlockEntity seated =
                        ControllerTracker.findForVehicle(player.level(), player.getVehicle());
                // A remotely-authorized seat may still update optical search
                // or tracking, but its view packet must not drive head aim.
                if (seated != null && (seated.isPodViewEnabled(player)
                        || seated.hasOpticalLock() || seated.isOpticalSearching())) {
                    controller = seated;
                }
            }
            boolean podView = controller != null && controller.isPodViewEnabled(player);
            if (podView) {
                Vec3 podOrigin = controller.getPodCameraPosition();
                if (podOrigin == null || origin.distanceToSqr(podOrigin) > 16.0 * 16.0) return;
                // In pod view target is the client's persistent gyro point,
                // not a newly clipped point produced after the aircraft moved.
                // Re-solving from the live pod origin compensates translation
                // as well as yaw, pitch and roll.
                controller.setDesiredPodWorldTarget(
                        target, payload.opticalHalfTanX, payload.opticalHalfTanY);
            } else if (controller != null && (controller.isHeadAimEnabled() || controller.hasOpticalLock()
                    || controller.isOpticalSearching())) {
                if (target.distanceToSqr(origin)
                        > NORMAL_AIM_MAX_DISTANCE * NORMAL_AIM_MAX_DISTANCE) return;
                if (origin.distanceToSqr(player.position()) > 128.0 * 128.0) return;
                controller.setDesiredAimRay(
                        origin, direction, target, payload.hit,
                        payload.hitSubLevelId,
                        new Vec3(payload.hitSubLevelX, payload.hitSubLevelY, payload.hitSubLevelZ),
                        payload.opticalHalfTanX,
                        payload.opticalHalfTanY,
                        lockReference
                );
            }
        });
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
