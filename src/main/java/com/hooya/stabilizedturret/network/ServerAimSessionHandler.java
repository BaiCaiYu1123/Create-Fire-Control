package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.CannonMountCompat;
import com.hooya.stabilizedturret.content.controller.RemoteControlTracker;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class ServerAimSessionHandler {
    private static final Set<UUID> ACTIVE = new HashSet<>();
    private static final Map<UUID, StabilizerControllerBlockEntity> LAST_SEATED = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        StabilizerControllerBlockEntity seatedController = ControllerTracker.findForVehicle(
                player.level(),
                player.getVehicle()
        );
        StabilizerControllerBlockEntity previousSeat = LAST_SEATED.get(player.getUUID());
        if (previousSeat != null && previousSeat != seatedController) {
            previousSeat.closeScope(player);
        }
        if (seatedController == null) {
            LAST_SEATED.remove(player.getUUID());
        } else {
            LAST_SEATED.put(player.getUUID(), seatedController);
        }

        PacketDistributor.sendToPlayer(player, RadarSessionPayload.from(seatedController));

        StabilizerControllerBlockEntity inputController = RemoteControlTracker.findInputController(player);
        StabilizerControllerBlockEntity remoteController = inputController != null
                && inputController.isRemoteHeadAimEnabled() ? inputController : null;
        boolean remoteControl = remoteController != null;
        // Remote authorization changes only who supplies head-aim input. A
        // seated gunner retains scope, thermal, lock and all other functions.
        StabilizerControllerBlockEntity controller = inputController != null
                && (inputController.isPodViewEnabled(player) || inputController.isScopeEnabled(player))
                ? inputController
                : remoteControl ? remoteController : seatedController;

        if (controller == null) {
            if (ACTIVE.remove(player.getUUID())) {
                PacketDistributor.sendToPlayer(player, inactivePayload());
            }
            return;
        }

        boolean newlyActive = ACTIVE.add(player.getUUID());
        boolean podView = controller.isPodViewEnabled(player);
        if (!controller.hasAircraftEquipment() && !podView
                && (newlyActive || (!controller.isHeadAimReady() && player.tickCount % 100 == 0))) {
            player.displayClientMessage(controller.getHeadAimStatusMessage(), true);
        }
        Vec3 scope = podView ? controller.getPodCameraPosition() : controller.getScopePosition();
        boolean scoped = !podView && controller.isScopeEnabled(player) && scope != null;
        BindingRef mount = controller.getBinding(BindingSlot.CANNON_MOUNT);
        BindingRef aimReference = controller.getAimReferenceBinding();
        BindingRef cameraReference = podView
                ? controller.getBinding(BindingSlot.POD)
                : aimReference;
        Vec3 cannonOrigin = controller.getMeasuredAimOrigin();
        Vec3 cannonDirection = controller.getMeasuredAimDirection();
        boolean cannonAimAvailable = cannonOrigin != null
                && cannonDirection != null
                && cannonDirection.lengthSqr() > 1.0e-8;
        Vec3 cannonAimPoint = cannonAimAvailable
                ? cannonOrigin.add(cannonDirection.normalize().scale(4096.0))
                : Vec3.ZERO;
        int cannonEntityId = mount == null || controller.getLevel() == null
                ? -1
                : CannonMountCompat.getMountedCannonEntityId(controller.getLevel(), mount);
        UUID cannonEntityUuid = mount == null || controller.getLevel() == null
                ? null
                : CannonMountCompat.getMountedCannonEntityUuid(controller.getLevel(), mount);
        UUID cannonSubLevelId = mount == null || controller.getLevel() == null
                ? null
                : CannonMountCompat.getMountedCannonSubLevelId(controller.getLevel(), mount);
        UUID pitchSubLevelId = null;
        BindingRef pitch = controller.getBinding(BindingSlot.PITCH);
        if (pitch != null
                && controller.getLevel() != null
                && pitch.resolve(controller.getLevel()) instanceof SwivelBearingBlockEntity bearing
                && bearing.isAssembled()) {
            pitchSubLevelId = bearing.getSubLevelID();
        }
        if (scope == null) {
            scope = player.getEyePosition();
        }
        Vec3 opticalLockTarget = controller.getOpticalLockTarget();
        AABB opticalLockBounds = controller.getOpticalLockBounds();
        // A dead entity or removed sub-level can disappear between the session
        // packet and the controller's next server tick. Do not advertise an
        // active lock with the protocol's fallback (0,0,0) target; the client
        // would interpret that as a real bearing and snap the view toward it.
        boolean opticalLockActive = controller.hasOpticalLock() && opticalLockTarget != null;
        Vec3 podAim = controller.getPodDesiredWorldDirection();
        StabilizerControllerBlockEntity.GroundTargetSnapshot airToGroundTarget =
                controller.getAirToGroundTargetSnapshot();
        AABB airToGroundBounds = airToGroundTarget == null ? null : airToGroundTarget.bounds();

        // The two proven Sable scope implementations keep their optic anchor
        // in plot coordinates and apply ClientSubLevel.renderPose every frame.
        // Sending a 10 Hz world-space camera position makes a scope attached
        // to a moving turret visibly shake, so transmit the same pose-relative
        // representation whenever the cannon's physical sub-level is known.
        boolean scopePoseRelative = false;
        UUID scopePoseSubLevelId = cameraReference == null ? null : cameraReference.subLevelId();
        // BindingRef stores the sub-level that existed when the block was
        // bound. Bearings and schematic welding can move the device into a
        // new/nested live sub-level afterwards, so resolve the current owner
        // from the actual block position on every session tick.
        if (cameraReference != null && controller.getLevel() != null) {
            SubLevel liveCameraSubLevel = Sable.HELPER.getContaining(
                    controller.getLevel(), cameraReference.pos());
            if (liveCameraSubLevel != null && !liveCameraSubLevel.isRemoved()) {
                scopePoseSubLevelId = liveCameraSubLevel.getUniqueId();
            }
        }
        // Pod and cannon can be in different nested Sable levels. The client
        // needs the pose owner of the active camera, not the cannon entity's
        // level, otherwise pod view falls back to a stepped network position.
        UUID cameraPoseSubLevelId = podView ? scopePoseSubLevelId : cannonSubLevelId;
        if (scopePoseSubLevelId != null && controller.getLevel() != null) {
            SubLevel subLevel = SubLevelContainer.getContainer(controller.getLevel()).getSubLevel(scopePoseSubLevelId);
            if (subLevel != null && !subLevel.isRemoved()) {
                scope = subLevel.logicalPose().transformPositionInverse(scope);
                scopePoseRelative = true;
            }
        }
        PacketDistributor.sendToPlayer(
                player,
                new AimSessionPayload(
                        true,
                        remoteControl,
                        controller.isHeadAimEnabled()
                                && (podView || remoteControl || !controller.isRemoteHeadAimEnabled()),
                        controller.hasAircraftEquipment(),
                        scoped,
                        podView,
                        controller.isThermalEnabled(player),
                        controller.getThirdPersonHeight(),
                        controller.getViewAimOffsetX(),
                        controller.getViewAimOffsetY(),
                        cannonAimAvailable,
                        cannonAimPoint.x,
                        cannonAimPoint.y,
                        cannonAimPoint.z,
                        cannonOrigin == null ? 0.0 : cannonOrigin.x,
                        cannonOrigin == null ? 0.0 : cannonOrigin.y,
                        cannonOrigin == null ? 0.0 : cannonOrigin.z,
                        scope.x,
                        scope.y,
                        scope.z,
                        scopePoseRelative,
                        cannonEntityId,
                        cannonEntityUuid,
                        cameraPoseSubLevelId,
                        pitchSubLevelId,
                        cameraReference == null ? null : cameraReference.pos(),
                        cameraPoseSubLevelId,
                        opticalLockActive,
                        controller.isOpticalSearching(),
                        controller.isOpticalLockObscured(),
                        opticalLockTarget == null ? 0.0 : opticalLockTarget.x,
                        opticalLockTarget == null ? 0.0 : opticalLockTarget.y,
                        opticalLockTarget == null ? 0.0 : opticalLockTarget.z,
                        opticalLockBounds != null,
                        opticalLockBounds == null ? 0.0 : opticalLockBounds.minX,
                        opticalLockBounds == null ? 0.0 : opticalLockBounds.minY,
                        opticalLockBounds == null ? 0.0 : opticalLockBounds.minZ,
                        opticalLockBounds == null ? 0.0 : opticalLockBounds.maxX,
                        opticalLockBounds == null ? 0.0 : opticalLockBounds.maxY,
                        opticalLockBounds == null ? 0.0 : opticalLockBounds.maxZ,
                        podAim.x,
                        podAim.y,
                        podAim.z,
                        controller.isAirToGroundSearching(),
                        airToGroundTarget != null,
                        airToGroundBounds != null,
                        airToGroundBounds == null ? 0.0 : airToGroundBounds.minX,
                        airToGroundBounds == null ? 0.0 : airToGroundBounds.minY,
                        airToGroundBounds == null ? 0.0 : airToGroundBounds.minZ,
                        airToGroundBounds == null ? 0.0 : airToGroundBounds.maxX,
                        airToGroundBounds == null ? 0.0 : airToGroundBounds.maxY,
                        airToGroundBounds == null ? 0.0 : airToGroundBounds.maxZ
                )
        );
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        StabilizerControllerBlockEntity seated = LAST_SEATED.remove(player.getUUID());
        if (seated != null) {
            seated.closeScope(player);
        }
        ACTIVE.remove(player.getUUID());
        ControllerTracker.releaseViews(player);
        RemoteControlTracker.clear(player);
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LAST_SEATED.remove(player.getUUID());
        ACTIVE.remove(player.getUUID());
        ControllerTracker.releaseViews(player);
        RemoteControlTracker.clear(player);
        PacketDistributor.sendToPlayer(player, inactivePayload());
    }

    private static AimSessionPayload inactivePayload() {
        return new AimSessionPayload(
                false, false, false, false, false, false, false,
                0.0, 0.0, 0.0,
                false, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                false, -1, null, null, null, null, null,
                false, false, false, 0.0, 0.0, 0.0,
                false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, -1.0,
                false, false, false,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        );
    }

    private ServerAimSessionHandler() {
    }
}
