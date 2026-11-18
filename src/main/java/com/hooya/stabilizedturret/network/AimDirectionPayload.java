package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.bearing.MovingAimRay;
import com.hooya.stabilizedturret.content.controller.AimRayLimits;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.MianbaoOpticalMissileHandler;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Vector3d;
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
        double opticalLockReferenceZ,
        CameraFrame cameraFrame
) implements CustomPacketPayload {
    public record CameraFrame(UUID bodyId, Vec3 localOrigin, Vec3 surfaceNormal, Vec3 worldOffset) {
        // surfaceNormal is plot-local for hitSubLevelId, world-space for terrain.
        public CameraFrame(UUID bodyId,Vec3 localOrigin,Vec3 surfaceNormal) {
            this(bodyId,localOrigin,surfaceNormal,Vec3.ZERO);
        }
        public CameraFrame {
            if (bodyId == null || localOrigin == null || surfaceNormal == null || worldOffset == null
                    || !finite(localOrigin.x,localOrigin.y,localOrigin.z)
                    || !finite(surfaceNormal.x,surfaceNormal.y,surfaceNormal.z)
                    || !AimCameraGeometry.validOffset(worldOffset)
                    || surfaceNormal.lengthSqr() < 1e-12)
                throw new IllegalArgumentException("Invalid aim camera frame");
            surfaceNormal = surfaceNormal.normalize();
        }
    }

    public AimDirectionPayload(double ox,double oy,double oz,double dx,double dy,double dz,
                               double tx,double ty,double tz,boolean hit,UUID hitId,
                               double hx,double hy,double hz,double halfX,double halfY,
                               boolean locked,double lx,double ly,double lz) {
        this(ox,oy,oz,dx,dy,dz,tx,ty,tz,hit,hitId,hx,hy,hz,halfX,halfY,locked,lx,ly,lz,null);
    }
    private static final double OPTICAL_LOCK_REFERENCE_MAX_DISTANCE = 8192.0;
    /**
     * The pod camera position is sampled on the client render thread while
     * the authoritative Sable pose is advanced by the server. A 16-block
     * check worked for a stationary vehicle but rejected valid aim packets
     * once a carrier moved quickly or a client had normal network latency.
     * Keep this bounded for packet sanity, while allowing the render/server
     * poses to diverge by several ticks of high-speed motion.
     */
    private static final double POD_ORIGIN_SYNC_TOLERANCE = 512.0;
    private static final double SUBLEVEL_AIM_SYNC_TOLERANCE = AimCameraGeometry.MOVING_POSE_TOLERANCE;
    private static final java.util.Map<ServerPlayer,Long> ORIGIN_REJECTIONS=new java.util.WeakHashMap<>();
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
                buffer.writeBoolean(payload.cameraFrame != null);
                if (payload.cameraFrame != null) {
                    buffer.writeUUID(payload.cameraFrame.bodyId);
                    Vec3 local = payload.cameraFrame.localOrigin, normal = payload.cameraFrame.surfaceNormal;
                    buffer.writeDouble(local.x); buffer.writeDouble(local.y); buffer.writeDouble(local.z);
                    buffer.writeDouble(normal.x); buffer.writeDouble(normal.y); buffer.writeDouble(normal.z);
                    buffer.writeDouble(payload.cameraFrame.worldOffset.x);
                    buffer.writeDouble(payload.cameraFrame.worldOffset.y);
                    buffer.writeDouble(payload.cameraFrame.worldOffset.z);
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
                CameraFrame frame = buffer.readBoolean() ? new CameraFrame(buffer.readUUID(),
                        new Vec3(buffer.readDouble(),buffer.readDouble(),buffer.readDouble()),
                        new Vec3(buffer.readDouble(),buffer.readDouble(),buffer.readDouble()),
                        new Vec3(buffer.readDouble(),buffer.readDouble(),buffer.readDouble())) : null;
                return new AimDirectionPayload(
                        originX, originY, originZ,
                        directionX, directionY, directionZ,
                        targetX, targetY, targetZ, hit,
                        subLevelId, localX, localY, localZ,
                        opticalHalfTanX, opticalHalfTanY,
                        hasLockReference,
                        lockReferenceX, lockReferenceY, lockReferenceZ, frame
                );
            }
    );

    public static void handle(AimDirectionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // Television guidance owns an independent virtual direction. Late
            // normal-aim packets must not feed that mouse movement into a pod,
            // turret or view-driven vehicle controller.
            if (MianbaoOpticalMissileHandler.isViewing(player)) return;
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
                    || !AimRayLimits.withinTargetRange(origin, target)
                    || (lockReference != null
                    && lockReference.distanceToSqr(origin)
                    > OPTICAL_LOCK_REFERENCE_MAX_DISTANCE * OPTICAL_LOCK_REFERENCE_MAX_DISTANCE)) {
                rejectInput(player,payload,"ray-range",null);
                return;
            }
            Vec3 subLevelAnchor = validatedSubLevelAnchor(player, payload, target);
            if (payload.hit && payload.hitSubLevelId != null && subLevelAnchor == null) {
                rejectInput(player,payload,"target-frame",null);
                return;
            }
            direction = direction.normalize();
            if (target.subtract(origin).dot(direction) <= 0.0) {
                rejectInput(player,payload,"behind-camera",null);
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
                if (podOrigin == null
                        || origin.distanceToSqr(podOrigin)
                        > POD_ORIGIN_SYNC_TOLERANCE * POD_ORIGIN_SYNC_TOLERANCE) return;
                // In pod view target is the client's persistent gyro point,
                // not a newly clipped point produced after the aircraft moved.
                // Re-solving from the live pod origin compensates translation
                // as well as yaw, pitch and roll.
                controller.setDesiredPodWorldTarget(
                        target, payload.opticalHalfTanX, payload.opticalHalfTanY);
            } else if (controller != null && (controller.isHeadAimEnabled() || controller.hasOpticalLock()
                    || controller.isOpticalSearching())) {
                if (!authorizedCameraOrigin(player,controller,payload,origin)) {
                    rejectInput(player,payload,"camera-origin",controller);
                    return;
                }
                if (controller.isFreeLookHeld(player)
                        && !controller.hasOpticalLock()
                        && !controller.isOpticalSearching()) {
                    controller.setDesiredFreeLookDirection(
                            origin, direction,
                            payload.opticalHalfTanX,
                            payload.opticalHalfTanY
                    );
                } else {
                    controller.setDesiredAimRay(
                            origin, direction, target, payload.hit,
                            payload.hit ? payload.hitSubLevelId : null,
                            subLevelAnchor == null ? Vec3.ZERO : subLevelAnchor,
                            payload.opticalHalfTanX,
                            payload.opticalHalfTanY,
                            lockReference
                    );
                    controller.setMovingAimRay(validatedMovingRay(player,payload,origin,direction,target));
                }
            }
        });
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    private static void rejectInput(ServerPlayer player,AimDirectionPayload payload,String reason,
                                    StabilizerControllerBlockEntity controller) {
        long now=player.level().getGameTime(),previous=ORIGIN_REJECTIONS.getOrDefault(player,Long.MIN_VALUE);
        if(previous!=Long.MIN_VALUE&&now>=previous&&now-previous<200)return;
        ORIGIN_REJECTIONS.put(player,now);
        StabilizedTurret.LOGGER.info("Head aim input rejected: controller={} reason={} distance={} frame={} targetBody={}",
                controller==null?null:controller.getBlockPos(),reason,
                new Vec3(payload.originX,payload.originY,payload.originZ).distanceTo(player.position()),
                payload.cameraFrame,payload.hitSubLevelId);
    }

    private static boolean authorizedCameraOrigin(ServerPlayer player,StabilizerControllerBlockEntity controller,
                                                   AimDirectionPayload payload,Vec3 origin) {
        if(origin.distanceToSqr(player.position())<=128.0*128.0)return true;
        CameraFrame frame=payload.cameraFrame;if(frame==null)return false;
        SubLevel body=SubLevelContainer.getContainer(player.level()).getSubLevel(frame.bodyId);
        if(body==null||body.isRemoved()||!body.getPlot().contains(new Vector3d(frame.localOrigin.x,frame.localOrigin.y,frame.localOrigin.z)))return false;
        Vec3 projected=body.logicalPose().transformPosition(frame.localOrigin);
        var seat=controller.getBinding(BindingSlot.SEAT);
        if(seat!=null&&frame.bodyId.equals(seat.subLevelId())&&controller.isSeat(player.getVehicle())) {
            // Player world coordinates and rendered poses arrive on different schedules,
            // especially on a large ship. Verify the eye against its stable seat coordinates.
            Vec3 localEye=Vec3.atCenterOf(seat.pos()).add(0,player.getEyeHeight(),0);
            return AimCameraGeometry.matchesSeat(localEye,frame.localOrigin,projected,origin,frame.worldOffset);
        }
        SubLevel ridden=Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        Vec3 expectedEye=null;
        if(ridden!=null&&frame.bodyId.equals(ridden.getUniqueId()))
            expectedEye=Sable.HELPER.getEyePositionInterpolated(player,1);
        else if(controller.isScopeEnabled(player)) {
            boolean scopeBody=controller.getScopeReferenceBindings().stream().anyMatch(ref->{
                SubLevel owner=Sable.HELPER.getContaining(player.level(),ref.pos());
                return owner!=null&&frame.bodyId.equals(owner.getUniqueId());
            });
            if(scopeBody) {
                Vec3 scopeEye=controller.getScopePosition();
                if(scopeEye!=null) {
                    Vector3d local=body.logicalPose().transformPositionInverse(new Vector3d(scopeEye.x,scopeEye.y,scopeEye.z));
                    return AimCameraGeometry.matchesSeat(new Vec3(local.x,local.y,local.z),frame.localOrigin,
                            projected,origin,frame.worldOffset);
                }
            }
        }
        if(expectedEye==null)return false;
        return AimCameraGeometry.matches(expectedEye,projected,origin,frame.worldOffset);
    }

    private static MovingAimRay validatedMovingRay(ServerPlayer player,AimDirectionPayload payload,
                                                   Vec3 origin,Vec3 direction,Vec3 target) {
        if (!payload.hit || payload.cameraFrame == null) return null;
        CameraFrame frame=payload.cameraFrame;
        SubLevel body=SubLevelContainer.getContainer(player.level()).getSubLevel(frame.bodyId);
        if (body==null || body.isRemoved()) return null;
        Vector3d projected=body.logicalPose().transformPosition(
                new Vector3d(frame.localOrigin.x,frame.localOrigin.y,frame.localOrigin.z))
                .add(frame.worldOffset.x,frame.worldOffset.y,frame.worldOffset.z);
        if (!projected.isFinite() || origin.distanceToSqr(projected.x,projected.y,projected.z)
                >AimCameraGeometry.MOVING_POSE_TOLERANCE*AimCameraGeometry.MOVING_POSE_TOLERANCE)
            return null;
        if (payload.hitSubLevelId != null) {
            Vec3 anchor = validatedSubLevelAnchor(player, payload, target);
            SubLevel surface = SubLevelContainer.getContainer(player.level()).getSubLevel(payload.hitSubLevelId);
            if (anchor == null || surface == null || surface.isRemoved()) return null;
            // Physical BlockHitResult directions and anchors are both in plot
            // space. Keep that face attached to the external body; never feed
            // the previous rendered camera's fixed hit back into the servo.
            Vec3 point = surface.logicalPose().transformPosition(anchor);
            Vector3d normal = surface.logicalPose().transformNormal(new Vector3d(
                    frame.surfaceNormal.x, frame.surfaceNormal.y, frame.surfaceNormal.z));
            return new MovingAimRay(frame.bodyId, frame.localOrigin, direction, point,
                    new Vec3(normal.x, normal.y, normal.z), frame.worldOffset,
                    new MovingAimRay.PhysicalSurface(payload.hitSubLevelId, anchor, frame.surfaceNormal));
        }
        return new MovingAimRay(frame.bodyId,frame.localOrigin,direction,target,frame.surfaceNormal,frame.worldOffset);
    }

    /**
     * A Sable hit anchor is supplied in plot space.  Keep it only when it is
     * inside the server's actual plot and projects back to the world-space hit
     * sent in the same packet.  This prevents an otherwise valid UUID from
     * turning an arbitrary huge coordinate into a later server ray cast.
     */
    private static Vec3 validatedSubLevelAnchor(
            ServerPlayer player, AimDirectionPayload payload, Vec3 worldTarget
    ) {
        if (!payload.hit || payload.hitSubLevelId == null) return null;
        if (!finite(payload.hitSubLevelX, payload.hitSubLevelY, payload.hitSubLevelZ)) return null;
        SubLevel subLevel = SubLevelContainer.getContainer(player.level())
                .getSubLevel(payload.hitSubLevelId);
        if (subLevel == null || subLevel.isRemoved()) return null;

        Vec3 anchor = new Vec3(
                payload.hitSubLevelX, payload.hitSubLevelY, payload.hitSubLevelZ);
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        // Ray hits can lie exactly on an outer face; retain a small tolerance
        // for client/server collision and pose interpolation differences.
        if (anchor.x < bounds.minX() - 2.0 || anchor.x > bounds.maxX() + 3.0
                || anchor.y < bounds.minY() - 2.0 || anchor.y > bounds.maxY() + 3.0
                || anchor.z < bounds.minZ() - 2.0 || anchor.z > bounds.maxZ() + 3.0) {
            return null;
        }
        Vector3d projected = subLevel.logicalPose().transformPosition(
                new Vector3d(anchor.x, anchor.y, anchor.z));
        if (!Double.isFinite(projected.x) || !Double.isFinite(projected.y)
                || !Double.isFinite(projected.z)) {
            return null;
        }
        // Target is rebuilt on the client's screen-centre ray and may differ
        // from the transformed collision point when either Sable structure is
        // moving. The larger bounded tolerance covers several ticks of pose
        // skew without accepting an anchor from a distant plot.
        if (worldTarget.distanceToSqr(projected.x, projected.y, projected.z)
                > SUBLEVEL_AIM_SYNC_TOLERANCE * SUBLEVEL_AIM_SYNC_TOLERANCE) {
            return null;
        }
        return anchor;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
