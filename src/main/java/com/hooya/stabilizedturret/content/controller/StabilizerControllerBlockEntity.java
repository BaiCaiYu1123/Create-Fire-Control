package com.hooya.stabilizedturret.content.controller;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.hooya.stabilizedturret.content.bearing.YawLimits;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import com.hooya.stabilizedturret.content.bearing.BearingHeadVisibility;
import com.hooya.stabilizedturret.compat.synaxis.SynaxisWeldCompat;
import com.hooya.stabilizedturret.compat.mianbao.MountedHeavyMachineGunCompat;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.hooya.stabilizedturret.network.AimGroupSessionPayload;
import com.hooya.stabilizedturret.network.ScopeOrientationPayload;
import com.hooya.stabilizedturret.network.TargetLockTelemetry;
import com.hooya.stabilizedturret.network.RemoteInputStatePayload.RangeSample;
import com.hooya.stabilizedturret.content.bearing.FiniteAimTarget;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
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
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Quaterniond;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class StabilizerControllerBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {
    private static final double ROCKET_LAUNCH_SOUND_RANGE_SQR = 192.0 * 192.0;
    /** Five grouped salvos per second is the server-side ceiling for held fire. */
    private static final long AIRCRAFT_ROCKET_SALVO_INTERVAL_TICKS = 4L;
    private static final long OPTICAL_OCCLUSION_GRACE_TICKS = 40L;
    private static final long OPTICAL_VISIBILITY_CHECK_INTERVAL_TICKS = 2L;
    private static final long OWN_SUB_LEVEL_CACHE_TICKS = 20L;
    private static final long RADAR_TOPOLOGY_CACHE_TICKS = 200L;
    private static final int RADAR_TOPOLOGY_SUBLEVEL_BUDGET = 2;
    /** Reuse expensive ammunition/trajectory analysis briefly while preserving per-tick aiming. */
    private static final long POD_CANNON_SOLUTION_CACHE_TICKS = 2L;

    private final Map<BindingSlot, BindingRef> bindings = new EnumMap<>(BindingSlot.class);
    /** All physical azimuth/elevation bearings assigned to this computer. */
    private final List<BindingRef> yawBearings = new ArrayList<>();
    private final List<BindingRef> pitchBearings = new ArrayList<>();
    private final List<BindingRef> cannonMounts = new ArrayList<>();
    private final Map<BindingRef, PodCannonSolution> podCannonSolutions = new java.util.HashMap<>();
    private final List<BindingRef> missileLaunchers = new ArrayList<>();
    private final List<BindingRef> aaMissileLaunchers = new ArrayList<>();
    private final List<BindingRef> aircraftMissileRacks = new ArrayList<>();
    /** Binding order shared by anti-tank and air-defense direction anchors. */
    private final List<BindingRef> missileDirectionAnchors = new ArrayList<>();
    private final List<BindingRef> aircraftRocketPods = new ArrayList<>();
    private final List<BindingRef> cannonTurretPods = new ArrayList<>();
    private final List<BindingRef> countermeasureLaunchers = new ArrayList<>();
    /** UUIDs of flares launched by this controller while they remain active. */
    private final Set<UUID> launchedCountermeasures = new HashSet<>();
    private final List<BindingRef> remoteWeaponStations = new ArrayList<>();
    private final Map<BindingRef, Vec3> remoteWeaponStationHoldDirections =
            new HashMap<>();
    private final List<BindingRef> iffTransponders = new ArrayList<>();
    private final List<BindingRef> unassignedBearings = new ArrayList<>();
    private final Set<BindingRef> pitchCalibrationAttempted = new HashSet<>();
    private List<TurretGroup> cachedTurretGroups = List.of();
    private long cachedTurretGroupsAt = Long.MIN_VALUE;
    private int cachedTurretTopologyHash;
    private final WirelessPulseReceiver scopeReceiver = new WirelessPulseReceiver(this, ignored -> toggleScope(null));
    private final WirelessPulseReceiver thermalReceiver = new WirelessPulseReceiver(this, ignored -> toggleThermal(null));
    private final WirelessPulseReceiver opticalLockReceiver = new WirelessPulseReceiver(this, ignored -> toggleOpticalLock());
    private final WirelessPulseReceiver missileFireReceiver = new WirelessPulseReceiver(this, ignored -> fireSelectedGroundWeapon());
    private final WirelessPulseReceiver aaMissileFireReceiver = new WirelessPulseReceiver(this, ignored -> fireSelectedGroundWeapon());
    private final WirelessPulseReceiver radarLockReceiver = new WirelessPulseReceiver(this, ignored -> toggleRadarLockFromWireless());
    private final WirelessPulseReceiver aircraftMissileFireReceiver = new WirelessPulseReceiver(this, ignored -> fireNextAircraftMissile());
    private final WirelessPulseReceiver aircraftWeaponCycleReceiver = new WirelessPulseReceiver(this, ignored -> cycleAircraftWeapon());
    private final WirelessPulseReceiver aircraftWeaponCycleUpReceiver = new WirelessPulseReceiver(this, ignored -> cycleAircraftWeapon(-1));
    private final WirelessPulseReceiver aircraftTargetCycleReceiver = new WirelessPulseReceiver(this, ignored -> cycleAircraftRadarTarget());
    private final WirelessPulseReceiver groundTargetCycleReceiver = new WirelessPulseReceiver(this, ignored -> cycleGroundRadarTarget());
    private final WirelessPulseReceiver podViewReceiver =
            new WirelessPulseReceiver(this, ignored -> togglePodViewFromWireless());
    private final WirelessPulseReceiver airToGroundLockReceiver =
            new WirelessPulseReceiver(this, ignored -> toggleAirToGroundLockFromWireless());
    private final WirelessPulseReceiver countermeasureFireReceiver =
            new WirelessPulseReceiver(this, ignored -> fireNextCountermeasure());
    private final WirelessPulseReceiver freeLookReceiver =
            new WirelessPulseReceiver(this, ignored -> { });
    private final WirelessPulseReceiver televisionGuidanceReceiver =
            new WirelessPulseReceiver(this, ignored -> toggleTelevisionGuidanceFromWireless());
    private final WirelessPulseReceiver weaponViewReceiver =
            new WirelessPulseReceiver(this, ignored -> { });
    private final WirelessPulseReceiver machineGunFireReceiver =
            new WirelessPulseReceiver(this, ignored -> { });
    private final WirelessPulseReceiver laserRangeReceiver =
            new WirelessPulseReceiver(this, ignored -> requestLaserRange(null, null));
    public static final int INPUT_ACTION_MACHINE_GUN_FIRE = 14;
    public static final int INPUT_ACTION_LASER_RANGE = 15;
    public static final int INPUT_ACTION_AIRCRAFT_WEAPON_UP = 16;
    public static final int INPUT_ACTION_GROUND_TARGET_CYCLE = 43;
    public static final int WIRELESS_INPUT_ACTION_COUNT = FireControlWeaponInputs.ACTION_COUNT;
    public static final int INPUT_ACTION_FREE_LOOK = 12;
    public static final int INPUT_ACTION_TELEVISION_GUIDANCE = 13;
    public static final int INPUT_ACTION_COUNT = WIRELESS_INPUT_ACTION_COUNT;
    private final WirelessPulseReceiver[] weaponSelectionReceivers = createWeaponSelectionReceivers();
    private @Nullable FireControlWeaponInputs.GroundWeapon selectedGroundWeapon;
    private final Map<BindingRef, LaserRangeCompensation> laserRanges = new HashMap<>();
    private @Nullable RangeSample pendingLaserRange;
    private @Nullable ServerPlayer pendingLaserRangeOperator;
    private boolean pendingLaserRangeExplicit;
    private final String[] wirelessRemoteKeys = new String[INPUT_ACTION_COUNT];
    private final String[] normalRemoteKeys = new String[INPUT_ACTION_COUNT];
    private final String[] advancedRemoteKeys = new String[INPUT_ACTION_COUNT];
    private final boolean[] remoteInputPressed = new boolean[INPUT_ACTION_COUNT];
    private @Nullable UUID freeLookUserId;
    private @Nullable UUID weaponViewUserId;

    private double depressionLimitDegrees = 10.0;
    private double elevationLimitDegrees = 20.0;
    private double yawSpeedDegreesPerSecond = 30.0;
    private YawLimits yawLimits = YawLimits.UNLIMITED;
    private double pitchSpeedDegreesPerSecond = 15.0;
    private boolean yawReversed;
    private boolean pitchReversed;
    private boolean yawBearingHeadHidden;
    private boolean pitchBearingHeadHidden;
    private double radarRange = 3000.0;
    private double radarDepression = 0.0;
    private double radarElevation = 75.0;
    private double aaProximityFuseRange = 5.0;
    private double radarHudScale = 70.0;
    private double radarHudX = 1.0;
    private double radarHudY = 3.0;
    private double aircraftRadarRange = 2000.0;
    private double aircraftRadarHorizontalHalfAngle = 90.0;
    private double aircraftRadarVerticalAngle = 16.0;
    private double aircraftProximityFuseRange = 10.0;
    private double aircraftFuseSafetySeconds = 0.3;
    private double aircraftRadarMinimumTargetSpeed;
    private double aircraftRadarHudScale = 50.0;
    private double aircraftRadarHudX = 98.0;
    private double aircraftRadarHudY = 50.0;
    private double rwrRange = 2_000.0;
    private double rwrScale = 100.0;
    private boolean aircraftRocketSalvoMode;
    private double thirdPersonHeight = 1.0;
    private double viewAimOffsetX;
    private double viewAimOffsetY = -15.0;
    private Vec3 yawOffset = Vec3.ZERO;
    private Vec3 pitchOffset = Vec3.ZERO;
    private final Map<BindingRef, BearingSettings> bearingSettings = new HashMap<>();
    private Vec3 scopeOffset = Vec3.ZERO;
    private boolean virtualScopeEnabled;
    private ScopePreset scopePreset = ScopePreset.DEFAULT;
    private Vec3 desiredWorldDirection = new Vec3(0.0, 0.0, -1.0);
    private Vec3 podDesiredWorldDirection = new Vec3(0.0, 0.0, -1.0);
    private Vec3 podStabilizedWorldTarget = Vec3.ZERO;
    private Vec3 desiredWorldTarget = Vec3.ZERO;
    private Vec3 lastAimRayOrigin = Vec3.ZERO;
    private Vec3 lastAimRayTarget = new Vec3(0, 0, -4096);
    private com.hooya.stabilizedturret.content.bearing.MovingAimRay movingAimRay;
    private final Map<BindingRef, TurretAimConvergence.NearTargetGuard> nearTerrainAimGuards = new HashMap<>();
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
    private boolean stabilizerReactionEnabled = true;
    private double cannonRecoilMagnitude = CannonRecoilSettings.DEFAULT;
    private boolean mobileShotCompensationEnabled = true;
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
    private UUID opticalCameraAcquisitionId = UUID.randomUUID();
    private long opticalOccludedSince = Long.MIN_VALUE;
    private long lastOpticalVisibilityCheck = Long.MIN_VALUE;
    private boolean opticalLockObscured;
    private long lockedSubLevelLastPresenceCheck = Long.MIN_VALUE;
    private boolean lockedSubLevelPresent = true;
    private Set<UUID> cachedOwnSubLevelIds = Set.of();
    private long cachedOwnSubLevelIdsAt = Long.MIN_VALUE;
    private long cachedAimGroupsAt = Long.MIN_VALUE;
    private List<AimGroupSessionPayload.GroupAim> cachedAimGroups = List.of();
    private int frequencyConfigCursor;
    private int missileFireCursor;
    private int aaMissileFireCursor;
    private int aircraftMissileFireCursor;
    private int aircraftRocketFireCursor;
    private int countermeasureFireCursor;
    private long lastAircraftRocketSalvoTick = Long.MIN_VALUE;
    private MianbaoAircraftCompat.WeaponType selectedAircraftWeapon = MianbaoAircraftCompat.WeaponType.FAR;
    private boolean aircraftWeaponSelectionActive = true;
    private int infraredSelectionEpoch;
    private boolean infraredAmmoAvailable;

    private void selectAircraftWeapon(MianbaoAircraftCompat.WeaponType weapon) {
        if (selectedAircraftWeapon != weapon || !aircraftWeaponSelectionActive) {
            InfraredTargeting.clear(this);
            AntiRadiationTargeting.clear(this);
            infraredSelectionEpoch++;
        }
        aircraftWeaponSelectionActive = true;
        selectedAircraftWeapon = weapon;
    }

    public boolean isInfraredWeaponSelected() {
        return aircraftWeaponSelectionActive && MianbaoAircraftCompat.isInfrared(selectedAircraftWeapon);
    }
    public boolean isInfraredWeaponArmed() {
        if (!isInfraredWeaponSelected()) return false;
        boolean available = getAircraftAmmoCount(MianbaoAircraftCompat.WeaponType.CLOSE) > 0;
        if (available != infraredAmmoAvailable) {
            infraredAmmoAvailable = available;
            InfraredTargeting.clear(this);
            infraredSelectionEpoch++;
        }
        return available;
    }
    public boolean isAntiRadiationWeaponSelected() {
        return aircraftWeaponSelectionActive && MianbaoAircraftCompat.isAntiRadiation(selectedAircraftWeapon);
    }
    public int getInfraredSelectionEpoch() { return infraredSelectionEpoch; }

    public Vec3 getInfraredAircraftOrigin() {
        return level == null ? Vec3.ZERO : Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(getBlockPos()));
    }
    public Vec3 getInfraredAircraftForward() {
        if (level == null) return new Vec3(0, 0, -1);
        for (BindingRef rack : aircraftMissileRacks) {
            Vec3 axis = MianbaoAircraftCompat.launchDirection(level, rack);
            if (axis != null) return axis;
            // Empty racks need not have a block entity, but retain their physical facing.
            var body = rack.subLevelId() == null ? null : SubLevelContainer.getContainer(level).getSubLevel(rack.subLevelId());
            Level rackLevel = body == null ? level : body.getLevel();
            if (!rackLevel.hasChunkAt(rack.pos())) continue;
            BlockState state = rackLevel.getBlockState(rack.pos());
            Direction facing = state.hasProperty(BlockStateProperties.FACING) ? state.getValue(BlockStateProperties.FACING)
                    : state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : null;
            if (facing == null) continue;
            Vec3 origin = Vec3.atCenterOf(rack.pos());
            return Sable.HELPER.projectOutOfSubLevel(rackLevel, origin.add(facing.getStepX(), facing.getStepY(), facing.getStepZ()))
                    .subtract(Sable.HELPER.projectOutOfSubLevel(rackLevel, origin)).normalize();
        }
        var body = Sable.HELPER.getContaining(this);
        if (body == null) return new Vec3(0, 0, -1);
        Vector3d axis = body.logicalPose().transformNormal(new Vector3d(0, 0, -1));
        return new Vec3(axis.x, axis.y, axis.z).normalize();
    }
    public void resetInfraredLockAfterLaunch() {
        if (!isInfraredWeaponSelected()) return;
        InfraredTargeting.clear(this);
        infraredSelectionEpoch++;
        publishInfraredState();
    }
    public void publishInfraredState() {
        if (level == null || level.isClientSide) return;
        isInfraredWeaponArmed();
        ServerPlayer operator = getAircraftMissileOperator();
        if (operator != null) PacketDistributor.sendToPlayer(operator,
                com.hooya.stabilizedturret.network.InfraredStatePayload.from(this));
    }
    private long bombSightTick = Long.MIN_VALUE;
    private int bombSightCursor = -1;
    private com.hooya.stabilizedturret.network.BombSightPayload bombSight =
            com.hooya.stabilizedturret.network.BombSightPayload.inactive();
    private Vec3 aircraftVelocity = Vec3.ZERO;
    private Vec3 previousAircraftOrigin;
    private long previousAircraftOriginTick = Long.MIN_VALUE;
    private long rocketImpactPredictionTick = Long.MIN_VALUE;
    private RocketImpactPrediction rocketImpactPrediction = RocketImpactPrediction.unavailable();
    private UUID radarLockedSubLevelId;
    private UUID radarCameraAcquisitionId = UUID.randomUUID();
    private UUID aircraftSelectedTargetId;
    private UUID airToGroundLockedSubLevelId;
    private Set<UUID> airToGroundLockedMembers = Set.of();
    private long airToGroundSearchUntil = Long.MIN_VALUE;
    private List<RadarContact> radarContacts = List.of();
    private final RadarVisibility radarVisibility = new RadarVisibility();
    private final RadarVisibility missileLockVisibility = new RadarVisibility();
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
        java.util.Arrays.fill(wirelessRemoteKeys, "");
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
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, aircraftWeaponCycleUpReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, aircraftTargetCycleReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, groundTargetCycleReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, podViewReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, airToGroundLockReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, countermeasureFireReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, freeLookReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, televisionGuidanceReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, weaponViewReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, machineGunFireReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, laserRangeReceiver);
            for (WirelessPulseReceiver receiver : weaponSelectionReceivers)
                Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, receiver);
        }
    }

    @Override
    public void setRemoved() {
        ControllerTracker.remove(this);
        InfraredTargeting.clear(this);
        if (level != null && !level.isClientSide) {
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, scopeReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, thermalReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, opticalLockReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, missileFireReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, aaMissileFireReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, radarLockReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, aircraftMissileFireReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, aircraftWeaponCycleReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, aircraftWeaponCycleUpReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, aircraftTargetCycleReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, groundTargetCycleReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, podViewReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, airToGroundLockReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, countermeasureFireReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, freeLookReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, televisionGuidanceReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, weaponViewReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, machineGunFireReceiver);
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, laserRangeReceiver);
            for (WirelessPulseReceiver receiver : weaponSelectionReceivers)
                Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, receiver);
        }
        super.setRemoved();
    }

    private void toggleScope(@Nullable ServerPlayer requestedUser) {
        ServerPlayer user = requestedUser;
        if (user == null) {
            user = findSeatedGunner();
            if (user == null && remoteHeadAimEnabled) {
                user = RemoteControlTracker.findActivePlayers(this).stream()
                        .filter(this::canUseScope).findFirst().orElse(null);
            }
        }
        if (user == null) return;
        UUID userId = user.getUUID();
        if (userId.equals(scopeUserId)) {
            closeScope(user);
            return;
        }
        if (!canUseScope(user)) return;
        if (scopeUserId != null || (podViewUserId != null && !userId.equals(podViewUserId))) {
            user.displayClientMessage(Component.translatable(
                    "message.create_fire_control.scope_in_use"), true);
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
                    "message.create_fire_control.pod_in_use"), true);
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
        if (MianbaoAircraftCompat.isAntiRadiation(selectedAircraftWeapon)) {
            if (hasInputConfigured(10)) AntiRadiationTargeting.toggle(this, findSeatedGunner());
            return;
        }
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
        boolean televisionView = requestedUser != null
                && MianbaoOpticalMissileHandler.isViewing(requestedUser, this);
        if ((!isOpticalViewEnabled() && !televisionView)
                || (requestedUser != null && !isOpticalViewEnabled(requestedUser)
                && !televisionView)) return;
        thermalEnabled = !thermalEnabled;
        setChanged();
    }

    private java.util.EnumSet<FireControlWeaponInputs.GroundWeapon> boundGroundWeapons() {
        var types = java.util.EnumSet.noneOf(FireControlWeaponInputs.GroundWeapon.class);
        if (level == null) return types;
        if (!missileLaunchers.isEmpty() || remoteWeaponStations.stream().anyMatch(ref ->
                MianbaoRemoteWeaponStationCompat.isHeavyCannonStation(level, ref.pos())
                        && MianbaoRemoteWeaponStationCompat.isAntiTankMissileLoaded(level, ref))) {
            types.add(FireControlWeaponInputs.GroundWeapon.ANTI_TANK);
        }
        for (BindingRef launcher : aaMissileLaunchers) {
            if (MianbaoAirDefenseCompat.getAmmoCount(level, launcher) <= 0) continue;
            types.add(MianbaoAirDefenseCompat.hasOrdinaryRound(level, launcher)
                    ? FireControlWeaponInputs.GroundWeapon.AIR_DEFENSE
                    : FireControlWeaponInputs.GroundWeapon.HIGH_SPEED_AIR_DEFENSE);
        }
        return types;
    }

    private java.util.EnumSet<MianbaoAircraftCompat.WeaponType> boundAircraftWeapons() {
        var types = java.util.EnumSet.noneOf(MianbaoAircraftCompat.WeaponType.class);
        if (level == null) return types;
        for (BindingRef rack : aircraftMissileRacks) {
            types.addAll(MianbaoAircraftRackCompat.weaponsAt(level, rack));
        }
        if (!aircraftRocketPods.isEmpty()) types.add(MianbaoAircraftCompat.WeaponType.ROCKET_POD);
        return types;
    }

    public @Nullable FireControlWeaponInputs.GroundWeapon getSelectedGroundWeapon() {
        return selectedGroundWeapon;
    }

    private void selectBoundWeapon(int action) {
        if (level == null) return;
        removeInvalidBindings();
        var ground = FireControlWeaponInputs.groundWeapon(action);
        var aircraft = FireControlWeaponInputs.aircraftWeapon(action);
        Component name;
        if (ground != null && boundGroundWeapons().contains(ground)) {
            InfraredTargeting.clear(this);
            AntiRadiationTargeting.clear(this);
            infraredSelectionEpoch++;
            aircraftWeaponSelectionActive = false;
            selectedGroundWeapon = ground;
            name = ground.displayName();
        } else if (aircraft != null && boundAircraftWeapons().contains(aircraft)) {
            selectAircraftWeapon(aircraft);
            name = aircraft.displayName();
        } else return;
        setChanged();
        notifyMissileOperator(Component.translatable("message.create_fire_control.weapon.selected", name));
    }

    private void fireSelectedGroundWeapon() {
        if (level == null || (!hasInputConfigured(FireControlWeaponInputs.GROUND_FIRE)
                && !hasInputConfigured(FireControlWeaponInputs.LEGACY_AA_FIRE))) return;
        removeInvalidBindings();
        // Choose a default for old worlds or a newly bound computer. Once selected,
        // an empty type must never silently fire a different missile family.
        if (selectedGroundWeapon == null) {
            selectedGroundWeapon = boundGroundWeapons().stream().findFirst().orElse(null);
            if (selectedGroundWeapon != null) setChanged();
        }
        if (selectedGroundWeapon == FireControlWeaponInputs.GroundWeapon.ANTI_TANK) {
            fireNextMissile();
        } else if (selectedGroundWeapon != null) {
            fireNextAaMissile();
        } else {
            notifyMissileOperator(Component.translatable("message.create_fire_control.ground_weapon.missing"));
        }
    }

    /** Fires at most one native Mianbao missile for each wireless rising edge. */
    private void fireNextMissile() {
        if (level == null) {
            return;
        }
        removeInvalidBindings();
        List<BindingRef> heavyStations = remoteWeaponStations.stream()
                .filter(ref -> MianbaoRemoteWeaponStationCompat
                        .isHeavyCannonStation(level, ref.pos()))
                .toList();
        if (missileLaunchers.isEmpty() && heavyStations.isEmpty()) {
            notifyMissileOperator(Component.translatable("message.create_fire_control.missile_launcher_missing"));
            return;
        }

        int launcherCount = missileLaunchers.size();
        int size = launcherCount + heavyStations.size();
        int start = Math.floorMod(missileFireCursor, size);
        for (int offset = 0; offset < size; offset++) {
            int index = (start + offset) % size;
            boolean heavyStation = index >= launcherCount;
            BindingRef launcher = heavyStation
                    ? heavyStations.get(index - launcherCount)
                    : missileLaunchers.get(index);
            if (heavyStation) {
                if (!MianbaoRemoteWeaponStationCompat
                        .isAntiTankMissileLoaded(level, launcher)) continue;
            } else if (MianbaoMissileCompat.getAmmoCount(level, launcher) <= 0) continue;

            // Advance even when a third-party procedure fails. This prevents
            // one malformed launcher from permanently blocking the others,
            // while still invoking at most one launcher for this pulse.
            missileFireCursor = (index + 1) % size;
            boolean fired;
            if (heavyStation) {
                Entity missile = MianbaoRemoteWeaponStationCompat
                        .fireAntiTankMissile(level, launcher);
                fired = missile != null;
                if (missile != null) {
                    MianbaoMissileGuidanceHandler.adopt(missile, this);
                }
            } else {
                fired = MianbaoMissileCompat.fireOne(level, launcher);
            }
            setChanged();
            if (!fired) {
                notifyMissileOperator(Component.translatable("message.create_fire_control.missile_fire_failed"));
            }
            return;
        }
        notifyMissileOperator(Component.translatable("message.create_fire_control.missiles_depleted"));
    }

    /** Fires one flare from one launcher, even when many launchers are bound. */
    private void fireNextCountermeasure() {
        if (!hasInputConfigured(11) || level == null) return;
        removeInvalidBindings();
        if (countermeasureLaunchers.isEmpty()) {
            notifyMissileOperator(Component.translatable(
                    "message.create_fire_control.countermeasure_launcher_missing"));
            return;
        }

        int size = countermeasureLaunchers.size();
        int start = Math.floorMod(countermeasureFireCursor, size);
        for (int offset = 0; offset < size; offset++) {
            int index = (start + offset) % size;
            BindingRef launcher = countermeasureLaunchers.get(index);
            if (!MianbaoCountermeasureCompat.canFire(level, launcher)) continue;

            countermeasureFireCursor = (index + 1) % size;
            Entity launched = MianbaoCountermeasureCompat.fireOne(level, launcher);
            boolean fired = launched != null
                    || MianbaoCountermeasureManager.hasPendingLaunch(level, launcher.pos());
            if (fired) launchedCountermeasures.add(launched.getUUID());
            setChanged();
            if (!fired) {
                notifyMissileOperator(Component.translatable(
                        "message.create_fire_control.countermeasure_fire_failed"));
            }
            return;
        }
        notifyMissileOperator(Component.translatable(
                "message.create_fire_control.countermeasures_depleted"));
    }

    private void fireNextAaMissile() {
        if (level == null) {
            return;
        }
        removeInvalidBindings();
        if (aaMissileLaunchers.isEmpty()) {
            notifyMissileOperator(Component.translatable("message.create_fire_control.aa_missile.launcher_missing"));
            return;
        }
        int size = aaMissileLaunchers.size();
        int start = Math.floorMod(aaMissileFireCursor, size);
        String unavailable = "message.create_fire_control.aa_missile.depleted";
        for (int offset = 0; offset < size; offset++) {
            int index = (start + offset) % size;
            BindingRef launcher = aaMissileLaunchers.get(index);
            if (MianbaoAirDefenseCompat.getAmmoCount(level, launcher) <= 0) {
                continue;
            }
            boolean ordinary = MianbaoAirDefenseCompat.hasOrdinaryRound(level, launcher);
            if (selectedGroundWeapon == null || !selectedGroundWeapon.acceptsAaRound(ordinary)) continue;
            if (ordinary) {
                if (!isOrdinaryAaGuidanceReady()) {
                    unavailable = "message.create_fire_control.aa_missile.beam_missing";
                    continue;
                }
            } else if (!isRadarActive()) {
                unavailable = "message.create_fire_control.aa_missile.radar_inactive";
                continue;
            } else if (getRadarLockedSubLevel() == null) {
                unavailable = "message.create_fire_control.aa_missile.target_missing";
                continue;
            }
            aaMissileFireCursor = (index + 1) % size;
            boolean fired = MianbaoAirDefenseCompat.fireOne(level, launcher, this);
            setChanged();
            if (!fired) {
                notifyMissileOperator(Component.translatable("message.create_fire_control.aa_missile.fire_failed"));
            }
            return;
        }
        notifyMissileOperator(Component.translatable(unavailable));
    }

    private void fireNextAircraftMissile() {
        if (!hasInputConfigured(6) || level == null) return;
        removeInvalidBindings();
        if (selectedAircraftWeapon == MianbaoAircraftCompat.WeaponType.ROCKET_POD) {
            if (aircraftRocketSalvoMode) {
                fireAircraftRocketSalvo(true);
            } else {
                fireNextAircraftRocket();
            }
            return;
        }
        if (aircraftMissileRacks.isEmpty()) {
            notifyMissileOperator(Component.translatable("message.create_fire_control.aircraft_missile.rack_missing"));
            return;
        }
        if (MianbaoAircraftCompat.isInfrared(selectedAircraftWeapon)
                && InfraredTargeting.launchTarget(this) == null) {
            notifyMissileOperator(Component.translatable("message.create_fire_control.infrared_lock_required"));
            return;
        }
        if (MianbaoAircraftCompat.isRadarGuided(selectedAircraftWeapon)
                && !isAircraftRadarActive()) {
            notifyMissileOperator(Component.translatable("message.create_fire_control.aircraft_missile.radar_inactive"));
            return;
        }
        if (MianbaoAircraftCompat.isRadarGuided(selectedAircraftWeapon)
                && getAircraftGuidanceTargetId() == null) {
            notifyMissileOperator(Component.translatable("message.create_fire_control.aircraft_missile.target_missing"));
            return;
        }
        if (MianbaoAircraftCompat.isAirToGround(selectedAircraftWeapon)
                && (!isPodViewEnabled() || getAirToGroundLaunchTargetSnapshot() == null)) {
            notifyMissileOperator(Component.translatable(
                    "message.create_fire_control.air_to_ground_lock_required"));
            return;
        }
        if (MianbaoAircraftCompat.isAntiRadiation(selectedAircraftWeapon)
                && AntiRadiationTargeting.target(this) == null) {
            notifyMissileOperator(Component.translatable("message.create_fire_control.anti_radiation.target_required"));
            return;
        }
        if (MianbaoAircraftCompat.isLaserGuided(selectedAircraftWeapon)
                && (!isPodViewEnabled() || getPodGuidanceTarget() == null)) {
            notifyMissileOperator(Component.translatable(
                    "message.create_fire_control.pod_guidance_required"));
            return;
        }
        if (MianbaoAircraftCompat.isOpticalGuided(selectedAircraftWeapon)
                && MianbaoOpticalMissileHandler.hasActiveMissile(this)) {
            notifyMissileOperator(Component.translatable(
                    "message.create_fire_control.optical_missile_already_active"));
            return;
        }
        int size = aircraftMissileRacks.size();
        int start = Math.floorMod(aircraftMissileFireCursor, size);
        for (int offset = 0; offset < size; offset++) {
            int index = (start + offset) % size;
            BindingRef rack = aircraftMissileRacks.get(index);
            if (MianbaoAircraftRackCompat.ammoCount(level, rack, selectedAircraftWeapon) <= 0) continue;
            aircraftMissileFireCursor = (index + 1) % size;
            boolean fired = AirborneWeaponViewHandler.launch(this,
                    () -> MianbaoAircraftRackCompat.fireOne(this, rack, selectedAircraftWeapon));
            setChanged();
            if (fired && MianbaoAircraftCompat.isInfrared(selectedAircraftWeapon)) publishInfraredState();
            if (fired && MianbaoAircraftCompat.isAirToGround(selectedAircraftWeapon)) {
                clearAirToGroundLock();
            }
            if (fired) bombSightTick = Long.MIN_VALUE;
            if (!fired) notifyMissileOperator(Component.translatable("message.create_fire_control.aircraft_missile.fire_failed"));
            return;
        }
        notifyMissileOperator(Component.translatable("message.create_fire_control.aircraft_missile.selected_depleted"));
    }

    private void fireNextAircraftRocket() {
        if (level == null) return;
        if (aircraftRocketPods.isEmpty()) {
            notifyMissileOperator(Component.translatable(
                    "message.create_fire_control.aircraft_rocket_pod_missing"));
            return;
        }
        int size = aircraftRocketPods.size();
        int start = Math.floorMod(aircraftRocketFireCursor, size);
        for (int offset = 0; offset < size; offset++) {
            int index = (start + offset) % size;
            BindingRef pod = aircraftRocketPods.get(index);
            if (MianbaoRocketPodCompat.ammoCount(level, pod) <= 0) continue;
            aircraftRocketFireCursor = (index + 1) % size;
            boolean fired = MianbaoRocketPodCompat.fireOne(level, pod);
            setChanged();
            if (!fired) {
                notifyMissileOperator(Component.translatable(
                        "message.create_fire_control.aircraft_rocket_fire_failed"));
            }
            return;
        }
        notifyMissileOperator(Component.translatable(
                "message.create_fire_control.aircraft_rockets_depleted"));
    }

    private void fireAircraftRocketSalvo(boolean notifyFailure) {
        if (level == null) return;
        long gameTime = level.getGameTime();
        if (lastAircraftRocketSalvoTick != Long.MIN_VALUE
                && gameTime >= lastAircraftRocketSalvoTick
                && gameTime - lastAircraftRocketSalvoTick
                < AIRCRAFT_ROCKET_SALVO_INTERVAL_TICKS) {
            return;
        }
        lastAircraftRocketSalvoTick = gameTime;
        if (aircraftRocketPods.isEmpty()) {
            if (notifyFailure) {
                notifyMissileOperator(Component.translatable(
                        "message.create_fire_control.aircraft_rocket_pod_missing"));
            }
            return;
        }

        boolean hadAmmo = false;
        boolean fired = false;
        BindingRef soundPod = null;
        try (MianbaoRocketPodSoundGuard.Scope ignored =
                     MianbaoRocketPodSoundGuard.suppressNativeLaunchSounds()) {
            for (BindingRef pod : aircraftRocketPods) {
                if (MianbaoRocketPodCompat.ammoCount(level, pod) <= 0) continue;
                hadAmmo = true;
                // Do not consume another round when the global in-flight
                // safety ceiling has already been reached.
                if (!MianbaoRocketPodProjectileHandler.hasLaunchCapacity()) break;
                boolean podFired = MianbaoRocketPodCompat.fireOne(level, pod);
                fired |= podFired;
                if (podFired && soundPod == null) soundPod = pod;
            }
        }
        if (fired) {
            replaceAircraftRocketSalvoSound(soundPod);
            setChanged();
        } else if (notifyFailure) {
            notifyMissileOperator(Component.translatable(hadAmmo
                    ? "message.create_fire_control.aircraft_rocket_fire_failed"
                    : "message.create_fire_control.aircraft_rockets_depleted"));
        }
    }

    /**
     * Native per-pod launch sounds are suppressed while the grouped salvo is
     * fired. Stop the previous tick's replacement, then broadcast exactly one
     * new sound from the first pod that actually fired.
     */
    private void replaceAircraftRocketSalvoSound(@Nullable BindingRef soundPod) {
        if (level == null || soundPod == null) return;
        Vec3 source = MianbaoRocketPodCompat.muzzle(level, soundPod);
        if (source == null
                || !(SubLevelContainer.getContainer(level).getLevel()
                instanceof ServerLevel root)) {
            return;
        }

        ClientboundStopSoundPacket stopPacket = new ClientboundStopSoundPacket(
                MianbaoRocketPodSoundGuard.LAUNCH_SOUND, null);
        for (ServerPlayer player : root.getServer().getPlayerList().getPlayers()) {
            if (MianbaoMissileCompat.getRootServerLevel(player) != root
                    || MianbaoMissileCompat.getWorldPosition(player)
                    .distanceToSqr(source) > ROCKET_LAUNCH_SOUND_RANGE_SQR) {
                continue;
            }
            player.connection.send(stopPacket);
        }

        BuiltInRegistries.SOUND_EVENT.getOptional(MianbaoRocketPodSoundGuard.LAUNCH_SOUND)
                .ifPresent(sound -> root.playSound(
                        null, source.x, source.y, source.z,
                        sound, SoundSource.NEUTRAL, 8.0F, 0.8F));
    }

    private void cycleAircraftWeapon() {
        cycleAircraftWeapon(1);
    }
    private void cycleAircraftWeapon(int direction) {
        if (!hasInputConfigured(direction < 0 ? INPUT_ACTION_AIRCRAFT_WEAPON_UP : 7) || level == null) return;
        MianbaoAircraftCompat.WeaponType[] values = MianbaoAircraftCompat.WeaponType.values();
        for (int step = 1; step <= values.length; step++) {
            MianbaoAircraftCompat.WeaponType candidate = values[Math.floorMod(selectedAircraftWeapon.ordinal() + direction * step, values.length)];
            if (getAircraftAmmoCount(candidate) > 0) {
                selectAircraftWeapon(candidate);
                setChanged();
                notifyMissileOperator(Component.translatable("message.create_fire_control.aircraft_weapon.selected", candidate.displayName()));
                return;
            }
        }
        notifyMissileOperator(Component.translatable("message.create_fire_control.aircraft_missile.depleted"));
    }

    private void cycleGroundRadarTarget() {
        if (level == null || !hasInputConfigured(INPUT_ACTION_GROUND_TARGET_CYCLE)
                || !isRadarActive() || isAircraftRadarMode() || findSeatedGunner() == null
                || radarContacts.isEmpty()) return;
        setRadarLockedTarget(RadarTargetCycle.next(radarContacts, radarLockedSubLevelId));
        setChanged();
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
        if (level != null) RadarLockMemory.updateSelection(this, aircraftSelectedTargetId, level.getGameTime());
        setChanged();
    }


    private void toggleAirToGroundLock(@Nullable ServerPlayer operator) {
        if (MianbaoAircraftCompat.isAntiRadiation(selectedAircraftWeapon)) {
            if (hasInputConfigured(10)) AntiRadiationTargeting.toggle(this, operator);
            return;
        }
        if (!hasInputConfigured(10) || level == null || operator == null
                || !isPodViewEnabled(operator)) {
            return;
        }
        if (airToGroundLockedSubLevelId != null) {
            clearAirToGroundLock();
            operator.displayClientMessage(Component.translatable(
                    "message.create_fire_control.air_to_ground_lock_released"), true);
            return;
        }
        airToGroundSearchUntil = level.getGameTime() + 20L;
        GroundTargetSnapshot target = findAirToGroundTargetInPodFrame();
        if (target == null) {
            operator.displayClientMessage(Component.translatable(
                    "message.create_fire_control.air_to_ground_no_target"), true);
            setChanged();
            return;
        }
        airToGroundLockedSubLevelId = target.id();
        airToGroundLockedMembers = target.memberIds();
        airToGroundSearchUntil = Long.MIN_VALUE;
        operator.displayClientMessage(Component.translatable(
                "message.create_fire_control.air_to_ground_target_locked"), true);
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
                gunner.displayClientMessage(Component.translatable("message.create_fire_control.optical_lock.cancelled"), true);
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
        gunner.displayClientMessage(Component.translatable("message.create_fire_control.optical_lock.searching"), true);
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

    /** The player allowed to take television control of this controller's missile. */
    public @Nullable ServerPlayer getAircraftMissileOperator() {
        ServerPlayer seated = findSeatedGunner();
        if (seated != null) return seated;
        if (level == null || level.getServer() == null) return null;
        for (ServerPlayer candidate : level.getServer().getPlayerList().getPlayers()) {
            if (RemoteControlTracker.findInputController(candidate) == this) return candidate;
        }
        return null;
    }

    /** Strict television-guidance ownership: remote-control authorization is insufficient. */
    public boolean isSeatedMissileOperator(@Nullable ServerPlayer player) {
        return player != null && isSeat(player.getVehicle());
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
            opticalCameraAcquisitionId = UUID.randomUUID();
            resetOpticalOcclusion();
            opticalSearchUntil = Long.MIN_VALUE;
            gunner.displayClientMessage(Component.translatable("message.create_fire_control.optical_lock.acquired"), true);
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
                opticalCameraAcquisitionId = UUID.randomUUID();
                resetOpticalOcclusion();
                opticalSearchUntil = Long.MIN_VALUE;
                gunner.displayClientMessage(Component.translatable("message.create_fire_control.optical_lock.sable_acquired"), true);
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

    /** Complete connected Sable vehicle used for RWR scan and missile-target matching. */
    public Set<UUID> getOwnControlledSubLevelIds() {
        return ownControlledSubLevelIds();
    }

    public AABB getRwrReceiverBounds() {
        AABB bounds = new AABB(getRadarOrigin(), getRadarOrigin());
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return bounds;
        for (UUID id : getOwnControlledSubLevelIds()) {
            SubLevel part = container.getSubLevel(id);
            if (part == null || !hasUsableRadarBounds(part)) continue;
            AABB partBounds = subLevelWorldBounds(part);
            if (isFinite(partBounds)) bounds = unionBounds(bounds, partBounds);
        }
        return bounds;
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
        for (BindingRef ref : aircraftRocketPods) {
            if (ref.subLevelId() != null) ownIds.add(ref.subLevelId());
        }
        for (BindingRef ref : cannonTurretPods) {
            if (ref.subLevelId() != null) ownIds.add(ref.subLevelId());
        }
        for (BindingRef ref : countermeasureLaunchers) {
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
        nearTerrainAimGuards.clear();
        cachedOwnSubLevelIds = Set.of();
        cachedOwnSubLevelIdsAt = Long.MIN_VALUE;
        cachedAimGroupsAt = Long.MIN_VALUE;
        cachedAimGroups = List.of();
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
            if (!isMissileLockVisible(origin, representative, center, bounds, members, false)) continue;
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
        if (snapshot == null || !isMissileLockVisible(getPodCameraPosition(), snapshot.id(), snapshot.center(),
                snapshot.bounds(), snapshot.memberIds(), false)) {
            clearAirToGroundLock();
            return null;
        }
        airToGroundLockedMembers = snapshot.memberIds();
        return snapshot;
    }

    /** A launch must not reuse a clear path sampled before a wall was placed in this tick. */
    public @Nullable GroundTargetSnapshot getAirToGroundLaunchTargetSnapshot() {
        GroundTargetSnapshot snapshot = getAirToGroundTargetSnapshot();
        if (snapshot == null) return null;
        if (isMissileLockVisible(getPodCameraPosition(), snapshot.id(), snapshot.center(),
                snapshot.bounds(), snapshot.memberIds(), true)) return snapshot;
        if (!isMissileLockPending(snapshot.id())) clearAirToGroundLock();
        return null;
    }

    /** Only IR and air-to-ground locks use this cache; other missile targeting paths stay independent. */
    boolean isMissileLockVisible(@Nullable Vec3 origin, UUID id, Vec3 center, AABB bounds,
                                 Set<UUID> members, boolean fresh) {
        if (level == null || origin == null) return false;
        if (fresh) return missileLockVisibility.checkNow(level, origin, getOwnControlledSubLevelIds(),
                new RadarVisibility.Target(id, center, bounds, members)) == RadarVisibility.Result.CLEAR;
        return missileLockVisibility.scan(level, origin, getOwnControlledSubLevelIds(),
                List.of(new RadarVisibility.Target(id, center, bounds, members))).contains(id);
    }

    boolean isMissileLockPending(UUID id) { return missileLockVisibility.isPending(id); }

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
        return OpticalVisibilityRaycast.isClear(level, origin, target, targetSubLevelId, ignoredSubLevels);
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

    public @Nullable UUID getOpticalLockTargetId() {
        return lockedEntityId != null ? lockedEntityId : lockedSubLevelId;
    }

    public UUID getOpticalCameraAcquisitionId() { return opticalCameraAcquisitionId; }

    public UUID getRadarCameraAcquisitionId() { return radarCameraAcquisitionId; }

    private void setRadarLockedTarget(@Nullable UUID target) {
        RadarLockMemory.clearLock(this);
        if (!java.util.Objects.equals(target, radarLockedSubLevelId)) radarCameraAcquisitionId = UUID.randomUUID();
        radarLockedSubLevelId = target;
        if (level != null) RadarLockMemory.updateLock(this, target, level.getGameTime());
    }

    public TargetLockTelemetry getOpticalLockTelemetry() {
        Vec3 target = opticalLockTarget();
        if (target == null || level == null) {
            return TargetLockTelemetry.UNAVAILABLE;
        }
        Vec3 velocity = Vec3.ZERO;
        if (lockedEntityId != null && level instanceof ServerLevel serverLevel) {
            Entity entity = serverLevel.getEntity(lockedEntityId);
            if (entity != null) {
                velocity = MianbaoMissileCompat.getWorldVelocity(entity).add(
                        CannonBallisticsCompat.sampleBodyVelocity(level,
                                Sable.HELPER.getContaining(entity), target));
            }
        } else if (lockedSubLevelId != null) {
            velocity = getLockTargetVelocity(lockedSubLevelId, target);
        }
        return getTargetLockTelemetry(target, velocity);
    }

    public Vec3 getLockTargetVelocity(UUID subLevelId, Vec3 worldPoint) {
        return level == null ? Vec3.ZERO : CannonBallisticsCompat.sampleBodyVelocity(
                level, SubLevelContainer.getContainer(level).getSubLevel(subLevelId), worldPoint);
    }

    public TargetLockTelemetry getTargetLockTelemetry(
            Vec3 target, Vec3 velocity
    ) {
        if (level == null) return TargetLockTelemetry.UNAVAILABLE;
        BindingRef observer = bindings.get(BindingSlot.AIRCRAFT_RADAR);
        if (observer == null) observer = bindings.get(BindingSlot.RADAR_BEARING);
        BlockPos observerPos = observer == null ? worldPosition : observer.pos();
        Vec3 origin = getRadarOrigin();
        Vec3 ownVelocity = CannonBallisticsCompat.sampleBodyVelocity(
                level, Sable.HELPER.getContaining(level, observerPos), origin);
        Level root = SubLevelContainer.getContainer(level).getLevel();
        double groundHeight = root instanceof ServerLevel serverLevel
                ? MathematicalMissileManager.groundHeight(serverLevel, target.x, target.z)
                : Double.NaN;
        return TargetLockTelemetry.measure(
                origin, ownVelocity, target, velocity, groundHeight);
    }

    /** World-space bounds used to locate the target on the HUD. */
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
        lockedSubLevelPresent = false;
        // Presence is section metadata, not a scan of a potentially sparse bounding volume.
        // Plot holders already exist in memory; this also avoids loading empty plot chunks.
        for (var holder : subLevel.getPlot().getLoadedChunks()) {
            for (var section : holder.getChunk().getSections()) {
                if (section != null && !section.hasOnlyAir()) {
                    lockedSubLevelPresent = true;
                    return true;
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
        if (!headAimEnabled || level == null) {
            return false;
        }
        boolean turretAxesReady = bindings.containsKey(BindingSlot.YAW)
                && bindings.containsKey(BindingSlot.PITCH);
        if (!turretAxesReady && remoteWeaponStations.isEmpty()) {
            return false;
        }
        Vec3 direction = getMeasuredAimDirection();
        return direction != null && direction.lengthSqr() > 1.0e-8;
    }

    public boolean hasRemoteWeaponStations() {
        return !remoteWeaponStations.isEmpty();
    }

    public Component getHeadAimStatusMessage() {
        if (!headAimEnabled) {
            return Component.translatable("message.create_fire_control.head_aim.disabled");
        }
        boolean remoteStationOnly = !remoteWeaponStations.isEmpty()
                && (!bindings.containsKey(BindingSlot.YAW)
                || !bindings.containsKey(BindingSlot.PITCH));
        if (!remoteStationOnly && !bindings.containsKey(BindingSlot.YAW)) {
            return Component.translatable("message.create_fire_control.head_aim.yaw_missing");
        }
        if (!remoteStationOnly && !bindings.containsKey(BindingSlot.PITCH)) {
            return Component.translatable("message.create_fire_control.head_aim.pitch_missing");
        }
        if (!hasAimReferenceBinding()) {
            return Component.translatable("message.create_fire_control.head_aim.weapon_missing");
        }
        if (!isHeadAimReady()) {
            return Component.translatable("message.create_fire_control.head_aim.direction_missing");
        }
        if (remoteStationOnly) {
            return Component.translatable("message.create_fire_control.head_aim.station_ready");
        }
        return remoteWeaponStations.isEmpty()
                ? Component.translatable("message.create_fire_control.head_aim.bearings_ready")
                : Component.translatable("message.create_fire_control.head_aim.bearings_and_station_ready");
    }

    public boolean isScopeEnabled() {
        if (scopeUserId == null || level == null || level.getServer() == null) return false;
        return canUseScope(level.getServer().getPlayerList().getPlayer(scopeUserId));
    }

    /** A gunner needs both live turret axes, a measured muzzle and seat or remote authority. */
    public boolean canUseScope(@Nullable ServerPlayer player) {
        if (player == null || !player.isAlive() || level == null
                || !player.level().dimension().equals(level.dimension())) return false;
        if (!isSeat(player.getVehicle())
                && (!remoteHeadAimEnabled || RemoteControlTracker.findInputController(player) != this)) {
            return false;
        }
        BindingRef yaw = getBinding(BindingSlot.YAW);
        return yaw != null && yaw.resolve(level) instanceof SwivelBearingBlockEntity
                && isHeadAimReady() && getScopePosition() != null;
    }

    private void validateScopeUser() {
        if (scopeUserId != null && !isScopeEnabled()) closeScope();
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
        boolean televisionView = MianbaoOpticalMissileHandler.isViewing(player, this);
        return (isOpticalViewEnabled(player) || televisionView)
                && thermalEnabled && hasInputConfigured(1);
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

    public YawLimits getYawLimits() { return yawLimits; }

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

    public boolean isYawBearingHeadHidden() {
        return yawBearingHeadHidden;
    }

    public boolean isPitchBearingHeadHidden() {
        return pitchBearingHeadHidden;
    }

    public boolean isStabilizerEnabled() {
        return stabilizerEnabled;
    }

    public boolean isStabilizerReactionEnabled() {
        return stabilizerReactionEnabled;
    }

    public double getCannonRecoilMagnitude() {
        return cannonRecoilMagnitude;
    }

    public boolean isMobileShotCompensationEnabled() {
        return mobileShotCompensationEnabled;
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

    public BearingSettings getBearingSettings(BindingSlot slot, BindingRef ref) {
        BearingSettings stored = ref == null ? null : bearingSettings.get(ref);
        if (stored != null) return stored;
        boolean yaw = slot == BindingSlot.YAW;
        return new BearingSettings(
                yaw ? 180.0 : depressionLimitDegrees,
                yaw ? 180.0 : elevationLimitDegrees,
                yaw ? yawSpeedDegreesPerSecond : pitchSpeedDegreesPerSecond,
                yaw ? yawReversed : pitchReversed,
                yaw ? yawBearingHeadHidden : pitchBearingHeadHidden,
                yaw ? yawOffset : pitchOffset, yaw ? yawLimits : YawLimits.UNLIMITED);
    }

    public void setBearingSettings(BindingSlot slot, BlockPos targetPos,
                                    double depression, double elevation, double speed,
                                    boolean reversed, boolean headHidden,
                                    Vec3 offset, YawLimits limits) {
        if (level == null || targetPos == null || offset == null) return;
        List<BindingRef> refs = slot == BindingSlot.YAW ? yawBearings
                : slot == BindingSlot.PITCH ? pitchBearings : List.of();
        refs.stream().filter(ref -> targetPos.equals(ref.pos())).findFirst().ifPresent(ref -> {
            bearingSettings.put(ref, new BearingSettings(
                    Mth.clamp(depression, 0.0, 90.0),
                    Mth.clamp(elevation, 0.0, 89.0),
                    Mth.clamp(speed, 0.0, 720.0), reversed, headHidden,
                    clampOffset(offset), slot == BindingSlot.YAW ? limits : YawLimits.UNLIMITED));
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        });
    }

    public void setPreviewBearingSettings(BindingSlot slot, BindingRef ref,
            com.hooya.stabilizedturret.content.bearing.PreviewBearingSettings settings) {
        List<BindingRef> refs = slot == BindingSlot.YAW ? yawBearings
                : slot == BindingSlot.PITCH ? pitchBearings : List.of();
        if (level == null || !refs.contains(ref) || !ref.isSameTarget(level) || !settings.finite()) return;
        var s = settings.clamped();
        bearingSettings.put(ref, new BearingSettings(s.depression(), s.elevation(), s.speed(),
                s.reversed(), s.headHidden(), s.offset(), s.limits()));
        if (ref.resolve(level) instanceof SwivelBearingBlockEntity bearing) setBearingHeadHidden(bearing, s.headHidden());
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public void applyGlobalBearingSettings(BindingSlot slot, int mask,
                                           double depression, double elevation, double speed,
                                           boolean reversed, boolean headHidden, Vec3 offset, YawLimits limits) {
        if (level == null || offset == null) return;
        // Keep the controller defaults in step with the global operation.  The
        // per-bearing table below is authoritative for existing bindings, but
        // these defaults are used for newly bound bearings and for any legacy
        // reference without an explicit override.
        if (slot == BindingSlot.YAW) {
            yawLimits = new YawLimits((mask & 1) != 0 ? limits.left() : yawLimits.left(),
                    (mask & 2) != 0 ? limits.right() : yawLimits.right(),
                    (mask & 256) != 0 ? limits.centerDirection() : yawLimits.centerDirection());
            if ((mask & 4) != 0) yawSpeedDegreesPerSecond = Mth.clamp(speed, 0.1, 360.0);
            if ((mask & 8) != 0) {
                if (yawReversed != reversed) turretYawDirection.invalidate();
                yawReversed = reversed;
            }
            if ((mask & 16) != 0) yawBearingHeadHidden = headHidden;
            if ((mask & 32) != 0 || (mask & 64) != 0 || (mask & 128) != 0) {
                yawOffset = new Vec3(
                        (mask & 32) != 0 ? clampOffset(offset).x : yawOffset.x,
                        (mask & 64) != 0 ? clampOffset(offset).y : yawOffset.y,
                        (mask & 128) != 0 ? clampOffset(offset).z : yawOffset.z);
            }
        } else if (slot == BindingSlot.PITCH) {
            if ((mask & 1) != 0) depressionLimitDegrees = Mth.clamp(depression, 0.0, 90.0);
            if ((mask & 2) != 0) elevationLimitDegrees = Mth.clamp(elevation, 0.0, 89.0);
            if ((mask & 4) != 0) pitchSpeedDegreesPerSecond = Mth.clamp(speed, 0.1, 360.0);
            if ((mask & 8) != 0) {
                if (pitchReversed != reversed) turretPitchDirection.invalidate();
                pitchReversed = reversed;
            }
            if ((mask & 16) != 0) pitchBearingHeadHidden = headHidden;
            if ((mask & 32) != 0 || (mask & 64) != 0 || (mask & 128) != 0) {
                pitchOffset = new Vec3(
                        (mask & 32) != 0 ? clampOffset(offset).x : pitchOffset.x,
                        (mask & 64) != 0 ? clampOffset(offset).y : pitchOffset.y,
                        (mask & 128) != 0 ? clampOffset(offset).z : pitchOffset.z);
            }
        }
        List<BindingRef> refs = slot == BindingSlot.YAW ? yawBearings
                : slot == BindingSlot.PITCH ? pitchBearings : List.of();
        for (BindingRef ref : refs) {
            BearingSettings current = getBearingSettings(slot, ref);
            bearingSettings.put(ref, new BearingSettings(
                    (mask & 1) != 0 ? Mth.clamp(depression, 0.0, 90.0) : current.depression(),
                    (mask & 2) != 0 ? Mth.clamp(elevation, 0.0, 89.0) : current.elevation(),
                    (mask & 4) != 0 ? Mth.clamp(speed, 0.0, 720.0) : current.speed(),
                    (mask & 8) != 0 ? reversed : current.reversed(),
                    (mask & 16) != 0 ? headHidden : current.headHidden(),
                    new Vec3(
                            (mask & 32) != 0 ? clampOffset(offset).x : current.offset().x,
                            (mask & 64) != 0 ? clampOffset(offset).y : current.offset().y,
                            (mask & 128) != 0 ? clampOffset(offset).z : current.offset().z),
                    slot == BindingSlot.YAW ? new YawLimits(
                            (mask & 1) != 0 ? limits.left() : current.yawLimits().left(),
                            (mask & 2) != 0 ? limits.right() : current.yawLimits().right(),
                            (mask & 256) != 0 ? limits.centerDirection() : current.yawLimits().centerDirection())
                            : current.yawLimits()));
        }
        if (!refs.isEmpty() && (mask & 16) != 0) syncBearingHeadVisibility(refs, headHidden);
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public Vec3 getScopeOffset() {
        return scopeOffset;
    }

    public ScopePreset getScopePreset() {
        return scopePreset;
    }

    public void applySettings(
            double depression,
            double elevation,
            double yawSpeed,
            double pitchSpeed,
            boolean yawReversed,
            boolean pitchReversed,
            boolean yawBearingHeadHidden,
            boolean pitchBearingHeadHidden,
            double cameraHeight,
            double viewAimOffsetX,
            double viewAimOffsetY,
            Vec3 yawOffset,
            Vec3 pitchOffset,
            Vec3 scopeOffset,
            ScopePreset scopePreset,
            boolean stabilizer,
            boolean stabilizerReactionEnabled,
            double cannonRecoilMagnitude,
            boolean mobileShotCompensationEnabled,
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
            double aircraftRadarHudY,
            double rwrRange,
            double rwrScale,
            boolean aircraftRocketSalvoMode
    ) {
        depressionLimitDegrees = Mth.clamp(depression, 0.0, 90.0);
        elevationLimitDegrees = Mth.clamp(elevation, 0.0, 89.0);
        yawSpeedDegreesPerSecond = Mth.clamp(yawSpeed, 0.1, 360.0);
        pitchSpeedDegreesPerSecond = Mth.clamp(pitchSpeed, 0.1, 360.0);
        if (this.yawReversed != yawReversed) turretYawDirection.invalidate();
        if (this.pitchReversed != pitchReversed) turretPitchDirection.invalidate();
        this.yawReversed = yawReversed;
        this.pitchReversed = pitchReversed;
        this.yawBearingHeadHidden = yawBearingHeadHidden;
        this.pitchBearingHeadHidden = pitchBearingHeadHidden;
        thirdPersonHeight = Mth.clamp(cameraHeight, 0.0, 200.0);
        this.viewAimOffsetX = clampAimOffset(viewAimOffsetX);
        this.viewAimOffsetY = clampAimOffset(viewAimOffsetY);
        this.yawOffset = clampOffset(yawOffset);
        this.pitchOffset = clampOffset(pitchOffset);
        this.scopeOffset = clampOffset(scopeOffset);
        this.scopePreset = scopePreset == null ? ScopePreset.DEFAULT : scopePreset;
        if (stabilizerEnabled != stabilizer) {
            remoteWeaponStationHoldDirections.clear();
        }
        stabilizerEnabled = stabilizer;
        this.cannonRecoilMagnitude = CannonRecoilSettings.clamp(cannonRecoilMagnitude);
        if (this.mobileShotCompensationEnabled != mobileShotCompensationEnabled) {
            cachedAimGroupsAt = Long.MIN_VALUE;
        }
        this.mobileShotCompensationEnabled = mobileShotCompensationEnabled;
        if (this.stabilizerReactionEnabled!=stabilizerReactionEnabled) {
            this.stabilizerReactionEnabled=stabilizerReactionEnabled;
            pitchCalibrationAttempted.clear();
            cachedAimGroupsAt=Long.MIN_VALUE;
            applyStabilizerImplementation();
        }
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
        this.rwrRange = Mth.clamp(rwrRange, 200.0, 100_000.0);
        this.rwrScale = Mth.clamp(rwrScale, 40.0, 180.0);
        this.aircraftRocketSalvoMode = aircraftRocketSalvoMode;
        syncBearingHeadVisibility();
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
        return Mth.clamp(Math.round(value * 1000.0) / 1000.0, -20.0, 20.0);
    }

    private static double clampAimOffset(double value) {
        return Mth.clamp(Math.round(value * 1000.0) / 1000.0, -45.0, 45.0);
    }

    public int assignNextFrequency(ItemStack stack) {
        // Direct-selection frequencies are configured in the weapon pages.
        int shortcutCount = FireControlWeaponInputs.FIRST_GROUND_SELECTION * 2;
        if (frequencyConfigCursor >= shortcutCount) frequencyConfigCursor = 0;
        if (frequencyConfigCursor / 2 == FireControlWeaponInputs.LEGACY_AA_FIRE) frequencyConfigCursor += 2;
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
        frequencyConfigCursor = (frequencyConfigCursor + 1) % shortcutCount;
        setChanged();
        return assigned;
    }

    public void setFrequency(int channel, boolean first, ItemStack stack) {
        if (channel < 0 || channel >= WIRELESS_INPUT_ACTION_COUNT) return;
        WirelessPulseReceiver receiver = receiverForChannel(channel);
        if (level != null && !level.isClientSide) {
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, receiver);
        }
        receiver.setFrequency(first, stack);
        if (!hasInputConfigured(channel)) {
            if (channel == 1) {
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

    public CompoundTag inputUiState() {
        removeInvalidBindings();
        CompoundTag state = new CompoundTag();
        saveRemoteKeys(state, "WirelessRemoteKeys", wirelessRemoteKeys);
        saveRemoteKeys(state, "NormalRemoteKeys", normalRemoteKeys);
        saveRemoteKeys(state, "AdvancedRemoteKeys", advancedRemoteKeys);
        ListTag frequencies = new ListTag();
        for (int action=0; action<INPUT_ACTION_COUNT; action++) {
            CompoundTag entry = new CompoundTag();
            entry.put("First", receiverForChannel(action).getFrequency(true).saveOptional(level.registryAccess()));
            entry.put("Second", receiverForChannel(action).getFrequency(false).saveOptional(level.registryAccess()));
            frequencies.add(entry);
        }
        state.put("Frequencies", frequencies);
        state.putIntArray("GroundWeaponActions", boundGroundWeapons().stream()
                .mapToInt(FireControlWeaponInputs::action).toArray());
        state.putIntArray("AircraftWeaponActions", boundAircraftWeapons().stream()
                .mapToInt(FireControlWeaponInputs::action).toArray());
        for (var type : RemoteControllerCompat.Type.values()) state.putBoolean(type.name()+"Available", RemoteTerminalDetector.hasTerminal(this,level,type));
        return state;
    }
    public Component getFrequencyName(int channel, boolean first) {
        if (channel < 0 || channel >= WIRELESS_INPUT_ACTION_COUNT) return Component.literal("-");
        ItemStack stack = receiverForChannel(channel).getFrequency(first);
        return stack.isEmpty() ? Component.literal("-") : stack.getHoverName().copy();
    }

    private WirelessPulseReceiver receiverForChannel(int channel) {
        if (FireControlWeaponInputs.isSelection(channel))
            return weaponSelectionReceivers[channel - FireControlWeaponInputs.FIRST_GROUND_SELECTION];
        return switch (channel) {
            case 1 -> thermalReceiver;
            case 2 -> opticalLockReceiver;
            case 3 -> missileFireReceiver;
            case 4 -> aaMissileFireReceiver;
            case 5 -> radarLockReceiver;
            case 6 -> aircraftMissileFireReceiver;
            case 7 -> aircraftWeaponCycleReceiver;
            case INPUT_ACTION_AIRCRAFT_WEAPON_UP -> aircraftWeaponCycleUpReceiver;
            case 8 -> aircraftTargetCycleReceiver;
            case INPUT_ACTION_GROUND_TARGET_CYCLE -> groundTargetCycleReceiver;
            case 9 -> podViewReceiver;
            case 10 -> airToGroundLockReceiver;
            case 11 -> countermeasureFireReceiver;
            case INPUT_ACTION_FREE_LOOK -> freeLookReceiver;
            case INPUT_ACTION_TELEVISION_GUIDANCE -> televisionGuidanceReceiver;
            case FireControlWeaponInputs.WEAPON_VIEW -> weaponViewReceiver;
            case INPUT_ACTION_MACHINE_GUN_FIRE -> machineGunFireReceiver;
            case INPUT_ACTION_LASER_RANGE -> laserRangeReceiver;
            default -> scopeReceiver;
        };
    }

    private WirelessPulseReceiver[] createWeaponSelectionReceivers() {
        var receivers = new WirelessPulseReceiver[FireControlWeaponInputs.ACTION_COUNT - FireControlWeaponInputs.FIRST_GROUND_SELECTION];
        for (int index = 0; index < receivers.length; index++) {
            final int action = FireControlWeaponInputs.FIRST_GROUND_SELECTION + index;
            receivers[index] = new WirelessPulseReceiver(this, ignored -> selectBoundWeapon(action));
        }
        return receivers;
    }

    private boolean hasInputConfigured(int action) {
        return action >= 0 && action < INPUT_ACTION_COUNT
                && ((action < WIRELESS_INPUT_ACTION_COUNT
                && receiverForChannel(action).hasConfiguredFrequency())
                || (!normalRemoteKeys[action].isEmpty()
                && bindings.containsKey(BindingSlot.REMOTE_CONTROLLER_HUB))
                || (!advancedRemoteKeys[action].isEmpty()
                && bindings.containsKey(BindingSlot.TWEAKED_CONTROLLER_HUB))
                || (!wirelessRemoteKeys[action].isEmpty() && bindings.containsKey(BindingSlot.WIRELESS_CONTROLLER_HUB)));
    }

    public List<String> getRemoteKeys(RemoteControllerCompat.Type type) {
        String[] source = type == RemoteControllerCompat.Type.WIRELESS ? wirelessRemoteKeys : type == RemoteControllerCompat.Type.ADVANCED
                ? advancedRemoteKeys : normalRemoteKeys;
        return List.of(source.clone());
    }

    public boolean setRemoteKey(RemoteControllerCompat.Type type, int action, String key) {
        if (action < 0 || action >= INPUT_ACTION_COUNT) return false;
        String normalized = key == null ? "" : key;
        if (!normalized.isEmpty() && !(type == RemoteControllerCompat.Type.WIRELESS
                ? !com.hooya.stabilizedturret.content.remote.ControllerInput.chordInputs(normalized).isEmpty()
                : DriveByWireCompat.accepts(type, normalized))) return false;
        String[] destination = type == RemoteControllerCompat.Type.WIRELESS ? wirelessRemoteKeys : type == RemoteControllerCompat.Type.ADVANCED
                ? advancedRemoteKeys : normalRemoteKeys;
        destination[action] = normalized;
        if (type == RemoteControllerCompat.Type.WIRELESS) {
            BindingRef hub = getBinding(BindingSlot.WIRELESS_CONTROLLER_HUB);
            if (hub != null) com.hooya.stabilizedturret.content.remote.ControllerSessions.mappingsChanged(level, hub.pos());
        }
        remoteInputPressed[action] = false;
        if (!hasInputConfigured(action)) {
            if (action == 1) {
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
        if (level == null) return;
        BindingRef wirelessHub = getBinding(BindingSlot.WIRELESS_CONTROLLER_HUB);
        boolean wirelessValid = wirelessHub != null && RemoteTerminalDetector.hasTerminal(this, level, RemoteControllerCompat.Type.WIRELESS);
        Map<?, ?> wirelessSignals = wirelessValid ? FireControlInputRouter.signals(level, wirelessHub.pos(), RemoteControllerCompat.Type.WIRELESS) : Map.of();
        BindingRef normalHub = bindings.get(BindingSlot.REMOTE_CONTROLLER_HUB);
        BindingRef advancedHub = bindings.get(BindingSlot.TWEAKED_CONTROLLER_HUB);
        boolean normalValid = normalHub != null
                && RemoteTerminalDetector.hasTerminal(this, level, RemoteControllerCompat.Type.NORMAL);
        boolean advancedValid = advancedHub != null
                && RemoteTerminalDetector.hasTerminal(this, level, RemoteControllerCompat.Type.ADVANCED);
        Map<?, ?> normalSignals = normalValid ? FireControlInputRouter.signals(level, normalHub.pos(), RemoteControllerCompat.Type.NORMAL) : Map.of();
        Map<?, ?> advancedSignals = advancedValid ? FireControlInputRouter.signals(level, advancedHub.pos(), RemoteControllerCompat.Type.ADVANCED) : Map.of();
        // Apply direct selection before firing when both inputs rise on the same tick.
        for (int index = 0; index < INPUT_ACTION_COUNT; index++) {
            int action = (index + FireControlWeaponInputs.FIRST_GROUND_SELECTION) % INPUT_ACTION_COUNT;
            boolean normalActive = normalValid && !normalRemoteKeys[action].isEmpty()
                    && com.hooya.stabilizedturret.content.remote.ControllerInput.signal(normalSignals, normalRemoteKeys[action]) > 0;
            boolean advancedActive = advancedValid && !advancedRemoteKeys[action].isEmpty()
                    && com.hooya.stabilizedturret.content.remote.ControllerInput.signal(advancedSignals, advancedRemoteKeys[action]) > 0;
            boolean wirelessActive = wirelessValid && !wirelessRemoteKeys[action].isEmpty()
                    && com.hooya.stabilizedturret.content.remote.ControllerInput.signal(wirelessSignals, wirelessRemoteKeys[action]) > 0;
            boolean active = normalActive || advancedActive || wirelessActive;
            ServerPlayer operator = null;
            if (active && (action == INPUT_ACTION_FREE_LOOK || action == FireControlWeaponInputs.WEAPON_VIEW
                    || !remoteInputPressed[action])) {
                operator = RemoteControlTracker.findActivePlayer(
                        this, wirelessActive ? RemoteControllerCompat.Type.WIRELESS : normalActive ? RemoteControllerCompat.Type.NORMAL
                                : RemoteControllerCompat.Type.ADVANCED);
            }
            if (active && !remoteInputPressed[action]) {
                activateInput(action, operator);
            }
            if (action == INPUT_ACTION_FREE_LOOK) {
                freeLookUserId = active && operator != null ? operator.getUUID() : null;
            }
            if (action == FireControlWeaponInputs.WEAPON_VIEW) {
                weaponViewUserId = active && operator != null ? operator.getUUID() : null;
            }
            remoteInputPressed[action] = active;
        }
    }

    public boolean isFreeLookHeld(@Nullable ServerPlayer player) {
        return player != null && ((remoteInputPressed[INPUT_ACTION_FREE_LOOK]
                && player.getUUID().equals(freeLookUserId))
                || (freeLookReceiver.isPowered() && isSeat(player.getVehicle())));
    }

    /** Observer authorization is independent of the projectile's position and chunk tracking. */
    public @Nullable ServerPlayer getWeaponViewOperator() {
        if (level == null || level.isClientSide || isRemoved() || level.getServer() == null) return null;
        if (remoteInputPressed[FireControlWeaponInputs.WEAPON_VIEW] && weaponViewUserId != null) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(weaponViewUserId);
            if (player != null && player.isAlive() && player.level().dimension().equals(level.dimension())
                    && RemoteControlTracker.findInputController(player) == this) return player;
        }
        ServerPlayer seated = weaponViewReceiver.isPowered() ? findSeatedGunner() : null;
        return seated != null && seated.isAlive() ? seated : null;
    }

    private void toggleTelevisionGuidanceFromWireless() {
        ServerPlayer seated = findSeatedGunner();
        if (seated != null) {
            MianbaoOpticalMissileHandler.toggleView(seated, this);
        }
    }

    private void tickAircraftRocketSalvo() {
        if (!aircraftRocketSalvoMode
                || selectedAircraftWeapon != MianbaoAircraftCompat.WeaponType.ROCKET_POD
                || !hasInputConfigured(6)) {
            return;
        }
        boolean held = aircraftMissileFireReceiver.isPowered()
                || remoteInputPressed[6];
        if (held) fireAircraftRocketSalvo(false);
    }

    private void activateInput(int action, @Nullable ServerPlayer operator) {
        if (FireControlWeaponInputs.isSelection(action)) {
            selectBoundWeapon(action);
            return;
        }
        switch (action) {
            case 0 -> toggleScope(operator);
            case 1 -> toggleThermal(operator);
            case 2 -> toggleOpticalLock();
            case 3, 4 -> fireSelectedGroundWeapon();
            case 5 -> toggleRadarLockFromWireless();
            case 6 -> fireNextAircraftMissile();
            case 7 -> cycleAircraftWeapon();
            case INPUT_ACTION_AIRCRAFT_WEAPON_UP -> cycleAircraftWeapon(-1);
            case 8 -> cycleAircraftRadarTarget();
            case INPUT_ACTION_GROUND_TARGET_CYCLE -> cycleGroundRadarTarget();
            case 9 -> togglePodView(operator);
            case 10 -> toggleAirToGroundLock(operator);
            case 11 -> fireNextCountermeasure();
            case INPUT_ACTION_TELEVISION_GUIDANCE -> {
                if (operator != null) MianbaoOpticalMissileHandler.toggleView(operator, this);
            }
            case INPUT_ACTION_LASER_RANGE -> {
                // Native remote signals must work even if another controller/key
                // handler consumed the client's fallback input. A queued exact
                // client snapshot still takes priority over this live-ray fallback.
                requestLaserRange(operator, null);
            }
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
        if (virtualScopeEnabled) return virtualScopeGeometry().position();
        BindingRef pitch = getScopePitchBinding();
        if (pitch == null
                || !(pitch.resolve(level) instanceof SwivelBearingBlockEntity bearing)) return null;
        // Keep the sight in the bearing's parent/turret frame. Its default
        // anchor is the bearing centre plus one block toward the bearing head.
        Direction facing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
        return Sable.HELPER.projectOutOfSubLevel(
                bearing.getLevel(),
                Vec3.atCenterOf(bearing.getBlockPos().relative(facing)).add(scopeOffset));
    }

    public List<BindingRef> getScopeReferenceBindings() {
        BindingRef pitch = getScopePitchBinding();
        return pitch == null ? List.of() : List.of(pitch);
    }

    /** Use the same turret group as the scope camera, never a different bound gun. */
    public int getScopeApfsdsReserve() {
        BindingRef pitch = getScopePitchBinding();
        if (level != null && pitch != null) {
            for (TurretGroup group : turretGroups()) {
                if (!pitch.equals(group.pitch())) continue;
                for (BindingRef mount : group.cannonMounts()) {
                    if (CannonMountCompat.hasAssembledCannon(level, mount))
                        return com.hooya.stabilizedturret.content.loader.MzLoaderAutomation
                                .getScopeApfsdsReserve(level, mount);
                }
            }
        }
        return -1;
    }

    public ScopeOrientationPayload getScopeOrientation() {
        BindingRef pitch = getScopePitchBinding();
        if (level != null && pitch != null) {
            for (TurretGroup group : turretGroups()) {
                if (!pitch.equals(group.pitch())) continue;
                AimReference aim = measureAimReference(group);
                if (aim == null || group.yaws().isEmpty()) continue;
                BindingRef yaw = group.yaws().getFirst();
                BindingRef source = aimReferences(group).stream()
                        .filter(ref -> measureAimReference(ref) != null).findFirst().orElse(null);
                UUID barrelBody = null;
                if (source != null) {
                    barrelBody = CannonMountCompat.getMountedCannonSubLevelId(level, source);
                    if (barrelBody == null) {
                        SubLevel live = Sable.HELPER.getContaining(level, source.pos());
                        if (live != null && !live.isRemoved()) barrelBody = live.getUniqueId();
                    }
                }
                return ScopeOrientationResolver.measure(level, yaw, aim.direction(), barrelBody,
                        getBearingSettings(BindingSlot.YAW, yaw).yawLimits());
            }
        }
        return ScopeOrientationPayload.inactive();
    }

    public boolean isVirtualScopeEnabled() {
        return virtualScopeEnabled;
    }

    public String virtualScopeUnavailableReason() {
        return virtualScopeGeometry().unavailableReason();
    }

    public boolean setVirtualScopeEnabled(boolean enabled) {
        if (enabled && !virtualScopeUnavailableReason().isEmpty()) return false;
        virtualScopeEnabled = enabled;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        return true;
    }

    private record VirtualScopeSolution(@Nullable Vec3 position, String unavailableReason) {}

    private VirtualScopeSolution virtualScopeGeometry() {
        List<BindingRef> pitches = new ArrayList<>(getPitchBearingBindings());
        if (pitches.isEmpty()) {
            BindingRef fallback = getScopePitchBinding();
            if (fallback != null) pitches.add(fallback);
        }
        List<VirtualScopeGeometry.Axis> bearingAxes = new ArrayList<>();
        List<VirtualScopeGeometry.Axis> cameraAxes = new ArrayList<>();
        for (BindingRef pitch : pitches) {
            if (level == null || !(pitch.resolve(level) instanceof SwivelBearingBlockEntity bearing)
                    || !bearing.isAssembled()) {
                return new VirtualScopeSolution(null, "virtual_scope_requires_pitch");
            }
            Direction facing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
            Vec3 center = Sable.HELPER.projectOutOfSubLevel(bearing.getLevel(), Vec3.atCenterOf(bearing.getBlockPos()));
            Vec3 pivot = Sable.HELPER.projectOutOfSubLevel(bearing.getLevel(), Vec3.atCenterOf(bearing.getBlockPos().relative(facing)));
            VirtualScopeGeometry.Axis bearingAxis = new VirtualScopeGeometry.Axis(pivot, pivot.subtract(center));
            bearingAxes.add(bearingAxis);
            List<VirtualScopeGeometry.Axis> cannonAxes = cannonMounts.stream()
                    .filter(mount -> bearingChildDistance(mount, pitch) != Integer.MAX_VALUE)
                    .map(mount -> CannonMountCompat.getMountedCannonPitchAxis(level, mount))
                    .filter(java.util.Objects::nonNull).toList();
            VirtualScopeGeometry.Axis cannonAxis = VirtualScopeGeometry.nearestCannonAxis(center, cannonAxes);
            cameraAxes.add(VirtualScopeGeometry.cameraAxis(bearingAxis, cannonAxis));
        }
        if (bearingAxes.isEmpty()) return new VirtualScopeSolution(null, "virtual_scope_requires_pitch");
        if (VirtualScopeGeometry.midpoint(bearingAxes) == null) {
            return new VirtualScopeSolution(null, "virtual_scope_not_coaxial");
        }
        VirtualScopeGeometry.Axis result = VirtualScopeGeometry.midpoint(cameraAxes);
        return result == null ? new VirtualScopeSolution(null, "virtual_scope_not_coaxial")
                : new VirtualScopeSolution(result.pivot(), "");
    }

    /** Only the rotating turret and barrel bodies, never the carrier below the yaw bearing. */
    public List<UUID> getVirtualScopeHiddenSubLevels() {
        if (!(level instanceof ServerLevel server)) return List.of();
        Set<UUID> hidden = new HashSet<>();
        Set<UUID> supports = new HashSet<>();
        for (BindingRef yaw : getYawBearingBindings()) {
            if (yaw.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
                SubLevel support = Sable.HELPER.getContaining(bearing);
                if (support != null) supports.add(support.getUniqueId());
            }
        }
        for (BindingRef ref : getAllFireControlBearingBindings()) {
            if (ref.resolve(level) instanceof SwivelBearingBlockEntity bearing && bearing.isAssembled()
                    && bearing.getSubLevelID() != null) hidden.add(bearing.getSubLevelID());
        }
        boolean changed;
        do {
            int previous = hidden.size();
            hidden.addAll(SynaxisWeldCompat.expandWeldedSublevels(server, List.copyOf(hidden)));
            for (UUID id : List.copyOf(hidden)) {
                SubLevel body = SubLevelContainer.getContainer(level).getSubLevel(id);
                if (body == null || body.isRemoved()) continue;
                for (var actor : body.getPlot().getBlockEntityActors()) {
                    if (actor instanceof SwivelBearingBlockEntity bearing && bearing.isAssembled()
                            && bearing.getSubLevelID() != null) hidden.add(bearing.getSubLevelID());
                }
            }
            hidden.removeAll(supports);
            changed = hidden.size() != previous;
        } while (changed);
        return hidden.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    public List<UUID> getVirtualScopeHiddenEntities() {
        if (level == null) return List.of();
        return cannonMounts.stream().map(mount -> CannonMountCompat.getMountedCannonEntity(level, mount))
                .filter(java.util.Objects::nonNull).map(Entity::getUUID).distinct().toList();
    }

    private @Nullable BindingRef getScopePitchBinding() {
        if (level == null) return null;
        BindingRef groupedPitch = turretGroups().stream()
                .filter(group -> group.pitch() != null && !aimReferences(group).isEmpty())
                .map(TurretGroup::pitch)
                .filter(ref -> ref.resolve(level) instanceof SwivelBearingBlockEntity)
                .findFirst().orElse(null);
        if (groupedPitch != null) return groupedPitch;
        BindingRef pitch = getBinding(BindingSlot.PITCH);
        return pitch != null && pitch.resolve(level) instanceof SwivelBearingBlockEntity ? pitch : null;
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
        if (virtualScopeEnabled && !virtualScopeUnavailableReason().isEmpty()) {
            setVirtualScopeEnabled(false);
            closeScope();
        }
        validateScopeUser();
        if (!laserRanges.isEmpty()) {
            Set<BindingRef> weapons = new HashSet<>();
            for (TurretGroup group : turretGroups()) weapons.addAll(aimReferences(group));
            if (laserRanges.keySet().retainAll(weapons)) setChanged();
        }
        applyStabilizerImplementation();
        launchedCountermeasures.removeIf(id -> !MianbaoCountermeasureManager.isTrackedId(id));
        syncBearingHeadVisibility();
        tickRemoteInputs();
        applyPendingLaserRange();
        tickMachineGunFire();
        tickAircraftRocketSalvo();
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

        maintainBoundBearings(!gunnerAiming, stabilizerEnabled);
        calibratePitchFromCannon();

        boolean hasGunnerAim = gunnerAiming && (hasAimTarget || hasAimDirection);
        if (hasGunnerAim) {
            applyAimDirection();
        }
        maintainRemoteWeaponStations(hasGunnerAim);
        updatePodModel();
        updatePodControlledCannon();
        updateCannonTurretPods();
        AirborneWeaponViewHandler.observeControllerInput(this);
    }

    public void bind(BindingSlot slot, BindingRef target) {
        if (slot == BindingSlot.LASER_DESIGNATOR) return;
        BindingRef previousKeyboardHub = slot == BindingSlot.WIRELESS_CONTROLLER_HUB
                ? getBinding(BindingSlot.WIRELESS_CONTROLLER_HUB) : null;
        if (slot == BindingSlot.BEARING) {
            if (!unassignedBearings.contains(target)
                    && bindings.values().stream().noneMatch(target::equals)) {
                unassignedBearings.add(target);
            }
        } else if (slot == BindingSlot.YAW || slot == BindingSlot.PITCH) {
            List<BindingRef> axis = slot == BindingSlot.YAW ? yawBearings : pitchBearings;
            if (!axis.contains(target)) axis.add(target);
            bindings.putIfAbsent(slot, target); // legacy single-value readers
            unassignedBearings.remove(target);
        } else if (slot == BindingSlot.CANNON_MOUNT) {
            if (!cannonMounts.contains(target)) cannonMounts.add(target);
        } else if (slot == BindingSlot.MISSILE_LAUNCHER) {
            if (!missileLaunchers.contains(target)) {
                missileLaunchers.add(target);
            }
            registerMissileDirectionAnchor(target);
        } else if (slot == BindingSlot.AA_MISSILE_LAUNCHER) {
            if (!aaMissileLaunchers.contains(target)) aaMissileLaunchers.add(target);
            registerMissileDirectionAnchor(target);
        } else if (slot == BindingSlot.AIRCRAFT_MISSILE_RACK) {
            if (!aircraftMissileRacks.contains(target)) aircraftMissileRacks.add(target);
        } else if (slot == BindingSlot.AIRCRAFT_ROCKET_POD) {
            if (!aircraftRocketPods.contains(target)) aircraftRocketPods.add(target);
        } else if (slot == BindingSlot.CANNON_TURRET_POD) {
            if (!cannonTurretPods.contains(target)) cannonTurretPods.add(target);
        } else if (slot == BindingSlot.COUNTERMEASURE_LAUNCHER) {
            if (!countermeasureLaunchers.contains(target)) {
                countermeasureLaunchers.add(target);
            }
        } else if (slot == BindingSlot.REMOTE_WEAPON_STATION) {
            if (!remoteWeaponStations.contains(target)) {
                remoteWeaponStations.add(target);
            }
            if (level != null) {
                Vec3 measured = MianbaoRemoteWeaponStationCompat
                        .getMuzzleDirection(level, target);
                if (measured != null && measured.lengthSqr() > 1.0e-8) {
                    remoteWeaponStationHoldDirections.put(
                            target, measured.normalize());
                }
            }
        } else if (slot == BindingSlot.IFF_TRANSPONDER) {
            if (!iffTransponders.contains(target)) iffTransponders.add(target);
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
        if (slot == BindingSlot.YAW || slot == BindingSlot.PITCH) syncLegacyBearingBindings();
        if (slot == BindingSlot.PITCH) pitchCalibrationAttempted.remove(target);
        invalidateOwnSubLevelCache();
        if ((slot == BindingSlot.YAW || slot == BindingSlot.PITCH)
                && level != null
                && target.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
            // Apply the bound-body defaults immediately instead of waiting for
            // the controller's next server tick.
            BearingStabilizer.setImplementation(bearing,stabilizerReactionEnabled);
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
            setBearingHeadHidden(bearing, isYawSlot(slot)
                    ? yawBearingHeadHidden : pitchBearingHeadHidden);
        }
        if (slot == BindingSlot.WIRELESS_CONTROLLER_HUB) {
            if (previousKeyboardHub != null) com.hooya.stabilizedturret.content.remote.ControllerSessions
                    .mappingsChanged(level, previousKeyboardHub.pos());
            com.hooya.stabilizedturret.content.remote.ControllerSessions.mappingsChanged(level, target.pos());
        }
        if (slot == BindingSlot.CANNON_MOUNT || isMissileDirectionAnchorSlot(slot)
                || slot == BindingSlot.FORWARD_INDICATOR
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
            if (isPodBearingRole(slot) || slot == BindingSlot.LASER_DESIGNATOR) continue;
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
                    : slot == BindingSlot.AIRCRAFT_ROCKET_POD
                    ? !aircraftRocketPods.isEmpty()
                    : slot == BindingSlot.CANNON_TURRET_POD
                    ? !cannonTurretPods.isEmpty()
                    : slot == BindingSlot.COUNTERMEASURE_LAUNCHER
                    ? !countermeasureLaunchers.isEmpty()
                    : slot == BindingSlot.REMOTE_WEAPON_STATION
                    ? !remoteWeaponStations.isEmpty()
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
        if (slot == BindingSlot.WIRELESS_CONTROLLER_HUB && level != null && !level.isClientSide) {
            BindingRef previous = bindings.get(slot);
            BindingRef refreshed = previous == null ? null : previous.refreshWirelessTerminal(level);
            if (refreshed != previous) {
                bindings.put(slot, refreshed);
                setChanged();
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
            return refreshed;
        }
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
        if (slot == BindingSlot.AIRCRAFT_ROCKET_POD) {
            return aircraftRocketPods.stream().findFirst().orElse(null);
        }
        if (slot == BindingSlot.CANNON_TURRET_POD) {
            return cannonTurretPods.stream().findFirst().orElse(null);
        }
        if (slot == BindingSlot.COUNTERMEASURE_LAUNCHER) {
            return countermeasureLaunchers.stream().findFirst().orElse(null);
        }
        if (slot == BindingSlot.REMOTE_WEAPON_STATION) {
            return remoteWeaponStations.stream().findFirst().orElse(null);
        }
        if (slot == BindingSlot.IFF_TRANSPONDER) {
            return iffTransponders.stream().findFirst().orElse(null);
        }
        if (slot == BindingSlot.YAW) return yawBearings.stream().findFirst().orElse(bindings.get(slot));
        if (slot == BindingSlot.PITCH) return pitchBearings.stream().findFirst().orElse(bindings.get(slot));
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
        List<BindingRef> sourceAxis = sourceRole == BindingSlot.YAW ? yawBearings : pitchBearings;
        List<BindingRef> targetAxis = role == BindingSlot.YAW ? yawBearings : pitchBearings;
        if (sourceRole == BindingSlot.BEARING) {
            if (index < 0 || index >= unassignedBearings.size()) return false;
            selected = unassignedBearings.get(index);
        } else {
            if (index < 0 || index >= sourceAxis.size()) return false;
            selected = sourceAxis.get(index);
        }
        if (selected == null) return false;
        if (!selected.pos().equals(expectedPos)
                || !(selected.resolve(level) instanceof SwivelBearingBlockEntity selectedBearing)) {
            return false;
        }

        if (sourceRole == BindingSlot.BEARING) {
            unassignedBearings.remove(index);
        } else {
            sourceAxis.remove(selected);
            if (sourceAxis.isEmpty()) bindings.remove(sourceRole);
            BearingStabilizer.disable(selectedBearing);
            setBearingHeadHidden(selectedBearing, false);
        }
        if (!targetAxis.contains(selected)) targetAxis.add(selected);
        syncLegacyBearingBindings();
        if (role == BindingSlot.PITCH) pitchCalibrationAttempted.remove(selected);

        BearingStabilizer.setImplementation(selectedBearing,stabilizerReactionEnabled);
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
        setBearingHeadHidden(selectedBearing, isYawSlot(role)
                ? yawBearingHeadHidden : pitchBearingHeadHidden);
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

    private void syncLegacyBearingBindings() {
        if (yawBearings.isEmpty()) bindings.remove(BindingSlot.YAW);
        else bindings.put(BindingSlot.YAW, yawBearings.get(0));
        if (pitchBearings.isEmpty()) bindings.remove(BindingSlot.PITCH);
        else bindings.put(BindingSlot.PITCH, pitchBearings.get(0));
        cachedTurretGroupsAt = Long.MIN_VALUE;
    }

    private static boolean isMissileDirectionAnchorSlot(BindingSlot slot) {
        return slot == BindingSlot.MISSILE_LAUNCHER
                || slot == BindingSlot.AA_MISSILE_LAUNCHER;
    }

    private void registerMissileDirectionAnchor(BindingRef ref) {
        if (!missileDirectionAnchors.contains(ref)) missileDirectionAnchors.add(ref);
        cachedTurretGroupsAt = Long.MIN_VALUE;
    }

    private boolean isBoundMissileDirectionAnchor(BindingRef ref) {
        return missileLaunchers.contains(ref)
                || aaMissileLaunchers.contains(ref);
    }

    public List<BindingRef> getMissileLaunchers() {
        return List.copyOf(missileLaunchers);
    }

    /** Use the same first usable cannon as the physical cannon sight. Empty/unassembled mounts do not qualify. */
    public @Nullable BindingRef getPrimaryLockLeadCannon() {
        if (level == null) return null;
        for (TurretGroup group : turretGroups()) {
            for (BindingRef ref : aimReferences(group)) {
                if (cannonMounts.contains(ref) && CannonBallisticsCompat.getCurrentMuzzlePosition(level, ref) != null)
                    return ref;
            }
        }
        return cannonMounts.stream().filter(ref -> ref.isSameTarget(level)
                && CannonBallisticsCompat.getCurrentMuzzlePosition(level, ref) != null).findFirst().orElse(null);
    }

    /** Present the required bore in exactly the sight coordinates used by measuredCannonSightDirection. */
    public @Nullable Vec3 getCannonLockLeadSightPoint(BindingRef ref, CannonBallisticsCompat.ShotProfile profile,
            CannonLeadSolver.Solution solution) {
        return getCannonLockLeadSightPoint(ref, profile, solution, null);
    }

    public @Nullable Vec3 getCannonLockLeadSightPoint(BindingRef ref, CannonBallisticsCompat.ShotProfile profile,
            CannonLeadSolver.Solution solution, @Nullable Vec3 lockDepthTarget) {
        Vec3 direction = solution.launchDirection();
        FiniteAimTarget geometry = FiniteAimTarget.from(level, ref, profile, profile.muzzle());
        if (geometry != null) {
            Vec3 sight = profile.currentForward();
            if (stabilizerReactionEnabled)
                sight = BearingStabilizer.withoutMuzzleCompensation(level, geometry.bodyId(), sight);
            sight = LaserRangeCompensation.raise(sight, -savedLaserRange(ref).elevationRadians());
            direction = CannonSightDirection.direction(direction, profile.currentForward(), sight);
        }
        if (lockDepthTarget != null && hasAimRay) {
            Vec3 finite = OpticalLockAimGeometry.atTargetDepth(
                    profile.muzzle(), direction, lastAimRayOrigin, lockDepthTarget);
            if (finite != null) return finite;
        }
        // The ring and the cannon sight share the muzzle origin, retaining finite-range parallax.
        return profile.muzzle().add(direction.normalize().scale(solution.intercept().distanceTo(profile.muzzle())));
    }

    public List<BindingRef> getCannonMountBindings() {
        return List.copyOf(cannonMounts);
    }

    public List<BindingRef> getYawBearingBindings() {
        return List.copyOf(yawBearings);
    }

    public List<BindingRef> getPitchBearingBindings() {
        return List.copyOf(pitchBearings);
    }

    public List<BindingRef> getAaMissileLaunchers() {
        return List.copyOf(aaMissileLaunchers);
    }

    public List<BindingRef> getAircraftMissileRacks() { return List.copyOf(aircraftMissileRacks); }
    public List<BindingRef> getAircraftRocketPods() { return List.copyOf(aircraftRocketPods); }
    public List<BindingRef> getCannonTurretPods() { return List.copyOf(cannonTurretPods); }
    public List<BindingRef> getCountermeasureLaunchers() {
        return List.copyOf(countermeasureLaunchers);
    }
    public List<BindingRef> getRemoteWeaponStations() {
        return List.copyOf(remoteWeaponStations);
    }
    public List<BindingRef> getIffTransponderBindings() {
        return List.copyOf(iffTransponders);
    }
    public List<BindingRef> getAllFireControlBearingBindings() {
        List<BindingRef> result = new ArrayList<>();
        result.addAll(yawBearings);
        result.addAll(pitchBearings);
        if (result.isEmpty()) {
            BindingRef yaw = bindings.get(BindingSlot.YAW);
            BindingRef pitch = bindings.get(BindingSlot.PITCH);
            if (yaw != null) result.add(yaw);
            if (pitch != null) result.add(pitch);
        }
        return List.copyOf(result);
    }
    public MianbaoAircraftCompat.WeaponType getSelectedAircraftWeapon() { return selectedAircraftWeapon; }
    public int getAircraftAmmoCount(MianbaoAircraftCompat.WeaponType type) {
        if (level == null) return 0;
        if (type == MianbaoAircraftCompat.WeaponType.ROCKET_POD) {
            int rockets = 0;
            for (BindingRef pod : aircraftRocketPods) {
                rockets += MianbaoRocketPodCompat.ammoCount(level, pod);
            }
            return rockets;
        }
        int count = 0;
        for (BindingRef rack : aircraftMissileRacks) count += MianbaoAircraftRackCompat.ammoCount(level, rack, type);
        return count;
    }

    public RocketImpactPrediction getRocketImpactPrediction() {
        if (level == null
                || selectedAircraftWeapon != MianbaoAircraftCompat.WeaponType.ROCKET_POD
                || getAircraftAmmoCount(
                MianbaoAircraftCompat.WeaponType.ROCKET_POD) <= 0) {
            rocketImpactPrediction = RocketImpactPrediction.unavailable();
            rocketImpactPredictionTick = level == null ? Long.MIN_VALUE : level.getGameTime();
            return rocketImpactPrediction;
        }
        long now = level.getGameTime();
        if (rocketImpactPredictionTick != Long.MIN_VALUE
                && now >= rocketImpactPredictionTick
                && now - rocketImpactPredictionTick < 2L) {
            return rocketImpactPrediction;
        }
        rocketImpactPredictionTick = now;

        BindingRef selectedPod = null;
        int size = aircraftRocketPods.size();
        int start = size == 0 ? 0 : Math.floorMod(aircraftRocketFireCursor, size);
        for (int offset = 0; offset < size; offset++) {
            BindingRef candidate = aircraftRocketPods.get((start + offset) % size);
            if (MianbaoRocketPodCompat.ammoCount(level, candidate) > 0) {
                selectedPod = candidate;
                break;
            }
        }
        Vec3 origin = selectedPod == null ? null
                : MianbaoRocketPodCompat.muzzle(level, selectedPod);
        Vec3 direction = selectedPod == null ? null
                : MianbaoRocketPodCompat.launchDirection(level, selectedPod);
        // Sight-guided rounds follow the shooter's changing view and have no fixed ballistic impact.
        if (origin == null || direction == null || MianbaoRocketPodCompat.hasVisualGuidedRound(level, selectedPod)
                || !(SubLevelContainer.getContainer(level).getLevel() instanceof ServerLevel root)) {
            rocketImpactPrediction = RocketImpactPrediction.unavailable();
            return rocketImpactPrediction;
        }
        Vec3 initialVelocity = direction.scale(MianbaoRocketPodCompat.muzzleSpeed(level, selectedPod))
                .add(MianbaoRocketPodCompat.carrierVelocityAtMuzzle(level, selectedPod));
        double firstDrag = MianbaoRocketPodCompat.firstTickDrag(level, selectedPod);
        double flightDrop = MianbaoRocketPodCompat.flightDrop(level, selectedPod);
        double firstGravity = MianbaoRocketPodCompat.firstGravity(level, selectedPod);
        Vec3 impact = MianbaoRocketPodCompat.predictImpact(
                root,
                origin,
                initialVelocity,
                firstDrag,
                flightDrop,
                firstGravity,
                ownControlledSubLevelIds());
        rocketImpactPrediction = new RocketImpactPrediction(
                impact != null,
                impact == null ? Vec3.ZERO : impact,
                true,
                origin,
                initialVelocity,
                firstDrag,
                flightDrop,
                firstGravity);
        return rocketImpactPrediction;
    }

    /** Match the next missile release; retain a bound empty rack's axes after the last shot. */
    public @Nullable BindingRef getAntiRadiationSearchRack() {
        if (level == null || !isAntiRadiationWeaponSelected()) return null;
        int size = aircraftMissileRacks.size();
        int start = size == 0 ? 0 : Math.floorMod(aircraftMissileFireCursor, size);
        BindingRef empty = null;
        for (int offset = 0; offset < size; offset++) {
            BindingRef rack = aircraftMissileRacks.get((start + offset) % size);
            if (MianbaoAircraftRackCompat.weaponAt(level, rack) == MianbaoAircraftCompat.WeaponType.ANTI_RADIATION) return rack;
            if (empty == null && rack.isSameTarget(level) && level.hasChunkAt(rack.pos())
                    && BuiltInRegistries.BLOCK.getKey(level.getBlockState(rack.pos()).getBlock()).toString()
                    .equals("mianbaos_modernwarfare:rack")) empty = rack;
        }
        return empty;
    }

    /** Uses the same round-robin cursor as the single-release input. */
    public @Nullable BindingRef getNextAircraftBombRack() {
        if (level == null || !MianbaoBombCompat.isBomb(selectedAircraftWeapon)) return null;
        int size = aircraftMissileRacks.size();
        int start = size == 0 ? 0 : Math.floorMod(aircraftMissileFireCursor, size);
        for (int offset = 0; offset < size; offset++) {
            BindingRef rack = aircraftMissileRacks.get((start + offset) % size);
            if (MianbaoAircraftRackCompat.ammoCount(level, rack, selectedAircraftWeapon) > 0) return rack;
        }
        return null;
    }

    public com.hooya.stabilizedturret.network.BombSightPayload getBombSightPayload() {
        long now = level == null ? Long.MIN_VALUE : level.getGameTime();
        int ammo = getAircraftAmmoCount(selectedAircraftWeapon);
        if (level != null && bombSightTick != Long.MIN_VALUE && now >= bombSightTick && now - bombSightTick < 2L
                && bombSight.selectedWeapon() == selectedAircraftWeapon.ordinal()
                && bombSight.selectedAmmo() == ammo && bombSightCursor == aircraftMissileFireCursor) return bombSight;
        bombSightTick = now;
        bombSightCursor = aircraftMissileFireCursor;
        bombSight = com.hooya.stabilizedturret.network.BombSightPayload.from(this, ownControlledSubLevelIds());
        return bombSight;
    }

    /** Returns the traverse-group index used by the controller settings UI. */
    public int getBearingGroupIndex(BindingSlot slot, int index) {
        Map<Integer, Integer> groups = bearingGroupMap();
        if (slot == BindingSlot.YAW) {
            return groups.getOrDefault(index, index >= 0 ? index : -1);
        }
        if (slot != BindingSlot.PITCH || index < 0 || index >= pitchBearings.size()) {
            return -1;
        }
        List<BindingRef> sortedYaw = yawBearings.stream().sorted(bindingOrder()).toList();
        List<BindingRef> parents = parentBearings(pitchBearings.get(index), sortedYaw);
        if (parents.isEmpty()) return sortedYaw.isEmpty() ? 0 : index % sortedYaw.size();
        return groups.getOrDefault(yawBearings.indexOf(parents.get(0)), 0);
    }

    private Map<Integer, Integer> bearingGroupMap() {
        if (yawBearings.isEmpty()) return Map.of();
        int[] parent = new int[yawBearings.size()];
        for (int index = 0; index < parent.length; index++) parent[index] = index;
        List<BindingRef> sortedYaw = yawBearings.stream().sorted(bindingOrder()).toList();
        for (BindingRef pitch : pitchBearings) {
            List<BindingRef> parents = parentBearings(pitch, sortedYaw);
            if (parents.size() < 2) continue;
            int first = yawBearings.indexOf(parents.get(0));
            for (int index = 1; index < parents.size(); index++) {
                int other = yawBearings.indexOf(parents.get(index));
                unionBearingGroups(parent, first, other);
            }
        }
        Map<Integer, Integer> roots = new HashMap<>();
        Map<Integer, Integer> result = new HashMap<>();
        for (int index = 0; index < parent.length; index++) {
            int root = findBearingGroup(parent, index);
            roots.putIfAbsent(root, roots.size());
            result.put(index, roots.get(root));
        }
        return result;
    }

    private static int findBearingGroup(int[] parent, int value) {
        int root = value;
        while (parent[root] != root) root = parent[root];
        while (parent[value] != value) {
            int next = parent[value];
            parent[value] = root;
            value = next;
        }
        return root;
    }

    private static void unionBearingGroups(int[] parent, int first, int second) {
        int rootFirst = findBearingGroup(parent, first);
        int rootSecond = findBearingGroup(parent, second);
        if (rootFirst != rootSecond) parent[rootSecond] = rootFirst;
    }

    public List<BoundDevice> getBoundDevices() {
        List<BoundDevice> devices = new ArrayList<>();
        for (BindingSlot slot : BindingSlot.values()) {
            if (slot == BindingSlot.LASER_DESIGNATOR) continue;
            if (slot == BindingSlot.BEARING) {
                for (int index = 0; index < unassignedBearings.size(); index++) {
                    devices.add(new BoundDevice(slot, index, unassignedBearings.get(index)));
                }
            } else if (slot == BindingSlot.YAW || slot == BindingSlot.PITCH) {
                List<BindingRef> axis = slot == BindingSlot.YAW ? yawBearings : pitchBearings;
                for (int index = 0; index < axis.size(); index++) {
                    devices.add(new BoundDevice(slot, index, axis.get(index)));
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
            } else if (slot == BindingSlot.AIRCRAFT_ROCKET_POD) {
                for (int index = 0; index < aircraftRocketPods.size(); index++) {
                    devices.add(new BoundDevice(slot, index, aircraftRocketPods.get(index)));
                }
            } else if (slot == BindingSlot.CANNON_TURRET_POD) {
                for (int index = 0; index < cannonTurretPods.size(); index++) {
                    devices.add(new BoundDevice(slot, index, cannonTurretPods.get(index)));
                }
            } else if (slot == BindingSlot.COUNTERMEASURE_LAUNCHER) {
                for (int index = 0; index < countermeasureLaunchers.size(); index++) {
                    devices.add(new BoundDevice(
                            slot, index, countermeasureLaunchers.get(index)));
                }
            } else if (slot == BindingSlot.REMOTE_WEAPON_STATION) {
                for (int index = 0; index < remoteWeaponStations.size(); index++) {
                    devices.add(new BoundDevice(slot, index, remoteWeaponStations.get(index)));
                }
            } else if (slot == BindingSlot.IFF_TRANSPONDER) {
                for (int index = 0; index < iffTransponders.size(); index++) {
                    devices.add(new BoundDevice(slot, index, iffTransponders.get(index)));
                }
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
        } else if (slot == BindingSlot.YAW || slot == BindingSlot.PITCH) {
            List<BindingRef> axis = slot == BindingSlot.YAW ? yawBearings : pitchBearings;
            if (index < 0 || index >= axis.size()) return false;
            removed = axis.get(index);
            if (!removed.pos().equals(expectedPos)) return false;
            axis.remove(index);
            if (axis.isEmpty()) bindings.remove(slot);
            else bindings.put(slot, axis.get(0));
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
        } else if (slot == BindingSlot.AIRCRAFT_ROCKET_POD) {
            if (index < 0 || index >= aircraftRocketPods.size()) return false;
            BindingRef candidate = aircraftRocketPods.get(index);
            if (!candidate.pos().equals(expectedPos)) return false;
            removed = aircraftRocketPods.remove(index);
            aircraftRocketFireCursor = aircraftRocketPods.isEmpty()
                    ? 0 : Math.floorMod(aircraftRocketFireCursor, aircraftRocketPods.size());
        } else if (slot == BindingSlot.CANNON_TURRET_POD) {
            if (index < 0 || index >= cannonTurretPods.size()) return false;
            BindingRef candidate = cannonTurretPods.get(index);
            if (!candidate.pos().equals(expectedPos)) return false;
            removed = cannonTurretPods.remove(index);
        } else if (slot == BindingSlot.COUNTERMEASURE_LAUNCHER) {
            if (index < 0 || index >= countermeasureLaunchers.size()) return false;
            BindingRef candidate = countermeasureLaunchers.get(index);
            if (!candidate.pos().equals(expectedPos)) return false;
            removed = countermeasureLaunchers.remove(index);
            countermeasureFireCursor = countermeasureLaunchers.isEmpty()
                    ? 0 : Math.floorMod(
                    countermeasureFireCursor, countermeasureLaunchers.size());
        } else if (slot == BindingSlot.REMOTE_WEAPON_STATION) {
            if (index < 0 || index >= remoteWeaponStations.size()) return false;
            BindingRef candidate = remoteWeaponStations.get(index);
            if (!candidate.pos().equals(expectedPos)) return false;
            removed = remoteWeaponStations.remove(index);
            remoteWeaponStationHoldDirections.remove(candidate);
        } else if (slot == BindingSlot.IFF_TRANSPONDER) {
            if (index < 0 || index >= iffTransponders.size()) return false;
            BindingRef candidate = iffTransponders.get(index);
            if (!candidate.pos().equals(expectedPos)) return false;
            removed = iffTransponders.remove(index);
        } else if (isPodBearingRole(slot)) {
            return false;
        } else {
            BindingRef candidate = bindings.get(slot);
            if (candidate == null || !candidate.pos().equals(expectedPos)) {
                return false;
            }
            removed = bindings.remove(slot);
        }

        if (slot == BindingSlot.WIRELESS_CONTROLLER_HUB)
            com.hooya.stabilizedturret.content.remote.ControllerSessions.mappingsChanged(level, removed.pos());

        if ((slot == BindingSlot.YAW || slot == BindingSlot.PITCH)
                && level != null
                && removed.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
            BearingStabilizer.disable(bearing);
            setBearingHeadHidden(bearing, false);
            if (slot == BindingSlot.PITCH) pitchCalibrationAttempted.remove(removed);
        }
        if (isMissileDirectionAnchorSlot(slot)) {
            missileDirectionAnchors.remove(removed);
        }
        if (slot == BindingSlot.CANNON_MOUNT || isMissileDirectionAnchorSlot(slot)
                || slot == BindingSlot.FORWARD_INDICATOR) {
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
            UUID subLevelId, Vec3 center, Vec3 velocity, AABB bounds,
            Set<UUID> memberIds, boolean missile
    ) {
        public RadarContact(UUID subLevelId, Vec3 center, Vec3 velocity,
                            AABB bounds, Set<UUID> memberIds) {
            this(subLevelId, center, velocity, bounds, memberIds, false);
        }
    }

    public record GroundTargetSnapshot(
            UUID id, Vec3 center, AABB bounds, Set<UUID> memberIds
    ) {
    }

    public record RocketImpactPrediction(
            boolean available,
            Vec3 position,
            boolean trajectoryAvailable,
            Vec3 origin,
            Vec3 initialVelocity,
            double firstDrag,
            double flightDrop,
            double firstGravity
    ) {
        public static RocketImpactPrediction unavailable() {
            return new RocketImpactPrediction(
                    false, Vec3.ZERO, false, Vec3.ZERO, Vec3.ZERO,
                    MianbaoRocketFlight.AIR_DRAG, MianbaoRocketFlight.STANDARD_DROP,
                    MianbaoRocketFlight.ARROW_GRAVITY);
        }
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

    public record BearingSettings(double depression, double elevation, double speed,
                                  boolean reversed, boolean headHidden, Vec3 offset, YawLimits yawLimits) {}

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
        if (MianbaoAircraftCompat.isInfrared(type)) return InfraredTargeting.launchTarget(this) != null;
        if (MianbaoAircraftCompat.isAntiRadiation(type)) return AntiRadiationTargeting.target(this) != null;
        if (type == MianbaoAircraftCompat.WeaponType.ROCKET_POD) return true;
        if (MianbaoAircraftCompat.isOpticalGuided(type)) return true;
        if (MianbaoAircraftCompat.isAirToAir(type)) {
            return isAircraftRadarActive() && getAircraftGuidanceTargetId() != null;
        }
        if (MianbaoAircraftCompat.isAirToGround(type)) {
            return getAirToGroundLaunchTargetSnapshot() != null;
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
        movingAimRay = null;
        if (!hasAimTarget) cachedAimGroupsAt = Long.MIN_VALUE;
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
        movingAimRay = null;
        boolean hadAimTarget = hasAimTarget;
        opticalSearchHalfTanX = Mth.clamp(opticalHalfTanX, 1.0e-4, 1.0);
        opticalSearchHalfTanY = Mth.clamp(opticalHalfTanY, 1.0e-4, 1.0);
        if (cameraDirection.lengthSqr() > 1.0e-8) {
            lastAimRayOrigin = cameraOrigin;
            lastAimRayDirection = cameraDirection.normalize();
            lastAimRayTarget = target;
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
            Vec3 aimAtReferenceRange = OpticalLockAimGeometry.atTargetDepth(
                    cameraOrigin, cameraDirection, cameraOrigin, reference);
            if (aimAtReferenceRange == null) {
                aimAtReferenceRange = cameraOrigin.add(cameraDirection.normalize().scale(targetRange));
            }
            lockAimOffset = aimAtReferenceRange.subtract(reference);
            convergenceTarget = lockTarget.add(lockAimOffset);
        }
        if (hit || lockTarget != null) {
            setDesiredWorldTarget(convergenceTarget);
        } else {
            // Empty sky is a direction, not a 4096-block ballistic target.
            hasAimTarget = false;
            setDesiredWorldDirection(cameraDirection);
        }
        if (level != null) {
            lastAimUpdateGameTime = level.getGameTime();
        }
        if (hadAimTarget != hasAimTarget) cachedAimGroupsAt = Long.MIN_VALUE;
    }

    public void setMovingAimRay(@Nullable com.hooya.stabilizedturret.content.bearing.MovingAimRay ray) {
        movingAimRay = hasAimTarget && !hasOpticalLock() && !isPodControlledCannonActive() ? ray : null;
    }

    /**
     * Free look preserves an inertial world direction instead of a block or
     * other absolute convergence point. This prevents a moving carrier from
     * turning the turret back toward the block under the reticle at entry.
     */
    public void setDesiredFreeLookDirection(
            Vec3 cameraOrigin,
            Vec3 cameraDirection,
            double opticalHalfTanX,
            double opticalHalfTanY
    ) {
        movingAimRay = null;
        if (cameraDirection.lengthSqr() <= 1.0e-8) return;
        opticalSearchHalfTanX = Mth.clamp(opticalHalfTanX, 1.0e-4, 1.0);
        opticalSearchHalfTanY = Mth.clamp(opticalHalfTanY, 1.0e-4, 1.0);
        Vec3 direction = cameraDirection.normalize();
        lastAimRayOrigin = cameraOrigin;
        lastAimRayDirection = direction;
        lastAimRayTarget = cameraOrigin.add(direction.scale(4096));
        hasAimRay = true;
        lastAimRayHit = false;
        lastAimSubLevelId = null;
        lastAimSubLevelAnchor = Vec3.ZERO;
        if (hasAimTarget) cachedAimGroupsAt = Long.MIN_VALUE;
        hasAimTarget = false;
        setDesiredWorldDirection(direction);
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
                                    Component.translatable("message.create_fire_control.optical_lock.occluded"),
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

    private List<TurretGroup> turretGroups() {
        if (level == null) return List.of();
        long now = level.getGameTime();
        int topologyHash = turretTopologyHash();
        if (cachedTurretGroupsAt != Long.MIN_VALUE && now >= cachedTurretGroupsAt
                && now - cachedTurretGroupsAt < 10L && topologyHash == cachedTurretTopologyHash) {
            return cachedTurretGroups;
        }
        List<BindingRef> sortedYaw = yawBearings.stream().sorted(bindingOrder()).toList();
        List<BindingRef> sortedPitch = pitchBearings.stream().sorted(bindingOrder()).toList();
        Map<BindingRef, List<BindingRef>> mountsByPitch = new HashMap<>();
        Map<BindingRef, List<BindingRef>> launchersByPitch = new HashMap<>();
        Set<BindingRef> assignedMounts = new HashSet<>();
        Set<BindingRef> assignedLaunchers = new HashSet<>();
        BindingRef forwardIndicator = bindings.get(BindingSlot.FORWARD_INDICATOR);
        List<BindingRef> directionReferences = new ArrayList<>(missileDirectionAnchors);
        if (forwardIndicator != null) directionReferences.add(forwardIndicator);
        for (BindingRef mount : cannonMounts) {
            BindingRef pitch = closestParentBearing(mount, sortedPitch);
            if (pitch != null) {
                mountsByPitch.computeIfAbsent(pitch, ignored -> new ArrayList<>()).add(mount);
                assignedMounts.add(mount);
            }
        }
        for (BindingRef launcher : directionReferences) {
            if (!isBoundDirectionReference(launcher)) continue;
            BindingRef pitch = closestParentBearing(launcher, sortedPitch);
            if (pitch != null) {
                launchersByPitch.computeIfAbsent(pitch, ignored -> new ArrayList<>()).add(launcher);
                assignedLaunchers.add(launcher);
            }
        }
        List<TurretGroup> result = new ArrayList<>();
        Set<BindingRef> usedYaw = new HashSet<>();
        for (BindingRef pitch : sortedPitch) {
            List<BindingRef> yaws = parentBearings(pitch, sortedYaw);
            usedYaw.addAll(yaws);
            List<BindingRef> mounts = mountsByPitch.getOrDefault(pitch, List.of()).stream()
                    .toList();
            List<BindingRef> references = List.copyOf(
                    launchersByPitch.getOrDefault(pitch, List.of()));
            if (!mounts.isEmpty() || !references.isEmpty()) {
                result.add(new TurretGroup(yaws, pitch, mounts, references));
            }
        }
        for (BindingRef yaw : sortedYaw) {
            if (usedYaw.contains(yaw)) continue;
            List<BindingRef> mounts = cannonMounts.stream()
                    .filter(ref -> !assignedMounts.contains(ref))
                    .filter(ref -> yaw.equals(closestParentBearing(ref, sortedYaw)))
                    .toList();
            List<BindingRef> references = directionReferences.stream()
                    .filter(this::isBoundDirectionReference)
                    .filter(ref -> !assignedLaunchers.contains(ref))
                    .filter(ref -> yaw.equals(closestParentBearing(ref, sortedYaw)))
                    .toList();
            if (!mounts.isEmpty() || !references.isEmpty()) {
                result.add(new TurretGroup(List.of(yaw), null, mounts, references));
            }
        }
        if (result.isEmpty()) {
            List<BindingRef> references = directionReferences.stream()
                    .filter(this::isBoundDirectionReference).toList();
            if (!cannonMounts.isEmpty() || !references.isEmpty()) {
                result.add(new TurretGroup(
                        sortedYaw.stream().findFirst().map(List::of).orElse(List.of()),
                        sortedPitch.stream().findFirst().orElse(null),
                        List.copyOf(cannonMounts), List.copyOf(references)));
            }
        } else if (forwardIndicator != null
                && isBoundForwardIndicator(forwardIndicator)
                && !assignedLaunchers.contains(forwardIndicator)
                && result.stream().noneMatch(group ->
                group.otherReferences().contains(forwardIndicator))) {
            // A detached/static indicator still supplies the single fallback
            // ray. Associate it with the first configured turret axes so head
            // aim remains usable even when no topology path can be inferred.
            result.add(new TurretGroup(
                    sortedYaw.stream().findFirst().map(List::of).orElse(List.of()),
                    sortedPitch.stream().findFirst().orElse(null),
                    List.of(), List.of(forwardIndicator)));
        }
        cachedTurretGroups = List.copyOf(result);
        cachedTurretGroupsAt = now;
        cachedTurretTopologyHash = topologyHash;
        return cachedTurretGroups;
    }

    private int turretTopologyHash() {
        int hash = 31 * yawBearings.hashCode() + pitchBearings.hashCode();
        hash = 31 * hash + cannonMounts.hashCode();
        hash = 31 * hash + missileDirectionAnchors.hashCode();
        return 31 * hash + java.util.Objects.hashCode(
                bindings.get(BindingSlot.FORWARD_INDICATOR));
    }

    private @Nullable BindingRef closestParentBearing(BindingRef device, List<BindingRef> candidates) {
        return candidates.stream()
                .filter(ref -> bearingChildDistance(device, ref) != Integer.MAX_VALUE)
                .min(Comparator.comparingInt((BindingRef ref) -> bearingChildDistance(device, ref))
                        .thenComparingDouble(ref -> bearingSpatialDistance(device, ref))
                        .thenComparing(bindingOrder())).orElse(null);
    }

    /**
     * Finds all equally-near azimuth parents.  A single elevation bearing can
     * intentionally have two symmetric direction bearings; those must stay in
     * one group.  When the physical bearings are not symmetric, choose only
     * the closest one so two independent turrets in one welded sublevel do not
     * steal each other's cannon references.
     */
    private List<BindingRef> parentBearings(BindingRef device, List<BindingRef> candidates) {
        int topologyDistance = candidates.stream().mapToInt(ref -> bearingChildDistance(device, ref))
                .filter(distance -> distance != Integer.MAX_VALUE).min().orElse(Integer.MAX_VALUE);
        if (topologyDistance == Integer.MAX_VALUE) return List.of();
        List<BindingRef> topologyMatches = candidates.stream()
                .filter(ref -> bearingChildDistance(device, ref) == topologyDistance).toList();
        double nearest = topologyMatches.stream()
                .mapToDouble(ref -> bearingSpatialDistance(device, ref)).min().orElse(Double.POSITIVE_INFINITY);
        if (!Double.isFinite(nearest)) return List.of();
        double tolerance = Math.max(0.05, nearest * 0.02);
        return topologyMatches.stream()
                .filter(ref -> Math.abs(bearingSpatialDistance(device, ref) - nearest) <= tolerance)
                .toList();
    }

    private double bearingSpatialDistance(BindingRef device, BindingRef bearing) {
        if (device == null || bearing == null || level == null) return Double.POSITIVE_INFINITY;
        Vec3 deviceCenter = device.worldCenter(level);
        Vec3 bearingCenter = bearing.worldCenter(level);
        if (deviceCenter != null && bearingCenter != null) {
            return deviceCenter.distanceToSqr(bearingCenter);
        }
        return device.pos().distSqr(bearing.pos());
    }

    private int bearingChildDistance(BindingRef device, BindingRef bearingRef) {
        if (device == null || device.subLevelId() == null || bearingRef == null
                || !(bearingRef.resolve(level) instanceof SwivelBearingBlockEntity bearing)
                || !bearing.isAssembled() || bearing.getSubLevelID() == null) return Integer.MAX_VALUE;
        UUID childId = bearing.getSubLevelID();
        if (childId.equals(device.subLevelId())) return 0;
        if (level instanceof ServerLevel server) {
            int welded = SynaxisWeldCompat.weldedDistance(server, childId, device.subLevelId());
            if (welded >= 0) return welded;
        }
        SubLevel child = SubLevelContainer.getContainer(level).getSubLevel(childId);
        if (child == null || child.isRemoved()) return Integer.MAX_VALUE;
        List<SubLevel> queue = new ArrayList<>();
        Map<UUID, Integer> distance = new HashMap<>();
        queue.add(child); distance.put(childId, 0);
        for (int i = 0; i < queue.size(); i++) {
            SubLevel current = queue.get(i);
            int currentDistance = distance.get(current.getUniqueId());
            for (dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor actor : current.getPlot().getBlockEntityActors()) {
                Iterable<SubLevel> dependencies = actor.sable$getConnectionDependencies();
                if (dependencies == null) continue;
                for (SubLevel connected : dependencies) {
                    if (connected == null || connected.isRemoved()) continue;
                    UUID id = connected.getUniqueId();
                    if (distance.putIfAbsent(id, currentDistance + 1) != null) continue;
                    if (device.subLevelId().equals(id)) return currentDistance + 1;
                    queue.add(connected);
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    private static Comparator<BindingRef> bindingOrder() {
        return Comparator.comparing((BindingRef ref) -> ref.subLevelId() == null ? "" : ref.subLevelId().toString())
                .thenComparingLong(ref -> ref.pos().asLong());
    }

    private List<BindingRef> aimReferences(TurretGroup group) {
        List<BindingRef> refs = new ArrayList<>(
                group.cannonMounts().size() + group.otherReferences().size());
        refs.addAll(group.cannonMounts());
        refs.addAll(group.otherReferences());

        // Cannon mount > missile launcher. Equal-priority references keep
        // wrench-binding order and are failover candidates, not an average.
        int bestPriority = Integer.MAX_VALUE;
        for (BindingRef ref : refs) {
            if (isBoundForwardIndicator(ref) && hasUsablePrimaryAimReference()) continue;
            if (measureAimReference(ref) == null) continue;
            bestPriority = Math.min(bestPriority, aimReferencePriority(ref));
        }
        if (bestPriority == Integer.MAX_VALUE) return List.of();
        int selectedPriority = bestPriority;
        return refs.stream()
                .filter(ref -> !isBoundForwardIndicator(ref)
                        || !hasUsablePrimaryAimReference())
                .filter(ref -> aimReferencePriority(ref) == selectedPriority)
                .toList();
    }

    private int aimReferencePriority(BindingRef ref) {
        if (ref != null && cannonMounts.contains(ref)) return 0;
        if (ref != null && isBoundMissileDirectionAnchor(ref)) return 1;
        if (isBoundForwardIndicator(ref)) return 2;
        return 2;
    }

    private boolean hasUsablePrimaryAimReference() {
        for (BindingRef ref : cannonMounts) {
            if (measureAimReference(ref) != null) return true;
        }
        for (BindingRef ref : missileDirectionAnchors) {
            if (isBoundMissileDirectionAnchor(ref)
                    && measureAimReference(ref) != null) return true;
        }
        for (BindingRef ref : remoteWeaponStations) {
            if (measureAimReference(ref) != null) return true;
        }
        return false;
    }

    private @Nullable AimReference measureAimReference(TurretGroup group) {
        for (BindingRef ref : aimReferences(group)) {
            AimReference measured = measureAimReference(ref);
            if (measured != null) return measured;
        }
        return null;
    }

    private @Nullable AimReference measureAimReference(BindingRef ref) {
        if (level == null || ref == null) return null;
        Vec3 origin = null;
        Vec3 direction = null;
        if (remoteWeaponStations.contains(ref)) {
            origin = MianbaoRemoteWeaponStationCompat.getMuzzlePosition(level, ref);
            direction = MianbaoRemoteWeaponStationCompat.getMuzzleDirection(level, ref);
        } else if (CannonMountCompat.hasAssembledCannon(level, ref)) {
            origin = CannonBallisticsCompat.getCurrentMuzzlePosition(level, ref);
            if (origin == null) origin = CannonMountCompat.getMountedCannonPosition(level, ref);
            direction = CannonMountCompat.getAssembledCannonDirection(level, ref);
        } else if (missileLaunchers.contains(ref)) {
            origin = MianbaoMissileCompat.getLauncherMuzzlePosition(level, ref);
            direction = MianbaoMissileCompat.getLauncherDirection(level, ref);
        } else if (MianbaoAirDefenseCompat.isBoundLauncherPresent(level, ref)) {
            origin = MianbaoAirDefenseCompat.getLauncherMuzzlePosition(level, ref);
            direction = MianbaoAirDefenseCompat.getLauncherDirection(level, ref);
        } else if (isBoundForwardIndicator(ref)) {
            origin = ref.worldCenter(level);
            direction = measureForwardIndicatorDirection(ref);
        }
        if (origin == null || direction == null || direction.lengthSqr() < 1.0e-8) return null;
        return new AimReference(origin, direction.normalize(), ref.subLevelId());
    }

    private boolean isBoundDirectionReference(BindingRef ref) {
        return isBoundMissileDirectionAnchor(ref) || isBoundForwardIndicator(ref);
    }

    private boolean isBoundForwardIndicator(@Nullable BindingRef ref) {
        return ref != null
                && ref.equals(bindings.get(BindingSlot.FORWARD_INDICATOR))
                && ForwardIndicatorCompat.isPresent(level, ref);
    }

    private @Nullable Vec3 measureForwardIndicatorDirection(BindingRef ref) {
        return isBoundForwardIndicator(ref) ? ForwardIndicatorCompat.worldDirection(level, ref) : null;
    }

    public @Nullable BindingRef getAimReferenceBinding() {
        if (level == null) {
            return null;
        }
        BindingRef turretReference = turretGroups().stream()
                .flatMap(group -> aimReferences(group).stream())
                .findFirst().orElse(null);
        if (turretReference != null) return turretReference;
        return remoteWeaponStations.stream()
                .filter(ref -> measureAimReference(ref) != null)
                .findFirst().orElse(null);
    }

    private boolean hasAimReferenceBinding() {
        return getAimReferenceBinding() != null;
    }

    /**
     * The combined heavy-cannon station becomes an optic/muzzle anchor only
     * when it is the sole live device capable of supplying that anchor.
     */
    private @Nullable BindingRef getExclusiveHeavyCannonAimReference() {
        if (level == null || remoteWeaponStations.size() != 1) return null;
        if (!yawBearings.isEmpty() || !pitchBearings.isEmpty()
                || bindings.containsKey(BindingSlot.YAW)
                || bindings.containsKey(BindingSlot.PITCH)) return null;
        BindingRef station = remoteWeaponStations.get(0);
        if (!MianbaoRemoteWeaponStationCompat.isHeavyCannonStation(
                level, station.pos()) || measureAimReference(station) == null) {
            return null;
        }
        boolean hasOtherAnchor = !cannonMounts.isEmpty()
                || !missileLaunchers.isEmpty()
                || !aaMissileLaunchers.isEmpty()
                || turretGroups().stream().anyMatch(group ->
                !aimReferences(group).isEmpty());
        return hasOtherAnchor ? null : station;
    }

    public boolean hasExclusiveHeavyCannonAimReference() {
        return getExclusiveHeavyCannonAimReference() != null;
    }

    public boolean exclusivelyControlsHeavyCannon(Level targetLevel, BlockPos targetPos) {
        BindingRef station = getExclusiveHeavyCannonAimReference();
        return station != null && station.matches(targetLevel, targetPos);
    }

    public boolean blocksCompetingHeavyCannonAim(
            Level targetLevel, BlockPos targetPos
    ) {
        boolean boundHeavyCannon = remoteWeaponStations.stream().anyMatch(ref ->
                ref.matches(targetLevel, targetPos)
                        && level != null
                        && MianbaoRemoteWeaponStationCompat.isHeavyCannonStation(
                        level, ref.pos()));
        return boundHeavyCannon && (stabilizerEnabled
                || exclusivelyControlsHeavyCannon(targetLevel, targetPos));
    }

    public @Nullable Vec3 getMeasuredAimDirection() {
        if (level == null) {
            return null;
        }
        for (TurretGroup group : turretGroups()) {
            AimReference measured = measureAimReference(group);
            if (measured != null) return measured.direction();
        }
        for (BindingRef station : remoteWeaponStations) {
            AimReference measured = measureAimReference(station);
            if (measured != null) return measured.direction();
        }
        return null;
    }

    public @Nullable Vec3 getMeasuredAimOrigin() {
        if (level == null) {
            return null;
        }
        for (TurretGroup group : turretGroups()) {
            AimReference measured = measureAimReference(group);
            if (measured != null) return measured.origin();
        }
        for (BindingRef station : remoteWeaponStations) {
            AimReference measured = measureAimReference(station);
            if (measured != null) return measured.origin();
        }
        return null;
    }

    /** Returns one measured muzzle ray or predicted trajectory per independent turret group. */
    public List<AimGroupSessionPayload.GroupAim> getMeasuredAimGroups() {
        if (level == null) {
            return List.of();
        }
        // Multiple gunners may view one controller. Prepare each marker only once per tick.
        if (cachedAimGroupsAt == level.getGameTime()) return cachedAimGroups;
        List<AimGroupSessionPayload.GroupAim> result = new ArrayList<>();
        // Several elevation bearings sharing one yaw bearing are one physical
        // turret group. Its first usable cannon supplies the reticle's launch frame;
        // averaging different muzzle directions cannot represent a real barrel.
        // Distinct yaw groups remain separate and get separate reticles.
        for (List<TurretGroup> cluster : aimClusters(turretGroups())) {
            AimReference measured = measureAimReference(cluster);
            if (measured == null) {
                continue;
            }
            BindingRef primary = cluster.stream().flatMap(group -> aimReferences(group).stream())
                    .filter(ref -> measureAimReference(ref) != null).findFirst().orElse(null);
            result.add(measuredAimGroup(primary, measured));
        }
        // The heavy station supplies the normal fire-control muzzle marker
        // only in its explicitly exclusive-anchor mode. With another cannon
        // or launcher bound, that existing device remains the sole marker.
        BindingRef exclusiveHeavyCannon = getExclusiveHeavyCannonAimReference();
        if (exclusiveHeavyCannon != null) {
            AimReference measured = measureAimReference(exclusiveHeavyCannon);
            if (measured != null) {
                Vec3 direction = measured.direction().normalize();
                result.add(new AimGroupSessionPayload.GroupAim(
                        measured.origin().x, measured.origin().y, measured.origin().z,
                        direction.x, direction.y, direction.z));
            }
        }
        cachedAimGroups = List.copyOf(result);
        cachedAimGroupsAt = level.getGameTime();
        return cachedAimGroups;
    }

    private AimGroupSessionPayload.GroupAim measuredAimGroup(@Nullable BindingRef primary, AimReference fallback) {
        boolean podView = isPodViewEnabled();
        if (!podView) return measuredCannonSightDirection(primary, fallback);
        if (hasAimRay && !lastAimRayHit && !hasAimTarget && !isPodControlledCannonActive()) {
            // The pod retains its existing handling of an infinite aim ray.
            Vec3 direction = fallback.direction().normalize();
            return new AimGroupSessionPayload.GroupAim(fallback.origin().x, fallback.origin().y, fallback.origin().z,
                    direction.x, direction.y, direction.z,null,measuredMuzzleFrame(primary,null));
        }
        CannonBallisticsCompat.ShotProfile profile = primary == null ? null
                : CannonBallisticsCompat.withCurrentFrame(CannonBallisticsCompat.analyze(level, primary), level, primary);
        // Preserve the pod's existing deterministic launch override only while that mode is active.
        if (profile != null && isPodControlledCannonActive() && cannonMounts.contains(primary)) {
            Vec3 target = getPodGuidanceTarget();
            if (target != null) {
                profile = PodControlledCannonBallistics.apply(profile);
                Vec3 launch = getCannonBallisticLaunchDirection(primary, profile, target);
                if (launch != null) profile = CannonBallisticsCompat.withLaunchDirection(profile, launch);
            }
        }
        if (profile != null && profile.muzzleSpeed() > 0.0) {
            Vec3 velocity = profile.currentForward().scale(profile.muzzleSpeed()).add(profile.carrierVelocity());
            Vec3 impact = SubLevelContainer.getContainer(level).getLevel() instanceof ServerLevel root
                    ? CannonBallisticsCompat.predictImpact(root, profile, ownControlledSubLevelIds()) : null;
            Vec3 origin = profile.muzzle();
            Vec3 forward = profile.currentForward();
            return new AimGroupSessionPayload.GroupAim(origin.x, origin.y, origin.z,
                    forward.x, forward.y, forward.z,
                    new AimGroupSessionPayload.BallisticAim(velocity.x, velocity.y, velocity.z,
                            profile.gravity(), profile.drag(), profile.quadraticDrag(),
                            Mth.clamp(profile.maxFlightTicks(), 1, 1200), impact != null,
                            impact == null ? 0.0 : impact.x,
                            impact == null ? 0.0 : impact.y,
                            impact == null ? 0.0 : impact.z),isPodControlledCannonActive() ? null
                            : measuredMuzzleFrame(primary,profile));
        }
        Vec3 direction = fallback.direction().normalize();
        return new AimGroupSessionPayload.GroupAim(fallback.origin().x, fallback.origin().y, fallback.origin().z,
                direction.x, direction.y, direction.z);
    }

    private AimGroupSessionPayload.GroupAim measuredCannonSightDirection(
            @Nullable BindingRef primary, AimReference fallback) {
        Vec3 origin = fallback.origin();
        Vec3 direction = fallback.direction().normalize();
        AimGroupSessionPayload.SightAim sightAim = null;
        AimGroupSessionPayload.MuzzleFrame frame=measuredMuzzleFrame(primary,null);
        var rootContainer=SubLevelContainer.getContainer(level);
        int interpolationTick=rootContainer instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer serverContainer
                && serverContainer.trackingSystem()!=null ? serverContainer.trackingSystem().getInterpolationTick() : 0;
        if (frame != null) {
            // Remove acquired motion compensation and the separately latched
            // rangefinder elevation, including while aiming into empty sky.
            Vec3 motionSight=stabilizerReactionEnabled
                    ? BearingStabilizer.withoutMuzzleCompensation(level,frame.bodyId(),direction) : direction;
            Vec3 sight=LaserRangeCompensation.raise(motionSight, -savedLaserRange(primary).elevationRadians());
            sightAim=new AimGroupSessionPayload.SightAim(direction,sight,interpolationTick);
        }
        return new AimGroupSessionPayload.GroupAim(origin.x, origin.y, origin.z,
                direction.x, direction.y, direction.z, null, frame, sightAim);
    }

    private @Nullable AimGroupSessionPayload.MuzzleFrame measuredMuzzleFrame(
            @Nullable BindingRef ref,@Nullable CannonBallisticsCompat.ShotProfile profile) {
        if (ref==null) return null;
        Vec3 point=profile==null ? CannonBallisticsCompat.getCurrentMuzzlePosition(level,ref) : profile.muzzle();
        if (point==null) return null;
        FiniteAimTarget geometry=FiniteAimTarget.from(level,ref,profile,point);
        if (geometry==null) return null;
        AimGroupSessionPayload.BodyMotion motion=null;
        SubLevel body=SubLevelContainer.getContainer(level).getSubLevel(geometry.bodyId());
        if (body instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel serverBody
                && serverBody.getMassTracker()!=null && serverBody.getMassTracker().getCenterOfMass()!=null) try {
            var system=dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem.require(
                    SubLevelContainer.getContainer(level).getLevel());
            Vector3d position=new Vector3d(body.logicalPose().position());
            Vector3d com=new Vector3d(serverBody.getMassTracker().getCenterOfMass());
            Vector3d linear=system.getPipeline().getLinearVelocity(serverBody,new Vector3d());
            Vector3d angular=system.getPipeline().getAngularVelocity(serverBody,new Vector3d());
            motion=new AimGroupSessionPayload.BodyMotion(new Vec3(position.x,position.y,position.z),
                    new Vec3(com.x,com.y,com.z),new Vec3(linear.x,linear.y,linear.z),
                    new Vec3(angular.x,angular.y,angular.z),new Quaterniond(body.logicalPose().orientation()));
        } catch (RuntimeException | LinkageError unavailable) {
            // Assembly/teardown can expose geometry before its physics handle is ready.
            // The existing pose-history velocity remains the display fallback for that interval.
        }
        return new AimGroupSessionPayload.MuzzleFrame(geometry.bodyId(),
                geometry.localMuzzle(),geometry.localForward(),profile==null ? 0 : profile.muzzleSpeed(),motion);
    }

    /**
     * Returns the current physical cannon/launcher aim point.
     *
     * <p>This deliberately never returns the player's view-ray endpoint.
     * Guidance follows the highest-priority measured axis: cannon mounts,
     * then a bound anti-tank or air-defense launcher in binding order.
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
        for (BindingRef station : remoteWeaponStations) {
            if (MianbaoRemoteWeaponStationCompat.isHeavyCannonStation(
                    level, station.pos())
                    && MianbaoRemoteWeaponStationCompat.getMuzzlePosition(
                    level, station) != null) {
                return getMeasuredAimOrigin() != null
                        && getMeasuredAimDirection() != null;
            }
        }
        return false;
    }

    public boolean isOrdinaryAaGuidanceReady() {
        Vec3 direction = getMeasuredAimDirection();
        return level != null && getMeasuredAimOrigin() != null
                && direction != null && direction.lengthSqr() > 1.0e-8;
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
            BindingRef pitchRef = pitchBearings.stream().findFirst().orElse(bindings.get(BindingSlot.PITCH));
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
        for (List<TurretGroup> cluster : aimClusters(turretGroups())) {
            AimReference centre = measureAimReference(cluster);
            if (centre == null) continue;
            Map<TurretGroup, GroupAimCommand> requests = new HashMap<>();
            List<FiniteAimTarget> yawTargets = new ArrayList<>();
            Vec3 yawRequest = Vec3.ZERO;
            for (TurretGroup group : cluster) {
                AimReference measured = measureAimReference(group);
                if (measured == null) continue;
                GroupAimCommand request = requestedGroupAim(group, measured);
                requests.put(group, request);
                yawRequest = yawRequest.add(request.direction());
                yawTargets.addAll(request.targets());
            }
            // A shared yaw receives one averaged launch direction. Each pitch
            // retains its own muzzle, ammunition and finite-range solution.
            if (yawRequest.lengthSqr() < 1.0e-8) continue;
            yawRequest = yawRequest.normalize();
            // An unresolved group still contributes its ordinary request; keep the same weights.
            if (yawTargets.size() != requests.size()) yawTargets.clear();
            Vec3 operatorDirection = hasAimRay && !hasOpticalLock() ? lastAimRayDirection : null;

            Set<BindingRef> commandedYaw = new HashSet<>();
            for (TurretGroup group : cluster) {
                for (BindingRef yaw : group.yaws()) {
                    if (commandedYaw.add(yaw)) {
                        BearingSettings settings = getBearingSettings(BindingSlot.YAW, yaw);
                        commandBearing(yaw, true, settings.speed(),
                                centre.direction(), yawRequest, settings.reversed() ? -1.0 : 1.0,
                                settings.depression(), settings.elevation(), settings.offset(), yawTargets, operatorDirection);
                    }
                }
                if (group.pitch() == null) continue;
                AimReference measured = measureAimReference(group);
                if (measured == null) continue;
                GroupAimCommand groupCommand = requests.get(group);
                Vec3 groupRequest = groupCommand == null ? Vec3.ZERO : groupCommand.direction();
                if (groupRequest.lengthSqr() < 1.0e-8) continue;
                Vec3 pitchRequest = clampPitchRequestToTurret(
                        group.pitch(), groupRequest, measured.direction());
                BearingSettings settings = getBearingSettings(BindingSlot.PITCH, group.pitch());
                commandBearing(group.pitch(), false, settings.speed(),
                        measured.direction(), pitchRequest, settings.reversed() ? -1.0 : 1.0,
                        settings.depression(), settings.elevation(), settings.offset(), groupCommand.targets(), operatorDirection);
            }
        }
    }

    private Vec3 requestedGroupDirection(TurretGroup group, AimReference measured) {
        return requestedGroupAim(group, measured).direction();
    }

    private record GroupAimCommand(Vec3 direction, List<FiniteAimTarget> targets) {}

    private boolean isBearingMuzzleCompensationEnabled() {
        return stabilizerReactionEnabled && mobileShotCompensationEnabled;
    }

    private LaserRangeCompensation savedLaserRange(@Nullable BindingRef ref) {
        return ref == null ? LaserRangeCompensation.ZERO : laserRanges.getOrDefault(ref, LaserRangeCompensation.ZERO);
    }

    public void requestLaserRange(@Nullable ServerPlayer operator,
            @Nullable RangeSample sample) {
        if (level == null || level.isClientSide) return;
        if (!headAimEnabled || isPodViewEnabled()) {
            StabilizedTurret.LOGGER.info("Laser rangefinder ignored: controller={} reason={} headAim={}",
                    getBlockPos(), isPodViewEnabled() ? "pod-view" : "head-aim-disabled", headAimEnabled);
            return;
        }
        if (operator == null) operator = findSeatedGunner();
        if (operator == null || MianbaoOpticalMissileHandler.isViewing(operator)) {
            StabilizedTurret.LOGGER.info("Laser rangefinder ignored: controller={} reason={}",
                    getBlockPos(), operator == null ? "no-operator" : "television-guidance");
            return;
        }
        boolean explicit = sample != null;
        if (explicit && (ControllerTracker.findForPlayerAim(operator) != this
                || sample.origin().distanceToSqr(operator.position()) > 128.0 * 128.0)) {
            StabilizedTurret.LOGGER.info("Laser rangefinder ignored: controller={} reason=invalid-snapshot-owner-or-origin",
                    getBlockPos());
            return;
        }
        if (sample == null) {
            if (!hasAimRay || level.getGameTime() - lastAimUpdateGameTime > 5L) {
                StabilizedTurret.LOGGER.info("Laser rangefinder ignored: controller={} reason=no-fresh-aim-ray hasRay={} age={}",
                        getBlockPos(), hasAimRay, level.getGameTime() - lastAimUpdateGameTime);
                return;
            }
            sample = RangeSample.tryCreate(
                    lastAimRayOrigin, lastAimRayDirection, lastAimRayTarget, lastAimRayHit);
            if (sample == null) {
                StabilizedTurret.LOGGER.info("Laser rangefinder ignored: controller={} reason=invalid-aim-ray",
                        getBlockPos());
                return;
            }
        }
        // A wireless fallback must not overwrite the exact client input sample.
        if (pendingLaserRangeExplicit && !explicit) return;
        pendingLaserRange = sample;
        pendingLaserRangeOperator = operator;
        pendingLaserRangeExplicit = explicit;
        StabilizedTurret.LOGGER.info("Laser rangefinder accepted: controller={} source={} hit={} range={} target={}",
                getBlockPos(), explicit ? "press-snapshot" : "native-input-fallback", sample.hit(),
                sample.origin().distanceTo(sample.target()), sample.target());
    }

    private void applyPendingLaserRange() {
        var sample = pendingLaserRange;
        ServerPlayer operator = pendingLaserRangeOperator;
        pendingLaserRange = null;
        pendingLaserRangeOperator = null;
        pendingLaserRangeExplicit = false;
        if (sample == null) return;
        if (!sample.hit()) {
            laserRanges.clear();
            cachedAimGroupsAt = Long.MIN_VALUE;
            setChanged();
            StabilizedTurret.LOGGER.info("Laser rangefinder reset: controller={} reason=no-terrain-hit", getBlockPos());
            if (operator != null) operator.displayClientMessage(Component.translatable(
                    "message.create_fire_control.laser_range.reset"), true);
            return;
        }
        Map<BindingRef, LaserRangeCompensation> measured = new HashMap<>();
        for (TurretGroup group : turretGroups()) {
            for (BindingRef ref : aimReferences(group)) {
                CannonBallisticsCompat.ShotProfile profile = CannonBallisticsCompat.withCurrentFrame(
                        CannonBallisticsCompat.analyze(level, ref), level, ref);
                LaserRangeCompensation correction = LaserRangeCompensation.measure(profile, sample.target());
                if (correction != null) {
                    measured.put(ref, correction);
                    StabilizedTurret.LOGGER.info("Laser rangefinder solved: controller={} weapon={} range={} elevationDeg={} projectile={} muzzleSpeed={} virtualScope={} scoped={}",
                            getBlockPos(), ref.pos(), correction.distance(), Math.toDegrees(correction.elevationRadians()),
                            profile.projectile(), profile.muzzleSpeed(), virtualScopeEnabled, scopeUserId != null);
                } else if (profile == null) {
                    StabilizedTurret.LOGGER.info("Laser rangefinder unavailable: controller={} weapon={} reason=no-ammunition-profile",
                            getBlockPos(), ref.pos());
                } else {
                    StabilizedTurret.LOGGER.info("Laser rangefinder unavailable: controller={} weapon={} reason=no-ballistic-solution range={} muzzleSpeed={} gravity={} drag={} maxTicks={}",
                            getBlockPos(), ref.pos(), profile.muzzle().distanceTo(sample.target()), profile.muzzleSpeed(),
                            profile.gravity(), profile.drag(), profile.maxFlightTicks());
                }
            }
        }
        if (measured.isEmpty()) {
            StabilizedTurret.LOGGER.info("Laser rangefinder unchanged: controller={} reason=no-weapon-solution groups={}",
                    getBlockPos(), turretGroups().size());
            if (operator != null) operator.displayClientMessage(Component.translatable(
                    "message.create_fire_control.laser_range.unavailable"), true);
            return;
        }
        laserRanges.putAll(measured);
        cachedAimGroupsAt = Long.MIN_VALUE;
        setChanged();
        if (operator != null) operator.displayClientMessage(Component.translatable(
                "message.create_fire_control.laser_range.measured",
                String.format(java.util.Locale.ROOT, "%.1f", sample.target().distanceTo(sample.origin()))), true);
    }

    private GroupAimCommand requestedGroupAim(TurretGroup group, AimReference measured) {
        BindingRef primary = aimReferences(group).stream().filter(ref -> measureAimReference(ref) != null)
                .findFirst().orElse(null);
        double elevationCorrection = savedLaserRange(primary).elevationRadians();
        // The camera is on the pitch axis, but elevation moves a long muzzle
        // away from the unraised sight line. Finite muzzle convergence would
        // then change the infinite reticle whenever its input ray hits terrain.
        Vec3 parallelSight = virtualScopeEnabled && scopeUserId != null && hasAimRay && !hasOpticalLock()
                && !isPodViewEnabled() ? lastAimRayDirection : null;
        // A noncolliding barrel may pass through the face under the reticle.
        // Never send that unreachable point to either finite inverse: the
        // predictive servo and physical fallback would chase a rearward muzzle
        // ray. Keep the real hit for HUD/rangefinding; drive by sight only.
        if (parallelSight == null && primary != null && hasAimTarget && hasAimRay && !hasOpticalLock()) {
            Vec3 trackingPoint = movingAimRay == null ? desiredWorldTarget : movingAimRay.currentTarget(level);
            Vec3 pivot = bearingAimPivot(group.pitch());
            if (pivot == null) for (BindingRef yaw : group.yaws()) {
                pivot = bearingAimPivot(yaw);
                if (pivot != null) break;
            }
            if (trackingPoint == null || pivot != null && nearTerrainAimGuards.computeIfAbsent(primary,
                    ignored -> new TurretAimConvergence.NearTargetGuard())
                    .update(pivot, measured.origin(), trackingPoint)) {
                Vec3 sight = LaserRangeCompensation.raise(lastAimRayDirection, elevationCorrection);
                return new GroupAimCommand(clampPitchRequestToTurret(group.pitch(), sight, measured.direction()),
                        List.of());
            }
        } else if (primary != null) nearTerrainAimGuards.remove(primary);
        Vec3 fallback = TurretAimConvergence.requestedDirection(
                hasAimTarget && parallelSight == null, desiredWorldTarget, measured.origin(),
                parallelSight == null ? desiredWorldDirection : parallelSight);
        fallback = LaserRangeCompensation.raise(fallback, elevationCorrection);
        if (!hasAimTarget) return new GroupAimCommand(fallback, List.of());
        // Physical-bearing head aim has its own policy. Display/pod launch
        // solutions keep the shared ballistic solver's original inheritance.
        boolean compensateCarrierVelocity = isBearingMuzzleCompensationEnabled();
        // Match measureAimReference's first usable device exactly; a launcher
        // must never receive the solution of a different bound cannon.
        for (BindingRef ref : aimReferences(group)) {
            AimReference reference = measureAimReference(ref);
            if (reference == null) continue;
            // Continuous inverses only solve vehicle motion. Gravity is a saved
            // rangefinder elevation and does not follow the current target depth.
            CannonBallisticsCompat.ShotProfile profile = compensateCarrierVelocity
                    ? CannonBallisticsCompat.withCurrentFrame(CannonBallisticsCompat.analyze(level, ref), level, ref)
                    : null;
            FiniteAimTarget target = FiniteAimTarget.from(level, ref, profile, desiredWorldTarget);
            if (target == null && profile == null) {
                SubLevel body = Sable.HELPER.getContaining(level, ref.pos());
                if (body != null && !body.isRemoved()) {
                    Vector3d localMuzzle = body.logicalPose().transformPositionInverse(
                            new Vector3d(reference.origin().x, reference.origin().y, reference.origin().z));
                    Vector3d localForward = body.logicalPose().transformNormalInverse(
                            new Vector3d(reference.direction().x, reference.direction().y, reference.direction().z));
                    target = new FiniteAimTarget(body.getUniqueId(),
                            new Vec3(localMuzzle.x, localMuzzle.y, localMuzzle.z),
                            new Vec3(localForward.x, localForward.y, localForward.z), desiredWorldTarget, null,
                            new FiniteAimTarget.Source(ref.pos(), null));
                }
            }
            if (target != null) {
                target = target.withMovingRay(movingAimRay)
                        .withCarrierVelocityCompensation(compensateCarrierVelocity)
                        .withElevationCorrection(savedLaserRange(ref).elevationRadians())
                        .withParallelSight(parallelSight);
                Vec3 solved = stabilizerReactionEnabled ? target.currentDirection(level)
                        : target.currentDirection(level, Vec3.ZERO);
                if (solved != null) {
                    Vec3 bounded=clampPitchRequestToTurret(group.pitch(),solved,reference.direction());
                    if (bounded.distanceToSqr(solved)>1e-12) {
                        // An unreachable shot has no finite ballistic tracking solution.
                        // In particular, a target above the turret can stay behind the
                        // muzzle at every azimuth: chasing that inverse spins forever.
                        // Hold the nearest allowed sight direction, then reacquire the
                        // finite shot through the ordinary operator path on re-entry.
                        Vec3 sight=hasAimRay && !hasOpticalLock() ? lastAimRayDirection : boundarySightDirection(group,solved);
                        Vec3 boundedSight=clampPitchRequestToTurret(group.pitch(),sight,reference.direction());
                        // For a sight itself beyond the envelope its nearest endpoint
                        // takes precedence over an unreachable ballistic inverse.
                        if (boundedSight.distanceToSqr(sight)>1e-12)
                            return new GroupAimCommand(boundedSight,List.of());
                        Vec3 up=turretUpFor(group.pitch());
                        Vec3 horizontal=sight.subtract(up.scale(sight.dot(up)));
                        if (horizontal.lengthSqr()<1e-8) horizontal=bounded.subtract(up.scale(bounded.dot(up)));
                        double vertical=Mth.clamp(bounded.dot(up),-1,1);
                        Vec3 boundary=horizontal.normalize().scale(Math.sqrt(Math.max(0,1-vertical*vertical)))
                                .add(up.scale(vertical)).normalize();
                        return new GroupAimCommand(boundary,List.of());
                    }
                    return new GroupAimCommand(solved, List.of(target));
                }
            }
            Vec3 solved = LaserRangeCompensation.launchDirection(profile, reference.origin(), reference.direction(),
                    profile == null ? Vec3.ZERO : profile.carrierVelocity(), desiredWorldTarget,
                    savedLaserRange(ref).elevationRadians(), parallelSight);
            return new GroupAimCommand(solved == null ? fallback : solved, List.of());
        }
        return new GroupAimCommand(fallback, List.of());
    }

    private @Nullable Vec3 bearingAimPivot(@Nullable BindingRef ref) {
        if (ref == null || !(ref.resolve(level) instanceof SwivelBearingBlockEntity bearing)) return null;
        Direction facing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
        return Sable.HELPER.projectOutOfSubLevel(bearing.getLevel(),
                bearing.getBlockPos().relative(facing).getCenter());
    }

    private Vec3 boundarySightDirection(TurretGroup group,Vec3 fallback) {
        // Fixed/optical targets also need a pivot reference: muzzle parallax
        // must not keep reversing an impossible elevation request's azimuth.
        for (BindingRef ref:group.yaws()) {
            if (!(ref.resolve(level) instanceof SwivelBearingBlockEntity bearing)) continue;
            Direction facing=bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
            Vec3 local=bearing.getBlockPos().relative(facing).getCenter();
            Vector3d pivot=new Vector3d(local.x,local.y,local.z);
            SubLevel parent=Sable.HELPER.getContaining(bearing);
            if (parent!=null && !parent.isRemoved()) parent.logicalPose().transformPosition(pivot);
            Vec3 direction=desiredWorldTarget.subtract(new Vec3(pivot.x,pivot.y,pivot.z));
            if (direction.lengthSqr()>1e-8) return direction.normalize();
        }
        return fallback;
    }

    /**
     * Keeps a bound heavy station inertially stable on both axes. Its gunner
     * aim always follows the gunner. The exclusive station uses the computer's
     * configured rates/envelope; as a secondary weapon it keeps Mianbao's
     * native rates/envelope. Stabilization remains active in every topology.
     */
    private void maintainRemoteWeaponStations(boolean hasGunnerAim) {
        if (level == null || remoteWeaponStations.isEmpty()) return;
        remoteWeaponStationHoldDirections.keySet().retainAll(remoteWeaponStations);
        BindingRef exclusiveHeavyCannon = getExclusiveHeavyCannonAimReference();
        for (BindingRef station : remoteWeaponStations) {
            boolean heavyCannon = MianbaoRemoteWeaponStationCompat
                    .isHeavyCannonStation(level, station.pos());
            if (!heavyCannon) {
                if (!hasGunnerAim || MianbaoRemoteWeaponStationCompat
                        .isManuallyControlled(level, station)) continue;
                Vec3 legacyTarget = remoteWeaponStationAimTarget(station);
                if (legacyTarget != null) {
                    MianbaoRemoteWeaponStationCompat.pointMuzzleAt(
                            level, station, legacyTarget);
                }
                continue;
            }

            Vec3 muzzle = MianbaoRemoteWeaponStationCompat
                    .getMuzzlePosition(level, station);
            Vec3 measured = MianbaoRemoteWeaponStationCompat
                    .getMuzzleDirection(level, station);
            if (muzzle == null || measured == null
                    || measured.lengthSqr() <= 1.0e-8) continue;
            Vec3 measuredDirection = measured.normalize();

            boolean fireControlAim = hasGunnerAim;
            Vec3 target = fireControlAim
                    ? remoteWeaponStationAimTarget(station) : null;
            if (fireControlAim && target != null) {
                Vec3 held = target.subtract(muzzle);
                if (held.lengthSqr() > 1.0e-8) {
                    remoteWeaponStationHoldDirections.put(
                            station, held.normalize());
                }
            } else if (stabilizerEnabled) {
                Vec3 held = remoteWeaponStationHoldDirections
                        .computeIfAbsent(station, ignored -> measuredDirection);
                target = muzzle.add(held.scale(4096.0));
            } else {
                remoteWeaponStationHoldDirections.put(station, measuredDirection);
            }

            if (target != null) {
                if (station.equals(exclusiveHeavyCannon)) {
                    MianbaoRemoteWeaponStationCompat.pointMuzzleAt(
                            level, station, target,
                            yawSpeedDegreesPerSecond,
                            pitchSpeedDegreesPerSecond,
                            depressionLimitDegrees,
                            elevationLimitDegrees);
                } else {
                    MianbaoRemoteWeaponStationCompat.pointMuzzleAt(
                            level, station, target);
                }
            }
        }
    }

    private @Nullable Vec3 remoteWeaponStationAimTarget(BindingRef station) {
        Vec3 target = hasAimTarget ? desiredWorldTarget : null;
        if (target == null && hasAimDirection
                && desiredWorldDirection.lengthSqr() > 1.0e-8) {
            Vec3 muzzle = MianbaoRemoteWeaponStationCompat
                    .getMuzzlePosition(level, station);
            if (muzzle != null) {
                target = muzzle.add(
                        desiredWorldDirection.normalize().scale(4096.0));
            }
        }
        return target;
    }

    /** One held input drives every bound auxiliary machine gun without firing the cannon. */
    private void tickMachineGunFire() {
        if (level == null) return;
        boolean trigger = machineGunFireReceiver.isPowered()
                || remoteInputPressed[INPUT_ACTION_MACHINE_GUN_FIRE];
        for (BindingRef station : remoteWeaponStations) {
            MianbaoRemoteWeaponStationCompat.tickFireControl(level, station, trigger);
        }
        for (BindingRef mount : cannonMounts) {
            Entity assembled = CannonMountCompat.getMountedCannonEntity(level, mount);
            if (assembled instanceof PitchOrientedContraptionEntity cannonEntity
                    && MountedHeavyMachineGunCompat.hasAttachments(cannonEntity)) {
                MountedHeavyMachineGunCompat.tickFireControl(cannonEntity, trigger);
            }
        }
    }

    public boolean controlsCannonContraption(PitchOrientedContraptionEntity cannon) {
        if (level == null || cannon == null || cannon.isRemoved()) return false;
        for (BindingRef mount : cannonMounts) {
            if (CannonMountCompat.getMountedCannonEntity(level, mount) == cannon) return true;
        }
        return false;
    }

    /**
     * A yaw shared by several elevation bearings is commanded once. Their
     * bound barrel references form one physical aiming centre, while each
     * elevation bearing still receives its own mechanical pitch command.
     */
    private List<List<TurretGroup>> aimClusters(List<TurretGroup> groups) {
        List<List<TurretGroup>> clusters = new ArrayList<>();
        Set<TurretGroup> remaining = new LinkedHashSet<>(groups);
        while (!remaining.isEmpty()) {
            TurretGroup seed = remaining.iterator().next();
            List<TurretGroup> cluster = new ArrayList<>();
            cluster.add(seed);
            remaining.remove(seed);
            Set<BindingRef> yaws = new HashSet<>(seed.yaws());
            boolean expanded;
            do {
                expanded = false;
                for (Iterator<TurretGroup> iterator = remaining.iterator(); iterator.hasNext();) {
                    TurretGroup candidate = iterator.next();
                    boolean sharesYaw = candidate.yaws().stream().anyMatch(yaws::contains);
                    if (!sharesYaw) continue;
                    cluster.add(candidate);
                    yaws.addAll(candidate.yaws());
                    iterator.remove();
                    expanded = true;
                }
            } while (expanded);
            clusters.add(List.copyOf(cluster));
        }
        return List.copyOf(clusters);
    }

    private @Nullable AimReference measureAimReference(List<TurretGroup> groups) {
        Vec3 originSum = Vec3.ZERO;
        Vec3 directionSum = Vec3.ZERO;
        int count = 0;
        for (TurretGroup group : groups) {
            AimReference measured = measureAimReference(group);
            if (measured == null) continue;
            originSum = originSum.add(measured.origin());
            directionSum = directionSum.add(measured.direction());
            count++;
        }
        return count == 0 || directionSum.lengthSqr() < 1.0e-8 ? null
                : new AimReference(originSum.scale(1.0 / count), directionSum.normalize());
    }

    private void applyAimDirectionFor(
            @Nullable BindingRef yawRef,
            @Nullable BindingRef pitchRef,
            @Nullable Vec3 measuredWorldDirection,
            @Nullable Vec3 aimOrigin) {
        if (measuredWorldDirection == null || aimOrigin == null
                || measuredWorldDirection.lengthSqr() < 1.0e-8) return;
        Vec3 requested = hasAimTarget
                ? desiredWorldTarget.subtract(aimOrigin) : desiredWorldDirection;
        if (requested.lengthSqr() < 1.0e-8) return;
        requested = clampPitchRequestToTurret(pitchRef, requested.normalize(), measuredWorldDirection.normalize());
        commandBearing(yawRef, true, yawSpeedDegreesPerSecond,
                measuredWorldDirection, requested, yawReversed ? -1.0 : 1.0,
                depressionLimitDegrees, elevationLimitDegrees, yawOffset);
        commandBearing(pitchRef, false, pitchSpeedDegreesPerSecond,
                measuredWorldDirection, requested, pitchReversed ? -1.0 : 1.0,
                depressionLimitDegrees, elevationLimitDegrees, pitchOffset);
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
        return clampPitchRequestToTurret(getBinding(pitchSlot), requested, measured);
    }

    private Vec3 clampPitchRequestToTurret(
            @Nullable BindingRef pitchRef,
            Vec3 requested,
            Vec3 measured
    ) {
        Vec3 turretUp = turretUpFor(pitchRef);
        double vertical = Mth.clamp(requested.dot(turretUp), -1.0, 1.0);
        double elevation = Math.asin(vertical);
        BearingSettings pitchEnvelope = getBearingSettings(BindingSlot.PITCH, pitchRef);
        double clampedElevation = Mth.clamp(
                elevation,
                -Math.toRadians(pitchEnvelope.depression()),
                Math.toRadians(pitchEnvelope.elevation())
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

    private Vec3 turretUpFor(@Nullable BindingRef pitchRef) {
        Vec3 turretUp = new Vec3(0.0, 1.0, 0.0);
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
        return turretUp;
    }

    private double applyAxisSign(BindingSlot slot, double angle, Vector3d controllerRight) {
        BindingRef ref = bindings.get(slot);
        BearingSettings settings = getBearingSettings(slot, ref);
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
        BearingSettings settings = getBearingSettings(slot, ref);
        double automaticSign = 1.0;
        AimDirectionDetector detector = slot == BindingSlot.YAW
                ? turretYawDirection
                : slot == BindingSlot.PITCH ? turretPitchDirection : null;
        boolean manuallyReversed = settings.reversed();
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
        commandBearing(ref, isYawSlot(slot), settings.speed(),
                measuredWorldDirection, requestedWorldDirection, automaticSign,
                settings.depression(), settings.elevation(), settings.offset());
        if (learnDirection && !manuallyReversed && detector != null && ref != null && level != null
                && ref.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
            detector.recordCommand(
                    ref, bearing, measuredWorldDirection, requestedWorldDirection,
                    automaticSign, settings.speed());
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
        commandBearing(ref, yaw, speedDegreesPerSecond, measuredWorldDirection, requestedWorldDirection,
                errorScale, depressionLimit, elevationLimit, localOffset, List.of(), null);
    }

    private void commandBearing(@Nullable BindingRef ref, boolean yaw, double speedDegreesPerSecond,
            Vec3 measuredWorldDirection, Vec3 requestedWorldDirection, double errorScale,
            double depressionLimit, double elevationLimit, Vec3 localOffset,
            List<FiniteAimTarget> finiteTargets, @Nullable Vec3 operatorDirection) {
        if (ref == null || level == null) {
            return;
        }
        if (ref.resolve(level) instanceof SwivelBearingBlockEntity bearing && bearing.isAssembled()) {
            if (yaw) BearingStabilizer.setYawLimits(bearing, getBearingSettings(BindingSlot.YAW, ref).yawLimits());
            double mechanicalDegreesPerSecond = Math.abs(KineticBlockEntity.convertToAngular(
                    bearing.getExtraKinetics().getSpeed()
            )) * 20.0;
            double effectiveSpeed = mechanicalDegreesPerSecond > 1.0e-4
                    ? Math.min(speedDegreesPerSecond, mechanicalDegreesPerSecond)
                    : speedDegreesPerSecond;
            Vec3 scaledRequest = scaledBearingRequest(
                    bearing, measuredWorldDirection, requestedWorldDirection, errorScale);
            BearingStabilizer.commandFiniteAim(
                    bearing,
                    measuredWorldDirection,
                    scaledRequest,
                    yaw,
                    effectiveSpeed,
                    depressionLimit,
                    elevationLimit,
                    localOffset, Math.abs(errorScale - 1.0) < 1e-9 ? finiteTargets : List.of(), operatorDirection,
                    stabilizerEnabled
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
        getBinding(BindingSlot.WIRELESS_CONTROLLER_HUB);
        boolean changed = false;
        Iterator<Map.Entry<BindingSlot, BindingRef>> iterator = bindings.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BindingSlot, BindingRef> entry = iterator.next();
            // A moving/unloaded terminal can temporarily be absent from the loaded directory.
            // Retain its persistent identity, but hasTerminal must still validate it before use.
            if (entry.getKey() == BindingSlot.WIRELESS_CONTROLLER_HUB
                    && entry.getValue().hasWirelessProcessorIdentity()) continue;
            if (!entry.getValue().isSameTarget(level)) {
                iterator.remove();
                changed = true;
            }
        }
        if (yawBearings.removeIf(binding -> !binding.isSameTarget(level))) changed = true;
        if (pitchBearings.removeIf(binding -> !binding.isSameTarget(level))) changed = true;
        bearingSettings.keySet().removeIf(ref -> !yawBearings.contains(ref) && !pitchBearings.contains(ref));
        pitchCalibrationAttempted.retainAll(pitchBearings);
        syncLegacyBearingBindings();
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
        if (aaMissileLaunchers.removeIf(binding ->
                !MianbaoAirDefenseCompat.isBoundLauncherPresent(level, binding))) {
            aaMissileFireCursor = aaMissileLaunchers.isEmpty()
                    ? 0 : Math.floorMod(aaMissileFireCursor, aaMissileLaunchers.size());
            changed = true;
        }
        if (aircraftMissileRacks.removeIf(binding -> !binding.isSameTarget(level))) {
            aircraftMissileFireCursor = aircraftMissileRacks.isEmpty() ? 0 : Math.floorMod(aircraftMissileFireCursor, aircraftMissileRacks.size());
            changed = true;
        }
        if (missileDirectionAnchors.removeIf(ref -> !isBoundMissileDirectionAnchor(ref))) {
            changed = true;
        }
        if (aircraftRocketPods.removeIf(binding -> !binding.isSameTarget(level))) {
            aircraftRocketFireCursor = aircraftRocketPods.isEmpty()
                    ? 0 : Math.floorMod(aircraftRocketFireCursor, aircraftRocketPods.size());
            changed = true;
        }
        if (cannonTurretPods.removeIf(binding ->
                !binding.isSameTarget(level)
                        || (level.hasChunkAt(binding.pos())
                        && !MianbaoCannonTurretPodCompat.isCannonTurret(
                        level, binding.pos())))) {
            changed = true;
        }
        if (countermeasureLaunchers.removeIf(binding -> !binding.isSameTarget(level))) {
            countermeasureFireCursor = countermeasureLaunchers.isEmpty()
                    ? 0 : Math.floorMod(
                    countermeasureFireCursor, countermeasureLaunchers.size());
            changed = true;
        }
        if (remoteWeaponStations.removeIf(binding ->
                !binding.isSameTarget(level)
                        || (level.hasChunkAt(binding.pos())
                        && !MianbaoRemoteWeaponStationCompat.isStation(level, binding.pos())))) {
            changed = true;
        }
        remoteWeaponStationHoldDirections.keySet().retainAll(remoteWeaponStations);
        if (iffTransponders.removeIf(binding -> !binding.isSameTarget(level))) {
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
        removed |= yawBearings.removeIf(binding -> binding.matches(targetLevel, targetPos));
        removed |= pitchBearings.removeIf(binding -> binding.matches(targetLevel, targetPos));
        bearingSettings.keySet().removeIf(ref -> ref.matches(targetLevel, targetPos));
        syncLegacyBearingBindings();
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
        if (missileDirectionAnchors.removeIf(
                binding -> binding.matches(targetLevel, targetPos))) {
            removed = true;
        }
        if (aircraftRocketPods.removeIf(binding -> binding.matches(targetLevel, targetPos))) {
            aircraftRocketFireCursor = aircraftRocketPods.isEmpty()
                    ? 0 : Math.floorMod(aircraftRocketFireCursor, aircraftRocketPods.size());
            removed = true;
        }
        if (cannonTurretPods.removeIf(
                binding -> binding.matches(targetLevel, targetPos))) {
            removed = true;
        }
        if (countermeasureLaunchers.removeIf(
                binding -> binding.matches(targetLevel, targetPos))) {
            countermeasureFireCursor = countermeasureLaunchers.isEmpty()
                    ? 0 : Math.floorMod(
                    countermeasureFireCursor, countermeasureLaunchers.size());
            removed = true;
        }
        if (remoteWeaponStations.removeIf(
                binding -> binding.matches(targetLevel, targetPos))) {
            remoteWeaponStationHoldDirections.keySet().retainAll(remoteWeaponStations);
            removed = true;
        }
        if (iffTransponders.removeIf(
                binding -> binding.matches(targetLevel, targetPos))) {
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

    /** Game tick of the positions in the current radar contact snapshot. */
    long getRadarContactSampleTick() {
        return lastRadarScanTick;
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
        return !aircraftMissileRacks.isEmpty() || !aircraftRocketPods.isEmpty()
                || bindings.containsKey(BindingSlot.AIRCRAFT_RADAR);
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
    public double getRwrRange() { return rwrRange; }
    public double getRwrScale() { return rwrScale; }
    public boolean isAircraftRocketSalvoMode() { return aircraftRocketSalvoMode; }
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
            for (BindingRef pod : aircraftRocketPods) {
                Vec3 muzzle = MianbaoRocketPodCompat.muzzle(level, pod);
                if (muzzle != null) {
                    origin = muzzle;
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
        if (MianbaoAircraftCompat.isAntiRadiation(selectedAircraftWeapon)) {
            return;
        }
        if (gunner == null || !isRadarActive()) return;
        if (radarLockedSubLevelId != null || RadarLockMemory.displayLock(this) != null) {
            RadarLockMemory.clearLock(this);
            radarLockedSubLevelId = null;
            setChanged();
            return;
        }
        if (isAircraftRadarMode() && aircraftSelectedTargetId != null) {
            RadarContact selected = getRadarContact(aircraftSelectedTargetId);
            if (selected != null) {
                setRadarLockedTarget(selected.subLevelId());
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
            setRadarLockedTarget(best.subLevelId());
            setChanged();
        }
    }

    public void toggleRadarLock(ServerPlayer player, @Nullable UUID requestedTarget) {
        if (player == null || findSeatedGunner() != player || !isRadarActive()) return;
        if (requestedTarget == null || requestedTarget.equals(RadarLockMemory.displayLock(this))) {
            RadarLockMemory.clearLock(this);
            radarLockedSubLevelId = null;
            setChanged();
            return;
        }
        boolean scanned = radarContacts.stream()
                .anyMatch(contact -> contact.subLevelId().equals(requestedTarget));
        if (scanned) {
            setRadarLockedTarget(requestedTarget);
            setChanged();
        }
    }

    private void updateRadarScan() {
        if (level == null || level.isClientSide) return;
        long now = level.getGameTime();
        if (!isRadarActive()) {
            RadarLockMemory.clear(this);
            radarContacts = List.of();
            radarVisibility.clear();
            previousRadarCenters.clear();
            radarLockedSubLevelId = null;
            return;
        }
        if (lastRadarScanTick != Long.MIN_VALUE && now - lastRadarScanTick < 2L) return;
        long elapsed = lastRadarScanTick == Long.MIN_VALUE ? 2L : Math.max(1L, now - lastRadarScanTick);
        lastRadarScanTick = now;
        Vec3 origin = getRadarOrigin();
        Set<UUID> own = ownControlledSubLevelIds();
        Set<String> iffChannels = com.hooya.stabilizedturret.content.iff
                .IffIdentification.channelsForStructure(level, own);
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
            if (com.hooya.stabilizedturret.content.iff.IffIdentification
                    .sharesEnabledChannel(level, iffChannels, members)) continue;
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
        addRadarMissileContacts(
                contacts, currentCenters, origin, loaded, iffChannels);
        Set<UUID> visible = radarVisibility.scan(level, origin, own, contacts.stream()
                .map(contact -> new RadarVisibility.Target(contact.subLevelId(), contact.center(),
                        contact.bounds(), contact.memberIds())).toList(),
                RadarVisibility.lockPriority(RadarLockMemory.displayLock(this), aircraftSelectedTargetId));
        contacts.removeIf(contact -> !visible.contains(contact.subLevelId()));
        contacts.sort(java.util.Comparator.comparingDouble(contact -> contact.center().distanceToSqr(origin)));
        if (contacts.size() > 20) contacts = new ArrayList<>(contacts.subList(0, 20));
        radarContacts = List.copyOf(contacts);
        aircraftSelectedTargetId = RadarLockMemory.updateSelection(this, aircraftSelectedTargetId, now);
        if (isAircraftRadarMode() && aircraftSelectedTargetId == null) {
            aircraftSelectedTargetId = radarContacts.isEmpty() ? null : radarContacts.get(0).subLevelId();
            RadarLockMemory.updateSelection(this, aircraftSelectedTargetId, now);
        }
        previousRadarCenters.clear();
        previousRadarCenters.putAll(currentCenters);
        UUID previousLock = radarLockedSubLevelId;
        radarLockedSubLevelId = RadarLockMemory.updateLock(this, radarLockedSubLevelId, now);
        if (!java.util.Objects.equals(previousLock, radarLockedSubLevelId)) {
            setChanged();
        }
    }

    /**
     * Adds Mianbao land-attack missiles as independent radar tracks. Missile
     * entities may live either in the root level or in a moving Sable
     * sublevel, so both entity stores are inspected and projected into the
     * radar's world coordinate system.
     */
    private void addRadarMissileContacts(
            List<RadarContact> contacts,
            Map<UUID, Vec3> currentCenters,
            Vec3 origin,
            List<SubLevel> loadedSubLevels,
            Set<String> iffChannels
    ) {
        if (level == null) return;
        Set<UUID> visited = new HashSet<>();
        // Sable plots can share the root entity store. Inspect each store once,
        // rather than walking every entity again for every scanned structure.
        Set<ServerLevel> scannedLevels = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        Level rootLevel = container.getLevel();
        if (rootLevel instanceof ServerLevel root) {
            scannedLevels.add(root);
            scanRadarMissilesInLevel(
                    contacts, currentCenters, origin, visited, root, iffChannels);
        }
        for (SubLevel subLevel : loadedSubLevels) {
            if (subLevel.getLevel() instanceof ServerLevel server && scannedLevels.add(server)) {
                scanRadarMissilesInLevel(
                        contacts, currentCenters, origin, visited, server, iffChannels);
            }
        }
    }

    private void scanRadarMissilesInLevel(
            List<RadarContact> contacts,
            Map<UUID, Vec3> currentCenters,
            Vec3 origin,
            Set<UUID> visited,
            ServerLevel source,
            Set<String> iffChannels
    ) {
        double activeRange = isAircraftRadarMode() ? aircraftRadarRange : radarRange;
        for (net.minecraft.world.entity.Entity missile : source.getAllEntities()) {
            if (!missile.isAlive()
                    || (!MianbaoCruiseMissileCompat.isRadarMissile(missile)
                    && !MianbaoAircraftCompat.isAirToGroundMissile(missile))
                    || !visited.add(missile.getUUID())) {
                continue;
            }
            if (com.hooya.stabilizedturret.content.iff.FireControlMissileOwnership
                    .isFriendly(level, iffChannels, missile)) continue;
            AABB bounds = projectRadarEntityBounds(source, missile.getBoundingBox());
            if (!isFinite(bounds) || distanceToBoundsSqr(origin, bounds) > activeRange * activeRange) {
                continue;
            }
            Vec3 center = bounds.getCenter();
            boolean inVolume = isAircraftRadarMode()
                    ? pointInAircraftRadarCone(origin, center)
                    : boundsIntersectsElevationWindow(
                    origin, bounds, radarDepression, radarElevation);
            if (!inVolume) continue;

            Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
            if (isAircraftRadarMode()
                    && velocity.length() * 20.0 < aircraftRadarMinimumTargetSpeed) {
                continue;
            }
            currentCenters.put(missile.getUUID(), center);
            contacts.add(new RadarContact(
                    missile.getUUID(), center, velocity, bounds, Set.of(), true));
        }
    }

    private static AABB projectRadarEntityBounds(Level source, AABB localBounds) {
        AABB projected = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 world = Sable.HELPER.projectOutOfSubLevel(source, new Vec3(
                    (corner & 1) == 0 ? localBounds.minX : localBounds.maxX,
                    (corner & 2) == 0 ? localBounds.minY : localBounds.maxY,
                    (corner & 4) == 0 ? localBounds.minZ : localBounds.maxZ));
            AABB point = new AABB(world, world);
            projected = projected == null ? point : unionBounds(projected, point);
        }
        return projected == null ? localBounds : projected;
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
        maintainBearings(yawBearings, true, acceptMechanicalInput, stabilizeWorld);
        maintainBearings(pitchBearings, false, acceptMechanicalInput, stabilizeWorld);
        if (level == null) return;
        for (BindingRef yaw : yawBearings) {
            if (yaw.resolve(level) instanceof SwivelBearingBlockEntity bearing)
                BearingStabilizer.maintainAzimuthObservations(bearing,List.of());
        }
        for (List<TurretGroup> cluster : aimClusters(turretGroups())) {
            AimReference centre = measureAimReference(cluster);
            if (centre == null) continue;
            List<BearingStabilizer.AzimuthObservation> observations = new ArrayList<>();
            boolean complete = true;
            for (TurretGroup group : cluster) {
                AimReference measured = measureAimReference(group);
                UUID bodyId = measured == null ? null : measured.bodyId();
                if (measured == null || bodyId == null) { complete = false; break; }
                observations.add(new BearingStabilizer.AzimuthObservation(bodyId,measured.direction()));
            }
            if (!complete) observations = List.of();
            Set<BindingRef> measuredYaw = new HashSet<>();
            for (TurretGroup group : cluster) {
                for (BindingRef yaw : group.yaws()) {
                    if (measuredYaw.add(yaw) && yaw.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
                        BearingStabilizer.maintainDirectionReference(bearing, centre.direction());
                        BearingStabilizer.maintainAzimuthObservations(bearing,observations);
                    }
                }
                AimReference measured = measureAimReference(group);
                if (measured != null && group.pitch() != null
                        && group.pitch().resolve(level) instanceof SwivelBearingBlockEntity bearing) {
                    BearingStabilizer.maintainDirectionReference(bearing, measured.direction(), measured.bodyId());
                }
            }
        }
    }

    private void applyStabilizerImplementation() {
        if (level==null || level.isClientSide) return;
        Set<BindingRef> refs=new HashSet<>(yawBearings);
        refs.addAll(pitchBearings);
        for (BindingRef ref:refs) {
            if (ref.resolve(level) instanceof SwivelBearingBlockEntity bearing)
                BearingStabilizer.setImplementation(bearing,stabilizerReactionEnabled);
        }
    }

    private void syncBearingHeadVisibility() {
        if (level == null || level.isClientSide) {
            return;
        }
        syncConfiguredBearingHeadVisibility(BindingSlot.YAW, yawBearings);
        syncConfiguredBearingHeadVisibility(BindingSlot.PITCH, pitchBearings);
    }

    private void syncBearingHeadVisibility(List<BindingRef> refs, boolean hidden) {
        if (level == null) return;
        for (BindingRef ref : refs) {
            if (ref.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
                setBearingHeadHidden(bearing, hidden);
            }
        }
    }

    private void syncConfiguredBearingHeadVisibility(
            BindingSlot slot,
            List<BindingRef> refs
    ) {
        if (level == null) return;
        for (BindingRef ref : refs) {
            if (ref.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
                setBearingHeadHidden(bearing, getBearingSettings(slot, ref).headHidden());
            }
        }
    }

    private static void setBearingHeadHidden(SwivelBearingBlockEntity bearing, boolean hidden) {
        Level bearingLevel = bearing.getLevel();
        BlockPos platePos = bearing.getPlatePos();
        if (bearingLevel != null && platePos != null
                && bearingLevel.getBlockEntity(platePos) instanceof BearingHeadVisibility visibility) {
            visibility.stabilizedTurret$setBearingHeadHidden(hidden);
        }
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
                if (yaw) BearingStabilizer.setYawLimits(bearing, getBearingSettings(slot, ref).yawLimits());
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

    private void maintainBearings(
            List<BindingRef> refs,
            boolean yaw,
            boolean acceptMechanicalInput,
            boolean stabilizeWorld
    ) {
        for (BindingRef ref : refs) {
            if (level != null && ref.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
                BearingSettings settings = getBearingSettings(yaw ? BindingSlot.YAW : BindingSlot.PITCH, ref);
                if (yaw) BearingStabilizer.setYawLimits(bearing, settings.yawLimits());
                BearingStabilizer.maintainBinding(
                        bearing, yaw, true, stabilizeWorld, acceptMechanicalInput,
                        settings.speed(), settings.depression(), settings.elevation(),
                        settings.offset());
                setBearingHeadHidden(bearing, settings.headHidden());
            }
        }
    }

    private void calibratePitchFromCannon() {
        if (level == null) {
            return;
        }
        for (BindingRef yaw : yawBearings) {
            if (!getBearingSettings(BindingSlot.YAW, yaw).yawLimits().enabled()) continue;
            if (yaw.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
                AimReference measured = turretGroups().stream().filter(group -> group.yaws().contains(yaw))
                        .map(this::measureAimReference).filter(java.util.Objects::nonNull).findFirst().orElse(null);
                if (measured != null) BearingStabilizer.calibrateYawDirection(bearing, measured.direction());
            }
        }
        for (BindingRef pitch : pitchBearings) {
            if (pitchCalibrationAttempted.contains(pitch)) continue;
            if (pitch.resolve(level) instanceof SwivelBearingBlockEntity bearing) {
                AimReference measured = turretGroups().stream()
                        .filter(group -> pitch.equals(group.pitch()))
                        .map(this::measureAimReference).filter(java.util.Objects::nonNull)
                        .findFirst().orElse(null);
                if (measured != null) {
                    BearingStabilizer.calibrateElevationDirection(bearing, measured.direction());
                    pitchCalibrationAttempted.add(pitch);
                }
            }
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
        if (level == null || !cannonMountPodControl || !isPodViewEnabled()) {
            podCannonSolutions.clear();
            return;
        }
        Vec3 target = getPodGuidanceTarget();
        if (target == null) return;
        long now = level.getGameTime();
        podCannonSolutions.keySet().removeIf(ref -> !cannonMounts.contains(ref));
        for (BindingRef mount : cannonMounts) {
            if (!mount.isSameTarget(level)
                    || !CannonMountCompat.hasAssembledCannon(level, mount)) continue;
            PodCannonSolution cached = podCannonSolutions.get(mount);
            boolean targetStable = cached != null
                    && cached.target().distanceToSqr(target) <= 0.25;
            Vec3 launchDirection;
            if (cached != null && targetStable && now >= cached.tick()
                    && now - cached.tick() < POD_CANNON_SOLUTION_CACHE_TICKS) {
                launchDirection = cached.direction();
            } else {
                CannonBallisticsCompat.ShotProfile profile =
                        CannonBallisticsCompat.analyze(level, mount);
                profile = PodControlledCannonBallistics.apply(profile);
                launchDirection = getCannonBallisticLaunchDirection(mount, profile, target);
                podCannonSolutions.put(mount,
                        new PodCannonSolution(now, target, launchDirection));
            }
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

    /** Keeps aircraft cannon pods on the optical line of sight and handles redstone fire. */
    private void updateCannonTurretPods() {
        if (level == null || cannonTurretPods.isEmpty()) return;
        Vec3 target = bindings.containsKey(BindingSlot.POD) && isPodViewEnabled()
                ? getPodGuidanceTarget() : null;
        for (BindingRef cannonPod : cannonTurretPods) {
            if (!cannonPod.isSameTarget(level)
                    || !MianbaoCannonTurretPodCompat.isCannonTurret(
                    level, cannonPod.pos())) continue;
            if (target != null) {
                MianbaoCannonTurretPodCompat.pointMuzzleAt(level, cannonPod, target);
            }
            MianbaoCannonTurretPodCompat.tickPoweredFire(level, cannonPod);
        }
    }

    private record TurretGroup(List<BindingRef> yaws,
                               @Nullable BindingRef pitch,
                               List<BindingRef> cannonMounts,
                               List<BindingRef> otherReferences) {}

    private record AimReference(Vec3 origin, Vec3 direction, UUID bodyId) {
        private AimReference(Vec3 origin, Vec3 direction) { this(origin, direction, null); }
    }

    private record PodCannonSolution(long tick, Vec3 target, @Nullable Vec3 direction) {
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
                "火控计算机：%s | 方向机：%s | 高低机：%s | 座椅：%s | 火炮底座：%s | 导弹发射器：%s | 俯角 %.1f° / 仰角 %.1f° | 转速 %.1f°/s / %.1f°/s",
                stabilizerEnabled ? "开启" : "关闭",
                bindings.containsKey(BindingSlot.YAW) ? "已绑定" : "未绑定",
                bindings.containsKey(BindingSlot.PITCH) ? "已绑定" : "未绑定",
                bindings.containsKey(BindingSlot.SEAT) ? "已绑定" : "未绑定",
                bindings.containsKey(BindingSlot.CANNON_MOUNT) ? "已绑定" : "未绑定",
                missileDirectionAnchors.isEmpty() ? "未绑定" : "已绑定",
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
                    || slot == BindingSlot.AIRCRAFT_ROCKET_POD
                    || slot == BindingSlot.CANNON_TURRET_POD
                    || slot == BindingSlot.COUNTERMEASURE_LAUNCHER
                    || slot == BindingSlot.REMOTE_WEAPON_STATION
                    || slot == BindingSlot.LASER_DESIGNATOR
                    || isPodBearingRole(slot)) {
                continue;
            }
            BindingRef ref = bindings.get(slot);
            if (ref != null) {
                tag.put("Binding_" + slot.name(), ref.save());
            }
        }
        tag.remove("Binding_" + BindingSlot.LASER_DESIGNATOR.name());
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
        ListTag missileAnchorTags = new ListTag();
        for (BindingRef anchor : missileDirectionAnchors) {
            if (isBoundMissileDirectionAnchor(anchor)) missileAnchorTags.add(anchor.save());
        }
        tag.put("MissileDirectionAnchorBindings", missileAnchorTags);
        ListTag aircraftRocketPodTags = new ListTag();
        for (BindingRef pod : aircraftRocketPods) aircraftRocketPodTags.add(pod.save());
        tag.put("AircraftRocketPodBindings", aircraftRocketPodTags);
        tag.putInt("AircraftRocketFireCursor", aircraftRocketFireCursor);
        ListTag cannonTurretPodTags = new ListTag();
        for (BindingRef pod : cannonTurretPods) cannonTurretPodTags.add(pod.save());
        tag.put("CannonTurretPodBindings", cannonTurretPodTags);
        ListTag countermeasureLauncherTags = new ListTag();
        for (BindingRef launcher : countermeasureLaunchers) {
            countermeasureLauncherTags.add(launcher.save());
        }
        tag.put("CountermeasureLauncherBindings", countermeasureLauncherTags);
        tag.putInt("CountermeasureFireCursor", countermeasureFireCursor);
        ListTag remoteWeaponStationTags = new ListTag();
        for (BindingRef station : remoteWeaponStations) {
            remoteWeaponStationTags.add(station.save());
        }
        tag.put("RemoteWeaponStationBindings", remoteWeaponStationTags);
        ListTag iffTransponderTags = new ListTag();
        for (BindingRef transponder : iffTransponders) {
            iffTransponderTags.add(transponder.save());
        }
        tag.put("IffTransponderBindings", iffTransponderTags);
        ListTag bearingTags = new ListTag();
        for (BindingRef bearing : unassignedBearings) bearingTags.add(bearing.save());
        tag.put("UnassignedBearingBindings", bearingTags);
        ListTag yawBearingTags = new ListTag();
        for (BindingRef bearing : yawBearings) yawBearingTags.add(bearing.save());
        tag.put("YawBearingBindings", yawBearingTags);
        ListTag pitchBearingTags = new ListTag();
        for (BindingRef bearing : pitchBearings) pitchBearingTags.add(bearing.save());
        tag.put("PitchBearingBindings", pitchBearingTags);
        // Remove retired pod-bearing data when an older controller is saved.
        tag.remove("Binding_" + BindingSlot.POD_YAW.name());
        tag.remove("Binding_" + BindingSlot.POD_PITCH.name());
        tag.remove("PodYawBearingBindings");
        tag.remove("PodPitchBearingBindings");
        tag.putInt("SelectedAircraftWeapon", selectedAircraftWeapon.ordinal());
        tag.putBoolean("AircraftWeaponSelectionActive", aircraftWeaponSelectionActive);
        if (selectedGroundWeapon != null) tag.putString("SelectedGroundWeapon", selectedGroundWeapon.name());
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
        tag.putDouble("RwrRange", rwrRange);
        tag.putDouble("RwrScale", rwrScale);
        tag.putBoolean("AircraftRocketSalvoMode", aircraftRocketSalvoMode);
        if (radarLockedSubLevelId != null) {
            tag.putUUID("RadarLockedSubLevel", radarLockedSubLevelId);
            tag.putUUID("RadarCameraAcquisition", radarCameraAcquisitionId);
        }
        tag.putDouble("DepressionLimit", depressionLimitDegrees);
        tag.putDouble("ElevationLimit", elevationLimitDegrees);
        tag.putDouble("YawSpeed", yawSpeedDegreesPerSecond);
        yawLimits.save(tag);
        tag.putDouble("PitchSpeed", pitchSpeedDegreesPerSecond);
        tag.putBoolean("YawReversed", yawReversed);
        tag.putBoolean("PitchReversed", pitchReversed);
        tag.putBoolean("YawBearingHeadHidden", yawBearingHeadHidden);
        tag.putBoolean("PitchBearingHeadHidden", pitchBearingHeadHidden);
        tag.putDouble("ThirdPersonHeight", thirdPersonHeight);
        tag.putDouble("ViewAimOffsetX", viewAimOffsetX);
        tag.putDouble("ViewAimOffsetY", viewAimOffsetY);
        tag.putDouble("YawOffsetX", yawOffset.x);
        tag.putDouble("YawOffsetY", yawOffset.y);
        tag.putDouble("YawOffsetZ", yawOffset.z);
        tag.putDouble("PitchOffsetX", pitchOffset.x);
        tag.putDouble("PitchOffsetY", pitchOffset.y);
        tag.putDouble("PitchOffsetZ", pitchOffset.z);
        ListTag bearingSettingsTags = new ListTag();
        bearingSettings.forEach((ref, settings) -> {
            CompoundTag entry = new CompoundTag();
            entry.put("Ref", ref.save());
            entry.putDouble("Depression", settings.depression());
            entry.putDouble("Elevation", settings.elevation());
            entry.putDouble("Speed", settings.speed());
            settings.yawLimits().save(entry);
            entry.putBoolean("Reversed", settings.reversed());
            entry.putBoolean("HeadHidden", settings.headHidden());
            entry.putDouble("OffsetX", settings.offset().x);
            entry.putDouble("OffsetY", settings.offset().y);
            entry.putDouble("OffsetZ", settings.offset().z);
            bearingSettingsTags.add(entry);
        });
        tag.put("BearingSettings", bearingSettingsTags);
        tag.putDouble("ScopeOffsetX", scopeOffset.x);
        tag.putBoolean("VirtualScopeEnabled", virtualScopeEnabled);
        tag.putDouble("ScopeOffsetY", scopeOffset.y);
        tag.putDouble("ScopeOffsetZ", scopeOffset.z);
        tag.putString("ScopePreset", scopePreset.serializedName());
        tag.putDouble("AimX", desiredWorldDirection.x);
        tag.putDouble("AimY", desiredWorldDirection.y);
        tag.putDouble("AimZ", desiredWorldDirection.z);
        tag.putBoolean("HasAim", hasAimDirection);
        tag.putDouble("PodAimX", podDesiredWorldDirection.x);
        tag.putDouble("PodAimY", podDesiredWorldDirection.y);
        tag.putDouble("PodAimZ", podDesiredWorldDirection.z);
        tag.putBoolean("HasPodAim", hasPodAimDirection);
        tag.putBoolean("StabilizerEnabled", stabilizerEnabled);
        tag.putBoolean("StabilizerReactionEnabled", stabilizerReactionEnabled);
        tag.putDouble(CannonRecoilSettings.TAG, cannonRecoilMagnitude);
        tag.putBoolean("MobileShotCompensationEnabled", mobileShotCompensationEnabled);
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
        aircraftWeaponCycleUpReceiver.save(tag, "AircraftWeaponCycleUpWireless", registries);
        aircraftTargetCycleReceiver.save(tag, "AircraftTargetCycleWireless", registries);
        groundTargetCycleReceiver.save(tag, "GroundTargetCycleWireless", registries);
        podViewReceiver.save(tag, "PodViewWireless", registries);
        airToGroundLockReceiver.save(tag, "AirToGroundLockWireless", registries);
        countermeasureFireReceiver.save(tag, "CountermeasureFireWireless", registries);
        freeLookReceiver.save(tag, "FreeLookWireless", registries);
        televisionGuidanceReceiver.save(tag, "TelevisionGuidanceWireless", registries);
        weaponViewReceiver.save(tag, "WeaponViewWireless", registries);
        machineGunFireReceiver.save(tag, "MachineGunFireWireless", registries);
        laserRangeReceiver.save(tag, "LaserRangeWireless", registries);
        for (int index = 0; index < weaponSelectionReceivers.length; index++)
            weaponSelectionReceivers[index].save(tag, "WeaponSelectionWireless" + index, registries);
        ListTag rangeTags = new ListTag();
        for (var entry : laserRanges.entrySet()) {
            CompoundTag saved = new CompoundTag();
            saved.put("Weapon", entry.getKey().save());
            saved.putDouble("Distance", entry.getValue().distance());
            saved.putDouble("Elevation", entry.getValue().elevationRadians());
            rangeTags.add(saved);
        }
        tag.put("LaserRanges", rangeTags);
        saveRemoteKeys(tag, "WirelessRemoteKeys", wirelessRemoteKeys);
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
            if (type == RemoteControllerCompat.Type.WIRELESS
                    ? !com.hooya.stabilizedturret.content.remote.ControllerInput.chordInputs(value).isEmpty()
                    : DriveByWireCompat.accepts(type, value)) values[index] = value;
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        nearTerrainAimGuards.clear();
        bindings.clear();
        yawBearings.clear();
        pitchBearings.clear();
        pitchCalibrationAttempted.clear();
        cannonMounts.clear();
        missileLaunchers.clear();
        aaMissileLaunchers.clear();
        aircraftMissileRacks.clear();
        missileDirectionAnchors.clear();
        aircraftRocketPods.clear();
        cannonTurretPods.clear();
        countermeasureLaunchers.clear();
        remoteWeaponStations.clear();
        remoteWeaponStationHoldDirections.clear();
        iffTransponders.clear();
        unassignedBearings.clear();
        invalidateOwnSubLevelCache();
        for (BindingSlot slot : BindingSlot.values()) {
            if (slot == BindingSlot.BEARING
                    || slot == BindingSlot.CANNON_MOUNT
                    || slot == BindingSlot.MISSILE_LAUNCHER
                    || slot == BindingSlot.AA_MISSILE_LAUNCHER
                    || slot == BindingSlot.AIRCRAFT_MISSILE_RACK
                    || slot == BindingSlot.AIRCRAFT_ROCKET_POD
                    || slot == BindingSlot.CANNON_TURRET_POD
                    || slot == BindingSlot.COUNTERMEASURE_LAUNCHER
                    || slot == BindingSlot.REMOTE_WEAPON_STATION
                    || slot == BindingSlot.LASER_DESIGNATOR
                    || isPodBearingRole(slot)) {
                continue;
            }
            BindingRef ref = BindingRef.load(tag, "Binding_" + slot.name());
            if (ref != null) {
                bindings.put(slot, ref);
            }
        }
        ListTag yawBearingTags = tag.getList("YawBearingBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < yawBearingTags.size(); index++) {
            BindingRef bearing = BindingRef.loadTag(yawBearingTags.getCompound(index));
            if (!yawBearings.contains(bearing)) yawBearings.add(bearing);
        }
        ListTag pitchBearingTags = tag.getList("PitchBearingBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < pitchBearingTags.size(); index++) {
            BindingRef bearing = BindingRef.loadTag(pitchBearingTags.getCompound(index));
            if (!pitchBearings.contains(bearing)) pitchBearings.add(bearing);
        }
        if (yawBearings.isEmpty()) {
            BindingRef legacy = bindings.get(BindingSlot.YAW);
            if (legacy != null) yawBearings.add(legacy);
        }
        if (pitchBearings.isEmpty()) {
            BindingRef legacy = bindings.get(BindingSlot.PITCH);
            if (legacy != null) pitchBearings.add(legacy);
        }
        syncLegacyBearingBindings();
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
        ListTag missileAnchorTags = tag.getList(
                "MissileDirectionAnchorBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < missileAnchorTags.size(); index++) {
            BindingRef anchor = BindingRef.loadTag(missileAnchorTags.getCompound(index));
            if (isBoundMissileDirectionAnchor(anchor)
                    && !missileDirectionAnchors.contains(anchor)) {
                missileDirectionAnchors.add(anchor);
            }
        }
        // Older saves kept only per-family lists, so retain each family's
        // existing order for anchors that have no cross-family entry yet.
        missileLaunchers.forEach(this::registerMissileDirectionAnchor);
        aaMissileLaunchers.forEach(this::registerMissileDirectionAnchor);
        ListTag aircraftRocketTags = tag.getList("AircraftRocketPodBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < aircraftRocketTags.size(); index++) {
            BindingRef pod = BindingRef.loadTag(aircraftRocketTags.getCompound(index));
            if (!aircraftRocketPods.contains(pod)) aircraftRocketPods.add(pod);
        }
        aircraftRocketFireCursor = aircraftRocketPods.isEmpty() ? 0
                : Math.floorMod(tag.getInt("AircraftRocketFireCursor"), aircraftRocketPods.size());
        ListTag cannonTurretTags = tag.getList(
                "CannonTurretPodBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < cannonTurretTags.size(); index++) {
            BindingRef pod = BindingRef.loadTag(cannonTurretTags.getCompound(index));
            if (!cannonTurretPods.contains(pod)) cannonTurretPods.add(pod);
        }
        ListTag countermeasureTags = tag.getList(
                "CountermeasureLauncherBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < countermeasureTags.size(); index++) {
            BindingRef launcher = BindingRef.loadTag(countermeasureTags.getCompound(index));
            if (!countermeasureLaunchers.contains(launcher)) {
                countermeasureLaunchers.add(launcher);
            }
        }
        countermeasureFireCursor = countermeasureLaunchers.isEmpty() ? 0
                : Math.floorMod(tag.getInt("CountermeasureFireCursor"),
                countermeasureLaunchers.size());
        ListTag remoteStationTags = tag.getList(
                "RemoteWeaponStationBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < remoteStationTags.size(); index++) {
            BindingRef station = BindingRef.loadTag(remoteStationTags.getCompound(index));
            if (!remoteWeaponStations.contains(station)) remoteWeaponStations.add(station);
        }
        ListTag iffTransponderTags = tag.getList(
                "IffTransponderBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < iffTransponderTags.size(); index++) {
            BindingRef transponder = BindingRef.loadTag(
                    iffTransponderTags.getCompound(index));
            if (!iffTransponders.contains(transponder)) iffTransponders.add(transponder);
        }
        ListTag bearingTags = tag.getList("UnassignedBearingBindings", Tag.TAG_COMPOUND);
        for (int index = 0; index < bearingTags.size(); index++) {
            BindingRef bearing = BindingRef.loadTag(bearingTags.getCompound(index));
            if (!unassignedBearings.contains(bearing)) unassignedBearings.add(bearing);
        }
        int selectedOrdinal = Mth.clamp(tag.getInt("SelectedAircraftWeapon"), 0,
                MianbaoAircraftCompat.WeaponType.values().length - 1);
        selectedAircraftWeapon = MianbaoAircraftCompat.WeaponType.values()[selectedOrdinal];
        aircraftWeaponSelectionActive = !tag.contains("AircraftWeaponSelectionActive") || tag.getBoolean("AircraftWeaponSelectionActive");
        InfraredTargeting.clear(this);
        infraredSelectionEpoch++;
        selectedGroundWeapon = java.util.Arrays.stream(FireControlWeaponInputs.GroundWeapon.values())
                .filter(type -> type.name().equals(tag.getString("SelectedGroundWeapon")))
                .findFirst().orElse(null);
        radarRange = Mth.clamp(tag.contains("RadarRange") ? tag.getDouble("RadarRange") : 3000.0,
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
                ? tag.getDouble("RadarHudY") : 3.0, 0.0, 100.0);
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
                ? tag.getDouble("AircraftRadarHudScale") : 50.0, 40.0, 180.0);
        aircraftRadarHudX = Mth.clamp(tag.contains("AircraftRadarHudX")
                ? tag.getDouble("AircraftRadarHudX") : 98.0, 0.0, 100.0);
        aircraftRadarHudY = Mth.clamp(tag.contains("AircraftRadarHudY")
                ? tag.getDouble("AircraftRadarHudY") : 50.0, 0.0, 100.0);
        rwrRange = Mth.clamp(tag.contains("RwrRange")
                ? tag.getDouble("RwrRange") : 2_000.0, 200.0, 100_000.0);
        rwrScale = Mth.clamp(tag.contains("RwrScale")
                ? tag.getDouble("RwrScale") : 100.0, 40.0, 180.0);
        aircraftRocketSalvoMode = tag.getBoolean("AircraftRocketSalvoMode");
        radarLockedSubLevelId = tag.hasUUID("RadarLockedSubLevel")
                ? tag.getUUID("RadarLockedSubLevel") : null;
        if (tag.hasUUID("RadarCameraAcquisition")) radarCameraAcquisitionId = tag.getUUID("RadarCameraAcquisition");
        depressionLimitDegrees = tag.contains("DepressionLimit") ? tag.getDouble("DepressionLimit") : 10.0;
        elevationLimitDegrees = Mth.clamp(
                tag.contains("ElevationLimit") ? tag.getDouble("ElevationLimit") : 20.0,
                0.0, 89.0);
        yawSpeedDegreesPerSecond = tag.contains("YawSpeed") ? tag.getDouble("YawSpeed") : 30.0;
        yawLimits = YawLimits.load(tag);
        pitchSpeedDegreesPerSecond = tag.contains("PitchSpeed") ? tag.getDouble("PitchSpeed") : 15.0;
        yawReversed = tag.getBoolean("YawReversed");
        pitchReversed = tag.getBoolean("PitchReversed");
        yawBearingHeadHidden = tag.getBoolean("YawBearingHeadHidden");
        pitchBearingHeadHidden = tag.getBoolean("PitchBearingHeadHidden");
        double storedThirdPersonHeight = tag.contains("ThirdPersonHeight")
                ? tag.getDouble("ThirdPersonHeight") : 1.0;
        // Migrate the previous shipped default without overriding an unrelated
        // value the player may already have chosen.
        if (Math.abs(storedThirdPersonHeight - 2.5) < 1.0e-6) {
            storedThirdPersonHeight = 1.0;
        }
        thirdPersonHeight = Mth.clamp(storedThirdPersonHeight, 0.0, 200.0);
        viewAimOffsetX = clampAimOffset(tag.getDouble("ViewAimOffsetX"));
        double storedViewAimOffsetY = tag.contains("ViewAimOffsetY")
                ? tag.getDouble("ViewAimOffsetY") : -15.0;
        if (Math.abs(storedViewAimOffsetY + 15.6) < 1.0e-6) {
            storedViewAimOffsetY = -15.0;
        }
        viewAimOffsetY = clampAimOffset(storedViewAimOffsetY);
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
        bearingSettings.clear();
        for (Tag value : tag.getList("BearingSettings", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            if (!entry.contains("Ref", Tag.TAG_COMPOUND)) continue;
            BindingRef ref = BindingRef.loadTag(entry.getCompound("Ref"));
            bearingSettings.put(ref, new BearingSettings(
                    Mth.clamp(entry.getDouble("Depression"), 0.0, 90.0),
                    Mth.clamp(entry.getDouble("Elevation"), 0.0, 89.0),
                    Mth.clamp(entry.getDouble("Speed"), 0.0, 720.0),
                    entry.getBoolean("Reversed"), entry.getBoolean("HeadHidden"),
                    clampOffset(new Vec3(entry.getDouble("OffsetX"),
                            entry.getDouble("OffsetY"), entry.getDouble("OffsetZ"))), YawLimits.load(entry)));
        }
        scopeOffset = clampOffset(new Vec3(
                tag.getDouble("ScopeOffsetX"),
                tag.getDouble("ScopeOffsetY"),
                tag.getDouble("ScopeOffsetZ")
        ));
        virtualScopeEnabled = tag.getBoolean("VirtualScopeEnabled");
        scopePreset = ScopePreset.fromSerializedName(tag.getString("ScopePreset"));
        desiredWorldDirection = new Vec3(tag.getDouble("AimX"), tag.getDouble("AimY"), tag.getDouble("AimZ"));
        hasAimDirection = tag.getBoolean("HasAim");
        podDesiredWorldDirection = new Vec3(
                tag.getDouble("PodAimX"), tag.getDouble("PodAimY"), tag.getDouble("PodAimZ"));
        hasPodAimDirection = tag.getBoolean("HasPodAim")
                && podDesiredWorldDirection.lengthSqr() > 1.0e-8;
        if (hasPodAimDirection) podDesiredWorldDirection = podDesiredWorldDirection.normalize();
        stabilizerEnabled = !tag.contains("StabilizerEnabled") || tag.getBoolean("StabilizerEnabled");
        stabilizerReactionEnabled = !tag.contains("StabilizerReactionEnabled")
                || tag.getBoolean("StabilizerReactionEnabled");
        cannonRecoilMagnitude = CannonRecoilSettings.read(tag);
        mobileShotCompensationEnabled = !tag.contains("MobileShotCompensationEnabled")
                || tag.getBoolean("MobileShotCompensationEnabled");
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
                tag.getInt("FrequencyConfigCursor"), 0,
                WIRELESS_INPUT_ACTION_COUNT * 2 - 1);
        scopeReceiver.load(tag, "ScopeWireless", registries);
        thermalReceiver.load(tag, "ThermalWireless", registries);
        opticalLockReceiver.load(tag, "OpticalLockWireless", registries);
        missileFireReceiver.load(tag, "MissileFireWireless", registries);
        aaMissileFireReceiver.load(tag, "AaMissileFireWireless", registries);
        radarLockReceiver.load(tag, "RadarLockWireless", registries);
        aircraftMissileFireReceiver.load(tag, "AircraftMissileFireWireless", registries);
        aircraftWeaponCycleReceiver.load(tag, "AircraftWeaponCycleWireless", registries);
        aircraftWeaponCycleUpReceiver.load(tag, "AircraftWeaponCycleUpWireless", registries);
        aircraftTargetCycleReceiver.load(tag, "AircraftTargetCycleWireless", registries);
        groundTargetCycleReceiver.load(tag, "GroundTargetCycleWireless", registries);
        podViewReceiver.load(tag, "PodViewWireless", registries);
        airToGroundLockReceiver.load(tag, "AirToGroundLockWireless", registries);
        countermeasureFireReceiver.load(tag, "CountermeasureFireWireless", registries);
        freeLookReceiver.load(tag, "FreeLookWireless", registries);
        televisionGuidanceReceiver.load(tag, "TelevisionGuidanceWireless", registries);
        weaponViewReceiver.load(tag, "WeaponViewWireless", registries);
        machineGunFireReceiver.load(tag, "MachineGunFireWireless", registries);
        laserRangeReceiver.load(tag, "LaserRangeWireless", registries);
        for (int index = 0; index < weaponSelectionReceivers.length; index++)
            weaponSelectionReceivers[index].load(tag, "WeaponSelectionWireless" + index, registries);
        if (!missileFireReceiver.hasConfiguredFrequency() && aaMissileFireReceiver.hasConfiguredFrequency()) {
            missileFireReceiver.setFrequency(true, aaMissileFireReceiver.getFrequency(true));
            missileFireReceiver.setFrequency(false, aaMissileFireReceiver.getFrequency(false));
        }
        aaMissileFireReceiver.setFrequency(true, ItemStack.EMPTY);
        aaMissileFireReceiver.setFrequency(false, ItemStack.EMPTY);
        laserRanges.clear();
        for (Tag rangeTag : tag.getList("LaserRanges", Tag.TAG_COMPOUND)) {
            CompoundTag saved = (CompoundTag) rangeTag;
            BindingRef weapon = BindingRef.load(saved, "Weapon");
            double distance = saved.getDouble("Distance"), elevation = saved.getDouble("Elevation");
            if (weapon != null && Double.isFinite(distance) && distance >= 0
                    && Double.isFinite(elevation) && Math.abs(elevation) <= Math.PI / 2)
                laserRanges.put(weapon, new LaserRangeCompensation(distance, elevation));
        }
        loadRemoteKeys(tag, "WirelessRemoteKeys", wirelessRemoteKeys, RemoteControllerCompat.Type.WIRELESS);
        loadRemoteKeys(tag, "NormalRemoteKeys", normalRemoteKeys, RemoteControllerCompat.Type.NORMAL);
        loadRemoteKeys(tag, "AdvancedRemoteKeys", advancedRemoteKeys, RemoteControllerCompat.Type.ADVANCED);
        FireControlWeaponInputs.migrateGroundFireKeys(wirelessRemoteKeys);
        FireControlWeaponInputs.migrateGroundFireKeys(normalRemoteKeys);
        FireControlWeaponInputs.migrateGroundFireKeys(advancedRemoteKeys);
        java.util.Arrays.fill(remoteInputPressed, false);
        freeLookUserId = null;
    }
}
