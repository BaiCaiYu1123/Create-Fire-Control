package com.hooya.stabilizedturret.content.controller;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class StabilizerControllerBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {
    private static final long OPTICAL_OCCLUSION_GRACE_TICKS = 40L;
    private static final long OPTICAL_VISIBILITY_CHECK_INTERVAL_TICKS = 2L;
    private static final long OWN_SUB_LEVEL_CACHE_TICKS = 20L;
    private static final long RADAR_TOPOLOGY_CACHE_TICKS = 200L;
    private static final int RADAR_TOPOLOGY_SUBLEVEL_BUDGET = 2;

    private final Map<BindingSlot, BindingRef> bindings = new EnumMap<>(BindingSlot.class);
    private final List<BindingRef> cannonMounts = new ArrayList<>();
    private final List<BindingRef> missileLaunchers = new ArrayList<>();
    private final List<BindingRef> aaMissileLaunchers = new ArrayList<>();
    private final List<BindingRef> aircraftMissileRacks = new ArrayList<>();
    private final List<BindingRef> unassignedBearings = new ArrayList<>();
    private final WirelessPulseReceiver scopeReceiver = new WirelessPulseReceiver(this, ignored -> toggleScope(null));
    private final WirelessPulseReceiver thermalReceiver = new WirelessPulseReceiver(this, ignored -> toggleThermal(null));
    private final WirelessPulseReceiver opticalLockReceiver = new WirelessPulseReceiver(this, ignored -> toggleOpticalLock());
    private final WirelessPulseReceiver missileFireReceiver = new WirelessPulseReceiver(this, ignored -> fireNextMissile());
    private final WirelessPulseReceiver aaMissileFireReceiver = new WirelessPulseReceiver(this, ignored -> fireNextAaMissile());
    private final WirelessPulseReceiver radarLockReceiver = new WirelessPulseReceiver(this, ignored -> toggleRadarLockFromWireless());
    private final WirelessPulseReceiver aircraftMissileFireReceiver = new WirelessPulseReceiver(this, ignored -> fireNextAircraftMissile());
    private final WirelessPulseReceiver aircraftWeaponCycleReceiver = new WirelessPulseReceiver(this, ignored -> cycleAircraftWeapon());
    private final WirelessPulseReceiver aircraftTargetCycleReceiver = new WirelessPulseReceiver(this, ignored -> cycleAircraftRadarTarget());
    private final WirelessPulseReceiver podViewReceiver =
            new WirelessPulseReceiver(this, ignored -> togglePodViewFromWireless());
    private final WirelessPulseReceiver airToGroundLockReceiver =
            new WirelessPulseReceiver(this, ignored -> toggleAirToGroundLockFromWireless());
    private static final int INPUT_ACTION_COUNT = 11;
    private final String[] normalRemoteKeys = new String[INPUT_ACTION_COUNT];
    private final String[] advancedRemoteKeys = new String[INPUT_ACTION_COUNT];
    private final boolean[] remoteInputPressed = new boolean[INPUT_ACTION_COUNT];

    private double depressionLimitDegrees = 10.0;
    private double elevationLimitDegrees = 20.0;
    private double yawSpeedDegreesPerSecond = 30.0;
    private double pitchSpeedDegreesPerSecond = 15.0;
    private boolean yawReversed;
    private boolean pitchReversed;
    private double radarRange = 10000.0;
    private double radarDepression = 0.0;
    private double radarElevation = 75.0;
    private double aaProximityFuseRange = 5.0;
    private double radarHudScale = 70.0;
    private double radarHudX = 1.0;
    private double radarHudY = 1.0;
    private double aircraftRadarRange = 2000.0;
    private double aircraftRadarHorizontalHalfAngle = 90.0;
    private double aircraftRadarVerticalAngle = 16.0;
    private double aircraftProximityFuseRange = 10.0;
    private double aircraftFuseSafetySeconds = 0.3;
    private double aircraftRadarMinimumTargetSpeed;
    private double aircraftRadarHudScale = 100.0;
    private double aircraftRadarHudX = 98.0;
    private double aircraftRadarHudY = 50.0;
    private double thirdPersonHeight;
    private double viewAimOffsetX;
    private double viewAimOffsetY;
    private Vec3 yawOffset = Vec3.ZERO;
    private Vec3 pitchOffset = Vec3.ZERO;
    private Vec3 scopeOffset = Vec3.ZERO;
    private Vec3 desiredWorldDirection = new Vec3(0.0, 0.0, -1.0);
    private Vec3 podDesiredWorldDirection = new Vec3(0.0, 0.0, -1.0);
    private Vec3 podStabilizedWorldTarget = Vec3.ZERO;
    private Vec3 desiredWorldTarget = Vec3.ZERO;
    private Vec3 lastAimRayOrigin = Vec3.ZERO;
    private Vec3 lastAimRayDirection = new Vec3(0.0, 0.0, -1.0);
    private double opticalSearchHalfTanX = Math.tan(Math.toRadians(2.5));
    private double opticalSearchHalfTanY = Math.tan(Math.toRadians(2.5));
    private boolean hasAimDirection;
    private boolean hasPodAimDirection;
    private boolean hasPodStabilizedWorldTarget;
    private boolean hasAimTarget;
    private boolean hasAimRay;
    private boolean lastAimRayHit;
    private boolean stabilizerEnabled = true;
    private boolean headAimEnabled = true;
    private boolean remoteHeadAimEnabled;
    private boolean cannonMountPodControl;
    private double podCannonDepressionLimitDegrees = 90.0;
    private double podCannonElevationLimitDegrees = 90.0;
    private UUID scopeUserId;
    private boolean thermalEnabled;
    private UUID podViewUserId;
    private UUID lockedEntityId;
    private UUID lockedSubLevelId;
    private Vec3 lockedSubLevelAnchor = Vec3.ZERO;
    private Vec3 lockAimOffset = Vec3.ZERO;
    private UUID lastAimSubLevelId;
    private Vec3 lastAimSubLevelAnchor = Vec3.ZERO;
    private long opticalSearchUntil = Long.MIN_VALUE;
    private long opticalLockAcquiredAt = Long.MIN_VALUE;
    private long opticalOccludedSince = Long.MIN_VALUE;
    private long lastOpticalVisibilityCheck = Long.MIN_VALUE;
    private boolean opticalLockObscured;
    private long lockedSubLevelLastPresenceCheck = Long.MIN_VALUE;
    private boolean lockedSubLevelPresent = true;
    private Set<UUID> cachedOwnSubLevelIds = Set.of();
    private long cachedOwnSubLevelIdsAt = Long.MIN_VALUE;
    private int frequencyConfigCursor;
    private int missileFireCursor;
    private int aaMissileFireCursor;
    private int aircraftMissileFireCursor;
    private MianbaoAircraftCompat.WeaponType selectedAircraftWeapon = MianbaoAircraftCompat.WeaponType.FAR;
    private Vec3 aircraftVelocity = Vec3.ZERO;
    private Vec3 previousAircraftOrigin;
    private long previousAircraftOriginTick = Long.MIN_VALUE;
    private UUID radarLockedSubLevelId;
    private UUID aircraftSelectedTargetId;
    private UUID airToGroundLockedSubLevelId;
    private Set<UUID> airToGroundLockedMembers = Set.of();
    private long airToGroundSearchUntil = Long.MIN_VALUE;
    private List<RadarContact> radarContacts = List.of();
    private final Map<UUID, Vec3> previousRadarCenters = new java.util.HashMap<>();
    private long lastRadarScanTick = Long.MIN_VALUE;
    private long cachedRadarTopologyAt = Long.MIN_VALUE;
    private Set<UUID> cachedRadarTopologyIds = Set.of();
    private List<Set<UUID>> cachedRadarClusters = List.of();
    private RadarUnionFind radarTopologyBuildUnion;
    private List<SubLevel> radarTopologyBuildLoaded = List.of();
    private Set<UUID> radarTopologyBuildIds = Set.of();
    private final Set<UUID> radarTopologyBuildVisited = new HashSet<>();
    private int radarTopologyBuildIndex;
    private long lastAimUpdateGameTime = Long.MIN_VALUE;
    private final AimDirectionDetector turretYawDirection = new AimDirectionDetector();
    private final AimDirectionDetector turretPitchDirection = new AimDirectionDetector();

    public StabilizerControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STABILIZER_CONTROLLER.get(), pos, state);
        java.util.Arrays.fill(normalRemoteKeys, "");
        java.util.Arrays.fill(advancedRemoteKeys, "");
    }

    @Override
    public void onLoad() {
        super.onLoad();
        ControllerTracker.add(this);
        if (level != null && !level.isClientSide) {
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, scopeReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, thermalReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, opticalLockReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, missileFireReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, aaMissileFireReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, radarLockReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, aircraftMissileFireReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, aircraftWeaponCycleReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, aircraftTargetCycleReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, podViewReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, airToGroundLockReceiver);
        }
    }

    @Override
    public void setRemoved() {
        ControllerTracker.remove(this);
        if (level != null && !level.isClientSide) {
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, scopeReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, thermalReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, opticalLockReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, missileFireReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, aaMissileFireReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, radarLockReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, aircraftMissileFireReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, aircraftWeaponCycleReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, aircraftTargetCycleReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, podViewReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, airToGroundLockReceiver);
        }
        super.setRemoved();
    }

    private void toggleScope(@Nullable ServerPlayer requestedUser) {
        ServerPlayer seated = findSeatedGunner();
        ServerPlayer user = requestedUser == null
                ? seated
                : requestedUser == seated ? requestedUser : null;
        if (!hasInputConfigured(0) || !hasAimReferenceBinding() || user == null) return;
        UUID userId = user.getUUID();
        if (userId.equals(scopeUserId)) {
            closeScope(user);
            return;
        }
        if (scopeUserId != null || (podViewUserId != null && !userId.equals(podViewUserId))) {
            user.displayClientMessage(Component.translatable(
                    "message.stabilized_turret.scope_in_use"), true);
            return;
        }
        if (userId.equals(podViewUserId)) closePodView(user);
        scopeUserId = userId;
        setChanged();
    }

    private void togglePodView(@Nullable ServerPlayer requestedUser) {
        togglePodView(requestedUser, false);
    }

    private void togglePodView(@Nullable ServerPlayer requestedUser, boolean wireless) {
        ServerPlayer user = requestedUser;
        if (!hasInputConfigured(9) || !hasPodBinding() || user == null
                || (!wireless && RemoteControlTracker.findInputController(user) != this)) return;
        UUID userId = user.getUUID();
        if (userId.equals(podViewUserId)) {
            closePodView(user);
            return;
        }
        if (podViewUserId != null || (scopeUserId != null && !userId.equals(scopeUserId))) {
            user.displayClientMessage(Component.translatable(
                    "message.stabilized_turret.pod_in_use"), true);
            return;
        }
        if (userId.equals(scopeUserId)) closeScope(user);
        // A closed pod keeps its local model rotation. Re-capture that actual
        // model bearing when a new view session starts, then let the first
        // client aim packet establish a fresh world-space gyro target.
        hasPodAimDirection = false;
        hasPodStabilizedWorldTarget = false;
        ensurePodAimDirection();
        podViewUserId = userId;
        thermalEnabled = false;
        clearOpticalLock();
        setChanged();
    }

    private void togglePodViewFromWireless() {
        togglePodView(findWirelessPodOperator(), true);
    }

    private void toggleAirToGroundLockFromWireless() {
        ServerPlayer operator = findPodViewUser();
        if (operator != null) toggleAirToGroundLock(operator);
    }

    private @Nullable ServerPlayer findPodViewUser() {
        return podViewUserId == null || level == null || level.getServer() == null
                ? null : level.getServer().getPlayerList().getPlayer(podViewUserId);
    }

    private @Nullable ServerPlayer findWirelessPodOperator() {
        ServerPlayer current = findPodViewUser();
        if (current != null) return current;
        ServerPlayer normal = RemoteControlTracker.findActivePlayer(
                this, RemoteControllerCompat.Type.NORMAL);
        if (normal != null) return normal;
        ServerPlayer advanced = RemoteControlTracker.findActivePlayer(
                this, RemoteControllerCompat.Type.ADVANCED);
        if (advanced != null) return advanced;
        if (level == null || level.getServer() == null) return null;
        Vec3 controllerWorld = Sable.HELPER.projectOutOfSubLevel(
                level, Vec3.atCenterOf(worldPosition));
        ServerPlayer best = null;
        double bestDistance = 128.0 * 128.0;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (!player.level().dimension().equals(level.dimension())) continue;
            double distance = player.position().distanceToSqr(controllerWorld);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private void toggleThermal(@Nullable ServerPlayer requestedUser) {
        if (!hasInputConfigured(1)) {
            thermalEnabled = false;
            return;
        }
        if (!isOpticalViewEnabled()
                || (requestedUser != null && !isOpticalViewEnabled(requestedUser))) return;
        thermalEnabled = !thermalEnabled;
        setChanged();
    }

    /** Fires at most one native Mianbao missile for each wireless rising edge. */
    private void fireNextMissile() {
        if (!hasInputConfigured(3) || level == null) {
            return;
        }
        removeInvalidBindings();
        if (missileLaunchers.isEmpty()) {
            notifyMissileOperator(Component.translatable("message.stabilized_turret.missile_launcher_missing"));
            return;
        }

        int size = missileLaunchers.size();
        int start = Math.floorMod(missileFireCursor, size);
        for (int offset = 0; offset < size; offset++) {
            int index = (start + offset) % size;
            BindingRef launcher = missileLaunchers.get(index);
            if (MianbaoMissileCompat.getAmmoCount(level, launcher) <= 0) {
                continue;
            }

            // Advance even when a third-party procedure fails. This prevents
            // one malformed launcher from permanently blocking the others,
            // while still invoking at most one launcher for this pulse.
            missileFireCursor = (index + 1) % size;
            boolean fired = MianbaoMissileCompat.fireOne(level, launcher);
            setChanged();
            if (!fired) {
                notifyMissileOperator(Component.translatable("message.stabilized_turret.missile_fire_failed"));
            }
            return;
        }
        notifyMissileOperator(Component.translatable("message.stabilized_turret.missiles_depleted"));
    }

    private void fireNextAaMissile() {
        if (!hasInputConfigured(4) || level == null) {
            return;
        }
        removeInvalidBindings();
        if (aaMissileLaunchers.isEmpty()) {
            notifyMissileOperator(Component.literal("防空导弹发射器：未绑定"));
            return;
        }
        if (!isRadarActive()) {
            notifyMissileOperator(Component.literal("防空导弹：雷达未工作"));
            return;
        }
        if (getRadarLockedSubLevel() == null) {
            notifyMissileOperator(Component.literal("防空导弹：未锁定雷达目标"));
            return;
        }
        int size = aaMissileLaunchers.size();
        int start = Math.floorMod(aaMissileFireCursor, size);
        for (int offset = 0; offset < size; offset++) {
            int index = (start + offset) % size;
            BindingRef launcher = aaMissileLaunchers.get(index);
            if (MianbaoAirDefenseCompat.getAmmoCount(level, launcher) <= 0) {
                continue;
            }
            aaMissileFireCursor = (index + 1) % size;
            boolean fired = MianbaoAirDefenseCompat.fireOne(level, launcher);
            setChanged();
            if (!fired) {
                notifyMissileOperator(Component.literal("防空导弹发射失败"));
            }
            return;
        }
        notifyMissileOperator(Component.literal("防空导弹已用尽"));
    }

    private void fireNextAircraftMissile() {
        if (!hasInputConfigured(6) || level == null) return;
        removeInvalidBindings();
        if (aircraftMissileRacks.isEmpty()) {
            notifyMissileOperator(Component.literal("机载导弹挂架：未绑定"));
            return;
        }
        if (MianbaoAircraftCompat.isAirToAir(selectedAircraftWeapon)
                && !isAircraftRadarActive()) {
            notifyMissileOperator(Component.literal("机载导弹：未检测到工作的机载雷达"));
            return;
        }
        if (MianbaoAircraftCompat.isAirToAir(selectedAircraftWeapon)
                && getAircraftGuidanceTargetId() == null) {
            notifyMissileOperator(Component.literal("机载导弹：当前没有选择雷达目标"));
            return;
        }
        if (MianbaoAircraftCompat.isAirToGround(selectedAircraftWeapon)
                && (!isPodViewEnabled() || getAirToGroundTargetSnapshot() == null)) {
            notifyMissileOperator(Component.translatable(
                    "message.stabilized_turret.air_to_ground_lock_required"));
            return;
        }
        if (MianbaoAircraftCompat.isLaserGuided(selectedAircraftWeapon)
                && (!isPodViewEnabled() || getPodGuidanceTarget() == null)) {
            notifyMissileOperator(Component.translatable(
                    "message.stabilized_turret.pod_guidance_required"));
            return;
        }
        int size = aircraftMissileRacks.size();
        int start = Math.floorMod(aircraftMissileFireCursor, size);
        for (int offset = 0; offset < size; offset++) {
            int index = (start + offset) % size;
            BindingRef rack = aircraftMissileRacks.get(index);
            if (MianbaoAircraftCompat.weaponAt(level, rack) != selectedAircraftWeapon) continue;
            aircraftMissileFireCursor = (index + 1) % size;
            boolean fired = MianbaoAircraftCompat.fireOne(level, rack, selectedAircraftWeapon);
            setChanged();
            if (fired && MianbaoAircraftCompat.isAirToGround(selectedAircraftWeapon)) {
                clearAirToGroundLock();
            }
            if (!fired) notifyMissileOperator(Component.literal("机载导弹发射失败"));
            return;
        }
        notifyMissileOperator(Component.literal("当前选择的机载导弹已用尽"));
    }

    private void cycleAircraftWeapon() {
        if (!hasInputConfigured(7) || level == null) return;
        MianbaoAircraftCompat.WeaponType[] values = MianbaoAircraftCompat.WeaponType.values();
        for (int step = 1; step <= values.length; step++) {
            MianbaoAircraftCompat.WeaponType candidate = values[(selectedAircraftWeapon.ordinal() + step) % values.length];
            if (getAircraftAmmoCount(candidate) > 0) {
                selectedAircraftWeapon = candidate;
                setChanged();
                notifyMissileOperator(Component.literal("机载武器：" + aircraftWeaponName(candidate)));
                return;
            }
        }
        notifyMissileOperator(Component.literal("机载导弹已用尽"));
    }

    private void cycleAircraftRadarTarget() {
        if (!hasInputConfigured(8) || !isAircraftRadarActive()
                || findSeatedGunner() == null || radarContacts.isEmpty()) return;
        int current = -1;
        if (aircraftSelectedTargetId != null) {
            for (int i = 0; i < radarContacts.size(); i++) {
                RadarContact contact = radarContacts.get(i);
                if (contact.subLevelId().equals(aircraftSelectedTargetId)
                        || contact.memberIds().contains(aircraftSelectedTargetId)) { current = i; break; }
            }
        }
        aircraftSelectedTargetId = radarContacts.get((current + 1) % radarContacts.size()).subLevelId();
        setChanged();
    }

    private static String aircraftWeaponName(MianbaoAircraftCompat.WeaponType type) {
        return switch (type) {
            case FAR -> "远距离空空导弹";
            case MEDIUM -> "中距离空空导弹";
            case CLOSE -> "近距离格斗导弹";
            case AIR_TO_GROUND_DIRECT -> "普通空对地导弹";
            case AIR_TO_GROUND_TOP_ATTACK -> "攻顶空对地导弹";
            case LASER_GUIDED -> "激光制导导弹";
        };
    }

    private void toggleAirToGroundLock(@Nullable ServerPlayer operator) {
        if (!hasInputConfigured(10) || level == null || operator == null
                || !isPodViewEnabled(operator)) {
            return;
        }
        if (airToGroundLockedSubLevelId != null) {
            clearAirToGroundLock();
            operator.displayClientMessage(Component.translatable(
                    "message.stabilized_turret.air_to_ground_lock_released"), true);
            return;
        }
        airToGroundSearchUntil = level.getGameTime() + 20L;
        GroundTargetSnapshot target = findAirToGroundTargetInPodFrame();
        if (target == null) {
            operator.displayClientMessage(Component.translatable(
                    "message.stabilized_turret.air_to_ground_no_target"), true);
            setChanged();
            return;
        }
        airToGroundLockedSubLevelId = target.id();
        airToGroundLockedMembers = target.memberIds();
        airToGroundSearchUntil = Long.MIN_VALUE;
        operator.displayClientMessage(Component.translatable(
                "message.stabilized_turret.air_to_ground_target_locked"), true);
        setChanged();
    }

    private void notifyMissileOperator(Component message) {
        ServerPlayer operator = findSeatedGunner();
        if (operator == null && level != null && level.getServer() != null) {
            for (ServerPlayer candidate : level.getServer().getPlayerList().getPlayers()) {
                if (RemoteControlTracker.findInputController(candidate) == this) {
                    operator = candidate;
                    break;
                }
            }
        }
        if (operator == null && level != null && level.getServer() != null) {
            double bestDistance = 64.0;
            for (ServerPlayer candidate : level.getServer().getPlayerList().getPlayers()) {
                double distance = Sable.HELPER.distanceSquaredWithSubLevels(
                        candidate.level(), candidate.position(), Vec3.atCenterOf(worldPosition));
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    operator = candidate;
                }
            }
        }
        if (operator != null) {
            operator.displayClientMessage(message, true);
        }
    }

    /**
     * One pulse acquires a target under the gunner's current reticle; the next
     * pulse releases it. Acquisition is deliberately restricted to the bound
     * seat, so a stray wireless signal cannot steer an unmanned tank.
     */
    private void toggleOpticalLock() {
        if (!hasInputConfigured(2)) {
            clearOpticalLock();
            return;
        }
        if (hasOpticalLock()) {
            clearOpticalLock();
            ServerPlayer gunner = findSeatedGunner();
            if (gunner != null) {
                gunner.displayClientMessage(Component.literal("光电锁定：已取消锁定"), true);
            }
            setChanged();
            return;
        }
        ServerPlayer gunner = findSeatedGunner();
        if (gunner == null) {
            return;
        }
        // The acquisition frame is deliberately visible for one second even
        // when no valid aim point or target exists.
        opticalSearchUntil = level == null ? Long.MIN_VALUE : level.getGameTime() + 20L;
        gunner.displayClientMessage(Component.literal("光电锁定：正在搜索目标"), true);
        acquireOpticalLock(gunner);
        setChanged();
    }

    private @Nullable ServerPlayer findSeatedGunner() {
        if (level == null || level.getServer() == null) {
            return null;
        }
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (isSeat(player.getVehicle())) {
                return player;
            }
        }
        return null;
    }

    private void acquireOpticalLock(ServerPlayer gunner) {
        if (level == null || (!hasAimTarget && !hasAimRay)) {
            return;
        }
        LivingEntity entity = findLivingTargetOnAimRay(gunner);
        if (entity != null) {
            lockedEntityId = entity.getUUID();
            lockedSubLevelId = null;
            // The lock acquisition itself centres the gun and reticle on the
            // target. Subsequent manual aim packets become the lead offset.
            lockAimOffset = Vec3.ZERO;
            opticalLockAcquiredAt = level.getGameTime();
            resetOpticalOcclusion();
            opticalSearchUntil = Long.MIN_VALUE;
            gunner.displayClientMessage(Component.literal("光电锁定：目标已锁定"), true);
            return;
        }
        if (lastAimSubLevelId != null) {
            if (isOwnControlledSubLevel(lastAimSubLevelId)) {
                return;
            }
            SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(lastAimSubLevelId);
            if (subLevel != null && !subLevel.isRemoved()) {
                Vec3 worldAnchor = subLevel.logicalPose().transformPosition(lastAimSubLevelAnchor);
                if (!isTargetAtLeastPartlyVisible(
                        opticalVisibilityOrigin(),
                        worldAnchor,
                        subLevelWorldBounds(subLevel),
                        lastAimSubLevelId,
                        ownControlledSubLevelIds()
                )) {
                    return;
                }
                lockedSubLevelId = lastAimSubLevelId;
                lockedSubLevelAnchor = lastAimSubLevelAnchor;
                lockedEntityId = null;
                lockAimOffset = Vec3.ZERO;
                opticalLockAcquiredAt = level.getGameTime();
                resetOpticalOcclusion();
                opticalSearchUntil = Long.MIN_VALUE;
                gunner.displayClientMessage(Component.literal("光电锁定：Sable 物理结构已锁定"), true);
            }
        }
    }

    /**
     * Finds a living target along the actual reticle ray. The previous
     * implementation searched only around the block-ray endpoint, which is
     * normally thousands of blocks behind an entity standing in open space.
     */
    private @Nullable LivingEntity findLivingTargetOnAimRay(ServerPlayer gunner) {
        Vec3 start = getAimOrigin();
        Vec3 direction = getMeasuredAimDirection();
        if (direction == null || direction.lengthSqr() < 1.0e-8) {
            if (hasAimRay && lastAimRayDirection.lengthSqr() > 1.0e-8) {
            start = lastAimRayOrigin;
            direction = lastAimRayDirection.normalize();
            } else {
                start = gunner.getEyePosition();
                direction = desiredWorldTarget.subtract(start).normalize();
            }
        }
        if (direction.lengthSqr() < 1.0e-8) {
            return null;
        }

        // Entity simulation/tracking ranges are much shorter than the
        // ballistic convergence ray. 1024 blocks still covers long-range
        // optics without constructing an unbounded search area.
        direction = direction.normalize();
        Vec3 frameRight = direction.cross(new Vec3(0.0, 1.0, 0.0));
        if (frameRight.lengthSqr() < 1.0e-8) {
            frameRight = new Vec3(1.0, 0.0, 0.0);
        } else {
            frameRight = frameRight.normalize();
        }
        Vec3 frameUp = frameRight.cross(direction).normalize();
        LivingEntity best = null;
        double bestAngularError = Double.POSITIVE_INFINITY;
        double bestDistance = Double.POSITIVE_INFINITY;
        Set<UUID> ignoredSubLevels = ownControlledSubLevelIds();
        // Iterate the server's already-loaded entities. A 2048-block AABB
        // query would walk an enormous number of empty entity sections when
        // the ray is diagonal and can cause visible TPS drops.
        for (net.minecraft.world.entity.Entity loaded : gunner.serverLevel().getAllEntities()) {
            if (!(loaded instanceof LivingEntity candidate)
                    || !candidate.isAlive()
                    || candidate == gunner
                    || candidate.isPassengerOfSameVehicle(gunner)) {
                continue;
            }
            Vec3 centre = candidate.getBoundingBox().getCenter();
            Vec3 relative = centre.subtract(start);
            double distanceAlongRay = relative.dot(direction);
            if (distanceAlongRay <= 0.0 || distanceAlongRay > 1024.0) {
                continue;
            }
            AABB bounds = candidate.getBoundingBox();
            double targetRadius = 0.5 * Math.sqrt(
                    bounds.getXsize() * bounds.getXsize()
                            + bounds.getYsize() * bounds.getYsize()
                            + bounds.getZsize() * bounds.getZsize()
            );
            double horizontal = Math.abs(relative.dot(frameRight));
            double vertical = Math.abs(relative.dot(frameUp));
            double allowedHorizontal = targetRadius + opticalSearchHalfTanX * distanceAlongRay;
            double allowedVertical = targetRadius + opticalSearchHalfTanY * distanceAlongRay;
            if (horizontal > allowedHorizontal || vertical > allowedVertical) {
                continue;
            }
            double angularError = Math.max(
                    horizontal / Math.max(1.0e-4, allowedHorizontal),
                    vertical / Math.max(1.0e-4, allowedVertical)
            );
            boolean betterTarget = angularError < bestAngularError - 1.0e-6
                    || (Math.abs(angularError - bestAngularError) <= 1.0e-6
                    && distanceAlongRay < bestDistance);
            if (betterTarget && isTargetAtLeastPartlyVisible(
                    start,
                    centre,
                    bounds,
                    null,
                    ignoredSubLevels
            )) {
                bestAngularError = angularError;
                bestDistance = distanceAlongRay;
                best = candidate;
            }
        }
        return best;
    }

    private boolean isOwnControlledSubLevel(@Nullable UUID id) {
        return id != null && ownControlledSubLevelIds().contains(id);
    }

    private Set<UUID> ownControlledSubLevelIds() {
        long now = level == null ? Long.MIN_VALUE : level.getGameTime();
        if (cachedOwnSubLevelIdsAt != Long.MIN_VALUE
                && now >= cachedOwnSubLevelIdsAt
                && now - cachedOwnSubLevelIdsAt < OWN_SUB_LEVEL_CACHE_TICKS) {
            return cachedOwnSubLevelIds;
        }

        Set<UUID> ownIds = new HashSet<>();
        SubLevel controllerSubLevel = Sable.HELPER.getContaining(this);
        if (controllerSubLevel != null) {
            ownIds.add(controllerSubLevel.getUniqueId());
        }
        for (BindingSlot slot : BindingSlot.values()) {
            BindingRef ref = bindings.get(slot);
            if (ref != null && ref.subLevelId() != null) {
                ownIds.add(ref.subLevelId());
            }
        }
        for (BindingRef ref : missileLaunchers) {
            if (ref.subLevelId() != null) ownIds.add(ref.subLevelId());
        }
        for (BindingRef ref : aaMissileLaunchers) {
            if (ref.subLevelId() != null) ownIds.add(ref.subLevelId());
        }
        for (BindingRef ref : aircraftMissileRacks) {
            if (ref.subLevelId() != null) ownIds.add(ref.subLevelId());
        }
        if (level != null) {
            for (UUID ownId : Set.copyOf(ownIds)) {
                SubLevel ownSubLevel = SubLevelContainer.getContainer(level).getSubLevel(ownId);
                if (ownSubLevel != null && !ownSubLevel.isRemoved()) {
                    for (SubLevel connected : SubLevelHelper.getConnectedChain(ownSubLevel)) {
                        if (connected != null && !connected.isRemoved()) {
                            ownIds.add(connected.getUniqueId());
                        }
                    }
                }
            }
        }
        cachedOwnSubLevelIds = Set.copyOf(ownIds);
        cachedOwnSubLevelIdsAt = now;
        return cachedOwnSubLevelIds;
    }

    private void invalidateOwnSubLevelCache() {
        cachedOwnSubLevelIds = Set.of();
        cachedOwnSubLevelIdsAt = Long.MIN_VALUE;
    }

    private @Nullable GroundTargetSnapshot findAirToGroundTargetInPodFrame() {
        if (level == null || !hasPodStabilizedWorldTarget) return null;
        Vec3 origin = getPodCameraPosition();
        if (origin == null) return null;
        Vec3 direction = podStabilizedWorldTarget.subtract(origin);
        if (direction.lengthSqr() < 1.0e-8) return null;
        direction = direction.normalize();
        Vec3 right = direction.cross(new Vec3(0.0, 1.0, 0.0));
        if (right.lengthSqr() < 1.0e-8) right = new Vec3(1.0, 0.0, 0.0);
        else right = right.normalize();
        Vec3 up = right.cross(direction).normalize();

        List<SubLevel> loaded = SubLevelContainer.getContainer(level).getAllSubLevels().stream()
                .filter(subLevel -> subLevel != null && !subLevel.isRemoved())
                .map(subLevel -> (SubLevel) subLevel)
                .toList();
        Map<UUID, SubLevel> byId = new java.util.HashMap<>();
        for (SubLevel subLevel : loaded) byId.put(subLevel.getUniqueId(), subLevel);
        Set<UUID> own = ownControlledSubLevelIds();
        GroundTargetSnapshot best = null;
        double bestError = Double.POSITIVE_INFINITY;
        double bestDistance = Double.POSITIVE_INFINITY;
        double halfTanX = Math.max(1.0e-4, opticalSearchHalfTanX * 0.28);
        double halfTanY = Math.max(1.0e-4, opticalSearchHalfTanY * 0.28);
        for (Set<UUID> members : radarConnectedClusters(loaded, byId.keySet())) {
            if (members.stream().anyMatch(own::contains)) continue;
            AABB bounds = null;
            for (UUID member : members) {
                SubLevel subLevel = byId.get(member);
                if (subLevel == null || !hasUsableRadarBounds(subLevel)) continue;
                AABB part = subLevelWorldBounds(subLevel);
                if (!isFinite(part)) continue;
                bounds = bounds == null ? part : unionBounds(bounds, part);
            }
            if (bounds == null) continue;
            Vec3 center = bounds.getCenter();
            Vec3 relative = center.subtract(origin);
            double along = relative.dot(direction);
            if (along <= 0.0 || along > 65_536.0) continue;
            double radius = 0.5 * Math.sqrt(
                    bounds.getXsize() * bounds.getXsize()
                            + bounds.getYsize() * bounds.getYsize()
                            + bounds.getZsize() * bounds.getZsize());
            double horizontal = Math.abs(relative.dot(right));
            double vertical = Math.abs(relative.dot(up));
            double allowedHorizontal = radius + halfTanX * along;
            double allowedVertical = radius + halfTanY * along;
            if (horizontal > allowedHorizontal || vertical > allowedVertical) continue;
            UUID representative = members.stream()
                    .min(java.util.Comparator.comparing(UUID::toString)).orElseThrow();
            if (!isTargetAtLeastPartlyVisible(origin, center, bounds, representative, own)) continue;
            double error = Math.max(
                    horizontal / Math.max(1.0e-4, allowedHorizontal),
                    vertical / Math.max(1.0e-4, allowedVertical));
            if (error < bestError - 1.0e-6
                    || (Math.abs(error - bestError) <= 1.0e-6 && along < bestDistance)) {
                bestError = error;
                bestDistance = along;
                best = new GroundTargetSnapshot(
                        representative, center, bounds, Set.copyOf(members));
            }
        }
        return best;
    }

    public @Nullable GroundTargetSnapshot getAirToGroundTargetSnapshot() {
        if (level == null || airToGroundLockedSubLevelId == null
                || airToGroundLockedMembers.isEmpty()) return null;
        GroundTargetSnapshot snapshot = resolveGroundTarget(
                airToGroundLockedSubLevelId, airToGroundLockedMembers);
        if (snapshot == null) {
            clearAirToGroundLock();
        } else {
            airToGroundLockedMembers = snapshot.memberIds();
        }
        return snapshot;
    }

    public @Nullable GroundTargetSnapshot resolveGroundTarget(
            @Nullable UUID representative,
            Set<UUID> members
    ) {
        if (level == null || representative == null || members == null || members.isEmpty()) {
            return null;
        }
        AABB bounds = null;
        Set<UUID> existing = new HashSet<>();
        for (UUID id : members) {
            SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(id);
            if (subLevel == null || subLevel.isRemoved() || !hasUsableRadarBounds(subLevel)) continue;
            AABB part = subLevelWorldBounds(subLevel);
            if (!isFinite(part)) continue;
            existing.add(id);
            bounds = bounds == null ? part : unionBounds(bounds, part);
        }
        if (bounds == null || existing.isEmpty()) return null;
        return new GroundTargetSnapshot(
                representative, bounds.getCenter(), bounds, Set.copyOf(existing));
    }

    public boolean isAirToGroundSearching() {
        return level != null && level.getGameTime() <= airToGroundSearchUntil
                && airToGroundLockedSubLevelId == null;
    }

    public void clearAirToGroundLock() {
        boolean changed = airToGroundLockedSubLevelId != null
                || !airToGroundLockedMembers.isEmpty()
                || airToGroundSearchUntil != Long.MIN_VALUE;
        airToGroundLockedSubLevelId = null;
        airToGroundLockedMembers = Set.of();
        airToGroundSearchUntil = Long.MIN_VALUE;
        if (changed) setChanged();
    }

    /**
     * A target is considered obscured only when every sampled point is hidden.
     * This deliberately allows a lock through a partially exposed corner while
     * rejecting entities and physical structures completely covered by blocks.
     */
    private boolean isTargetAtLeastPartlyVisible(
            Vec3 origin,
            Vec3 primaryPoint,
            @Nullable AABB bounds,
            @Nullable UUID targetSubLevelId,
            Set<UUID> ignoredSubLevels
    ) {
        if (!isFinite(origin) || !isFinite(primaryPoint)) {
            return false;
        }
        if (isVisibilityRayClear(origin, primaryPoint, targetSubLevelId, ignoredSubLevels)) {
            return true;
        }
        if (bounds == null) {
            return false;
        }

        double insetX = Math.min(0.12, bounds.getXsize() * 0.2);
        double insetY = Math.min(0.12, bounds.getYsize() * 0.2);
        double insetZ = Math.min(0.12, bounds.getZsize() * 0.2);
        double minX = bounds.minX + insetX;
        double minY = bounds.minY + insetY;
        double minZ = bounds.minZ + insetZ;
        double maxX = bounds.maxX - insetX;
        double maxY = bounds.maxY - insetY;
        double maxZ = bounds.maxZ - insetZ;
        Vec3 centre = bounds.getCenter();
        Vec3[] samples = new Vec3[]{
                centre,
                new Vec3(minX, centre.y, centre.z),
                new Vec3(maxX, centre.y, centre.z),
                new Vec3(centre.x, minY, centre.z),
                new Vec3(centre.x, maxY, centre.z),
                new Vec3(centre.x, centre.y, minZ),
                new Vec3(centre.x, centre.y, maxZ),
                new Vec3(minX, minY, minZ),
                new Vec3(minX, minY, maxZ),
                new Vec3(minX, maxY, minZ),
                new Vec3(minX, maxY, maxZ),
                new Vec3(maxX, minY, minZ),
                new Vec3(maxX, minY, maxZ),
                new Vec3(maxX, maxY, minZ),
                new Vec3(maxX, maxY, maxZ)
        };
        for (Vec3 sample : samples) {
            if (isVisibilityRayClear(origin, sample, targetSubLevelId, ignoredSubLevels)) {
                return true;
            }
        }
        return false;
    }

    private boolean isVisibilityRayClear(
            Vec3 origin,
            Vec3 target,
            @Nullable UUID targetSubLevelId,
            Set<UUID> ignoredSubLevels
    ) {
        if (level == null || origin.distanceToSqr(target) <= 1.0e-8) {
            return true;
        }
        ClipContext context = new ClipContext(
                origin,
                target,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                CollisionContext.empty()
        );
        if (!ignoredSubLevels.isEmpty()) {
            ((ClipContextExtension) context).sable$setSubLevelIgnoring(
                    subLevel -> ignoredSubLevels.contains(subLevel.getUniqueId())
            );
        }
        HitResult result = level.clip(context);
        if (result.getType() == HitResult.Type.MISS) {
            return true;
        }

        Vec3 rawHit = result.getLocation();
        SubLevel hitSubLevel = Sable.HELPER.getContaining(level, rawHit);
        if (hitSubLevel != null
                && targetSubLevelId != null
                && targetSubLevelId.equals(hitSubLevel.getUniqueId())) {
            // Hitting the target structure itself is a clear line of sight.
            return true;
        }
        Vec3 worldHit = hitSubLevel == null
                ? rawHit
                : hitSubLevel.logicalPose().transformPosition(rawHit);
        double targetDistance = origin.distanceTo(target);
        double hitDistance = origin.distanceTo(worldHit);
        return hitDistance + 0.20 >= targetDistance;
    }

    private Vec3 opticalVisibilityOrigin() {
        Vec3 scope = getScopePosition();
        if (scope != null) {
            return scope;
        }
        if (level != null && hasAimRay && lastAimUpdateGameTime != Long.MIN_VALUE
                && level.getGameTime() - lastAimUpdateGameTime <= 5L) {
            return lastAimRayOrigin;
        }
        return getAimOrigin();
    }

    private static boolean isFinite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private void resetOpticalOcclusion() {
        opticalOccludedSince = Long.MIN_VALUE;
        lastOpticalVisibilityCheck = Long.MIN_VALUE;
        opticalLockObscured = false;
    }

    private void clearOpticalLock() {
        boolean hadLock = hasOpticalLock();
        lockedEntityId = null;
        lockedSubLevelId = null;
        lockAimOffset = Vec3.ZERO;
        opticalLockAcquiredAt = Long.MIN_VALUE;
        resetOpticalOcclusion();
        lockedSubLevelLastPresenceCheck = Long.MIN_VALUE;
        lockedSubLevelPresent = true;
        opticalSearchUntil = Long.MIN_VALUE;
        if (hadLock) {
            // Never leave one tick of the destroyed/removed target in the
            // normal head-aim path. Fresh client aim packets will repopulate
            // these fields immediately after a manual unlock.
            hasAimTarget = false;
            hasAimDirection = false;
            hasAimRay = false;
            lastAimUpdateGameTime = Long.MIN_VALUE;
        }
    }

    public boolean hasOpticalLock() {
        return lockedEntityId != null || lockedSubLevelId != null;
    }

    public boolean isOpticalSearching() {
        return level != null && level.getGameTime() <= opticalSearchUntil && !hasOpticalLock();
    }

    public boolean isOpticalLockObscured() {
        return hasOpticalLock() && opticalLockObscured;
    }

    public @Nullable Vec3 getOpticalLockTarget() {
        return opticalLockTarget();
    }

    /** World-space bounds used to size the target box on the HUD. */
    public @Nullable AABB getOpticalLockBounds() {
        if (level == null) {
            return null;
        }
        if (lockedEntityId != null && level instanceof ServerLevel serverLevel) {
            net.minecraft.world.entity.Entity entity = serverLevel.getEntity(lockedEntityId);
            return !(entity instanceof LivingEntity living) || !living.isAlive() || living.isRemoved()
                    ? null
                    : living.getBoundingBox();
        }
        if (lockedSubLevelId == null) {
            return null;
        }
        SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(lockedSubLevelId);
        if (subLevel == null || subLevel.isRemoved() || !lockedSubLevelStillExists(subLevel)) {
            return null;
        }
        return subLevelWorldBounds(subLevel);
    }

    public static AABB subLevelWorldBounds(SubLevel subLevel) {
        BoundingBox3ic local = subLevel.getPlot().getBoundingBox();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int ix = 0; ix < 2; ix++) {
            for (int iy = 0; iy < 2; iy++) {
                for (int iz = 0; iz < 2; iz++) {
                    Vector3d corner = new Vector3d(
                            ix == 0 ? local.minX() : local.maxX() + 1.0,
                            iy == 0 ? local.minY() : local.maxY() + 1.0,
                            iz == 0 ? local.minZ() : local.maxZ() + 1.0
                    );
                    subLevel.logicalPose().transformPosition(corner);
                    minX = Math.min(minX, corner.x);
                    minY = Math.min(minY, corner.y);
                    minZ = Math.min(minZ, corner.z);
                    maxX = Math.max(maxX, corner.x);
                    maxY = Math.max(maxY, corner.y);
                    maxZ = Math.max(maxZ, corner.z);
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private @Nullable Vec3 opticalLockTarget() {
        if (level == null) {
            return null;
        }
        if (lockedEntityId != null) {
            net.minecraft.world.entity.Entity entity = level instanceof ServerLevel serverLevel
                    ? serverLevel.getEntity(lockedEntityId)
                    : null;
            return entity instanceof LivingEntity living && living.isAlive() && !living.isRemoved()
                    ? living.getBoundingBox().getCenter()
                    : null;
        }
        if (lockedSubLevelId != null) {
            SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(lockedSubLevelId);
            if (subLevel != null && !subLevel.isRemoved() && lockedSubLevelStillExists(subLevel)) {
                return subLevel.logicalPose().transformPosition(lockedSubLevelAnchor);
            }
        }
        return null;
    }

    private boolean lockedSubLevelStillExists(SubLevel subLevel) {
        if (level == null) {
            return false;
        }
        long gameTime = level.getGameTime();
        if (lockedSubLevelLastPresenceCheck != Long.MIN_VALUE
                && gameTime - lockedSubLevelLastPresenceCheck < 10L) {
            return lockedSubLevelPresent;
        }
        lockedSubLevelLastPresenceCheck = gameTime;
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        lockedSubLevelPresent = false;
        for (int y = bounds.minY(); y <= bounds.maxY() && !lockedSubLevelPresent; y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ() && !lockedSubLevelPresent; z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    cursor.set(x, y, z);
                    if (!level.getBlockState(cursor).isAir()) {
                        lockedSubLevelPresent = true;
                        break;
                    }
                }
            }
        }
        return lockedSubLevelPresent;
    }

    public boolean isHeadAimEnabled() {
        return headAimEnabled;
    }

    public boolean isRemoteHeadAimEnabled() {
        return remoteHeadAimEnabled;
    }

    public boolean hasRemoteTerminal() {
        return RemoteTerminalDetector.hasAnyTerminal(this);
    }

    public boolean isHeadAimReady() {
        if (!headAimEnabled || level == null
                || !bindings.containsKey(BindingSlot.YAW)
                || !bindings.containsKey(BindingSlot.PITCH)) {
            return false;
        }
        Vec3 direction = getMeasuredAimDirection();
        return direction != null && direction.lengthSqr() > 1.0e-8;
    }

    public Component getHeadAimStatusMessage() {
        if (!headAimEnabled) {
            return Component.literal("头瞄未工作：控制器中的头瞄开关已关闭");
        }
        if (!bindings.containsKey(BindingSlot.YAW)) {
            return Component.literal("头瞄未工作：没有绑定方向机轴承");
        }
        if (!bindings.containsKey(BindingSlot.PITCH)) {
            return Component.literal("头瞄未工作：没有绑定高低机轴承");
        }
        if (!hasAimReferenceBinding()) {
            return Component.literal("头瞄未工作：没有绑定火炮底座或激光制导器");
        }
        if (!isHeadAimReady()) {
            return Component.literal("头瞄未工作：火炮或激光制导器没有提供有效方向");
        }
        return Component.literal("头瞄已就绪：屏幕中心正在控制方向机和高低机");
    }

    public boolean isScopeEnabled() {
        return scopeUserId != null
                && hasInputConfigured(0)
                && hasAimReferenceBinding();
    }

    public boolean isScopeEnabled(ServerPlayer player) {
        return player != null && player.getUUID().equals(scopeUserId) && isScopeEnabled();
    }

    public void closeScope() {
        if (scopeUserId == null) return;
        scopeUserId = null;
        thermalEnabled = false;
        setChanged();
    }

    public void closeScope(ServerPlayer player) {
        if (player != null && player.getUUID().equals(scopeUserId)) closeScope();
    }

    public void closePodView(ServerPlayer player) {
        if (player == null || !player.getUUID().equals(podViewUserId)) return;
        podViewUserId = null;
        clearAirToGroundLock();
        setChanged();
    }

    public void releaseViews(ServerPlayer player) {
        closeScope(player);
        closePodView(player);
    }

    public boolean isThermalEnabled() {
        return isOpticalViewEnabled()
                && thermalEnabled
                && hasInputConfigured(1);
    }

    public boolean isThermalEnabled(ServerPlayer player) {
        return isOpticalViewEnabled(player) && isThermalEnabled();
    }

    private boolean isOpticalViewEnabled() {
        return isScopeEnabled() || isPodViewEnabled();
    }

    private boolean isOpticalViewEnabled(ServerPlayer player) {
        return isScopeEnabled(player) || isPodViewEnabled(player);
    }

    public double getDepressionLimitDegrees() {
        return depressionLimitDegrees;
    }

    public double getElevationLimitDegrees() {
        return elevationLimitDegrees;
    }

    public double getPodCannonDepressionLimitDegrees() {
        return podCannonDepressionLimitDegrees;
    }

    public double getPodCannonElevationLimitDegrees() {
        return podCannonElevationLimitDegrees;
    }

    public void setPodCannonLimits(double depression, double elevation) {
        podCannonDepressionLimitDegrees = Mth.clamp(depression, 0.0, 90.0);
        podCannonElevationLimitDegrees = Mth.clamp(elevation, 0.0, 90.0);
        setChanged();
    }

    public double getYawSpeedDegreesPerSecond() {
        return yawSpeedDegreesPerSecond;
    }

    public double getPitchSpeedDegreesPerSecond() {
        return pitchSpeedDegreesPerSecond;
    }

    public boolean isYawReversed() {
        return yawReversed;
    }

    public boolean isPitchReversed() {
        return pitchReversed;
    }

    public boolean isStabilizerEnabled() {
        return stabilizerEnabled;
    }

    public double getThirdPersonHeight() {
        return thirdPersonHeight;
    }

    public double getViewAimOffsetX() {
        return viewAimOffsetX;
    }

    public double getViewAimOffsetY() {
        return viewAimOffsetY;
    }

    public Vec3 getYawOffset() {
        return yawOffset;
    }

    public Vec3 getPitchOffset() {
        return pitchOffset;
    }

    public Vec3 getScopeOffset() {
        return scopeOffset;
    }

    public void applySettings(
            double depression,
            double elevation,
            double yawSpeed,
            double pitchSpeed,
            boolean yawReversed,
            boolean pitchReversed,
            double cameraHeight,
            double viewAimOffsetX,
            double viewAimOffsetY,
            Vec3 yawOffset,
            Vec3 pitchOffset,
            Vec3 scopeOffset,
            boolean stabilizer,
            boolean headAim,
            boolean remoteHeadAim,
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
            double aircraftRadarHudY
    ) {
        depressionLimitDegrees = Mth.clamp(depression, 0.0, 90.0);
        elevationLimitDegrees = Mth.clamp(elevation, 0.0, 90.0);
        yawSpeedDegreesPerSecond = Mth.clamp(yawSpeed, 0.1, 360.0);
        pitchSpeedDegreesPerSecond = Mth.clamp(pitchSpeed, 0.1, 360.0);
        if (this.yawReversed != yawReversed) turretYawDirection.invalidate();
        if (this.pitchReversed != pitchReversed) turretPitchDirection.invalidate();
        this.yawReversed = yawReversed;
        this.pitchReversed = pitchReversed;
        thirdPersonHeight = Mth.clamp(cameraHeight, 0.0, 32.0);
        this.viewAimOffsetX = clampAimOffset(viewAimOffsetX);
        this.viewAimOffsetY = clampAimOffset(viewAimOffsetY);
        this.yawOffset = clampOffset(yawOffset);
        this.pitchOffset = clampOffset(pitchOffset);
        this.scopeOffset = clampOffset(scopeOffset);
        stabilizerEnabled = stabilizer;
        if (headAimEnabled && !headAim) {
            // A disabled gunner channel must not leave one last target alive.
            lastAimUpdateGameTime = Long.MIN_VALUE;
            hasAimTarget = false;
        }
        headAimEnabled = headAim;
        remoteHeadAimEnabled = remoteHeadAim;
        this.radarRange = Mth.clamp(radarRange, 200.0, 20000.0);
        this.radarDepression = Mth.clamp(radarDepression, 0.0, 90.0);
        this.radarElevation = Mth.clamp(radarElevation, 0.0, 90.0);
        this.aaProximityFuseRange = Mth.clamp(aaProximityFuseRange, 0.0, 10.0);
        this.radarHudScale = Mth.clamp(radarHudScale, 40.0, 150.0);
        this.radarHudX = Mth.clamp(radarHudX, 0.0, 100.0);
        this.radarHudY = Mth.clamp(radarHudY, 0.0, 100.0);
        this.aircraftRadarRange = Mth.clamp(aircraftRadarRange, 200.0, 100000.0);
        this.aircraftRadarHorizontalHalfAngle = Mth.clamp(aircraftRadarHorizontalHalfAngle, 5.0, 90.0);
        this.aircraftRadarVerticalAngle = Mth.clamp(aircraftRadarVerticalAngle, 2.0, 90.0);
        this.aircraftProximityFuseRange = Mth.clamp(aircraftProximityFuseRange, 0.0, 10.0);
        this.aircraftFuseSafetySeconds = Mth.clamp(aircraftFuseSafetySeconds, 0.0, 10.0);
        this.aircraftRadarMinimumTargetSpeed = Mth.clamp(
                aircraftRadarMinimumTargetSpeed, 0.0, 2000.0);
        this.aircraftRadarHudScale = Mth.clamp(aircraftRadarHudScale, 40.0, 180.0);
        this.aircraftRadarHudX = Mth.clamp(aircraftRadarHudX, 0.0, 100.0);
        this.aircraftRadarHudY = Mth.clamp(aircraftRadarHudY, 0.0, 100.0);
        setChanged();
    }

    private static Vec3 clampOffset(Vec3 offset) {
        return new Vec3(
                clampOffsetComponent(offset.x),
                clampOffsetComponent(offset.y),
                clampOffsetComponent(offset.z)
        );
    }

    private static double clampOffsetComponent(double value) {
        return Mth.clamp(Math.round(value * 10.0) / 10.0, -20.0, 20.0);
    }

    private static double clampAimOffset(double value) {
        return Mth.clamp(Math.round(value * 10.0) / 10.0, -45.0, 45.0);
    }

    public int assignNextFrequency(ItemStack stack) {
        if (frequencyConfigCursor >= INPUT_ACTION_COUNT * 2) frequencyConfigCursor = 0;
        WirelessPulseReceiver receiver = receiverForChannel(frequencyConfigCursor / 2);
        boolean first = (frequencyConfigCursor & 1) == 0;
        if (level != null && !level.isClientSide) {
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, receiver);
        }
        receiver.setFrequency(first, stack);
        if (level != null && !level.isClientSide) {
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, receiver);
        }
        int assigned = frequencyConfigCursor;
        frequencyConfigCursor = (frequencyConfigCursor + 1) % (INPUT_ACTION_COUNT * 2);
        setChanged();
        return assigned;
    }

    public void setFrequency(int channel, boolean first, ItemStack stack) {
        if (channel < 0 || channel >= INPUT_ACTION_COUNT) return;
        WirelessPulseReceiver receiver = receiverForChannel(channel);
        if (level != null && !level.isClientSide) {
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, receiver);
        }
        receiver.setFrequency(first, stack);
        if (!hasInputConfigured(channel)) {
            if (channel == 1) {
                thermalEnabled = false;
            } else if (channel == 0) {
                closeScope();
                thermalEnabled = false;
            } else if (channel == 2) {
                clearOpticalLock();
            } else if (channel == 5) {
                radarLockedSubLevelId = null;
            } else if (channel == 9) {
                podViewUserId = null;
            } else if (channel == 10) {
                clearAirToGroundLock();
            }
        }
        if (level != null && !level.isClientSide) {
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, receiver);
        }
        setChanged();
    }

    public String getFrequencyName(int channel, boolean first) {
        if (channel < 0 || channel >= INPUT_ACTION_COUNT) return "-";
        ItemStack stack = receiverForChannel(channel).getFrequency(first);
        return stack.isEmpty() ? "-" : stack.getHoverName().getString();
    }

    private WirelessPulseReceiver receiverForChannel(int channel) {
        return switch (channel) {
            case 1 -> thermalReceiver;
            case 2 -> opticalLockReceiver;
            case 3 -> missileFireReceiver;
            case 4 -> aaMissileFireReceiver;
            case 5 -> radarLockReceiver;
            case 6 -> aircraftMissileFireReceiver;
            case 7 -> aircraftWeaponCycleReceiver;
            case 8 -> aircraftTargetCycleReceiver;
            case 9 -> podViewReceiver;
            case 10 -> airToGroundLockReceiver;
            default -> scopeReceiver;
        };
    }

    private boolean hasInputConfigured(int action) {
        return action >= 0 && action < INPUT_ACTION_COUNT
                && (receiverForChannel(action).hasConfiguredFrequency()
                || (!normalRemoteKeys[action].isEmpty()
                && bindings.containsKey(BindingSlot.REMOTE_CONTROLLER_HUB))
                || (!advancedRemoteKeys[action].isEmpty()
                && bindings.containsKey(BindingSlot.TWEAKED_CONTROLLER_HUB)));
    }

    public List<String> getRemoteKeys(RemoteControllerCompat.Type type) {
        String[] source = type == RemoteControllerCompat.Type.ADVANCED
                ? advancedRemoteKeys : normalRemoteKeys;
        return List.of(source.clone());
    }

    public boolean setRemoteKey(RemoteControllerCompat.Type type, int action, String key) {
        if (action < 0 || action >= INPUT_ACTION_COUNT) return false;
        String normalized = key == null ? "" : key;
        if (!normalized.isEmpty() && !DriveByWireCompat.accepts(type, normalized)) return false;
        String[] destination = type == RemoteControllerCompat.Type.ADVANCED
                ? advancedRemoteKeys : normalRemoteKeys;
        destination[action] = normalized;
        remoteInputPressed[action] = false;
        if (!hasInputConfigured(action)) {
            if (action == 0) {
                closeScope();
                thermalEnabled = false;
            } else if (action == 1) {
                thermalEnabled = false;
            } else if (action == 2) {
                clearOpticalLock();
            } else if (action == 5) {
                radarLockedSubLevelId = null;
            } else if (action == 9) {
                podViewUserId = null;
            } else if (action == 10) {
                clearAirToGroundLock();
            }
        }
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        return true;
    }

    private void tickRemoteInputs() {
        if (level == null || !DriveByWireCompat.isAvailable()) return;
        BindingRef normalHub = bindings.get(BindingSlot.REMOTE_CONTROLLER_HUB);
        BindingRef advancedHub = bindings.get(BindingSlot.TWEAKED_CONTROLLER_HUB);
        boolean normalValid = normalHub != null
                && RemoteTerminalDetector.hasTerminal(this, level, RemoteControllerCompat.Type.NORMAL);
        boolean advancedValid = advancedHub != null
                && RemoteTerminalDetector.hasTerminal(this, level, RemoteControllerCompat.Type.ADVANCED);
        Map<?, ?> normalSignals = normalValid ? DriveByWireCompat.signals(level, normalHub.pos()) : Map.of();
        Map<?, ?> advancedSignals = advancedValid ? DriveByWireCompat.signals(level, advancedHub.pos()) : Map.of();
        for (int action = 0; action < INPUT_ACTION_COUNT; action++) {
            boolean normalActive = normalValid && !normalRemoteKeys[action].isEmpty()
                    && DriveByWireCompat.signal(normalSignals, normalRemoteKeys[action]) > 0;
            boolean advancedActive = advancedValid && !advancedRemoteKeys[action].isEmpty()
                    && DriveByWireCompat.signal(advancedSignals, advancedRemoteKeys[action]) > 0;
            boolean active = normalActive || advancedActive;
            if (active && !remoteInputPressed[action]) {
                ServerPlayer operator = RemoteControlTracker.findActivePlayer(
                        this, normalActive ? RemoteControllerCompat.Type.NORMAL
                                : RemoteControllerCompat.Type.ADVANCED);
                activateInput(action, operator);
            }
            remoteInputPressed[action] = active;
        }
    }

    private void activateInput(int action, @Nullable ServerPlayer operator) {
        switch (action) {
            case 0 -> toggleScope(operator);
            case 1 -> toggleThermal(operator);
            case 2 -> toggleOpticalLock();
            case 3 -> fireNextMissile();
            case 4 -> fireNextAaMissile();
            case 5 -> toggleRadarLockFromWireless();
            case 6 -> fireNextAircraftMissile();
            case 7 -> cycleAircraftWeapon();
            case 8 -> cycleAircraftRadarTarget();
            case 9 -> togglePodView(operator);
            case 10 -> toggleAirToGroundLock(operator);
            default -> { }
        }
    }

    public boolean isPodViewEnabled() {
        return podViewUserId != null && hasInputConfigured(9) && hasPodBinding();
    }

    public boolean isPodViewEnabled(ServerPlayer player) {
        return player != null && player.getUUID().equals(podViewUserId) && isPodViewEnabled();
    }

    public boolean hasPodBinding() {
        if (level == null) return false;
        BindingRef pod = bindings.get(BindingSlot.POD);
        return pod != null && pod.isSameTarget(level)
                && ElectroOpticalPodCompat.worldPosition(level, pod) != null;
    }

    public @Nullable Vec3 getPodCameraPosition() {
        if (level == null) return null;
        BindingRef pod = bindings.get(BindingSlot.POD);
        if (pod == null) return null;
        return ElectroOpticalPodCompat.worldPosition(level, pod);
    }

    public @Nullable Vec3 getScopePosition() {
        if (level == null) {
            return null;
        }
        BindingRef reference = getAimReferenceBinding();
        if (reference == null) {
            return null;
        }
        // The selected cannon/designator is the scope's zero point. Apply the configured
        // offset before projecting out of the physical sub-level so the
        // offset remains local to (and rotates with) the turret.
        return Sable.HELPER.projectOutOfSubLevel(
                level,
                Vec3.atCenterOf(reference.pos()).add(scopeOffset)
        );
    }

    public static void tick(Level level, BlockPos pos, BlockState state, StabilizerControllerBlockEntity controller) {
        if (level.isClientSide || Sable.HELPER.getContaining(controller) != null) {
            return;
        }
        controller.serverTick();
    }

    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        if (!subLevel.getLevel().isClientSide) {
            serverTick();
        }
    }

    private void serverTick() {
        removeInvalidBindings();
        tickRemoteInputs();
        updateAircraftVelocity();
        updateRadarScan();
        if (airToGroundLockedSubLevelId != null) {
            getAirToGroundTargetSnapshot();
        }
        if (isOpticalSearching()) {
            ServerPlayer gunner = findSeatedGunner();
            if (gunner != null) {
                acquireOpticalLock(gunner);
            }
        }
        boolean opticalAiming = updateOpticalLock();
        boolean recentAim = level != null && lastAimUpdateGameTime != Long.MIN_VALUE
                && level.getGameTime() - lastAimUpdateGameTime <= 5L;
        boolean gunnerAiming = !isPodViewEnabled()
                && (opticalAiming || (headAimEnabled && recentAim));

        maintainBoundBearings(!gunnerAiming, stabilizerEnabled || gunnerAiming);
        calibratePitchFromCannon();

        if (gunnerAiming && (hasAimTarget || hasAimDirection)) {
            applyAimDirection();
        }
        updatePodModel();
        updatePodControlledCannon();
    }

    public void bind(BindingSlot slot, BindingRef target) {
        if (slot == BindingSlot.BEARING) {
            if (!unassignedBearings.contains(target)
                    && bindings.values().stream().noneMatch(target::equals)) {
                unassignedBearings.add(target);
            }
        } else if (slot == BindingSlot.CANNON_MOUNT) {
            if (!cannonMounts.contains(target)) cannonMounts.add(target);
        } else if (slot == BindingSlot.MISSILE_LAUNCHER) {
            if (!missileLaunchers.contains(target)) {
                missileLaunchers.add(target);
            }
        } else if (slot == BindingSlot.AA_MISSILE_LAUNCHER) {
            if (!aaMissileLaunchers.contains(target)) aaMissileLaunchers.add(target);
        } else if (slot == BindingSlot.AIRCRAFT_MISSILE_RACK) {
            if (!aircraftMissileRacks.contains(target)) aircraftMissileRacks.add(target);
        } else if (isPodBearingRole(slot)) {
            // Retained enum values are read only for old-network/NBT
            // compatibility. Pod-controlled physical bearings were removed.
            return;
        } else {
            bindings.put(slot, target);
        }
        if (slot == BindingSlot.POD) {
            hasPodAimDirection = false;
            hasPodStabilizedWorldTarget = false;
            ensurePodAimDirection();
        }
        if (slot == BindingSlot.CANNON_MOUNT) {
            cannonMountPodControl = false;
        }
        invalidateOwnSubLevelCache();
        if ((slot == BindingSlot.YAW || slot == BindingSlot.PITCH)
                && level != null
                && target.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
            // Apply the bound-body defaults immediately instead of waiting for
            // the controller's next server tick.
            BearingStabilizer.maintainBinding(
                    bearing,
                    isYawSlot(slot),
                    true,
                    stabilizerEnabled,
                    false,
                    0.0,
                    depressionLimitDegrees,
                    elevationLimitDegrees,
                    isYawSlot(slot) ? yawOffset : pitchOffset
            );
        }
        if (slot == BindingSlot.CANNON_MOUNT || slot == BindingSlot.LASER_DESIGNATOR
                || slot == BindingSlot.YAW || slot == BindingSlot.PITCH) {
            captureAimReferenceFromMount();
        }
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public BindingSlot firstUnboundSlot() {
        for (BindingSlot slot : BindingSlot.values()) {
            if (isPodBearingRole(slot)) continue;
            boolean bound = slot == BindingSlot.BEARING
                    ? !unassignedBearings.isEmpty()
                    : slot == BindingSlot.CANNON_MOUNT
                    ? !cannonMounts.isEmpty()
                    : slot == BindingSlot.MISSILE_LAUNCHER
                    ? !missileLaunchers.isEmpty()
                    : slot == BindingSlot.AA_MISSILE_LAUNCHER
                    ? !aaMissileLaunchers.isEmpty()
                    : slot == BindingSlot.AIRCRAFT_MISSILE_RACK
                    ? !aircraftMissileRacks.isEmpty()
                    : bindings.containsKey(slot);
            if (!bound) {
                return slot;
            }
        }
        // Once all single-value devices exist, the most common follow-up is
        // adding another launcher rather than replacing a bearing.
        return BindingSlot.MISSILE_LAUNCHER;
    }

    /**
     * Stabilization is autonomous. Before a gunner supplies a new line of
     * sight, lock the controller's current forward direction in world space.
     */
    private void ensureHoldDirection() {
        if (hasAimDirection
                || (!bindings.containsKey(BindingSlot.YAW) && !bindings.containsKey(BindingSlot.PITCH))) {
            return;
        }

        if (captureAimReferenceFromMount()) {
            return;
        }

        Direction facing = getBlockState().getValue(StabilizerControllerBlock.FACING);
        Vector3d worldForward = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        SubLevel containing = Sable.HELPER.getContaining(this);
        if (containing != null) {
            containing.logicalPose().transformNormal(worldForward);
        }
        if (worldForward.lengthSquared() > 1.0e-8) {
            worldForward.normalize();
            desiredWorldDirection = new Vec3(worldForward.x, worldForward.y, worldForward.z);
            hasAimDirection = true;
            setChanged();
        }
    }

    public @Nullable BindingRef getBinding(BindingSlot slot) {
        if (slot == BindingSlot.BEARING) {
            return unassignedBearings.stream().findFirst().orElse(null);
        }
        if (slot == BindingSlot.CANNON_MOUNT) {
            return cannonMounts.stream().findFirst().orElse(null);
        }
        if (slot == BindingSlot.MISSILE_LAUNCHER) {
            return missileLaunchers.stream().findFirst().orElse(null);
        }
        if (slot == BindingSlot.AA_MISSILE_LAUNCHER) {
            return aaMissileLaunchers.stream().findFirst().orElse(null);
        }
        if (slot == BindingSlot.AIRCRAFT_MISSILE_RACK) {
            return aircraftMissileRacks.stream().findFirst().orElse(null);
        }
        if (isPodBearingRole(slot)) return null;
        return bindings.get(slot);
    }

    public boolean assignBearingRole(
            BindingSlot sourceRole,
            int index,
            BlockPos expectedPos,
            BindingSlot role
    ) {
        if (level == null || !isAssignableBearingRole(role)
                || (sourceRole != BindingSlot.BEARING && !isAssignableBearingRole(sourceRole))) {
            return false;
        }
        BindingRef selected;
        if (sourceRole == BindingSlot.BEARING) {
            if (index < 0 || index >= unassignedBearings.size()) return false;
            selected = unassignedBearings.get(index);
        } else {
            selected = bindings.get(sourceRole);
        }
        if (selected == null) return false;
        if (!selected.pos().equals(expectedPos)
                || !(selected.resolve(level) instanceof SwivelBearingBlockEntity selectedBearing)) {
            return false;
        }

        if (sourceRole == BindingSlot.BEARING) {
            unassignedBearings.remove(index);
        } else {
            bindings.remove(sourceRole);
            BearingStabilizer.disable(selectedBearing);
        }
        BindingRef previous = bindings.put(role, selected);
        if (previous != null && !previous.equals(selected)) {
            if (previous.resolve(level) instanceof SwivelBearingBlockEntity previousBearing) {
                BearingStabilizer.disable(previousBearing);
            }
            if (!unassignedBearings.contains(previous)) unassignedBearings.add(previous);
        }

        BearingStabilizer.maintainBinding(
                selectedBearing,
                isYawSlot(role),
                true,
                stabilizerEnabled,
                false,
                0.0,
                depressionLimitDegrees,
                elevationLimitDegrees,
                isYawSlot(role) ? yawOffset : pitchOffset
        );
        invalidateOwnSubLevelCache();
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        return true;
    }

    private static boolean isAssignableBearingRole(BindingSlot role) {
        return role == BindingSlot.YAW || role == BindingSlot.PITCH;
    }

    private static boolean isPodBearingRole(BindingSlot role) {
        return role == BindingSlot.POD_YAW || role == BindingSlot.POD_PITCH;
    }

    public List<BindingRef> getMissileLaunchers() {
        return List.copyOf(missileLaunchers);
    }

    public List<BindingRef> getCannonMountBindings() {
        return List.copyOf(cannonMounts);
    }

    public List<BindingRef> getAaMissileLaunchers() {
        return List.copyOf(aaMissileLaunchers);
    }

    public List<BindingRef> getAircraftMissileRacks() { return List.copyOf(aircraftMissileRacks); }
    public List<BindingRef> getAllFireControlBearingBindings() {
        List<BindingRef> result = new ArrayList<>();
        BindingRef yaw = bindings.get(BindingSlot.YAW);
        BindingRef pitch = bindings.get(BindingSlot.PITCH);
        if (yaw != null) result.add(yaw);
        if (pitch != null) result.add(pitch);
        return List.copyOf(result);
    }
    public MianbaoAircraftCompat.WeaponType getSelectedAircraftWeapon() { return selectedAircraftWeapon; }
    public int getAircraftAmmoCount(MianbaoAircraftCompat.WeaponType type) {
        if (level == null) return 0;
        int count = 0;
        for (BindingRef rack : aircraftMissileRacks) count += MianbaoAircraftCompat.ammoCount(level, rack, type);
        return count;
    }

    public List<BoundDevice> getBoundDevices() {
        List<BoundDevice> devices = new ArrayList<>();
        for (BindingSlot slot : BindingSlot.values()) {
            if (slot == BindingSlot.BEARING) {
                for (int index = 0; index < unassignedBearings.size(); index++) {
                    devices.add(new BoundDevice(slot, index, unassignedBearings.get(index)));
                }
            } else if (slot == BindingSlot.CANNON_MOUNT) {
                for (int index = 0; index < cannonMounts.size(); index++) {
                    devices.add(new BoundDevice(slot, index, cannonMounts.get(index),
                            cannonMountPodControl ? "POD_CONTROL" : "DEFAULT", 0, false));
                }
            } else if (slot == BindingSlot.MISSILE_LAUNCHER) {
                for (int index = 0; index < missileLaunchers.size(); index++) {
                    devices.add(new BoundDevice(slot, index, missileLaunchers.get(index)));
                }
            } else if (slot == BindingSlot.AA_MISSILE_LAUNCHER) {
                for (int index = 0; index < aaMissileLaunchers.size(); index++) devices.add(new BoundDevice(slot, index, aaMissileLaunchers.get(index)));
            } else if (slot == BindingSlot.AIRCRAFT_MISSILE_RACK) {
                for (int index = 0; index < aircraftMissileRacks.size(); index++) devices.add(new BoundDevice(slot, index, aircraftMissileRacks.get(index)));
            } else if (isPodBearingRole(slot)) {
                // Legacy role: intentionally hidden and no longer active.
                continue;
            } else {
                BindingRef ref = bindings.get(slot);
                if (ref != null) {
                    devices.add(slot == BindingSlot.CANNON_MOUNT
                            ? new BoundDevice(
                            slot, -1, ref,
                            cannonMountPodControl ? "POD_CONTROL" : "DEFAULT",
                            0, false)
                            : new BoundDevice(slot, -1, ref));
                }
            }
        }
        return List.copyOf(devices);
    }

    /** Server-authoritative removal used by the controller's device page. */
    public boolean unbindDevice(BindingSlot slot, int index, BlockPos expectedPos) {
        BindingRef removed;
        if (slot == BindingSlot.BEARING) {
            if (index < 0 || index >= unassignedBearings.size()) return false;
            removed = unassignedBearings.get(index);
            if (!removed.pos().equals(expectedPos)) return false;
            unassignedBearings.remove(index);
        } else if (slot == BindingSlot.CANNON_MOUNT) {
            if (index < 0 || index >= cannonMounts.size()) return false;
            BindingRef candidate = cannonMounts.get(index);
            if (!candidate.pos().equals(expectedPos)) return false;
            removed = cannonMounts.remove(index);
        } else if (slot == BindingSlot.MISSILE_LAUNCHER) {
            if (index < 0 || index >= missileLaunchers.size()) {
                return false;
            }
            BindingRef candidate = missileLaunchers.get(index);
            if (!candidate.pos().equals(expectedPos)) {
                return false;
            }
            removed = missileLaunchers.remove(index);
            missileFireCursor = missileLaunchers.isEmpty()
                    ? 0
                    : Math.floorMod(missileFireCursor, missileLaunchers.size());
        } else if (slot == BindingSlot.AA_MISSILE_LAUNCHER) {
            if (index < 0 || index >= aaMissileLaunchers.size()) {
                return false;
            }
            BindingRef candidate = aaMissileLaunchers.get(index);
            if (!candidate.pos().equals(expectedPos)) {
                return false;
            }
            removed = aaMissileLaunchers.remove(index);
            aaMissileFireCursor = aaMissileLaunchers.isEmpty()
                    ? 0 : Math.floorMod(aaMissileFireCursor, aaMissileLaunchers.size());
        } else if (slot == BindingSlot.AIRCRAFT_MISSILE_RACK) {
            if (index < 0 || index >= aircraftMissileRacks.size()) return false;
            BindingRef candidate = aircraftMissileRacks.get(index);
            if (!candidate.pos().equals(expectedPos)) return false;
            removed = aircraftMissileRacks.remove(index);
            aircraftMissileFireCursor = aircraftMissileRacks.isEmpty() ? 0 : Math.floorMod(aircraftMissileFireCursor, aircraftMissileRacks.size());
        } else if (isPodBearingRole(slot)) {
            return false;
        } else {
            BindingRef candidate = bindings.get(slot);
            if (candidate == null || !candidate.pos().equals(expectedPos)) {
                return false;
            }
            removed = bindings.remove(slot);
        }

        if ((slot == BindingSlot.YAW || slot == BindingSlot.PITCH)
                && level != null
                && removed.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
            BearingStabilizer.disable(bearing);
        }
        if (slot == BindingSlot.CANNON_MOUNT || slot == BindingSlot.LASER_DESIGNATOR) {
            if (slot == BindingSlot.CANNON_MOUNT && cannonMounts.isEmpty()) {
                cannonMountPodControl = false;
            }
            if (!captureAimReferenceFromMount()) {
                closeScope();
                thermalEnabled = false;
            }
        }
        invalidateOwnSubLevelCache();
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        return true;
    }

    public boolean setCannonMountPodControl(BlockPos expectedPos, boolean enabled) {
        if (level == null) return false;
        BindingRef mount = cannonMounts.stream()
                .filter(ref -> ref.pos().equals(expectedPos) && ref.isSameTarget(level))
                .findFirst().orElse(null);
        if (mount == null) {
            return false;
        }
        if (enabled && !CannonMountCompat.hasAssembledCannon(level, mount)) {
            return false;
        }
        cannonMountPodControl = enabled;
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        return true;
    }

    public record RadarContact(
            UUID subLevelId, Vec3 center, Vec3 velocity, AABB bounds, Set<UUID> memberIds
    ) {
    }

    public record GroundTargetSnapshot(
            UUID id, Vec3 center, AABB bounds, Set<UUID> memberIds
    ) {
    }

    public record BoundDevice(
            BindingSlot slot,
            int index,
            BindingRef ref,
            String podMode,
            int podOrder,
            boolean podReversed
    ) {
        public BoundDevice(BindingSlot slot, int index, BindingRef ref) {
            this(slot, index, ref, "", 0, false);
        }
    }

    public void setDesiredWorldDirection(Vec3 direction) {
        if (direction.lengthSqr() < 1.0e-8) {
            return;
        }
        desiredWorldDirection = direction.normalize();
        hasAimDirection = true;
        setChanged();
    }

    public void setDesiredPodWorldDirection(Vec3 direction) {
        if (direction.lengthSqr() < 1.0e-8) return;
        podDesiredWorldDirection = direction.normalize();
        hasPodAimDirection = true;
        setChanged();
    }

    public void setDesiredPodWorldTarget(Vec3 target) {
        setDesiredPodWorldTarget(target, opticalSearchHalfTanX, opticalSearchHalfTanY);
    }

    public void setDesiredPodWorldTarget(Vec3 target, double halfTanX, double halfTanY) {
        if (!Double.isFinite(target.x)
                || !Double.isFinite(target.y)
                || !Double.isFinite(target.z)) {
            return;
        }
        opticalSearchHalfTanX = Mth.clamp(halfTanX, 1.0e-4, 1.0);
        opticalSearchHalfTanY = Mth.clamp(halfTanY, 1.0e-4, 1.0);
        boolean changed = !hasPodStabilizedWorldTarget
                || podStabilizedWorldTarget.distanceToSqr(target) > 1.0e-8;
        podStabilizedWorldTarget = target;
        hasPodStabilizedWorldTarget = true;
        updatePodDirectionFromTarget();
        if (changed) setChanged();
    }

    public @Nullable Vec3 getPodGuidanceTarget() {
        if (!hasPodStabilizedWorldTarget || !isFinite(podStabilizedWorldTarget)) return null;
        return podStabilizedWorldTarget;
    }

    public boolean isAircraftWeaponGuidanceReady(MianbaoAircraftCompat.WeaponType type) {
        if (type == null || level == null) return false;
        if (MianbaoAircraftCompat.isAirToAir(type)) {
            return isAircraftRadarActive() && getAircraftGuidanceTargetId() != null;
        }
        if (MianbaoAircraftCompat.isAirToGround(type)) {
            return getAirToGroundTargetSnapshot() != null;
        }
        return MianbaoAircraftCompat.isLaserGuided(type) && getPodGuidanceTarget() != null;
    }

    public Vec3 getPodDesiredWorldDirection() {
        updatePodDirectionFromTarget();
        ensurePodAimDirection();
        return podDesiredWorldDirection;
    }

    private void updatePodDirectionFromTarget() {
        if (!hasPodStabilizedWorldTarget) return;
        Vec3 origin = getPodCameraPosition();
        if (origin == null) return;
        Vec3 direction = podStabilizedWorldTarget.subtract(origin);
        if (direction.lengthSqr() <= 1.0e-6) return;
        podDesiredWorldDirection = direction.normalize();
        hasPodAimDirection = true;
    }

    private void ensurePodAimDirection() {
        if (hasPodAimDirection || level == null) return;
        BindingRef pod = bindings.get(BindingSlot.POD);
        Vec3 measured = pod == null ? null : ElectroOpticalPodCompat.modelWorldDirection(level, pod);
        if (measured == null || measured.lengthSqr() < 1.0e-8) {
            measured = pod == null ? null : ElectroOpticalPodCompat.baseWorldDirection(level, pod);
        }
        if (measured != null && measured.lengthSqr() > 1.0e-8) {
            podDesiredWorldDirection = measured.normalize();
            hasPodAimDirection = true;
            setChanged();
        }
    }

    public void setDesiredWorldTarget(Vec3 target) {
        desiredWorldTarget = target;
        hasAimTarget = true;
        Vec3 origin = getAimOrigin();
        setDesiredWorldDirection(target.subtract(origin));
    }

    public void setDesiredAimRay(Vec3 cameraOrigin, Vec3 cameraDirection, Vec3 target, boolean hit) {
        setDesiredAimRay(
                cameraOrigin, cameraDirection, target, hit, null, Vec3.ZERO,
                opticalSearchHalfTanX, opticalSearchHalfTanY, null
        );
    }

    public void setDesiredAimRay(
            Vec3 cameraOrigin,
            Vec3 cameraDirection,
            Vec3 target,
            boolean hit,
            @Nullable UUID hitSubLevelId,
            Vec3 hitSubLevelAnchor,
            double opticalHalfTanX,
            double opticalHalfTanY,
            @Nullable Vec3 opticalLockReference
    ) {
        opticalSearchHalfTanX = Mth.clamp(opticalHalfTanX, 1.0e-4, 1.0);
        opticalSearchHalfTanY = Mth.clamp(opticalHalfTanY, 1.0e-4, 1.0);
        if (cameraDirection.lengthSqr() > 1.0e-8) {
            lastAimRayOrigin = cameraOrigin;
            lastAimRayDirection = cameraDirection.normalize();
            hasAimRay = true;
        }
        lastAimRayHit = hit;
        Vec3 convergenceTarget = hit
                ? target
                : cameraOrigin.add(cameraDirection.normalize().scale(4096.0));
        lastAimSubLevelId = hit ? hitSubLevelId : null;
        lastAimSubLevelAnchor = hit ? hitSubLevelAnchor : Vec3.ZERO;
        Vec3 lockTarget = opticalLockTarget();
        if (lockTarget != null) {
            // Preserve player-controlled lead, but measure it against the
            // exact target snapshot used by that client frame. Comparing a
            // delayed ray with the server's newer target position creates a
            // false automatic lead proportional to ping and target speed.
            Vec3 reference = opticalLockReference == null
                    ? lockTarget
                    : opticalLockReference;
            double targetRange = cameraOrigin.distanceTo(reference);
            Vec3 aimAtReferenceRange = cameraOrigin.add(
                    cameraDirection.normalize().scale(targetRange)
            );
            lockAimOffset = aimAtReferenceRange.subtract(reference);
            convergenceTarget = lockTarget.add(lockAimOffset);
        }
        setDesiredWorldTarget(convergenceTarget);
        if (level != null) {
            lastAimUpdateGameTime = level.getGameTime();
        }
    }

    private boolean updateOpticalLock() {
        Vec3 target = opticalLockTarget();
        if (target == null) {
            if (hasOpticalLock()) {
                clearOpticalLock();
                setChanged();
            }
            return false;
        }

        if (level != null) {
            long now = level.getGameTime();
            if (lastOpticalVisibilityCheck == Long.MIN_VALUE
                    || now - lastOpticalVisibilityCheck >= OPTICAL_VISIBILITY_CHECK_INTERVAL_TICKS) {
                lastOpticalVisibilityCheck = now;
                boolean visible = isTargetAtLeastPartlyVisible(
                        opticalVisibilityOrigin(),
                        target,
                        getOpticalLockBounds(),
                        lockedSubLevelId,
                        ownControlledSubLevelIds()
                );
                if (visible) {
                    if (opticalLockObscured) {
                        opticalLockObscured = false;
                        opticalOccludedSince = Long.MIN_VALUE;
                        setChanged();
                    }
                } else {
                    if (!opticalLockObscured) {
                        opticalLockObscured = true;
                        opticalOccludedSince = now;
                        setChanged();
                    } else if (opticalOccludedSince != Long.MIN_VALUE
                            && now - opticalOccludedSince >= OPTICAL_OCCLUSION_GRACE_TICKS) {
                        ServerPlayer gunner = findSeatedGunner();
                        clearOpticalLock();
                        setChanged();
                        if (gunner != null) {
                            gunner.displayClientMessage(
                                    Component.literal("光电锁定：目标被遮挡，锁定已丢失"),
                                    true
                            );
                        }
                        return false;
                    }
                }
            }
        }
        desiredWorldTarget = target.add(lockAimOffset);
        hasAimTarget = true;
        hasAimDirection = true;
        desiredWorldDirection = desiredWorldTarget.subtract(getAimOrigin()).normalize();
        if (level != null) {
            lastAimUpdateGameTime = level.getGameTime();
        }
        return true;
    }

    public @Nullable BindingRef getAimReferenceBinding() {
        if (level == null) {
            return null;
        }
        for (BindingRef mount : cannonMounts) {
            if (mount.isSameTarget(level) && CannonMountCompat.hasAssembledCannon(level, mount)) {
                return mount;
            }
        }
        BindingRef designator = bindings.get(BindingSlot.LASER_DESIGNATOR);
        if (designator != null
                && designator.isSameTarget(level)
                && MianbaoMissileCompat.isDesignator(level.getBlockState(designator.pos()))) {
            return designator;
        }
        return null;
    }

    private boolean hasAimReferenceBinding() {
        return getAimReferenceBinding() != null;
    }

    public @Nullable Vec3 getMeasuredAimDirection() {
        if (level == null) {
            return null;
        }
        BindingRef reference = getAimReferenceBinding();
        if (reference == null) {
            return null;
        }
        Vec3 direction = MianbaoMissileCompat.isDesignator(level.getBlockState(reference.pos()))
                ? MianbaoMissileCompat.getDesignatorDirection(level, reference)
                : CannonMountCompat.getAssembledCannonDirection(level, reference);
        return direction == null || direction.lengthSqr() < 1.0e-8
                ? null
                : direction.normalize();
    }

    public @Nullable Vec3 getMeasuredAimOrigin() {
        if (level == null) {
            return null;
        }
        BindingRef reference = getAimReferenceBinding();
        if (reference == null) {
            return null;
        }
        return MianbaoMissileCompat.isDesignator(level.getBlockState(reference.pos()))
                ? MianbaoMissileCompat.getDesignatorOrigin(level, reference)
                : CannonMountCompat.getMountedCannonPosition(level, reference);
    }

    /**
     * Returns the current physical cannon/designator aim point.
     *
     * <p>This deliberately never returns the player's view-ray endpoint.
     * Guidance follows the barrel's measured axis; the laser designator is
     * used only when no assembled cannon mount is bound.
     */
    public @Nullable Vec3 getMissileGuidanceTarget() {
        if (level == null) {
            return null;
        }
        Vec3 origin = getMeasuredAimOrigin();
        Vec3 direction = getMeasuredAimDirection();
        if (origin == null || direction == null) {
            return null;
        }
        return origin.add(direction.normalize().scale(4096.0));
    }

    public boolean isMianbaoMissileGuidanceReady() {
        if (level == null || getAimReferenceBinding() == null) {
            return false;
        }
        for (BindingRef launcher : missileLaunchers) {
            if (MianbaoMissileCompat.getLauncherMuzzlePosition(level, launcher) != null) {
                return getMeasuredAimOrigin() != null && getMeasuredAimDirection() != null;
            }
        }
        return false;
    }

    public @Nullable Vec3 getBoundMissileLauncherMuzzlePosition() {
        if (level == null || missileLaunchers.isEmpty()) {
            return null;
        }
        return MianbaoMissileCompat.getLauncherMuzzlePosition(level, missileLaunchers.get(0));
    }
    private Vec3 getAimOrigin() {
        if (level != null) {
            Vec3 measuredOrigin = getMeasuredAimOrigin();
            if (measuredOrigin != null) {
                return measuredOrigin;
            }
            BindingRef pitchRef = bindings.get(BindingSlot.PITCH);
            if (pitchRef != null && pitchRef.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
                Direction facing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
                return Sable.HELPER.projectOutOfSubLevel(
                        bearing.getLevel(),
                        Vec3.atCenterOf(bearing.getBlockPos().relative(facing))
                );
            }
            return Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(worldPosition));
        }
        return Vec3.atCenterOf(worldPosition);
    }

    private void applyAimDirection() {
        if (level == null) return;
        applyAimDirectionFor(
                BindingSlot.YAW,
                BindingSlot.PITCH,
                getMeasuredAimDirection(),
                getAimOrigin()
        );
    }

    private void applyAimDirectionFor(
            BindingSlot yawSlot,
            BindingSlot pitchSlot,
            @Nullable Vec3 measuredWorldDirection,
            @Nullable Vec3 aimOrigin
    ) {
        if (measuredWorldDirection == null || measuredWorldDirection.lengthSqr() < 1.0e-8
                || aimOrigin == null) {
            return;
        }
        Vec3 requestedWorldDirection = hasAimTarget
                ? desiredWorldTarget.subtract(aimOrigin)
                : desiredWorldDirection;
        if (requestedWorldDirection.lengthSqr() < 1.0e-8) return;

        requestedWorldDirection = clampPitchRequestToTurret(
                pitchSlot,
                requestedWorldDirection.normalize(),
                measuredWorldDirection.normalize()
        );
        desiredWorldDirection = requestedWorldDirection;
        commandBearing(yawSlot, yawSpeedDegreesPerSecond,
                measuredWorldDirection, requestedWorldDirection);
        commandBearing(pitchSlot, pitchSpeedDegreesPerSecond,
                measuredWorldDirection, requestedWorldDirection);
    }

    /**
     * Clamps every automatic aim request in the turret parent's reference
     * frame before it reaches either bearing. Optical tracking therefore
     * cannot command elevation beyond the same depression/elevation limits.
     */
    private Vec3 clampPitchRequestToTurret(
            BindingSlot pitchSlot,
            Vec3 requested,
            Vec3 measured
    ) {
        Vec3 turretUp = new Vec3(0.0, 1.0, 0.0);
        BindingRef pitchRef = getBinding(pitchSlot);
        if (level != null && pitchRef != null
                && pitchRef.resolve(level) instanceof SwivelBearingBlockEntity pitchBearing) {
            SubLevel turretSubLevel = Sable.HELPER.getContaining(pitchBearing);
            if (turretSubLevel != null && !turretSubLevel.isRemoved()) {
                Vector3d transformedUp = new Vector3d(0.0, 1.0, 0.0);
                turretSubLevel.logicalPose().transformNormal(transformedUp);
                if (transformedUp.lengthSquared() > 1.0e-8) {
                    transformedUp.normalize();
                    turretUp = new Vec3(transformedUp.x, transformedUp.y, transformedUp.z);
                }
            }
        }
        double vertical = Mth.clamp(requested.dot(turretUp), -1.0, 1.0);
        double elevation = Math.asin(vertical);
        double clampedElevation = Mth.clamp(
                elevation,
                -Math.toRadians(depressionLimitDegrees),
                Math.toRadians(elevationLimitDegrees)
        );
        if (Math.abs(elevation - clampedElevation) < 1.0e-8) {
            return requested;
        }
        Vec3 horizontal = requested.subtract(turretUp.scale(vertical));
        if (horizontal.lengthSqr() < 1.0e-8) {
            double measuredVertical = measured.dot(turretUp);
            horizontal = measured.subtract(turretUp.scale(measuredVertical));
        }
        if (horizontal.lengthSqr() < 1.0e-8) {
            horizontal = new Vec3(0.0, 0.0, 1.0);
        }
        return horizontal.normalize().scale(Math.cos(clampedElevation))
                .add(turretUp.scale(Math.sin(clampedElevation)))
                .normalize();
    }

    private double applyAxisSign(BindingSlot slot, double angle, Vector3d controllerRight) {
        BindingRef ref = bindings.get(slot);
        if (ref == null || level == null || !(ref.resolve(level) instanceof SwivelBearingBlockEntity bearing)) {
            return angle;
        }

        Direction bearingFacing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
        Vector3d axis = new Vector3d(
                bearingFacing.getStepX(),
                bearingFacing.getStepY(),
                bearingFacing.getStepZ()
        );
        SubLevel bearingLevel = Sable.HELPER.getContaining(bearing);
        if (bearingLevel != null) {
            bearingLevel.logicalPose().transformNormal(axis);
        }

        SubLevel controllerLevel = Sable.HELPER.getContaining(this);
        Vector3d worldRight = new Vector3d(controllerRight);
        Vector3d worldUp = new Vector3d(0.0, 1.0, 0.0);
        if (controllerLevel != null) {
            controllerLevel.logicalPose().transformNormal(worldRight);
            controllerLevel.logicalPose().transformNormal(worldUp);
        }
        worldRight.normalize();
        worldUp.normalize();
        axis.normalize();

        if (isYawSlot(slot)) {
            return angle * (axis.dot(worldUp) < 0.0 ? -1.0 : 1.0);
        }
        return angle * (axis.dot(worldRight) > 0.0 ? -1.0 : 1.0);
    }

    private void commandBearing(
            BindingSlot slot,
            double speedDegreesPerSecond,
            Vec3 measuredWorldDirection,
            Vec3 requestedWorldDirection
    ) {
        BindingRef ref = bindings.get(slot);
        double automaticSign = 1.0;
        AimDirectionDetector detector = slot == BindingSlot.YAW
                ? turretYawDirection
                : slot == BindingSlot.PITCH ? turretPitchDirection : null;
        boolean manuallyReversed = (slot == BindingSlot.YAW && yawReversed)
                || (slot == BindingSlot.PITCH && pitchReversed);
        // AxisLock rotates the attached body directly around the bearing's
        // signed world axis.  That axis already changes sign when an
        // elevation bearing is mirrored from the left side of the cannon to
        // the right.  Running the old motion-based polarity learner on top of
        // that mirrored axis could apply the sign a second time: the first
        // command moved the barrel correctly, the detector called it
        // "opposite", and every later head-aim command drove away from the
        // target.  Yaw keeps the legacy learner for old asymmetric
        // assemblies; pitch is deterministic and needs only the explicit GUI
        // reverse switch.
        boolean learnDirection = slot == BindingSlot.YAW;
        if (learnDirection && detector != null && ref != null && level != null
                && ref.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
            if (!manuallyReversed) detector.observe(ref, bearing, measuredWorldDirection);
            automaticSign = manuallyReversed ? -1.0 : detector.directionSign;
        } else if (manuallyReversed) {
            automaticSign = -1.0;
        }
        commandBearing(ref, isYawSlot(slot), speedDegreesPerSecond,
                measuredWorldDirection, requestedWorldDirection, automaticSign,
                depressionLimitDegrees, elevationLimitDegrees,
                isYawSlot(slot) ? yawOffset : pitchOffset);
        if (learnDirection && !manuallyReversed && detector != null && ref != null && level != null
                && ref.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
            detector.recordCommand(
                    ref, bearing, measuredWorldDirection, requestedWorldDirection,
                    automaticSign, speedDegreesPerSecond);
        }
    }

    private void commandBearing(
            @Nullable BindingRef ref,
            boolean yaw,
            double speedDegreesPerSecond,
            Vec3 measuredWorldDirection,
            Vec3 requestedWorldDirection,
            double errorScale,
            double depressionLimit,
            double elevationLimit,
            Vec3 localOffset
    ) {
        if (ref == null || level == null) {
            return;
        }
        if (ref.resolve(level) instanceof SwivelBearingBlockEntity bearing && bearing.isAssembled()) {
            double mechanicalDegreesPerSecond = Math.abs(KineticBlockEntity.convertToAngular(
                    bearing.getExtraKinetics().getSpeed()
            )) * 20.0;
            double effectiveSpeed = mechanicalDegreesPerSecond > 1.0e-4
                    ? Math.min(speedDegreesPerSecond, mechanicalDegreesPerSecond)
                    : speedDegreesPerSecond;
            Vec3 scaledRequest = scaledBearingRequest(
                    bearing, measuredWorldDirection, requestedWorldDirection, errorScale);
            BearingStabilizer.commandAim(
                    bearing,
                    measuredWorldDirection,
                    scaledRequest,
                    yaw,
                    effectiveSpeed,
                    depressionLimit,
                    elevationLimit,
                    localOffset
            );
        }
    }

    private Vec3 scaledBearingRequest(
            SwivelBearingBlockEntity bearing,
            Vec3 measured,
            Vec3 requested,
            double scale
    ) {
        if (Math.abs(scale - 1.0) < 1.0e-9) return requested;
        Vector3d axis = worldBearingAxis(bearing);
        if (axis.lengthSquared() < 1.0e-8) return requested;
        Vector3d current = new Vector3d(measured.x, measured.y, measured.z).normalize();
        Vector3d desired = new Vector3d(requested.x, requested.y, requested.z).normalize();
        Vector3d currentProjected = new Vector3d(current)
                .sub(new Vector3d(axis).mul(current.dot(axis)));
        Vector3d desiredProjected = new Vector3d(desired)
                .sub(new Vector3d(axis).mul(desired.dot(axis)));
        if (currentProjected.lengthSquared() < 1.0e-8 || desiredProjected.lengthSquared() < 1.0e-8) {
            return requested;
        }
        currentProjected.normalize();
        desiredProjected.normalize();
        double sine = axis.dot(new Vector3d(currentProjected).cross(desiredProjected));
        double cosine = Mth.clamp(currentProjected.dot(desiredProjected), -1.0, 1.0);
        double scaledAngle = Math.atan2(sine, cosine) * Mth.clamp(scale, -1.0, 1.0);
        Vector3d result = new org.joml.Quaterniond().rotationAxis(
                scaledAngle, axis.x, axis.y, axis.z).transform(current);
        return new Vec3(result.x, result.y, result.z).normalize();
    }

    private static Vector3d worldBearingAxis(SwivelBearingBlockEntity bearing) {
        Direction facing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
        Vector3d axis = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        SubLevel containing = Sable.HELPER.getContaining(bearing);
        if (containing != null && !containing.isRemoved()) containing.logicalPose().transformNormal(axis);
        return axis.lengthSquared() < 1.0e-8 ? axis : axis.normalize();
    }

    private static double signedBearingAngle(
            SwivelBearingBlockEntity bearing,
            Vec3 from,
            Vec3 to
    ) {
        Vector3d axis = worldBearingAxis(bearing);
        if (axis.lengthSquared() < 1.0e-8) return 0.0;
        Vector3d a = new Vector3d(from.x, from.y, from.z);
        Vector3d b = new Vector3d(to.x, to.y, to.z);
        a.sub(new Vector3d(axis).mul(a.dot(axis)));
        b.sub(new Vector3d(axis).mul(b.dot(axis)));
        if (a.lengthSquared() < 1.0e-8 || b.lengthSquared() < 1.0e-8) return 0.0;
        a.normalize();
        b.normalize();
        return Math.toDegrees(Math.atan2(
                axis.dot(new Vector3d(a).cross(b)),
                Mth.clamp(a.dot(b), -1.0, 1.0)));
    }

    /**
     * Learns whether a positive bearing command moves the measured aim axis in
     * the positive or negative direction. Sable structures can be assembled
     * from either side of a bearing, so block facing alone is not sufficient.
     */
    private static final class AimDirectionDetector {
        private BindingRef binding;
        private SwivelBearingBlockEntity bearingInstance;
        private UUID childId;
        private Vec3 previousMeasured;
        private double lastExpectedDegrees;
        private int matchingSamples;
        private int opposingSamples;
        private boolean calibrated;
        private double directionSign = 1.0;

        private void invalidate() {
            binding = null;
            bearingInstance = null;
            childId = null;
            previousMeasured = null;
            lastExpectedDegrees = 0.0;
            matchingSamples = 0;
            opposingSamples = 0;
            calibrated = false;
            directionSign = 1.0;
        }

        private void reset(
                BindingRef ref,
                SwivelBearingBlockEntity bearing,
                @Nullable UUID attachedChild,
                Vec3 measured
        ) {
            binding = ref;
            bearingInstance = bearing;
            childId = attachedChild;
            previousMeasured = measured.normalize();
            lastExpectedDegrees = 0.0;
            matchingSamples = 0;
            opposingSamples = 0;
            calibrated = false;
            directionSign = 1.0;
        }

        private void observe(
                BindingRef ref,
                SwivelBearingBlockEntity bearing,
                Vec3 measured
        ) {
            UUID attachedChild = bearing.getSubLevelID();
            if (!ref.equals(binding) || bearing != bearingInstance
                    || !java.util.Objects.equals(childId, attachedChild)
                    || previousMeasured == null) {
                reset(ref, bearing, attachedChild, measured);
                return;
            }

            Vec3 normalized = measured.normalize();
            double observedDegrees = signedBearingAngle(
                    bearing, previousMeasured, normalized);
            previousMeasured = normalized;

            if (calibrated) {
                lastExpectedDegrees = 0.0;
                return;
            }

            double expectedDegrees = lastExpectedDegrees;
            lastExpectedDegrees = 0.0;
            if (Math.abs(expectedDegrees) < 0.02
                    || Math.abs(observedDegrees) < 0.005) {
                return;
            }

            // Reject changes that are too large to have come from the last
            // servo step. These are normally vehicle motion or a teleport.
            double plausibleLimit = Math.max(
                    2.0, Math.abs(expectedDegrees) * 4.0 + 0.5);
            if (Math.abs(observedDegrees) > plausibleLimit) {
                matchingSamples = 0;
                opposingSamples = 0;
                return;
            }

            boolean sameDirection = Math.signum(observedDegrees)
                    == Math.signum(expectedDegrees);
            if (sameDirection) {
                matchingSamples++;
                opposingSamples = 0;
                if (matchingSamples >= 2) calibrated = true;
            } else {
                opposingSamples++;
                matchingSamples = 0;
                if (opposingSamples >= 2) {
                    directionSign *= -1.0;
                    calibrated = true;
                }
            }
        }

        private void recordCommand(
                BindingRef ref,
                SwivelBearingBlockEntity bearing,
                Vec3 measured,
                Vec3 requested,
                double commandScale,
                double speedDegreesPerSecond
        ) {
            UUID attachedChild = bearing.getSubLevelID();
            if (!ref.equals(binding) || bearing != bearingInstance
                    || !java.util.Objects.equals(childId, attachedChild)
                    || previousMeasured == null) {
                reset(ref, bearing, attachedChild, measured);
            }
            double requestedDegrees = signedBearingAngle(bearing, measured, requested)
                    * commandScale;
            double maxStep = Math.max(0.0, speedDegreesPerSecond) / 20.0;
            lastExpectedDegrees = Mth.clamp(requestedDegrees, -maxStep, maxStep);
        }
    }

    private static boolean isYawSlot(BindingSlot slot) {
        return slot == BindingSlot.YAW;
    }

    private Vector3d fallbackLocalForward() {
        Direction forward = getBlockState().getValue(StabilizerControllerBlock.FACING);
        return new Vector3d(forward.getStepX(), forward.getStepY(), forward.getStepZ());
    }

    private boolean captureAimReferenceFromMount() {
        if (level == null) {
            return false;
        }
        Vec3 measuredDirection = getMeasuredAimDirection();
        if (measuredDirection == null) {
            return false;
        }

        desiredWorldDirection = measuredDirection;
        hasAimDirection = true;
        setChanged();
        return true;
    }

    private void removeInvalidBindings() {
        if (level == null) {
            return;
        }
        boolean changed = false;
        Iterator<Map.Entry<BindingSlot, BindingRef>> iterator = bindings.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BindingSlot, BindingRef> entry = iterator.next();
            if (!entry.getValue().isSameTarget(level)) {
                iterator.remove();
                changed = true;
            }
        }
        if (cannonMounts.removeIf(binding -> !binding.isSameTarget(level))) {
            if (cannonMounts.isEmpty()) cannonMountPodControl = false;
            changed = true;
        }
        if (missileLaunchers.removeIf(binding -> !binding.isSameTarget(level))) {
            missileFireCursor = missileLaunchers.isEmpty()
                    ? 0
                    : Math.floorMod(missileFireCursor, missileLaunchers.size());
            changed = true;
        }
        if (aaMissileLaunchers.removeIf(binding -> !binding.isSameTarget(level))) {
            aaMissileFireCursor = aaMissileLaunchers.isEmpty()
                    ? 0 : Math.floorMod(aaMissileFireCursor, aaMissileLaunchers.size());
            changed = true;
        }
        if (aircraftMissileRacks.removeIf(binding -> !binding.isSameTarget(level))) {
            aircraftMissileFireCursor = aircraftMissileRacks.isEmpty() ? 0 : Math.floorMod(aircraftMissileFireCursor, aircraftMissileRacks.size());
            changed = true;
        }
        if (unassignedBearings.removeIf(binding -> !binding.isSameTarget(level))) {
            changed = true;
        }
        if (changed) {
            if (!hasAimReferenceBinding()) {
                closeScope();
                thermalEnabled = false;
            }
            invalidateOwnSubLevelCache();
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void invalidateBindingsAt(Level targetLevel, BlockPos targetPos) {
        // Sable dispatches block events for physical sublevels through a different
        // Level instance from the controller's tick level. Object identity would
        // therefore leave a destroyed seat binding alive. Dimension and server
        // identity are the actual ownership boundary here.
        if (level == null
                || level.getServer() != targetLevel.getServer()
                || !level.dimension().equals(targetLevel.dimension())) {
            return;
        }
        boolean removed = bindings.entrySet().removeIf(entry -> entry.getValue().matches(targetLevel, targetPos));
        if (cannonMounts.removeIf(binding -> binding.matches(targetLevel, targetPos))) {
            if (cannonMounts.isEmpty()) cannonMountPodControl = false;
            removed = true;
        }
        if (missileLaunchers.removeIf(binding -> binding.matches(targetLevel, targetPos))) {
            missileFireCursor = missileLaunchers.isEmpty()
                    ? 0
                    : Math.floorMod(missileFireCursor, missileLaunchers.size());
            removed = true;
        }
        if (aaMissileLaunchers.removeIf(binding -> binding.matches(targetLevel, targetPos))) {
            aaMissileFireCursor = aaMissileLaunchers.isEmpty()
                    ? 0 : Math.floorMod(aaMissileFireCursor, aaMissileLaunchers.size());
            removed = true;
        }
        if (aircraftMissileRacks.removeIf(binding -> binding.matches(targetLevel, targetPos))) {
            aircraftMissileFireCursor = aircraftMissileRacks.isEmpty() ? 0 : Math.floorMod(aircraftMissileFireCursor, aircraftMissileRacks.size());
            removed = true;
        }
        if (unassignedBearings.removeIf(binding -> binding.matches(targetLevel, targetPos))) {
            removed = true;
        }
        if (removed) {
            if (!hasAimReferenceBinding()) {
                closeScope();
                thermalEnabled = false;
            }
            invalidateOwnSubLevelCache();
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public boolean isAircraftRadarActive() {
        if (level == null) return false;
        BindingRef ref = bindings.get(BindingSlot.AIRCRAFT_RADAR);
        return ref != null && ref.isSameTarget(level) && MianbaoAircraftCompat.isRadar(level.getBlockState(ref.pos()));
    }

    public boolean isRadarActive() {
        if (isAircraftRadarActive()) return true;
        if (level == null) return false;
        BindingRef ref = bindings.get(BindingSlot.RADAR_BEARING);
        if (ref == null || !(ref.resolve(level) instanceof MechanicalBearingBlockEntity bearing)) {
            return false;
        }
        return bearing.isRunning() && bearing.getMovedContraption() != null
                && Math.abs(bearing.getAngularSpeed()) > 1.0e-3F;
    }

    public double getRadarRange() {
        return radarRange;
    }

    public double getRadarDepression() { return radarDepression; }
    public double getRadarElevation() { return radarElevation; }
    public double getAaProximityFuseRange() { return aaProximityFuseRange; }

    public double getRadarHudScale() { return radarHudScale; }
    public double getRadarHudX() { return radarHudX; }
    public double getRadarHudY() { return radarHudY; }

    public List<RadarContact> getRadarContacts() {
        return radarContacts;
    }

    public @Nullable UUID getRadarLockedSubLevelId() {
        return radarLockedSubLevelId;
    }

    public @Nullable RadarContact getRadarContact(UUID id) {
        if (id == null) return null;
        return radarContacts.stream()
                .filter(contact -> contact.subLevelId().equals(id) || contact.memberIds().contains(id))
                .findFirst().orElse(null);
    }

    public @Nullable SubLevel getRadarLockedSubLevel() {
        if (level == null || radarLockedSubLevelId == null) return null;
        SubLevel target = SubLevelContainer.getContainer(level).getSubLevel(radarLockedSubLevelId);
        return target == null || target.isRemoved() ? null : target;
    }

    public Vec3 getRadarOrigin() {
        if (level == null) return Vec3.atCenterOf(worldPosition);
        BindingRef aircraft = bindings.get(BindingSlot.AIRCRAFT_RADAR);
        if (aircraft != null && aircraft.isSameTarget(level)) {
            return Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(aircraft.pos()));
        }
        BindingRef ref = bindings.get(BindingSlot.RADAR_BEARING);
        Vec3 local = ref == null ? Vec3.atCenterOf(worldPosition) : Vec3.atCenterOf(ref.pos());
        return Sable.HELPER.projectOutOfSubLevel(level, local);
    }

    public boolean isAircraftRadarMode() { return isAircraftRadarActive(); }

    /** Aircraft controls use their selected TWS contact directly; no second lock state is required. */
    public @Nullable UUID getAircraftGuidanceTargetId() {
        RadarContact selected = getRadarContact(aircraftSelectedTargetId);
        return selected == null ? null : selected.subLevelId();
    }

    /** Suppresses tank-only head-aim HUD whenever this computer belongs to an aircraft installation. */
    public boolean hasAircraftEquipment() {
        return !aircraftMissileRacks.isEmpty() || bindings.containsKey(BindingSlot.AIRCRAFT_RADAR);
    }

    public double getAircraftRadarRange() { return aircraftRadarRange; }
    public double getAircraftRadarHorizontalHalfAngle() { return aircraftRadarHorizontalHalfAngle; }
    public double getAircraftRadarVerticalAngle() { return aircraftRadarVerticalAngle; }
    public double getAircraftProximityFuseRange() { return aircraftProximityFuseRange; }
    public double getAircraftFuseSafetySeconds() { return aircraftFuseSafetySeconds; }
    public double getAircraftRadarMinimumTargetSpeed() { return aircraftRadarMinimumTargetSpeed; }
    public double getAircraftRadarHudScale() { return aircraftRadarHudScale; }
    public double getAircraftRadarHudX() { return aircraftRadarHudX; }
    public double getAircraftRadarHudY() { return aircraftRadarHudY; }
    public UUID getAircraftSelectedTargetId() { return aircraftSelectedTargetId; }
    public Vec3 getAircraftVelocity() { return aircraftVelocity; }

    public Vec3 getAircraftRadarForward() {
        if (level == null) return new Vec3(0, 0, -1);
        BindingRef ref = bindings.get(BindingSlot.AIRCRAFT_RADAR);
        if (ref == null) return new Vec3(0, 0, -1);
        BlockState state = level.getBlockState(ref.pos());
        Direction facing = state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)
                ? state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)
                : state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        Vector3d vector = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        if (ref.subLevelId() != null) {
            SubLevel sub = SubLevelContainer.getContainer(level).getSubLevel(ref.subLevelId());
            if (sub != null && !sub.isRemoved()) sub.logicalPose().transformNormal(vector);
        }
        Vec3 result = new Vec3(vector.x, vector.y, vector.z);
        return result.lengthSqr() < 1.0e-8 ? new Vec3(0, 0, -1) : result.normalize();
    }

    public Vec3 getAircraftRadarUp() {
        if (level == null) return new Vec3(0, 1, 0);
        BindingRef ref = bindings.get(BindingSlot.AIRCRAFT_RADAR);
        Vector3d vector = new Vector3d(0, 1, 0);
        if (ref != null && ref.subLevelId() != null) {
            SubLevel sub = SubLevelContainer.getContainer(level).getSubLevel(ref.subLevelId());
            if (sub != null && !sub.isRemoved()) sub.logicalPose().transformNormal(vector);
        }
        Vec3 result = new Vec3(vector.x, vector.y, vector.z);
        return result.lengthSqr() < 1.0e-8 ? new Vec3(0, 1, 0) : result.normalize();
    }

    private void updateAircraftVelocity() {
        if (level == null) {
            aircraftVelocity = Vec3.ZERO;
            previousAircraftOrigin = null;
            previousAircraftOriginTick = Long.MIN_VALUE;
            return;
        }
        Vec3 origin = null;
        BindingRef radar = bindings.get(BindingSlot.AIRCRAFT_RADAR);
        if (radar != null) {
            BlockEntity blockEntity = radar.resolve(level);
            if (blockEntity != null && blockEntity.getLevel() != null) {
                origin = Sable.HELPER.projectOutOfSubLevel(
                        blockEntity.getLevel(), Vec3.atCenterOf(radar.pos()));
            }
        }
        if (origin == null) {
            for (BindingRef rack : aircraftMissileRacks) {
                BlockEntity blockEntity = rack.resolve(level);
                if (blockEntity != null && blockEntity.getLevel() != null) {
                    origin = Sable.HELPER.projectOutOfSubLevel(
                            blockEntity.getLevel(), Vec3.atCenterOf(rack.pos()));
                    break;
                }
            }
        }
        if (origin == null) {
            BindingRef pod = bindings.get(BindingSlot.POD);
            if (pod != null) {
                BlockEntity blockEntity = pod.resolve(level);
                if (blockEntity != null && blockEntity.getLevel() != null) {
                    origin = Sable.HELPER.projectOutOfSubLevel(
                            blockEntity.getLevel(), Vec3.atCenterOf(pod.pos()));
                }
            }
        }
        if (origin == null) {
            aircraftVelocity = Vec3.ZERO;
            previousAircraftOrigin = null;
            previousAircraftOriginTick = Long.MIN_VALUE;
            return;
        }
        long now = level.getGameTime();
        if (previousAircraftOrigin != null && previousAircraftOriginTick != Long.MIN_VALUE && now > previousAircraftOriginTick)
            aircraftVelocity = origin.subtract(previousAircraftOrigin).scale(1.0 / (now - previousAircraftOriginTick));
        previousAircraftOrigin = origin;
        previousAircraftOriginTick = now;
    }

    public float getRadarSweepDegrees() {
        if (level == null) return 0.0F;
        BindingRef ref = bindings.get(BindingSlot.RADAR_BEARING);
        if (ref != null && ref.resolve(level) instanceof MechanicalBearingBlockEntity bearing) {
            return Mth.wrapDegrees(bearing.getInterpolatedAngle(1.0F));
        }
        return 0.0F;
    }

    private void toggleRadarLockFromWireless() {
        if (level == null || !hasInputConfigured(5)) return;
        ServerPlayer gunner = findSeatedGunner();
        if (gunner == null || !isRadarActive()) return;
        if (radarLockedSubLevelId != null) {
            radarLockedSubLevelId = null;
            setChanged();
            return;
        }
        if (isAircraftRadarMode() && aircraftSelectedTargetId != null) {
            RadarContact selected = getRadarContact(aircraftSelectedTargetId);
            if (selected != null) {
                radarLockedSubLevelId = selected.subLevelId();
                setChanged();
                return;
            }
        }
        boolean freshAimRay = hasAimRay && lastAimUpdateGameTime != Long.MIN_VALUE
                && level.getGameTime() - lastAimUpdateGameTime <= 10L;
        Vec3 origin = freshAimRay ? lastAimRayOrigin : gunner.getEyePosition();
        Vec3 direction = freshAimRay ? lastAimRayDirection : gunner.getLookAngle();
        if (direction.lengthSqr() < 1.0e-8) return;
        Vec3 normalized = direction.normalize();
        RadarContact best = null;
        double bestAngularError = Double.POSITIVE_INFINITY;
        for (RadarContact contact : radarContacts) {
            Vec3 toTarget = contact.center().subtract(origin);
            if (toTarget.lengthSqr() < 1.0e-8) continue;
            double alignment = normalized.dot(toTarget.normalize());
            if (alignment <= 0.0) continue;
            double angularError = 1.0 - alignment;
            if (angularError < bestAngularError) {
                bestAngularError = angularError;
                best = contact;
            }
        }
        if (best != null) {
            radarLockedSubLevelId = best.subLevelId();
            setChanged();
        }
    }

    public void toggleRadarLock(ServerPlayer player, @Nullable UUID requestedTarget) {
        if (player == null || findSeatedGunner() != player || !isRadarActive()) return;
        if (requestedTarget == null || requestedTarget.equals(radarLockedSubLevelId)) {
            radarLockedSubLevelId = null;
            setChanged();
            return;
        }
        boolean scanned = radarContacts.stream()
                .anyMatch(contact -> contact.subLevelId().equals(requestedTarget));
        if (scanned) {
            radarLockedSubLevelId = requestedTarget;
            setChanged();
        }
    }

    private void updateRadarScan() {
        if (level == null || level.isClientSide) return;
        long now = level.getGameTime();
        if (!isRadarActive()) {
            radarContacts = List.of();
            previousRadarCenters.clear();
            radarLockedSubLevelId = null;
            return;
        }
        if (lastRadarScanTick != Long.MIN_VALUE && now - lastRadarScanTick < 2L) return;
        long elapsed = lastRadarScanTick == Long.MIN_VALUE ? 2L : Math.max(1L, now - lastRadarScanTick);
        lastRadarScanTick = now;
        Vec3 origin = getRadarOrigin();
        Set<UUID> own = ownControlledSubLevelIds();
        List<RadarContact> contacts = new ArrayList<>();
        Map<UUID, Vec3> currentCenters = new java.util.HashMap<>();
        List<SubLevel> loaded = SubLevelContainer.getContainer(level).getAllSubLevels().stream()
                .filter(subLevel -> subLevel != null && !subLevel.isRemoved())
                .map(subLevel -> (SubLevel) subLevel)
                .toList();
        Map<UUID, SubLevel> byId = new java.util.HashMap<>();
        for (SubLevel subLevel : loaded) byId.put(subLevel.getUniqueId(), subLevel);

        for (Set<UUID> members : radarConnectedClusters(loaded, byId.keySet())) {
            if (members.stream().anyMatch(own::contains)) continue;
            AABB bounds = null;
            for (UUID member : members) {
                SubLevel subLevel = byId.get(member);
                if (subLevel == null || !hasUsableRadarBounds(subLevel)) continue;
                AABB part = subLevelWorldBounds(subLevel);
                if (!isFinite(part)) continue;
                bounds = bounds == null ? part : unionBounds(bounds, part);
            }
            if (bounds == null) continue;
            Vec3 center = bounds.getCenter();
            // A composite vehicle is detected when any part of its aggregate
            // world AABB intersects the radar volume. Testing only the center
            // incorrectly drops a low hull once its turret and barrel are
            // merged into the same target, especially with 0-degree depression.
            double activeRange = isAircraftRadarMode() ? aircraftRadarRange : radarRange;
            boolean inVolume = isAircraftRadarMode()
                    ? pointInAircraftRadarCone(origin, center)
                    : boundsIntersectsElevationWindow(origin, bounds, radarDepression, radarElevation);
            if (distanceToBoundsSqr(origin, bounds) > activeRange * activeRange || !inVolume) continue;
            UUID representative = members.stream().min(java.util.Comparator.comparing(UUID::toString)).orElseThrow();
            Vec3 previous = previousRadarCenters.get(representative);
            Vec3 velocity = previous == null ? Vec3.ZERO : center.subtract(previous).scale(1.0 / elapsed);
            currentCenters.put(representative, center);
            if (isAircraftRadarMode()
                    && velocity.length() * 20.0 < aircraftRadarMinimumTargetSpeed) continue;
            contacts.add(new RadarContact(representative, center, velocity, bounds, Set.copyOf(members)));
        }
        contacts.sort(java.util.Comparator.comparingDouble(contact -> contact.center().distanceToSqr(origin)));
        if (contacts.size() > 20) contacts = new ArrayList<>(contacts.subList(0, 20));
        radarContacts = List.copyOf(contacts);
        if (isAircraftRadarMode() && (aircraftSelectedTargetId == null
                || getRadarContact(aircraftSelectedTargetId) == null)) {
            aircraftSelectedTargetId = radarContacts.isEmpty() ? null : radarContacts.get(0).subLevelId();
        }
        previousRadarCenters.clear();
        previousRadarCenters.putAll(currentCenters);
        if (radarLockedSubLevelId != null && radarContacts.stream()
                .noneMatch(contact -> contact.subLevelId().equals(radarLockedSubLevelId))) {
            radarLockedSubLevelId = null;
            setChanged();
        }
    }

    private List<Set<UUID>> radarConnectedClusters(List<SubLevel> loaded, Set<UUID> loadedIds) {
        long now = level == null ? Long.MIN_VALUE : level.getGameTime();
        Set<UUID> immutableIds = Set.copyOf(loadedIds);

        if (radarTopologyBuildUnion != null && !radarTopologyBuildIds.equals(immutableIds)) {
            cancelRadarTopologyBuild();
        }
        boolean cacheClockValid = cachedRadarTopologyAt != Long.MIN_VALUE
                && now >= cachedRadarTopologyAt;
        boolean cacheFresh = cacheClockValid
                && now - cachedRadarTopologyAt < RADAR_TOPOLOGY_CACHE_TICKS
                && cachedRadarTopologyIds.equals(immutableIds);
        if (!cacheFresh && radarTopologyBuildUnion == null) {
            beginRadarTopologyBuild(loaded, immutableIds);
        }
        advanceRadarTopologyBuild(now);

        if (cachedRadarTopologyIds.equals(immutableIds)) {
            return cachedRadarClusters;
        }
        // A newly loaded set is useful immediately while its bearing graph is
        // being assembled over the next few scans. Returning singleton groups
        // avoids a blocking full traversal on the server thread.
        return immutableIds.stream().map(Set::of).toList();
    }

    private void beginRadarTopologyBuild(List<SubLevel> loaded, Set<UUID> loadedIds) {
        RadarUnionFind union = new RadarUnionFind();
        for (UUID id : loadedIds) union.add(id);
        radarTopologyBuildUnion = union;
        radarTopologyBuildLoaded = List.copyOf(loaded);
        radarTopologyBuildIds = loadedIds;
        radarTopologyBuildVisited.clear();
        radarTopologyBuildIndex = 0;
    }

    private void advanceRadarTopologyBuild(long now) {
        if (radarTopologyBuildUnion == null) return;
        int processedChains = 0;
        while (radarTopologyBuildIndex < radarTopologyBuildLoaded.size()
                && processedChains < RADAR_TOPOLOGY_SUBLEVEL_BUDGET) {
            SubLevel parent = radarTopologyBuildLoaded.get(radarTopologyBuildIndex++);
            if (parent == null || parent.isRemoved()
                    || !radarTopologyBuildVisited.add(parent.getUniqueId())) continue;
            processedChains++;

            // This is the same live graph used internally by
            // sable-schematic-api for loaded welds and constraints. Processing
            // each connected chain once avoids both missed composite targets
            // and listAll()'s server-wide directory traversal.
            for (SubLevel connected : SubLevelHelper.getConnectedChain(parent)) {
                if (connected == null || connected.isRemoved()) continue;
                UUID connectedId = connected.getUniqueId();
                if (!radarTopologyBuildIds.contains(connectedId)) continue;
                radarTopologyBuildUnion.union(parent.getUniqueId(), connectedId);
                radarTopologyBuildVisited.add(connectedId);
            }

            // Retain the explicit Aeronautics bearing edge as a fallback for
            // versions that publish their Sable constraint one tick later.
            for (dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor actor
                    : parent.getPlot().getBlockEntityActors()) {
                if (!(actor instanceof SwivelBearingBlockEntity bearing) || !bearing.isAssembled()) continue;
                UUID child = bearing.getSubLevelID();
                if (child != null && radarTopologyBuildIds.contains(child)) {
                    radarTopologyBuildUnion.union(parent.getUniqueId(), child);
                }
            }
        }
        if (radarTopologyBuildIndex < radarTopologyBuildLoaded.size()) return;

        Map<UUID, Set<UUID>> clusters = new java.util.LinkedHashMap<>();
        for (UUID id : radarTopologyBuildIds) {
            clusters.computeIfAbsent(
                    radarTopologyBuildUnion.find(id),
                    ignored -> new java.util.LinkedHashSet<>()).add(id);
        }
        cachedRadarTopologyIds = radarTopologyBuildIds;
        cachedRadarClusters = clusters.values().stream().map(Set::copyOf).toList();
        cachedRadarTopologyAt = now;
        cancelRadarTopologyBuild();
    }

    private void cancelRadarTopologyBuild() {
        radarTopologyBuildUnion = null;
        radarTopologyBuildLoaded = List.of();
        radarTopologyBuildIds = Set.of();
        radarTopologyBuildVisited.clear();
        radarTopologyBuildIndex = 0;
    }

    private static boolean hasUsableRadarBounds(SubLevel subLevel) {
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        return bounds.minX() <= bounds.maxX()
                && bounds.minY() <= bounds.maxY()
                && bounds.minZ() <= bounds.maxZ();
    }

    private static boolean isFinite(AABB bounds) {
        return Double.isFinite(bounds.minX) && Double.isFinite(bounds.minY)
                && Double.isFinite(bounds.minZ) && Double.isFinite(bounds.maxX)
                && Double.isFinite(bounds.maxY) && Double.isFinite(bounds.maxZ);
    }

    private static double distanceToBoundsSqr(Vec3 point, AABB bounds) {
        double x = Math.max(bounds.minX, Math.min(bounds.maxX, point.x));
        double y = Math.max(bounds.minY, Math.min(bounds.maxY, point.y));
        double z = Math.max(bounds.minZ, Math.min(bounds.maxZ, point.z));
        return point.distanceToSqr(x, y, z);
    }

    private boolean pointInAircraftRadarCone(Vec3 origin, Vec3 point) {
        Vec3 delta = point.subtract(origin);
        if (delta.lengthSqr() < 1.0e-8) return true;
        Vec3 forward = getAircraftRadarForward();
        Vec3 up = getAircraftRadarUp();
        Vec3 right = forward.cross(up).normalize();
        Vec3 planarForward = forward.subtract(up.scale(forward.dot(up))).normalize();
        double longitudinal = delta.dot(planarForward);
        if (longitudinal <= 0.0) return false;
        double azimuth = Math.toDegrees(Math.atan2(delta.dot(right), longitudinal));
        double elevation = Math.toDegrees(Math.atan2(delta.dot(up),
                Math.sqrt(longitudinal * longitudinal + Math.pow(delta.dot(right), 2))));
        return Math.abs(azimuth) <= aircraftRadarHorizontalHalfAngle
                && Math.abs(elevation) <= aircraftRadarVerticalAngle * 0.5;
    }

    private static boolean boundsIntersectsElevationWindow(
            Vec3 origin,
            AABB bounds,
            double depression,
            double elevation
    ) {
        double closestX = Math.max(bounds.minX, Math.min(bounds.maxX, origin.x));
        double closestZ = Math.max(bounds.minZ, Math.min(bounds.maxZ, origin.z));
        double horizontalMin = Math.sqrt(
                (closestX - origin.x) * (closestX - origin.x)
                        + (closestZ - origin.z) * (closestZ - origin.z));
        double horizontalMax = 0.0;
        for (double x : new double[]{bounds.minX, bounds.maxX}) {
            for (double z : new double[]{bounds.minZ, bounds.maxZ}) {
                horizontalMax = Math.max(horizontalMax,
                        Math.sqrt((x - origin.x) * (x - origin.x)
                                + (z - origin.z) * (z - origin.z)));
            }
        }
        double lowY = bounds.minY - origin.y;
        double highY = bounds.maxY - origin.y;
        double minElevation = Math.toDegrees(Math.atan2(
                lowY, Math.max(1.0e-6, lowY <= 0.0 ? horizontalMin : horizontalMax)));
        double maxElevation = Math.toDegrees(Math.atan2(
                highY, Math.max(1.0e-6, highY >= 0.0 ? horizontalMin : horizontalMax)));
        return maxElevation >= -depression && minElevation <= elevation;
    }

    private static AABB unionBounds(AABB a, AABB b) {
        return new AABB(Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
                Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
    }

    private static final class RadarUnionFind {
        private final Map<UUID, UUID> parents = new java.util.HashMap<>();
        private void add(UUID id) { parents.putIfAbsent(id, id); }
        private UUID find(UUID id) {
            add(id);
            UUID parent = parents.get(id);
            if (!parent.equals(id)) {
                parent = find(parent);
                parents.put(id, parent);
            }
            return parent;
        }
        private void union(UUID a, UUID b) {
            UUID rootA = find(a), rootB = find(b);
            if (!rootA.equals(rootB)) parents.put(rootB, rootA);
        }
    }

    private void maintainBoundBearings(boolean acceptMechanicalInput, boolean stabilizeWorld) {
        maintainBearings(
                new BindingSlot[]{BindingSlot.YAW, BindingSlot.PITCH},
                acceptMechanicalInput,
                stabilizeWorld
        );
    }

    private void maintainBearings(
            BindingSlot[] slots,
            boolean acceptMechanicalInput,
            boolean stabilizeWorld
    ) {
        for (BindingSlot slot : slots) {
            BindingRef ref = bindings.get(slot);
            if (ref != null && level != null
                    && ref.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
                boolean yaw = isYawSlot(slot);
                BearingStabilizer.maintainBinding(
                        bearing,
                        yaw,
                        true,
                        stabilizeWorld,
                        acceptMechanicalInput,
                        yaw ? yawSpeedDegreesPerSecond : pitchSpeedDegreesPerSecond,
                        depressionLimitDegrees,
                        elevationLimitDegrees,
                        yaw ? yawOffset : pitchOffset
                );
            }
        }
    }

    private void calibratePitchFromCannon() {
        if (level == null) {
            return;
        }
        BindingRef pitch = bindings.get(BindingSlot.PITCH);
        if (pitch == null) {
            return;
        }
        Vec3 measured = getMeasuredAimDirection();
        if (measured != null
                && pitch.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
            BearingStabilizer.calibrateElevationDirection(bearing, measured);
        }
    }

    private void updatePodModel() {
        if (level == null) return;
        BindingRef pod = bindings.get(BindingSlot.POD);
        if (pod == null || !isPodViewEnabled()) return;
        updatePodDirectionFromTarget();
        ensurePodAimDirection();
        Vec3 direction = podDesiredWorldDirection;
        if (direction.lengthSqr() > 1.0e-8) {
            ElectroOpticalPodCompat.pointModelAt(level, pod, direction.normalize());
        }
    }

    private void updatePodControlledCannon() {
        if (level == null || !cannonMountPodControl || !isPodViewEnabled()) return;
        Vec3 target = getPodGuidanceTarget();
        if (target == null) return;
        for (BindingRef mount : cannonMounts) {
            if (!mount.isSameTarget(level)
                    || !CannonMountCompat.hasAssembledCannon(level, mount)) continue;
            CannonBallisticsCompat.ShotProfile profile =
                    CannonBallisticsCompat.analyze(level, mount);
            profile = PodControlledCannonBallistics.apply(profile);
            Vec3 launchDirection = getCannonBallisticLaunchDirection(
                    mount, profile, target);
            if (launchDirection == null) {
                // A mount must continue following the pod while empty or when
                // an optional CBC add-on's ammunition cannot be analysed.
                // Ballistic compensation becomes available again as soon as a
                // valid shot profile exists.
                CannonMountCompat.pointAssembledCannonAt(
                        level, mount, target,
                        podCannonDepressionLimitDegrees,
                        podCannonElevationLimitDegrees);
            } else {
                CannonMountCompat.pointAssembledCannonAlong(
                        level, mount, launchDirection,
                        podCannonDepressionLimitDegrees,
                        podCannonElevationLimitDegrees);
            }
        }
    }

    /** Pure feed-forward solution; previous-shot auto-correction was removed. */
    public @Nullable Vec3 getCannonBallisticLaunchDirection(
            BindingRef mount,
            @Nullable CannonBallisticsCompat.ShotProfile profile,
            Vec3 rawTarget
    ) {
        if (profile == null || rawTarget == null) return null;
        Vec3 solved = CannonBallisticsCompat.solveLaunchDirection(profile, rawTarget);
        return solved == null || solved.lengthSqr() < 1.0e-8
                ? null : solved.normalize();
    }

    public boolean isPodControlledCannonActive() {
        return level != null && cannonMountPodControl && isPodViewEnabled()
                && !cannonMounts.isEmpty();
    }

    public @Nullable BindingRef getCannonMountBinding() {
        return cannonMounts.stream().findFirst().orElse(null);
    }

    public boolean isSeat(net.minecraft.world.entity.Entity vehicle) {
        BindingRef seat = bindings.get(BindingSlot.SEAT);
        return seat != null && level != null && seat.matchesEntityPosition(level, vehicle);
    }

    public String describe() {
        return String.format(
                "火控计算机：%s | 方向机：%s | 高低机：%s | 座椅：%s | 火炮底座：%s | 导弹发射器：%s | 激光制导器：%s | 俯角 %.1f° / 仰角 %.1f° | 转速 %.1f°/s / %.1f°/s",
                stabilizerEnabled ? "开启" : "关闭",
                bindings.containsKey(BindingSlot.YAW) ? "已绑定" : "未绑定",
                bindings.containsKey(BindingSlot.PITCH) ? "已绑定" : "未绑定",
                bindings.containsKey(BindingSlot.SEAT) ? "已绑定" : "未绑定",
                bindings.containsKey(BindingSlot.CANNON_MOUNT) ? "已绑定" : "未绑定",
                bindings.containsKey(BindingSlot.MISSILE_LAUNCHER) ? "已绑定" : "未绑定",
                bindings.containsKey(BindingSlot.LASER_DESIGNATOR) ? "已绑定" : "未绑定",
                depressionLimitDegrees,
                elevationLimitDegrees,
                yawSpeedDegreesPerSecond,
                pitchSpeedDegreesPerSecond
        );
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        for (BindingSlot slot : BindingSlot.values()) {
            if (slot == BindingSlot.BEARING
                    || slot == BindingSlot.CANNON_MOUNT
                    || slot == BindingSlot.MISSILE_LAUNCHER
                    || slot == BindingSlot.AA_MISSILE_LAUNCHER
                    || slot == BindingSlot.AIRCRAFT_MISSILE_RACK
                    || isPodBearingRole(slot)) {
                continue;
            }
            BindingRef ref = bindings.get(slot);
            if (ref != null) {
                tag.put("Binding_" + slot.name(), ref.save());
            }
        }
        tag.remove("Binding_" + BindingSlot.CANNON_MOUNT.name());
        ListTag cannonMountTags = new ListTag();
        for (BindingRef mount : cannonMounts) cannonMountTags.add(mount.save());
        tag.put("CannonMountBindings", cannonMountTags);
        // Remove legacy closed-loop calibration data if this controller was
        // loaded from an older build.
        tag.remove("CannonBallisticCorrections");
        tag.remove("Binding_" + BindingSlot.MISSILE_LAUNCHER.name());
        ListTag launcherTags = new ListTag();
        for (BindingRef launcher : missileLaunchers) {
            launcherTags.add(launcher.save());
        }
        tag.put("MissileLauncherBindings", launcherTags);
        tag.putInt("MissileFireCursor", missileFireCursor);
        ListTag aaLauncherTags = new ListTag();
        for (BindingRef launcher : aaMissileLaunchers) {
            aaLauncherTags.add(launcher.save());
        }
        tag.put("AaMissileLauncherBindings", aaLauncherTags);
        tag.putInt("AaMissileFireCursor", aaMissileFireCursor);
        ListTag aircraftRackTags = new ListTag();
        for (BindingRef rack : aircraftMissileRacks) aircraftRackTags.add(rack.save());
        tag.put("AircraftMissileRackBindings", aircraftRackTags);
        tag.putInt("AircraftMissileFireCursor", aircraftMissileFireCursor);
        ListTag bearingTags = new ListTag();
        for (BindingRef bearing : unassignedBearings) bearingTags.add(bearing.save());
        tag.put("UnassignedBearingBindings", bearingTags);
        // Remove retired pod-bearing data when an older controller is saved.
        tag.remove("Binding_" + BindingSlot.POD_YAW.name());
        tag.remove("Binding_" + BindingSlot.POD_PITCH.name());
        tag.remove("PodYawBearingBindings");
        tag.remove("PodPitchBearingBindings");
        tag.putInt("SelectedAircraftWeapon", selectedAircraftWeapon.ordinal());
        tag.putDouble("RadarRange", radarRange);
        tag.putDouble("RadarDepression", radarDepression);
        tag.putDouble("RadarElevation", radarElevation);
        tag.putDouble("AaProximityFuseRange", aaProximityFuseRange);
        tag.putDouble("RadarHudScale", radarHudScale);
        tag.putDouble("RadarHudX", radarHudX);
        tag.putDouble("RadarHudY", radarHudY);
        tag.putDouble("AircraftRadarRange", aircraftRadarRange);
        tag.putDouble("AircraftRadarHorizontalHalfAngle", aircraftRadarHorizontalHalfAngle);
        tag.putDouble("AircraftRadarVerticalAngle", aircraftRadarVerticalAngle);
        tag.putDouble("AircraftProximityFuseRange", aircraftProximityFuseRange);
        tag.putDouble("AircraftFuseSafetySeconds", aircraftFuseSafetySeconds);
        tag.putDouble("AircraftRadarMinimumTargetSpeed", aircraftRadarMinimumTargetSpeed);
        tag.putDouble("AircraftRadarHudScale", aircraftRadarHudScale);
        tag.putDouble("AircraftRadarHudX", aircraftRadarHudX);
        tag.putDouble("AircraftRadarHudY", aircraftRadarHudY);
        if (radarLockedSubLevelId != null) {
            tag.putUUID("RadarLockedSubLevel", radarLockedSubLevelId);
        }
        tag.putDouble("DepressionLimit", depressionLimitDegrees);
        tag.putDouble("ElevationLimit", elevationLimitDegrees);
        tag.putDouble("YawSpeed", yawSpeedDegreesPerSecond);
        tag.putDouble("PitchSpeed", pitchSpeedDegreesPerSecond);
        tag.putBoolean("YawReversed", yawReversed);
        tag.putBoolean("PitchReversed", pitchReversed);
        tag.putDouble("ThirdPersonHeight", thirdPersonHeight);
        tag.putDouble("ViewAimOffsetX", viewAimOffsetX);
        tag.putDouble("ViewAimOffsetY", viewAimOffsetY);
        tag.putDouble("YawOffsetX", yawOffset.x);
        tag.putDouble("YawOffsetY", yawOffset.y);
        tag.putDouble("YawOffsetZ", yawOffset.z);
        tag.putDouble("PitchOffsetX", pitchOffset.x);
        tag.putDouble("PitchOffsetY", pitchOffset.y);
        tag.putDouble("PitchOffsetZ", pitchOffset.z);
        tag.putDouble("ScopeOffsetX", scopeOffset.x);
        tag.putDouble("ScopeOffsetY", scopeOffset.y);
        tag.putDouble("ScopeOffsetZ", scopeOffset.z);
        tag.putDouble("AimX", desiredWorldDirection.x);
        tag.putDouble("AimY", desiredWorldDirection.y);
        tag.putDouble("AimZ", desiredWorldDirection.z);
        tag.putBoolean("HasAim", hasAimDirection);
        tag.putDouble("PodAimX", podDesiredWorldDirection.x);
        tag.putDouble("PodAimY", podDesiredWorldDirection.y);
        tag.putDouble("PodAimZ", podDesiredWorldDirection.z);
        tag.putBoolean("HasPodAim", hasPodAimDirection);
        tag.putBoolean("StabilizerEnabled", stabilizerEnabled);
        tag.putBoolean("HeadAimEnabled", headAimEnabled);
        tag.putBoolean("RemoteHeadAimEnabled", remoteHeadAimEnabled);
        tag.putBoolean("CannonMountPodControl", cannonMountPodControl);
        tag.putDouble("PodCannonDepressionLimit", podCannonDepressionLimitDegrees);
        tag.putDouble("PodCannonElevationLimit", podCannonElevationLimitDegrees);
        tag.putInt("FrequencyConfigCursor", frequencyConfigCursor);
        scopeReceiver.save(tag, "ScopeWireless", registries);
        thermalReceiver.save(tag, "ThermalWireless", registries);
        opticalLockReceiver.save(tag, "OpticalLockWireless", registries);
        missileFireReceiver.save(tag, "MissileFireWireless", registries);
        aaMissileFireReceiver.save(tag, "AaMissileFireWireless", registries);
        radarLockReceiver.save(tag, "RadarLockWireless", registries);
        aircraftMissileFireReceiver.save(tag, "AircraftMissileFireWireless", registries);
        aircraftWeaponCycleReceiver.save(tag, "AircraftWeaponCycleWireless", registries);
        aircraftTargetCycleReceiver.save(tag, "AircraftTargetCycleWireless", registries);
        podViewReceiver.save(tag, "PodViewWireless", registries);
        airToGroundLockReceiver.save(tag, "AirToGroundLockWireless", registries);
        saveRemoteKeys(tag, "NormalRemoteKeys", normalRemoteKeys);
        saveRemoteKeys(tag, "AdvancedRemoteKeys", advancedRemoteKeys);
    }

    private static void saveRemoteKeys(CompoundTag owner, String key, String[] values) {
        CompoundTag saved = new CompoundTag();
        for (int index = 0; index < values.length; index++) {
            if (!values[index].isEmpty()) saved.putString(Integer.toString(index), values[index]);
        }
        owner.put(key, saved);
    }

    private static void loadRemoteKeys(CompoundTag owner, String key, String[] values,
                                       RemoteControllerCompat.Type type) {
        java.util.Arrays.fill(values, "");
        if (!owner.contains(key, Tag.TAG_COMPOUND)) return;
        CompoundTag saved = owner.getCompound(key);
        for (int index = 0; index < values.length; index++) {
            String value = saved.getString(Integer.toString(index));
            if (DriveByWireCompat.accepts(type, value)) values[index] = value;
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        bindings.clear();
        cannonMounts.clear();
        missileLaunchers.clear();
        aaMissileLaunchers.clear();
        aircraftMissileRacks.clear();
        unassignedBearings.clear();
        invalidateOwnSubLevelCache();
        for (BindingSlot slot : BindingSlot.values()) {
            if (slot == BindingSlot.BEARING
                    || slot == BindingSlot.CANNON_MOUNT
                    || slot == BindingSlot.MISSILE_LAUNCHER
                    || slot == BindingSlot.AA_MISSILE_LAUNCHER
                    || slot == BindingSlot.AIRCRAFT_MISSILE_RACK
                    || isPodBearingRole(slot)) {
                continue;
            }
            BindingRef ref = BindingRef.load(tag, "Binding_" + slot.name());
            if (ref != null) {
                bindings.put(slot, ref);
            }
        }
        ListTag cannonMountTags = tag.getList("CannonMountBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < cannonMountTags.size(); index++) {
            BindingRef mount = BindingRef.loadTag(cannonMountTags.getCompound(index));
            if (!cannonMounts.contains(mount)) cannonMounts.add(mount);
        }
        if (cannonMounts.isEmpty()) {
            BindingRef legacyMount = BindingRef.load(
                    tag, "Binding_" + BindingSlot.CANNON_MOUNT.name());
            if (legacyMount != null) cannonMounts.add(legacyMount);
        }
        ListTag launcherTags = tag.getList("MissileLauncherBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < launcherTags.size(); index++) {
            BindingRef launcher = BindingRef.loadTag(launcherTags.getCompound(index));
            if (!missileLaunchers.contains(launcher)) {
                missileLaunchers.add(launcher);
            }
        }
        if (missileLaunchers.isEmpty()) {
            BindingRef legacyLauncher = BindingRef.load(
                    tag, "Binding_" + BindingSlot.MISSILE_LAUNCHER.name());
            if (legacyLauncher != null) {
                missileLaunchers.add(legacyLauncher);
            }
        }
        missileFireCursor = missileLaunchers.isEmpty()
                ? 0
                : Math.floorMod(tag.getInt("MissileFireCursor"), missileLaunchers.size());
        ListTag aaTags = tag.getList("AaMissileLauncherBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < aaTags.size(); index++) {
            BindingRef launcher = BindingRef.loadTag(aaTags.getCompound(index));
            if (!aaMissileLaunchers.contains(launcher)) aaMissileLaunchers.add(launcher);
        }
        aaMissileFireCursor = aaMissileLaunchers.isEmpty()
                ? 0 : Math.floorMod(tag.getInt("AaMissileFireCursor"), aaMissileLaunchers.size());
        ListTag aircraftTags = tag.getList("AircraftMissileRackBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < aircraftTags.size(); index++) {
            BindingRef rack = BindingRef.loadTag(aircraftTags.getCompound(index));
            if (!aircraftMissileRacks.contains(rack)) aircraftMissileRacks.add(rack);
        }
        aircraftMissileFireCursor = aircraftMissileRacks.isEmpty() ? 0
                : Math.floorMod(tag.getInt("AircraftMissileFireCursor"), aircraftMissileRacks.size());
        ListTag bearingTags = tag.getList("UnassignedBearingBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < bearingTags.size(); index++) {
            BindingRef bearing = BindingRef.loadTag(bearingTags.getCompound(index));
            if (!unassignedBearings.contains(bearing)) unassignedBearings.add(bearing);
        }
        int selectedOrdinal = Mth.clamp(tag.getInt("SelectedAircraftWeapon"), 0,
                MianbaoAircraftCompat.WeaponType.values().length - 1);
        selectedAircraftWeapon = MianbaoAircraftCompat.WeaponType.values()[selectedOrdinal];
        radarRange = Mth.clamp(tag.contains("RadarRange") ? tag.getDouble("RadarRange") : 10000.0,
                200.0, 20000.0);
        boolean hasLegacyRadarVerticalFov = tag.contains("RadarVerticalFov");
        double legacyRadarHalfFov = hasLegacyRadarVerticalFov
                ? Mth.clamp(tag.getDouble("RadarVerticalFov") * 0.5, 0.0, 90.0) : 0.0;
        radarDepression = Mth.clamp(tag.contains("RadarDepression")
                ? tag.getDouble("RadarDepression") : (hasLegacyRadarVerticalFov ? legacyRadarHalfFov : 0.0),
                0.0, 90.0);
        radarElevation = Mth.clamp(tag.contains("RadarElevation")
                ? tag.getDouble("RadarElevation") : (hasLegacyRadarVerticalFov ? legacyRadarHalfFov : 75.0),
                0.0, 90.0);
        aaProximityFuseRange = Mth.clamp(tag.contains("AaProximityFuseRange")
                ? tag.getDouble("AaProximityFuseRange") : 5.0, 0.0, 10.0);
        radarHudScale = Mth.clamp(tag.contains("RadarHudScale")
                ? tag.getDouble("RadarHudScale") : 70.0, 40.0, 150.0);
        radarHudX = Mth.clamp(tag.contains("RadarHudX")
                ? tag.getDouble("RadarHudX") : 1.0, 0.0, 100.0);
        radarHudY = Mth.clamp(tag.contains("RadarHudY")
                ? tag.getDouble("RadarHudY") : 1.0, 0.0, 100.0);
        aircraftRadarRange = Mth.clamp(tag.contains("AircraftRadarRange")
                ? tag.getDouble("AircraftRadarRange") : 2000.0, 200.0, 100000.0);
        aircraftRadarHorizontalHalfAngle = Mth.clamp(tag.contains("AircraftRadarHorizontalHalfAngle")
                ? tag.getDouble("AircraftRadarHorizontalHalfAngle") : 90.0, 5.0, 90.0);
        aircraftRadarVerticalAngle = Mth.clamp(tag.contains("AircraftRadarVerticalAngle")
                ? tag.getDouble("AircraftRadarVerticalAngle") : 16.0, 2.0, 90.0);
        aircraftProximityFuseRange = Mth.clamp(tag.contains("AircraftProximityFuseRange")
                ? tag.getDouble("AircraftProximityFuseRange") : 10.0, 0.0, 10.0);
        aircraftFuseSafetySeconds = Mth.clamp(tag.contains("AircraftFuseSafetySeconds")
                ? tag.getDouble("AircraftFuseSafetySeconds") : 0.3, 0.0, 10.0);
        aircraftRadarMinimumTargetSpeed = Mth.clamp(tag.getDouble(
                "AircraftRadarMinimumTargetSpeed"), 0.0, 2000.0);
        aircraftRadarHudScale = Mth.clamp(tag.contains("AircraftRadarHudScale")
                ? tag.getDouble("AircraftRadarHudScale") : 100.0, 40.0, 180.0);
        aircraftRadarHudX = Mth.clamp(tag.contains("AircraftRadarHudX")
                ? tag.getDouble("AircraftRadarHudX") : 98.0, 0.0, 100.0);
        aircraftRadarHudY = Mth.clamp(tag.contains("AircraftRadarHudY")
                ? tag.getDouble("AircraftRadarHudY") : 50.0, 0.0, 100.0);
        radarLockedSubLevelId = tag.hasUUID("RadarLockedSubLevel")
                ? tag.getUUID("RadarLockedSubLevel") : null;
        depressionLimitDegrees = tag.contains("DepressionLimit") ? tag.getDouble("DepressionLimit") : 10.0;
        elevationLimitDegrees = tag.contains("ElevationLimit") ? tag.getDouble("ElevationLimit") : 20.0;
        yawSpeedDegreesPerSecond = tag.contains("YawSpeed") ? tag.getDouble("YawSpeed") : 30.0;
        pitchSpeedDegreesPerSecond = tag.contains("PitchSpeed") ? tag.getDouble("PitchSpeed") : 15.0;
        yawReversed = tag.getBoolean("YawReversed");
        pitchReversed = tag.getBoolean("PitchReversed");
        thirdPersonHeight = Mth.clamp(tag.getDouble("ThirdPersonHeight"), 0.0, 32.0);
        viewAimOffsetX = clampAimOffset(tag.getDouble("ViewAimOffsetX"));
        viewAimOffsetY = clampAimOffset(tag.getDouble("ViewAimOffsetY"));
        yawOffset = clampOffset(new Vec3(
                tag.getDouble("YawOffsetX"),
                tag.getDouble("YawOffsetY"),
                tag.getDouble("YawOffsetZ")
        ));
        pitchOffset = clampOffset(new Vec3(
                tag.getDouble("PitchOffsetX"),
                tag.getDouble("PitchOffsetY"),
                tag.getDouble("PitchOffsetZ")
        ));
        scopeOffset = clampOffset(new Vec3(
                tag.getDouble("ScopeOffsetX"),
                tag.getDouble("ScopeOffsetY"),
                tag.getDouble("ScopeOffsetZ")
        ));
        desiredWorldDirection = new Vec3(tag.getDouble("AimX"), tag.getDouble("AimY"), tag.getDouble("AimZ"));
        hasAimDirection = tag.getBoolean("HasAim");
        podDesiredWorldDirection = new Vec3(
                tag.getDouble("PodAimX"), tag.getDouble("PodAimY"), tag.getDouble("PodAimZ"));
        hasPodAimDirection = tag.getBoolean("HasPodAim")
                && podDesiredWorldDirection.lengthSqr() > 1.0e-8;
        if (hasPodAimDirection) podDesiredWorldDirection = podDesiredWorldDirection.normalize();
        stabilizerEnabled = !tag.contains("StabilizerEnabled") || tag.getBoolean("StabilizerEnabled");
        headAimEnabled = !tag.contains("HeadAimEnabled") || tag.getBoolean("HeadAimEnabled");
        remoteHeadAimEnabled = tag.getBoolean("RemoteHeadAimEnabled");
        cannonMountPodControl = tag.getBoolean("CannonMountPodControl");
        podCannonDepressionLimitDegrees = Mth.clamp(
                tag.contains("PodCannonDepressionLimit")
                        ? tag.getDouble("PodCannonDepressionLimit") : 90.0,
                0.0, 90.0);
        podCannonElevationLimitDegrees = Mth.clamp(
                tag.contains("PodCannonElevationLimit")
                        ? tag.getDouble("PodCannonElevationLimit") : 90.0,
                0.0, 90.0);
        scopeUserId = null;
        podViewUserId = null;
        thermalEnabled = false;
        frequencyConfigCursor = Mth.clamp(
                tag.getInt("FrequencyConfigCursor"), 0, INPUT_ACTION_COUNT * 2 - 1);
        scopeReceiver.load(tag, "ScopeWireless", registries);
        thermalReceiver.load(tag, "ThermalWireless", registries);
        opticalLockReceiver.load(tag, "OpticalLockWireless", registries);
        missileFireReceiver.load(tag, "MissileFireWireless", registries);
        aaMissileFireReceiver.load(tag, "AaMissileFireWireless", registries);
        radarLockReceiver.load(tag, "RadarLockWireless", registries);
        aircraftMissileFireReceiver.load(tag, "AircraftMissileFireWireless", registries);
        aircraftWeaponCycleReceiver.load(tag, "AircraftWeaponCycleWireless", registries);
        aircraftTargetCycleReceiver.load(tag, "AircraftTargetCycleWireless", registries);
        podViewReceiver.load(tag, "PodViewWireless", registries);
        airToGroundLockReceiver.load(tag, "AirToGroundLockWireless", registries);
        loadRemoteKeys(tag, "NormalRemoteKeys", normalRemoteKeys, RemoteControllerCompat.Type.NORMAL);
        loadRemoteKeys(tag, "AdvancedRemoteKeys", advancedRemoteKeys, RemoteControllerCompat.Type.ADVANCED);
        java.util.Arrays.fill(remoteInputPressed, false);
    }
}
