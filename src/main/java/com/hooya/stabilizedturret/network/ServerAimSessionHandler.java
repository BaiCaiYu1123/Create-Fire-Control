package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.CannonMountCompat;
import com.hooya.stabilizedturret.content.controller.RemoteControlTracker;
import com.hooya.stabilizedturret.compat.schematic.SableSchematicGroupCompat;
import com.hooya.stabilizedturret.compat.synaxis.SynaxisWeldCompat;
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
import java.util.List;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class ServerAimSessionHandler {
    private static final Set<UUID> ACTIVE = new HashSet<>();
    private static final Set<UUID> AIM_GROUP_ACTIVE = new HashSet<>();
    private static final Map<UUID, StabilizerControllerBlockEntity> LAST_SEATED = new HashMap<>();
    private static final Map<UUID, CameraIgnoreCache> CAMERA_IGNORE_CACHE = new HashMap<>();
    private static final InactiveSessionSync<UUID> AUXILIARY_SYNC = new InactiveSessionSync<>();

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
        if (previousSeat != null && previousSeat != seatedController)
            com.hooya.stabilizedturret.content.controller.AntiRadiationTargeting.clear(previousSeat);
        if (previousSeat != null && previousSeat != seatedController
                && !previousSeat.canUseScope(player)) {
            previousSeat.closeScope(player);
            com.hooya.stabilizedturret.content.controller.InfraredTargeting.clear(previousSeat);
        }
        if (seatedController == null) {
            LAST_SEATED.remove(player.getUUID());
        } else {
            LAST_SEATED.put(player.getUUID(), seatedController);
        }

        if (AUXILIARY_SYNC.shouldSend(player.getUUID(), player, player.level().dimension(), seatedController != null)) {
            PacketDistributor.sendToPlayer(player, RadarSessionPayload.from(seatedController));
            PacketDistributor.sendToPlayer(player, AntiRadiationStatePayload.from(seatedController));
            PacketDistributor.sendToPlayer(player, InfraredStatePayload.from(seatedController));
            PacketDistributor.sendToPlayer(player, seatedController == null
                    ? BombSightPayload.inactive() : seatedController.getBombSightPayload());
        }

        StabilizerControllerBlockEntity inputController = RemoteControlTracker.findInputController(player);
        StabilizerControllerBlockEntity remoteController = inputController != null
                && inputController.isRemoteHeadAimEnabled() ? inputController : null;
        boolean remoteControl = remoteController != null;
        // Remote authorization changes only who supplies head-aim input. A
        // seated gunner retains scope, thermal, lock and all other functions.
        StabilizerControllerBlockEntity controller = ControllerTracker.playerViewController(
                player, seatedController, inputController);

        if (controller == null) {
            if (ACTIVE.remove(player.getUUID())) {
                PacketDistributor.sendToPlayer(player, inactivePayload());
            }
            if (AIM_GROUP_ACTIVE.remove(player.getUUID())) {
                PacketDistributor.sendToPlayer(player, AimGroupSessionPayload.inactive());
            }
            CAMERA_IGNORE_CACHE.remove(player.getUUID());
            return;
        }

        boolean newlyActive = ACTIVE.add(player.getUUID());
        boolean podView = controller.isPodViewEnabled(player);
        boolean turretAxesConnected = controller.getBinding(BindingSlot.YAW) != null
                && controller.getBinding(BindingSlot.PITCH) != null;
        boolean directWeaponAimConnected = controller.hasRemoteWeaponStations();
        if (!controller.hasAircraftEquipment() && !podView
                && (newlyActive || (!controller.isHeadAimReady() && player.tickCount % 100 == 0))) {
            player.displayClientMessage(controller.getHeadAimStatusMessage(), true);
        }
        Vec3 scope = podView ? controller.getPodCameraPosition() : controller.getScopePosition();
        boolean scoped = !podView && controller.isScopeEnabled(player) && scope != null;
        BindingRef mount = controller.getBinding(BindingSlot.CANNON_MOUNT);
        BindingRef cameraReference = podView
                ? controller.getBinding(BindingSlot.POD)
                : controller.getScopeReferenceBindings().stream().findFirst().orElse(null);
        List<AimGroupSessionPayload.GroupAim> measuredAimGroups = controller.getMeasuredAimGroups();
        AimGroupSessionPayload.GroupAim primaryAim = measuredAimGroups.stream().findFirst().orElse(null);
        Vec3 cannonOrigin = primaryAim == null
                ? null
                : new Vec3(primaryAim.originX(), primaryAim.originY(), primaryAim.originZ());
        Vec3 cannonDirection = primaryAim == null
                ? null
                : new Vec3(primaryAim.directionX(), primaryAim.directionY(), primaryAim.directionZ());
        boolean cannonAimAvailable = cannonOrigin != null
                && cannonDirection != null
                && cannonDirection.lengthSqr() > 1.0e-8
                // Ballistic reticles are resolved by the following group packet.
                && primaryAim.ballistic() == null;
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
        StabilizerControllerBlockEntity.RocketImpactPrediction rocketImpact =
                seatedController == null
                        ? StabilizerControllerBlockEntity.RocketImpactPrediction.unavailable()
                        : seatedController.getRocketImpactPrediction();
        List<UUID> cameraIgnoredSubLevelIds = cameraIgnoredSubLevelIds(
                player, seatedController, controller, podView
        );

        // The two proven Sable scope implementations keep their optic anchor
        // in plot coordinates and apply ClientSubLevel.renderPose every frame.
        // Sending a 10 Hz world-space camera position makes a scope attached
        // to a moving turret visibly shake, so transmit the same pose-relative
        // representation whenever the camera anchor's physical sub-level is known.
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
        // scope was converted into the live camera-reference sublevel above.
        // The cannon entity may live in a different nested level after a
        // Photomancy/Synaxis blueprint weld, so using cannonSubLevelId here
        // applies the wrong inverse transform on the client and throws the
        // sight camera far from the turret.
        UUID cameraPoseSubLevelId = scopePoseSubLevelId;
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
                        seatedController != null,
                        controller.isHeadAimEnabled()
                                && (podView || turretAxesConnected || directWeaponAimConnected)
                                && (podView || remoteControl || !controller.isRemoteHeadAimEnabled()),
                        controller.isFreeLookHeld(player),
                        controller.hasAircraftEquipment(),
                        scoped,
                        controller.getScopePreset().ordinal(),
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
                        airToGroundBounds == null ? 0.0 : airToGroundBounds.maxZ,
                        rocketImpact.available(),
                        rocketImpact.position().x,
                        rocketImpact.position().y,
                        rocketImpact.position().z,
                        rocketImpact.trajectoryAvailable(),
                        rocketImpact.origin().x,
                        rocketImpact.origin().y,
                        rocketImpact.origin().z,
                        rocketImpact.initialVelocity().x,
                        rocketImpact.initialVelocity().y,
                        rocketImpact.initialVelocity().z,
                        rocketImpact.firstDrag(),
                        rocketImpact.flightDrop(),
                        rocketImpact.firstGravity(),
                        cameraIgnoredSubLevelIds,
                        controller.getOpticalLockTelemetry(),
                        airToGroundTarget == null ? TargetLockTelemetry.UNAVAILABLE
                                : controller.getTargetLockTelemetry(airToGroundTarget.center(),
                                controller.getLockTargetVelocity(airToGroundTarget.id(), airToGroundTarget.center())),
                        scoped && controller.isVirtualScopeEnabled() ? controller.getVirtualScopeHiddenSubLevels() : List.of(),
                        scoped && controller.isVirtualScopeEnabled() ? controller.getVirtualScopeHiddenEntities() : List.of()
                )
        );
        if (scoped) {
            PacketDistributor.sendToPlayer(player, controller.getScopeOrientation());
            PacketDistributor.sendToPlayer(player, new ScopeAmmoPayload(controller.getScopeApfsdsReserve()));
        }
        if (!measuredAimGroups.isEmpty()) {
            AIM_GROUP_ACTIVE.add(player.getUUID());
            PacketDistributor.sendToPlayer(player, new AimGroupSessionPayload(measuredAimGroups));
        } else if (AIM_GROUP_ACTIVE.remove(player.getUUID())) {
            PacketDistributor.sendToPlayer(player, AimGroupSessionPayload.inactive());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID playerId = player.getUUID();
        AUXILIARY_SYNC.forget(playerId);
        LAST_SEATED.remove(playerId);
        CAMERA_IGNORE_CACHE.remove(playerId);
        ACTIVE.remove(playerId);
        AIM_GROUP_ACTIVE.remove(playerId);
        RemoteControlTracker.clear(player);
        ControllerTracker.releaseViews(player);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        AUXILIARY_SYNC.forget(player.getUUID());
        StabilizerControllerBlockEntity seated = LAST_SEATED.remove(player.getUUID());
        CAMERA_IGNORE_CACHE.remove(player.getUUID());
        if (seated != null) {
            seated.closeScope(player);
            com.hooya.stabilizedturret.content.controller.AntiRadiationTargeting.clear(seated);
        }
        ACTIVE.remove(player.getUUID());
        AIM_GROUP_ACTIVE.remove(player.getUUID());
        ControllerTracker.releaseViews(player);
        RemoteControlTracker.clear(player);
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        AUXILIARY_SYNC.forget(player.getUUID());
        var seated = LAST_SEATED.remove(player.getUUID());
        if (seated != null) com.hooya.stabilizedturret.content.controller.AntiRadiationTargeting.clear(seated);
        CAMERA_IGNORE_CACHE.remove(player.getUUID());
        ACTIVE.remove(player.getUUID());
        AIM_GROUP_ACTIVE.remove(player.getUUID());
        ControllerTracker.releaseViews(player);
        RemoteControlTracker.clear(player);
        PacketDistributor.sendToPlayer(player, inactivePayload());
        PacketDistributor.sendToPlayer(player, AimGroupSessionPayload.inactive());
        PacketDistributor.sendToPlayer(player, BombSightPayload.inactive());
        PacketDistributor.sendToPlayer(player, AntiRadiationStatePayload.inactive());
    }

    private static AimSessionPayload inactivePayload() {
        return new AimSessionPayload(
                false, false, false, false, false, false, false, 0, false, false,
                0.0, 0.0, 0.0,
                false, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                false, -1, null, null, null, null, null,
                false, false, false, 0.0, 0.0, 0.0,
                false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, -1.0,
                false, false, false,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                false, 0.0, 0.0, 0.0,
                false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.99F, 0.025, 0.05,
                List.of(), TargetLockTelemetry.UNAVAILABLE, TargetLockTelemetry.UNAVAILABLE,
                List.of(), List.of()
        );
    }

    private static List<UUID> cameraIgnoredSubLevelIds(
            ServerPlayer player,
            StabilizerControllerBlockEntity seatedController,
            StabilizerControllerBlockEntity activeController,
            boolean podView
    ) {
        net.minecraft.server.level.ServerLevel serverLevel = player.serverLevel();
        Set<UUID> roots = new HashSet<>();
        SubLevel tracking = Sable.HELPER.getTrackingSubLevel(player);
        SubLevel vehicle = Sable.HELPER.getVehicleSubLevel(player);
        addLiveSubLevelId(roots, tracking);
        addLiveSubLevelId(roots, vehicle);
        if (player.getVehicle() != null) {
            addLiveSubLevelId(roots, Sable.HELPER.getContaining(player.getVehicle()));
        }
        if (seatedController != null && seatedController.getLevel() != null) {
            BindingRef seat = seatedController.getBinding(BindingSlot.SEAT);
            if (seat != null) {
                UUID stored = seat.subLevelId();
                if (stored != null) roots.add(stored);
                addLiveSubLevelId(roots,
                        Sable.HELPER.getContaining(seatedController.getLevel(), seat.pos()));
            }
        }
        if (activeController != null && activeController.getLevel() != null
                && (podView || activeController.isScopeEnabled(player))) {
            BindingRef cameraReference = podView
                    ? activeController.getBinding(BindingSlot.POD)
                    : activeController.getScopeReferenceBindings().stream().findFirst().orElse(null);
            if (cameraReference != null) {
                if (cameraReference.subLevelId() != null) roots.add(cameraReference.subLevelId());
                addLiveSubLevelId(roots,
                        Sable.HELPER.getContaining(activeController.getLevel(), cameraReference.pos()));
            }
        }
        if (roots.isEmpty()) {
            CAMERA_IGNORE_CACHE.remove(player.getUUID());
            return List.of();
        }
        long now = serverLevel.getGameTime();
        CameraIgnoreCache cached = CAMERA_IGNORE_CACHE.get(player.getUUID());
        Set<UUID> immutableRoots = Set.copyOf(roots);
        if (cached != null && immutableRoots.equals(cached.roots())
                && now >= cached.gameTime() && now - cached.gameTime() < 20L) {
            return cached.ids();
        }
        Set<UUID> ids = expandIgnoredStructure(serverLevel, roots);
        List<UUID> result = ids.stream()
                .sorted(java.util.Comparator.comparing(UUID::toString))
                .toList();
        CAMERA_IGNORE_CACHE.put(player.getUUID(),
                new CameraIgnoreCache(now, immutableRoots, result));
        return result;
    }

    private static void addLiveSubLevelId(Set<UUID> ids, SubLevel subLevel) {
        if (subLevel != null && !subLevel.isRemoved() && subLevel.getUniqueId() != null) {
            ids.add(subLevel.getUniqueId());
        }
    }

    private static Set<UUID> expandIgnoredStructure(
            net.minecraft.server.level.ServerLevel level,
            Set<UUID> roots
    ) {
        Set<UUID> result = new HashSet<>(roots);
        boolean changed;
        do {
            int previousSize = result.size();
            result.addAll(SynaxisWeldCompat.expandWeldedSublevels(
                    level, List.copyOf(result)));
            for (UUID member : List.copyOf(result)) {
                result.addAll(SableSchematicGroupCompat.connectedCameraGroup(level, member));
            }
            changed = result.size() != previousSize;
        } while (changed);
        return Set.copyOf(result);
    }

    private record CameraIgnoreCache(long gameTime, Set<UUID> roots, List<UUID> ids) {}

    private ServerAimSessionHandler() {
    }

    @SubscribeEvent
    public static void onStop(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        AUXILIARY_SYNC.clear();
        LAST_SEATED.clear();
        CAMERA_IGNORE_CACHE.clear();
        ACTIVE.clear();
        AIM_GROUP_ACTIVE.clear();
    }
}
