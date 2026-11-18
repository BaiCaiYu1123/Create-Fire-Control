package com.hooya.stabilizedturret.content.display;

import com.mojang.serialization.Codec;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.compat.synaxis.SynaxisWeldCompat;
import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import com.hooya.stabilizedturret.content.controller.CannonMountCompat;
import com.hooya.stabilizedturret.content.controller.ForwardIndicatorCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoCruiseMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.RwrThreat;
import com.hooya.stabilizedturret.content.controller.RadarVisibility;
import com.hooya.stabilizedturret.content.controller.SableMissileFuze;
import com.hooya.stabilizedturret.content.iff.FireControlMissileOwnership;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import com.hooya.stabilizedturret.content.bearing.PreviewBearingSettings;
import com.hooya.stabilizedturret.content.bearing.YawLimits;
import com.hooya.stabilizedturret.content.bearing.BearingHeadVisibility;

/** Independent radar and automatic-weapon computer hosted by a display wall. */
public final class ConnectedDisplayBlockEntity extends BlockEntity {
    private static final Set<ConnectedDisplayBlockEntity> ACTIVE_DISPLAYS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final int SCAN_INTERVAL = 2;
    private static final int CONTACT_HOLD_TICKS = 40;
    private static final int MISSILE_DEFENSE_FIRE_INTERVAL_TICKS = 4;
    private static final int MANUAL_MISSILE_FIRE_INTERVAL_TICKS = 20;
    private static final double OWN_STRUCTURE_CLEARANCE_DISTANCE = 32.0;
    private static final TicketType<UUID> DEFENSE_VEHICLE_TICKET = TicketType.create(
            "create_fire_control_connected_display_defense", UUID::compareTo);
    private static final SubLevelLoadingTicketType<String> DEFENSE_SUBLEVEL_TICKET =
            SubLevelLoadingTicketType.create(ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "connected_display_defense"), Codec.STRING);
    public static final double MIN_RANGE = 100.0;
    public static final double MAX_RANGE = 10_000.0;
    public static final double MIN_CANNON_FIRE_RANGE = 20.0;
    public static final double MAX_CANNON_FIRE_RANGE = 600.0;
    public static final int MIN_CANNON_FIRE_POWER = 1;
    public static final int MAX_CANNON_FIRE_POWER = 15;
    public static final int DEFAULT_CANNON_FIRE_POWER = MAX_CANNON_FIRE_POWER;
    public static final double MIN_MISSILE_DEFENSE_RANGE = 50.0;
    public static final double MAX_MISSILE_DEFENSE_RANGE = 10_000.0;
    /** Zero is the persisted representation of an unlimited anti-missile range. */
    public static final double UNLIMITED_MISSILE_DEFENSE_RANGE = 0.0;
    public static final double MIN_MISSILE_PROXIMITY_RANGE = 1.0;
    public static final double MAX_MISSILE_PROXIMITY_RANGE = 30.0;
    public static final double DEFAULT_MISSILE_PROXIMITY_RANGE = 5.0;
    public static final int MIN_MAP_UPDATE_INTERVAL_SECONDS = 1;
    public static final int MAX_MAP_UPDATE_INTERVAL_SECONDS = 60;
    public static final int DEFAULT_MAP_UPDATE_INTERVAL_SECONDS = 30;
    public static final int DEFAULT_MAP_RESOLUTION = 256;
    /** 212.06 seconds, rounded up to the next server tick. */
    public static final long GOD_MODE_VIDEO_DURATION_TICKS = 4_242L;

    private final Map<BindingSlot, BindingRef> bindings = new HashMap<>();
    private final List<BindingRef> yawBearings = new ArrayList<>();
    private final List<BindingRef> pitchBearings = new ArrayList<>();
    private final Map<BindingRef, Vec3> pitchOffsets = new HashMap<>();
    private final Map<BindingRef, PreviewBearingSettings> previewBearingSettings = new HashMap<>();
    private final List<BindingRef> cannonMounts = new ArrayList<>();
    private final List<BindingRef> aaLaunchers = new ArrayList<>();
    private final List<BindingRef> cruiseLaunchers = new ArrayList<>();
    private final List<BindingRef> ballisticLaunchers = new ArrayList<>();
    private final List<BindingRef> iffTransponders = new ArrayList<>();
    private final Map<TargetKey, Vec3> previousCenters = new HashMap<>();
    private final Map<TargetKey, Contact> trackedContacts = new LinkedHashMap<>();
    private final Map<TargetKey, Long> contactLastSeen = new HashMap<>();
    private final UUID defenseVehicleTicketOwner = UUID.randomUUID();
    private final Set<ChunkPos> defenseVehicleTicketChunks = new HashSet<>();
    private final Set<UUID> defenseSubLevelTicketIds = new HashSet<>();
    private final Map<UUID, CruiseTelemetry> cruiseTelemetry = new LinkedHashMap<>();
    private final Map<UUID, BallisticTelemetry> ballisticTelemetry = new LinkedHashMap<>();
    private List<Contact> contacts = List.of();
    private final RadarVisibility radarVisibility = new RadarVisibility();
    private Set<MianbaoCruiseMissileCompat.Type> availableCruiseTypes = Set.of();
    private Set<MianbaoBallisticMissileCompat.Type> availableBallisticTypes = Set.of();
    private final List<CruiseStrikeTarget> cruiseStrikeTargets = new ArrayList<>();
    private boolean cruiseMultiSelect;
    private int cruiseSalvoCapacity;
    private WeaponCategory selectedWeapon = WeaponCategory.CANNON;
    private MianbaoCruiseMissileCompat.Type selectedCruiseType =
            MianbaoCruiseMissileCompat.Type.CRUISE;
    private MianbaoBallisticMissileCompat.Type selectedBallisticType =
            MianbaoBallisticMissileCompat.Type.NATIVE;
    private @Nullable Vec3 manualAimPoint;
    private boolean automaticAttack;
    private boolean scanStructureContacts = true;
    private boolean scanLivingContacts = true;
    private boolean scanMissileContacts = true;
    private boolean automaticMissileDefense;
    private double automaticMissileDefenseRange = UNLIMITED_MISSILE_DEFENSE_RANGE;
    private double missileProximityRange = DEFAULT_MISSILE_PROXIMITY_RANGE;
    private double radarRange = 2_000.0;
    private double cannonFireRange = MAX_CANNON_FIRE_RANGE;
    private int cannonFirePower = DEFAULT_CANNON_FIRE_POWER;
    private int mapUpdateIntervalSeconds = DEFAULT_MAP_UPDATE_INTERVAL_SECONDS;
    private int mapResolution = DEFAULT_MAP_RESOLUTION;
    private double yawSpeed = 120.0;
    private double pitchSpeed = 90.0;
    private double depressionLimit = 20.0;
    private double elevationLimit = 45.0;
    private boolean yawReversed;
    private boolean pitchReversed;
    private boolean godModeVideoPlaying;
    private long godModeVideoStartedAt = Long.MIN_VALUE;
    private @Nullable TargetKey selectedTarget;
    private @Nullable TargetKey manualFireTarget;
    private long manualFireUntil = Long.MIN_VALUE;
    private long lastScan = Long.MIN_VALUE;
    private Vec3 previousRadarOrigin;
    private Vec3 radarVelocity = Vec3.ZERO;
    private int cannonPulseTicks;
    private long lastMissileFire = Long.MIN_VALUE;
    private final ArrayDeque<PendingCruiseLaunch> pendingCruiseLaunches = new ArrayDeque<>();
    private final Map<BindingRef, Long> lastCannonFireDiagnostics = new HashMap<>();
    private final Map<BindingRef, Double> bearingNeutralAngles = new HashMap<>();
    private @Nullable ServerLevel defenseVehicleTicketLevel;
    private @Nullable ServerSubLevelContainer defenseSubLevelTicketContainer;
    private @Nullable String defenseSubLevelTicketKey;
    private long automaticStructureFireStartedAt = Long.MIN_VALUE;
    private long lastCruiseAvailabilityCheck = Long.MIN_VALUE;
    private int cruiseRoundRobin;
    private List<Set<UUID>> cachedTopology = List.of();
    private Set<UUID> cachedTopologyIds = Set.of();
    private long cachedTopologyAt = Long.MIN_VALUE;
    private List<TurretGroup> cachedTurretGroups = List.of();
    private long cachedTurretGroupsAt = Long.MIN_VALUE;
    private int cachedTurretTopologyHash;

    public ConnectedDisplayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONNECTED_DISPLAY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) ACTIVE_DISPLAYS.add(this);
    }

    @Override
    public void setRemoved() {
        ACTIVE_DISPLAYS.remove(this);
        AutoDefenseMissileGuidance.releaseDisplay(this);
        releaseDefenseVehicleTickets();
        super.setRemoved();
    }

    /** Immediately drops display bindings when the bound block is genuinely broken. */
    public static void invalidateBindingsAt(Level targetLevel, BlockPos targetPos) {
        if (targetLevel == null || targetPos == null) return;
        for (ConnectedDisplayBlockEntity display : Set.copyOf(ACTIVE_DISPLAYS)) {
            Level displayLevel = display == null ? null : display.getLevel();
            if (display == null || display.isRemoved() || displayLevel == null
                    || displayLevel.getServer() != targetLevel.getServer()
                    || !displayLevel.dimension().equals(targetLevel.dimension())) {
                continue;
            }
            boolean changed = display.bindings.entrySet().removeIf(
                    entry -> entry.getValue().matches(targetLevel, targetPos));
            changed |= display.yawBearings.removeIf(
                    ref -> ref.matches(targetLevel, targetPos));
            changed |= display.pitchBearings.removeIf(
                    ref -> ref.matches(targetLevel, targetPos));
            display.pitchOffsets.keySet().removeIf(
                    ref -> ref.matches(targetLevel, targetPos));
            display.previewBearingSettings.keySet().removeIf(ref -> ref.matches(targetLevel, targetPos));
            display.bearingNeutralAngles.keySet().removeIf(ref -> ref.matches(targetLevel, targetPos));
            changed |= display.cannonMounts.removeIf(
                    ref -> ref.matches(targetLevel, targetPos));
            changed |= display.aaLaunchers.removeIf(
                    ref -> ref.matches(targetLevel, targetPos));
            changed |= display.cruiseLaunchers.removeIf(
                    ref -> ref.matches(targetLevel, targetPos));
            changed |= display.ballisticLaunchers.removeIf(
                    ref -> ref.matches(targetLevel, targetPos));
            changed |= display.iffTransponders.removeIf(
                    ref -> ref.matches(targetLevel, targetPos));
            if (changed) {
                display.lastCruiseAvailabilityCheck = Long.MIN_VALUE;
                display.sync();
            }
        }
    }

    static @Nullable ConnectedDisplayBlockEntity findLoaded(BindingRef ref, Entity context) {
        if (ref == null || context == null || context.level().getServer() == null) return null;
        for (ConnectedDisplayBlockEntity display : Set.copyOf(ACTIVE_DISPLAYS)) {
            Level displayLevel = display == null ? null : display.getLevel();
            if (display == null || display.isRemoved() || displayLevel == null
                    || displayLevel.getServer() != context.level().getServer()
                    || !displayLevel.dimension().equals(context.level().dimension())
                    || !display.getBlockPos().equals(ref.pos())
                    || !ref.isSameTarget(displayLevel)) continue;
            return display;
        }
        return null;
    }

    public static List<ConnectedDisplayBlockEntity> loadedDisplays() {
        return List.copyOf(ACTIVE_DISPLAYS);
    }

    public static int normalizeMapResolution(int resolution) {
        if (resolution <= 128) return 128;
        if (resolution <= 256) return 256;
        return 512;
    }

    public static void tick(Level level, BlockPos pos, BlockState state,
                            ConnectedDisplayBlockEntity display) {
        if (!level.isClientSide) display.serverTick();
    }

    private void serverTick() {
        ACTIVE_DISPLAYS.add(this);
        maintainDefenseVehicleTickets();
        removeInvalidBindings();
        updateCruiseAvailability();
        maintainBoundBearings();
        long now = level.getGameTime();
        if (godModeVideoPlaying
                && now - godModeVideoStartedAt >= GOD_MODE_VIDEO_DURATION_TICKS) {
            godModeVideoPlaying = false;
            godModeVideoStartedAt = Long.MIN_VALUE;
            sync();
        }
        tickPendingCruiseLaunch(now);
        if (cannonPulseTicks > 0 && --cannonPulseTicks == 0) {
            setCannonFire(false);
        }
        if (!isRadarActive()) {
            radarVisibility.clear();
            trackedContacts.clear();
            contactLastSeen.clear();
            previousCenters.clear();
            previousRadarOrigin = null;
            updateContacts(List.of());
            setCannonFire(false);
            AutoDefenseMissileGuidance.releaseDisplay(this);
            manualFireTarget = null;
            manualFireUntil = Long.MIN_VALUE;
            returnBearingsToNeutral();
            return;
        }
        if (lastScan == Long.MIN_VALUE || now - lastScan >= SCAN_INTERVAL) {
            scan(now);
            lastScan = now;
        }
        Contact missileThreat = automaticMissileDefense ? nextMissileThreat() : null;
        if (missileThreat != null) {
            if (!missileThreat.key().equals(selectedTarget)) {
                selectedTarget = missileThreat.key();
                manualAimPoint = null;
                sync();
            }
            engage(missileThreat, now, false, true);
            return;
        }
        Contact target = selectedContact();
        if (automaticMissileDefense) {
            Contact trackedThreat = target != null && target.missile()
                    && AutoDefenseMissileGuidance.hasLaunched(this, target)
                    ? target : nextTrackedMissileThreat();
            if (trackedThreat != null) {
                if (!trackedThreat.key().equals(selectedTarget)) {
                    selectedTarget = trackedThreat.key();
                    manualAimPoint = null;
                    sync();
                }
                // Keep the bearings on an already-engaged threat. The launch
                // guard prevents a second interceptor, while an in-range
                // cannon may continue firing until the threat disappears.
                engage(trackedThreat, now, false, true);
                return;
            }
            if (target != null && target.missile()) {
                selectedTarget = null;
                manualAimPoint = null;
                target = null;
                setCannonFire(false);
                sync();
                if (!automaticAttack) {
                    returnBearingsToNeutral();
                    return;
                }
            }
        }
        if (automaticAttack) {
            boolean advance = target == null
                    || !canAutomaticallyAttack(target)
                    || (!target.key().living()
                    && automaticStructureFireStartedAt != Long.MIN_VALUE
                    && now - automaticStructureFireStartedAt >= 100L);
            if (advance) {
                Contact next = nextAutomaticContact(selectedTarget);
                TargetKey nextKey = next == null ? null : next.key();
                if (!java.util.Objects.equals(selectedTarget, nextKey)) {
                    selectedTarget = nextKey;
                    sync();
                }
                target = next;
                automaticStructureFireStartedAt = Long.MIN_VALUE;
            } else {
                target = selectedContact();
            }
            if (target == null && selectedTarget != null) {
                selectedTarget = null;
                sync();
            }
        }
        boolean manualFiring = target != null
                && manualFireTarget != null
                && manualFireTarget.equals(target.key())
                && (target.key().living() || now < manualFireUntil);
        boolean manualPointFiring = target == null
                && selectedWeapon == WeaponCategory.CANNON
                && manualAimPoint != null
                && manualFireTarget == null
                && now < manualFireUntil;
        if (manualFireTarget != null && !manualFiring) {
            manualFireTarget = null;
            manualFireUntil = Long.MIN_VALUE;
        } else if (manualFireTarget == null
                && manualFireUntil != Long.MIN_VALUE
                && !manualPointFiring) {
            manualFireUntil = Long.MIN_VALUE;
        }
        if (target == null) {
            if (selectedTarget != null && !automaticAttack) {
                selectedTarget = null;
                sync();
            }
            setCannonFire(false);
            if (selectedWeapon == WeaponCategory.CANNON && manualAimPoint != null) {
                Vec3 resolvedAimPoint = resolveCannonMapAimPoint(manualAimPoint);
                if (resolvedAimPoint.distanceToSqr(manualAimPoint) > 1.0e-8) {
                    // Persist and synchronize the authoritative terrain Y.
                    // It remains usable if the target chunk unloads later and
                    // also makes diagnostics report the coordinate we solve.
                    manualAimPoint = resolvedAimPoint;
                    sync();
                }
                engageFixedCannonPoint(resolvedAimPoint, manualPointFiring);
                return;
            }
            returnBearingsToNeutral();
            return;
        }
        engage(target, now, automaticAttack || manualFiring, false);
    }

    public boolean bind(BindingSlot slot, BindingRef ref) {
        if (slot == BindingSlot.LASER_DESIGNATOR) return false;
        if (slot == BindingSlot.CANNON_MOUNT) {
            if (!cannonMounts.contains(ref)) cannonMounts.add(ref);
        } else if (slot == BindingSlot.AA_MISSILE_LAUNCHER) {
            if (!aaLaunchers.contains(ref)) aaLaunchers.add(ref);
        } else if (slot == BindingSlot.CRUISE_MISSILE_LAUNCHER) {
            if (!cruiseLaunchers.contains(ref)) cruiseLaunchers.add(ref);
        } else if (slot == BindingSlot.BALLISTIC_MISSILE_LAUNCHER) {
            if (!ballisticLaunchers.contains(ref)) ballisticLaunchers.add(ref);
        } else if (slot == BindingSlot.YAW) {
            if (!yawBearings.contains(ref)) yawBearings.add(ref);
        } else if (slot == BindingSlot.PITCH) {
            if (!pitchBearings.contains(ref)) pitchBearings.add(ref);
            pitchOffsets.putIfAbsent(ref, Vec3.ZERO);
        } else if (slot == BindingSlot.FORWARD_INDICATOR) {
            if (!ForwardIndicatorCompat.isPresent(level, ref)) return false;
            bindings.put(slot, ref);
        } else if (slot == BindingSlot.RADAR_BEARING) {
            bindings.put(slot, ref);
        } else if (slot == BindingSlot.IFF_TRANSPONDER) {
            if (!iffTransponders.contains(ref)) iffTransponders.add(ref);
        } else if (slot == BindingSlot.BEARING) {
            if (!(ref.resolve(level) instanceof SwivelBearingBlockEntity bearing)) return false;
            if (!(bearing.getBlockState().getBlock() instanceof
                    dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock block)) {
                return false;
            }
            List<BindingRef> target = block.getRotationAxis(bearing.getBlockState())
                    == net.minecraft.core.Direction.Axis.Y ? yawBearings : pitchBearings;
            if (!target.contains(ref)) target.add(ref);
        } else {
            return false;
        }
        if (yawBearings.contains(ref)) maintainBearing(ref, true);
        if (pitchBearings.contains(ref)) maintainBearing(ref, false);
        sync();
        return true;
    }

    public void setSettings(boolean automaticAttack, double radarRange, double yawSpeed,
                            double pitchSpeed, double depressionLimit, double elevationLimit,
                            boolean yawReversed, boolean pitchReversed,
                            double cannonFireRange, int mapUpdateIntervalSeconds,
                            int mapResolution) {
        setSettings(automaticAttack, radarRange, yawSpeed, pitchSpeed,
                depressionLimit, elevationLimit, yawReversed, pitchReversed,
                cannonFireRange, cannonFirePower, mapUpdateIntervalSeconds, mapResolution);
    }

    public void setSettings(boolean automaticAttack, double radarRange, double yawSpeed,
                            double pitchSpeed, double depressionLimit, double elevationLimit,
                            boolean yawReversed, boolean pitchReversed,
                            double cannonFireRange, int cannonFirePower,
                            int mapUpdateIntervalSeconds, int mapResolution) {
        if (this.automaticAttack != automaticAttack) {
            automaticStructureFireStartedAt = Long.MIN_VALUE;
        }
        this.automaticAttack = automaticAttack;
        this.radarRange = Mth.clamp(radarRange, MIN_RANGE, MAX_RANGE);
        this.cannonFireRange = Mth.clamp(
                cannonFireRange,
                MIN_CANNON_FIRE_RANGE,
                MAX_CANNON_FIRE_RANGE);
        this.cannonFirePower = Mth.clamp(
                cannonFirePower,
                MIN_CANNON_FIRE_POWER,
                MAX_CANNON_FIRE_POWER);
        this.mapUpdateIntervalSeconds = Mth.clamp(
                mapUpdateIntervalSeconds,
                MIN_MAP_UPDATE_INTERVAL_SECONDS,
                MAX_MAP_UPDATE_INTERVAL_SECONDS);
        this.mapResolution = normalizeMapResolution(mapResolution);
        this.yawSpeed = Mth.clamp(yawSpeed, 0.0, 720.0);
        this.pitchSpeed = Mth.clamp(pitchSpeed, 0.0, 720.0);
        this.depressionLimit = Mth.clamp(depressionLimit, 0.0, 90.0);
        this.elevationLimit = Mth.clamp(elevationLimit, 0.0, 89.0);
        this.yawReversed = yawReversed;
        this.pitchReversed = pitchReversed;
        if (!automaticAttack) setCannonFire(false);
        sync();
    }

    public void setScanFilters(boolean structures, boolean living, boolean missiles) {
        if (scanStructureContacts == structures
                && scanLivingContacts == living
                && scanMissileContacts == missiles) return;
        boolean missileFilterDisabled = scanMissileContacts && !missiles;
        scanStructureContacts = structures;
        scanLivingContacts = living;
        scanMissileContacts = missiles;
        trackedContacts.entrySet().removeIf(entry -> !isContactTypeEnabled(entry.getValue()));
        contactLastSeen.keySet().retainAll(trackedContacts.keySet());
        previousCenters.keySet().retainAll(trackedContacts.keySet());
        updateContacts(contacts.stream().filter(this::isContactTypeEnabled).toList());
        if (selectedTarget != null && getContact(selectedTarget) == null) {
            selectedTarget = null;
            automaticStructureFireStartedAt = Long.MIN_VALUE;
            setCannonFire(false);
        }
        if (manualFireTarget != null && getContact(manualFireTarget) == null) {
            manualFireTarget = null;
            manualFireUntil = Long.MIN_VALUE;
            setCannonFire(false);
        }
        if (missileFilterDisabled) AutoDefenseMissileGuidance.cancelPending(this);
        sync();
    }

    public void setAutomaticMissileDefense(boolean automaticMissileDefense) {
        if (this.automaticMissileDefense == automaticMissileDefense) return;
        this.automaticMissileDefense = automaticMissileDefense;
        if (!automaticMissileDefense) {
            AutoDefenseMissileGuidance.cancelPending(this);
            releaseDefenseVehicleTickets();
        } else {
            maintainDefenseVehicleTickets();
        }
        sync();
    }

    public void setAutomaticMissileDefenseRange(double range) {
        double next = normalizeMissileDefenseRange(range);
        if (Double.compare(this.automaticMissileDefenseRange, next) == 0) return;
        this.automaticMissileDefenseRange = next;
        sync();
    }

    public void setMissileProximityRange(double range) {
        double next = Mth.clamp(Math.round(range * 2.0) / 2.0,
                MIN_MISSILE_PROXIMITY_RANGE, MAX_MISSILE_PROXIMITY_RANGE);
        if (Double.compare(missileProximityRange, next) == 0) return;
        missileProximityRange = next;
        sync();
    }

    public static double normalizeMissileDefenseRange(double range) {
        if (!Double.isFinite(range) || range <= UNLIMITED_MISSILE_DEFENSE_RANGE
                || range >= MAX_MISSILE_DEFENSE_RANGE) {
            return UNLIMITED_MISSILE_DEFENSE_RANGE;
        }
        return Mth.clamp(Math.round(Math.max(MIN_MISSILE_DEFENSE_RANGE, range) / 10.0) * 10.0,
                MIN_MISSILE_DEFENSE_RANGE, MAX_MISSILE_DEFENSE_RANGE - 10.0);
    }

    public void setRadarRange(double radarRange) {
        double next = Mth.clamp(radarRange, MIN_RANGE, MAX_RANGE);
        if (Math.abs(this.radarRange - next) < 0.01) return;
        this.radarRange = next;
        sync();
    }

    public void toggleAutomaticAttack() {
        automaticAttack = !automaticAttack;
        manualFireTarget = null;
        manualFireUntil = Long.MIN_VALUE;
        automaticStructureFireStartedAt = Long.MIN_VALUE;
        if (!automaticAttack) setCannonFire(false);
        sync();
    }

    public void toggleAutomaticMissileDefense() {
        setAutomaticMissileDefense(!automaticMissileDefense);
    }

    public void clearSelectedTarget() {
        selectedTarget = null;
        manualAimPoint = null;
        cruiseStrikeTargets.clear();
        manualFireTarget = null;
        manualFireUntil = Long.MIN_VALUE;
        automaticStructureFireStartedAt = Long.MIN_VALUE;
        setCannonFire(false);
        sync();
    }

    public void requestManualFire() {
        if (selectedWeapon != WeaponCategory.CANNON) return;
        Contact selected = selectedContact();
        if (selected == null && manualAimPoint == null) return;
        automaticAttack = false;
        if (selected == null) {
            manualFireTarget = null;
            manualFireUntil = level.getGameTime() + 100L;
        } else {
            manualFireTarget = selected.key();
            manualFireUntil = selected.key().living()
                    ? Long.MAX_VALUE
                    : level.getGameTime() + 100L;
        }
        sync();
    }

    public boolean requestMissileFire() {
        if (selectedWeapon != WeaponCategory.AIR_DEFENSE
                || level == null || aaLaunchers.isEmpty()) return false;
        Contact selected = selectedContact();
        if (selected == null) return false;
        long now = level.getGameTime();
        if (lastMissileFire != Long.MIN_VALUE
                && now - lastMissileFire < 2L) return false;
        for (BindingRef launcher : aaLaunchers) {
            if (MianbaoAirDefenseCompat.getRadarAmmoCount(level, launcher) <= 0) {
                continue;
            }
            AutoDefenseMissileGuidance.armNext(this, launcher, selected);
            if (!MianbaoAirDefenseCompat.fireOne(level, launcher)) {
                AutoDefenseMissileGuidance.cancelNext(this);
                continue;
            }
            lastMissileFire = now;
            return true;
        }
        return false;
    }

    public boolean requestCruiseMissileFire() {
        if (selectedWeapon != WeaponCategory.CRUISE || level == null
                || cruiseLaunchers.isEmpty() || selectedCruiseType == null) return false;
        long now = level.getGameTime();
        if (!pendingCruiseLaunches.isEmpty()
                || (lastMissileFire != Long.MIN_VALUE && now - lastMissileFire < 2L)) {
            return false;
        }
        List<CruiseStrikeTarget> requestedTargets;
        if (cruiseMultiSelect) {
            requestedTargets = List.copyOf(cruiseStrikeTargets.subList(0,
                    Math.min(cruiseStrikeTargets.size(), cruiseSalvoCapacity)));
        } else {
            Contact selected = selectedContact();
            TargetKey targetKey = selected == null ? null : selected.key();
            Vec3 point = selected == null ? manualAimPoint : selected.center();
            requestedTargets = point == null ? List.of()
                    : List.of(new CruiseStrikeTarget(targetKey, point));
        }
        if (requestedTargets.isEmpty()) return false;
        long launchAt = now + 20L;
        for (CruiseStrikeTarget strikeTarget : requestedTargets) {
            Contact contact = strikeTarget.targetKey() == null
                    ? null : getContact(strikeTarget.targetKey());
            Vec3 initialTarget = contact == null ? strikeTarget.point() : contact.center();
            pendingCruiseLaunches.addLast(new PendingCruiseLaunch(
                    launchAt, selectedCruiseType, strikeTarget.targetKey(),
                    strikeTarget.targetKey() == null ? strikeTarget.point() : null,
                    initialTarget));
            launchAt += 2L;
        }
        lastMissileFire = now;
        sync();
        return true;
    }

    public boolean requestBallisticMissileFire() {
        if (selectedWeapon != WeaponCategory.BALLISTIC || level == null
                || ballisticLaunchers.isEmpty()) return false;
        Contact selected = selectedContact();
        Vec3 target = selected == null ? manualAimPoint : selected.center();
        if (target == null) return false;
        long now = level.getGameTime();
        if (lastMissileFire != Long.MIN_VALUE && now - lastMissileFire < 2L) return false;
        for (BindingRef launcher : ballisticLaunchers) {
            if (!MianbaoBallisticMissileCompat.hasType(
                    level, launcher, selectedBallisticType)) continue;
            BallisticMissileGuidance.armNext(this, launcher,
                    selected == null ? null : selected.key(),
                    selected == null ? target : null, target);
            if (!MianbaoBallisticMissileCompat.fire(level, launcher)) {
                BallisticMissileGuidance.cancelPending(this);
                continue;
            }
            lastMissileFire = now;
            updateCruiseAvailability(true);
            sync();
            return true;
        }
        return false;
    }

    private void tickPendingCruiseLaunch(long now) {
        PendingCruiseLaunch pending = pendingCruiseLaunches.peekFirst();
        if (pending == null || now < pending.launchAt()) return;
        int size = cruiseLaunchers.size();
        for (int offset = 0; offset < size; offset++) {
            int index = Math.floorMod(cruiseRoundRobin + offset, size);
            BindingRef launcher = cruiseLaunchers.get(index);
            if (!MianbaoCruiseMissileCompat.hasType(level, launcher, pending.type())) continue;
            CruiseMissileGuidance.armNext(this, launcher, pending.type(),
                    pending.targetKey(), pending.fixedPoint(), pending.initialTarget());
            if (!MianbaoCruiseMissileCompat.fire(level, launcher,
                    pending.type(), pending.initialTarget())) {
                CruiseMissileGuidance.cancelNext(this);
                continue;
            }
            cruiseRoundRobin = (index + 1) % Math.max(1, size);
            pendingCruiseLaunches.removeFirst();
            lastMissileFire = now;
            updateCruiseAvailability(true);
            sync();
            return;
        }
        pendingCruiseLaunches.removeFirst();
        sync();
    }

    public void toggleCruiseMultiSelect() {
        if (selectedWeapon != WeaponCategory.CRUISE) return;
        cruiseMultiSelect = !cruiseMultiSelect;
        if (cruiseMultiSelect) {
            cruiseStrikeTargets.clear();
            Contact selected = selectedContact();
            if (selected != null) {
                cruiseStrikeTargets.add(new CruiseStrikeTarget(
                        selected.key(), selected.center()));
            } else if (manualAimPoint != null) {
                cruiseStrikeTargets.add(new CruiseStrikeTarget(null, manualAimPoint));
            }
            trimCruiseStrikeTargets();
        } else if (!cruiseStrikeTargets.isEmpty()) {
            CruiseStrikeTarget first = cruiseStrikeTargets.get(0);
            selectedTarget = first.targetKey();
            manualAimPoint = first.targetKey() == null ? first.point() : null;
            cruiseStrikeTargets.clear();
        }
        sync();
    }

    public void selectWeapon(WeaponCategory category) {
        if (category == null || selectedWeapon == category) return;
        selectedWeapon = category;
        manualFireTarget = null;
        manualFireUntil = Long.MIN_VALUE;
        setCannonFire(false);
        sync();
    }

    public void cycleCruiseMissileType() {
        if (availableCruiseTypes.isEmpty()) return;
        MianbaoCruiseMissileCompat.Type[] values = MianbaoCruiseMissileCompat.Type.values();
        int start = selectedCruiseType == null ? -1 : selectedCruiseType.ordinal();
        for (int offset = 1; offset <= values.length; offset++) {
            MianbaoCruiseMissileCompat.Type candidate = values[Math.floorMod(start + offset, values.length)];
            if (availableCruiseTypes.contains(candidate)) {
                selectedCruiseType = candidate;
                updateCruiseAvailability(true);
                sync();
                return;
            }
        }
    }

    public void cycleBallisticMissileType() {
        if (availableBallisticTypes.isEmpty()) return;
        MianbaoBallisticMissileCompat.Type[] values =
                MianbaoBallisticMissileCompat.Type.values();
        int start = selectedBallisticType == null ? -1 : selectedBallisticType.ordinal();
        for (int offset = 1; offset <= values.length; offset++) {
            MianbaoBallisticMissileCompat.Type candidate =
                    values[Math.floorMod(start + offset, values.length)];
            if (availableBallisticTypes.contains(candidate)) {
                selectedBallisticType = candidate;
                sync();
                return;
            }
        }
    }

    public boolean requestContextFire() {
        return switch (selectedWeapon) {
            case CANNON -> {
                boolean hasTarget = selectedContact() != null || manualAimPoint != null;
                requestManualFire();
                yield hasTarget;
            }
            case AIR_DEFENSE -> requestMissileFire();
            case CRUISE -> requestCruiseMissileFire();
            case BALLISTIC -> requestBallisticMissileFire();
        };
    }

    public boolean handleScreenInteraction(
            ConnectedDisplayLayout.ScreenHit hit
    ) {
        double x = hit.canvasX();
        double y = hit.canvasY();
        if (ConnectedDisplayLayout.inside(x, y,
                ConnectedDisplayLayout.GOD_MODE_X1, ConnectedDisplayLayout.GOD_MODE_Y1,
                ConnectedDisplayLayout.GOD_MODE_X2, ConnectedDisplayLayout.GOD_MODE_Y2)) {
            toggleGodModeVideo();
            return true;
        }
        // The switch remains interactive during playback, but the hidden
        // weapon UI must not receive clicks through the video layer.
        if (godModeVideoPlaying) return true;
        if (ConnectedDisplayLayout.inside(x, y,
                ConnectedDisplayLayout.CANNON_TAB_X1, ConnectedDisplayLayout.WEAPON_TAB_Y1,
                ConnectedDisplayLayout.CANNON_TAB_X2, ConnectedDisplayLayout.WEAPON_TAB_Y2)) {
            selectWeapon(WeaponCategory.CANNON);
            return true;
        }
        if (ConnectedDisplayLayout.inside(x, y,
                ConnectedDisplayLayout.AA_TAB_X1, ConnectedDisplayLayout.WEAPON_TAB_Y1,
                ConnectedDisplayLayout.AA_TAB_X2, ConnectedDisplayLayout.WEAPON_TAB_Y2)) {
            selectWeapon(WeaponCategory.AIR_DEFENSE);
            return true;
        }
        if (ConnectedDisplayLayout.inside(x, y,
                ConnectedDisplayLayout.CRUISE_TAB_X1, ConnectedDisplayLayout.WEAPON_TAB_Y1,
                ConnectedDisplayLayout.CRUISE_TAB_X2, ConnectedDisplayLayout.WEAPON_TAB_Y2)) {
            selectWeapon(WeaponCategory.CRUISE);
            return true;
        }
        if (ConnectedDisplayLayout.inside(x, y,
                ConnectedDisplayLayout.BALLISTIC_TAB_X1, ConnectedDisplayLayout.WEAPON_TAB_Y1,
                ConnectedDisplayLayout.BALLISTIC_TAB_X2, ConnectedDisplayLayout.WEAPON_TAB_Y2)) {
            selectWeapon(WeaponCategory.BALLISTIC);
            return true;
        }
        if (ConnectedDisplayLayout.inside(x, y,
                ConnectedDisplayLayout.CRUISE_TYPE_X1, ConnectedDisplayLayout.CRUISE_TYPE_Y1,
                ConnectedDisplayLayout.CRUISE_TYPE_X2, ConnectedDisplayLayout.CRUISE_TYPE_Y2)) {
            if (selectedWeapon == WeaponCategory.CRUISE) cycleCruiseMissileType();
            else if (selectedWeapon == WeaponCategory.BALLISTIC) cycleBallisticMissileType();
            return true;
        }
        if (ConnectedDisplayLayout.inside(x, y,
                ConnectedDisplayLayout.CRUISE_MULTI_X1, ConnectedDisplayLayout.CRUISE_MULTI_Y1,
                ConnectedDisplayLayout.CRUISE_MULTI_X2, ConnectedDisplayLayout.CRUISE_MULTI_Y2)) {
            toggleCruiseMultiSelect();
            return true;
        }
        if (ConnectedDisplayLayout.inside(
                x, y,
                ConnectedDisplayLayout.SLIDER_X1,
                ConnectedDisplayLayout.RADAR_SLIDER_Y1,
                ConnectedDisplayLayout.SLIDER_X2,
                ConnectedDisplayLayout.RADAR_SLIDER_Y2
        )) {
            setRadarRange(ConnectedDisplayLayout.sliderRange(x));
            return true;
        }
        if (ConnectedDisplayLayout.inside(
                x, y,
                ConnectedDisplayLayout.SLIDER_X1,
                ConnectedDisplayLayout.DEFENSE_SLIDER_Y1,
                ConnectedDisplayLayout.SLIDER_X2,
                ConnectedDisplayLayout.DEFENSE_SLIDER_Y2
        )) {
            setAutomaticMissileDefenseRange(
                ConnectedDisplayLayout.defenseSliderRange(x));
            return true;
        }
        if (ConnectedDisplayLayout.inside(
                x, y, ConnectedDisplayLayout.SLIDER_X1,
                ConnectedDisplayLayout.PROXIMITY_SLIDER_Y1,
                ConnectedDisplayLayout.SLIDER_X2,
                ConnectedDisplayLayout.PROXIMITY_SLIDER_Y2)) {
            setMissileProximityRange(
                    ConnectedDisplayLayout.proximitySliderRange(x));
            return true;
        }
        if (ConnectedDisplayLayout.inside(
                x, y,
                ConnectedDisplayLayout.AUTO_X1,
                ConnectedDisplayLayout.AUTO_Y1,
                ConnectedDisplayLayout.AUTO_X2,
                ConnectedDisplayLayout.AUTO_Y2
        )) {
            toggleAutomaticAttack();
            return true;
        }
        if (ConnectedDisplayLayout.inside(
                x, y,
                ConnectedDisplayLayout.AUTO_DEFENSE_X1,
                ConnectedDisplayLayout.AUTO_Y1,
                ConnectedDisplayLayout.AUTO_DEFENSE_X2,
                ConnectedDisplayLayout.AUTO_Y2
        )) {
            toggleAutomaticMissileDefense();
            return true;
        }
        if (ConnectedDisplayLayout.inside(
                x, y,
                ConnectedDisplayLayout.FIRE_X1,
                ConnectedDisplayLayout.FIRE_Y1,
                ConnectedDisplayLayout.FIRE_X2,
                ConnectedDisplayLayout.FIRE_Y2
        )) {
            requestContextFire();
            return true;
        }
        if (ConnectedDisplayLayout.inside(
                x, y,
                ConnectedDisplayLayout.CLEAR_X1,
                ConnectedDisplayLayout.CLEAR_Y1,
                ConnectedDisplayLayout.CLEAR_X2,
                ConnectedDisplayLayout.CLEAR_Y2
        )) {
            clearSelectedTarget();
            return true;
        }
        if (x < 0.0 || x > ConnectedDisplayLayout.RADAR_W
                || y < ConnectedDisplayLayout.HEADER_H
                || y > ConnectedDisplayLayout.CANVAS_H) {
            return false;
        }
        ConnectedDisplayLayout.RadarGeometry geometry =
                ConnectedDisplayLayout.radarGeometry(
                        hit.wallWidth(), hit.wallHeight());
        Vec3 origin = radarOrigin();
        Contact nearest = null;
        double nearestDistance = 30.0 * 30.0;
        for (Contact contact : contacts) {
            ConnectedDisplayLayout.RadarPoint point =
                    ConnectedDisplayLayout.radarPoint(
                            contact.center(), origin, radarRange, geometry);
            double distance = (point.x() - x) * (point.x() - x)
                    + (point.y() - y) * (point.y() - y);
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = contact;
            }
        }
        Vec3 clickedPoint = nearest == null
                ? ConnectedDisplayLayout.radarWorldPoint(
                x, y, origin, radarRange, geometry, mapResolution)
                : nearest.center();
        if (selectedWeapon == WeaponCategory.CRUISE && cruiseMultiSelect) {
            toggleCruiseStrikeTarget(nearest == null ? null : nearest.key(), clickedPoint);
        } else if (nearest == null) {
            manualAimPoint = clickedPoint;
            selectedTarget = null;
        } else {
            selectedTarget = nearest.key();
            manualAimPoint = null;
        }
        automaticAttack = false;
        manualFireTarget = null;
        manualFireUntil = Long.MIN_VALUE;
        setCannonFire(false);
        sync();
        return true;
    }

    public boolean isGodModeVideoPlaying() {
        return godModeVideoPlaying;
    }

    public long getGodModeVideoStartedAt() {
        return godModeVideoStartedAt;
    }

    private void toggleGodModeVideo() {
        if (level == null || level.isClientSide) return;
        godModeVideoPlaying = !godModeVideoPlaying;
        godModeVideoStartedAt = godModeVideoPlaying
                ? level.getGameTime() : Long.MIN_VALUE;
        sync();
    }

    /** Handles a map click whose world position came from the pannable console map. */
    public void handleConsoleMapInteraction(
            @Nullable UUID targetId,
            boolean targetLiving,
            double worldX,
            double worldZ
    ) {
        if (!Double.isFinite(worldX) || !Double.isFinite(worldZ)) return;
        Contact selected = null;
        if (targetId != null) {
            TargetKey requested = new TargetKey(targetId, targetLiving);
            selected = getContact(requested);
        }
        Vec3 point;
        if (selected != null) {
            point = selected.center();
        } else {
            Vec3 origin = radarOrigin();
            point = new Vec3(worldX, origin.y, worldZ);
        }
        if (selectedWeapon == WeaponCategory.CRUISE && cruiseMultiSelect) {
            toggleCruiseStrikeTarget(selected == null ? null : selected.key(), point);
        } else if (selected == null) {
            manualAimPoint = point;
            selectedTarget = null;
        } else {
            selectedTarget = selected.key();
            manualAimPoint = null;
        }
        automaticAttack = false;
        manualFireTarget = null;
        manualFireUntil = Long.MIN_VALUE;
        setCannonFire(false);
        sync();
    }

    /** Removes only the selected marker addressed by a console right-click. */
    public void handleConsoleMapCancel(
            @Nullable UUID targetId,
            boolean targetLiving,
            double worldX,
            double worldZ
    ) {
        if (!Double.isFinite(worldX) || !Double.isFinite(worldZ)) return;
        TargetKey requested = targetId == null ? null : new TargetKey(targetId, targetLiving);
        double tolerance = Math.max(2.0,
                radarRange / Math.max(64.0, mapResolution));
        if (selectedWeapon == WeaponCategory.CRUISE && cruiseMultiSelect) {
            int existing = -1;
            for (int index = 0; index < cruiseStrikeTargets.size(); index++) {
                CruiseStrikeTarget target = cruiseStrikeTargets.get(index);
                boolean matches = requested != null
                        ? requested.equals(target.targetKey())
                        : target.targetKey() == null
                        && target.point().distanceToSqr(new Vec3(worldX,
                        target.point().y, worldZ)) <= tolerance * tolerance;
                if (matches) {
                    existing = index;
                    break;
                }
            }
            if (existing < 0) return;
            cruiseStrikeTargets.remove(existing);
            if (cruiseStrikeTargets.isEmpty()) {
                selectedTarget = null;
                manualAimPoint = null;
            } else {
                CruiseStrikeTarget last = cruiseStrikeTargets.get(cruiseStrikeTargets.size() - 1);
                selectedTarget = last.targetKey();
                manualAimPoint = last.targetKey() == null ? last.point() : null;
            }
        } else if (requested != null && requested.equals(selectedTarget)) {
            clearSelectedTarget();
            return;
        } else if (requested == null && manualAimPoint != null
                && manualAimPoint.distanceToSqr(new Vec3(worldX,
                manualAimPoint.y, worldZ)) <= tolerance * tolerance) {
            clearSelectedTarget();
            return;
        } else {
            return;
        }
        automaticAttack = false;
        manualFireTarget = null;
        manualFireUntil = Long.MIN_VALUE;
        setCannonFire(false);
        sync();
    }

    public boolean isAutomaticAttack() { return automaticAttack; }
    public boolean isScanningStructures() { return scanStructureContacts; }
    public boolean isScanningLiving() { return scanLivingContacts; }
    public boolean isScanningMissiles() { return scanMissileContacts; }
    public boolean isAutomaticMissileDefense() { return automaticMissileDefense; }
    public double getAutomaticMissileDefenseRange() { return automaticMissileDefenseRange; }
    public double getMissileProximityRange() { return missileProximityRange; }
    public double getRadarRange() { return radarRange; }
    public double getCannonFireRange() { return cannonFireRange; }
    public int getCannonFirePower() { return cannonFirePower; }
    public int getMapUpdateIntervalSeconds() { return mapUpdateIntervalSeconds; }
    public int getMapResolution() { return mapResolution; }
    public double getYawSpeed() { return yawSpeed; }
    public double getPitchSpeed() { return pitchSpeed; }
    public double getDepressionLimit() { return depressionLimit; }
    public double getElevationLimit() { return elevationLimit; }
    public boolean isYawReversed() { return yawReversed; }
    public boolean isPitchReversed() { return pitchReversed; }
    public boolean hasBoundMissileLauncher() { return !aaLaunchers.isEmpty(); }
    public boolean hasCruiseMissileAvailable() { return !availableCruiseTypes.isEmpty(); }
    public boolean hasBallisticMissileAvailable() {
        return selectedBallisticType != null
                && availableBallisticTypes.contains(selectedBallisticType);
    }
    public WeaponCategory getSelectedWeapon() { return selectedWeapon; }
    public MianbaoCruiseMissileCompat.Type getSelectedCruiseType() { return selectedCruiseType; }
    public MianbaoBallisticMissileCompat.Type getSelectedBallisticType() {
        return selectedBallisticType;
    }
    public Set<MianbaoCruiseMissileCompat.Type> getAvailableCruiseTypes() {
        return availableCruiseTypes;
    }
    public boolean isCruiseMultiSelect() {
        return cruiseMultiSelect && selectedWeapon == WeaponCategory.CRUISE;
    }
    public int getCruiseSalvoCapacity() { return cruiseSalvoCapacity; }
    public List<CruiseStrikeTarget> getCruiseStrikeTargets() {
        return List.copyOf(cruiseStrikeTargets);
    }
    public @Nullable Vec3 getManualAimPoint() { return manualAimPoint; }
    public List<CruiseTelemetry> getCruiseTelemetry() {
        return List.copyOf(cruiseTelemetry.values());
    }
    public List<BallisticTelemetry> getBallisticTelemetry() {
        return List.copyOf(ballisticTelemetry.values());
    }
    public String cruiseOwnershipToken() {
        SubLevel containing = Sable.HELPER.getContaining(this);
        return worldPosition.asLong() + ":"
                + (containing == null ? "root" : containing.getUniqueId().toString());
    }
    public @Nullable TargetKey getSelectedTarget() { return selectedTarget; }
    public boolean isSelected(Contact contact) {
        return selectedTarget != null && selectedTarget.equals(contact.key());
    }
    public boolean isCruiseStrikeTarget(Contact contact) {
        if (contact == null) return false;
        for (CruiseStrikeTarget target : cruiseStrikeTargets) {
            if (contact.key().equals(target.targetKey())) return true;
        }
        return false;
    }
    public Vec3 getRadarWorldOrigin() { return radarOrigin(); }
    public boolean isRadarMemoryActive() { return isRadarActive(); }
    public List<Contact> getContacts() {
        return level != null && level.isClientSide
                ? com.hooya.stabilizedturret.client.ClientRadarMemory.display(this,
                com.hooya.stabilizedturret.client.ClientRadarVisibility.display(this, contacts)) : contacts;
    }

    /** Last measurement tick for this plot, including held contacts between scans. */
    public long getContactSampleTick(TargetKey key) {
        return contactLastSeen.getOrDefault(key, Long.MIN_VALUE);
    }

    public static List<RwrThreat> collectRwrRadarThreats(
            Level receiverLevel,
            Set<UUID> receiverStructure,
            Vec3 receiverOrigin,
            double range
    ) {
        if (receiverLevel == null || receiverStructure == null
                || receiverStructure.isEmpty()) return List.of();
        double rangeSqr = range * range;
        List<RwrThreat> result = new ArrayList<>();
        for (ConnectedDisplayBlockEntity display : Set.copyOf(ACTIVE_DISPLAYS)) {
            Level displayLevel = display == null ? null : display.getLevel();
            if (display == null || display.isRemoved() || displayLevel == null
                    || displayLevel.isClientSide
                    || displayLevel.getServer() != receiverLevel.getServer()
                    || !displayLevel.dimension().equals(receiverLevel.dimension())
                    || !display.isRadarActive()) continue;
            SubLevel containing = Sable.HELPER.getContaining(display);
            if (containing != null
                    && receiverStructure.contains(containing.getUniqueId())) continue;
            boolean scanning = display.contacts.stream().anyMatch(contact ->
                    !contact.key().living() && !contact.missile()
                            && isFreshRwrContact(display.getContactSampleTick(contact.key()), displayLevel.getGameTime())
                            && (receiverStructure.contains(contact.key().id())
                            || intersects(contact.members(), receiverStructure)));
            if (!scanning) continue;
            Vec3 position = display.radarOrigin();
            if (position.distanceToSqr(receiverOrigin) > rangeSqr) continue;
            result.add(new RwrThreat(
                    RwrThreat.sourceId(
                            "connected-display",
                            displayLevel,
                            display.getBlockPos(),
                            containing == null ? null : containing.getUniqueId()),
                    RwrThreat.Type.AAA,
                    position,
                    false));
        }
        return List.copyOf(result);
    }

    /** Held display plots are not evidence of current radar illumination. */
    private static boolean isFreshRwrContact(long sampled, long now) {
        return sampled != Long.MIN_VALUE && now >= sampled && now - sampled <= SCAN_INTERVAL + 1;
    }

    private static boolean intersects(Set<UUID> a, Set<UUID> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        Set<UUID> smaller = a.size() <= b.size() ? a : b;
        Set<UUID> larger = smaller == a ? b : a;
        for (UUID id : smaller) if (larger.contains(id)) return true;
        return false;
    }

    public @Nullable Contact getContact(TargetKey key) {
        if (key == null) return null;
        for (Contact contact : contacts) if (key.equals(contact.key())) return contact;
        return null;
    }

    public void updateCruiseTelemetry(CruiseTelemetry telemetry) {
        if (telemetry == null || level == null || level.isClientSide) return;
        CruiseTelemetry previous = cruiseTelemetry.put(telemetry.id(), telemetry);
        if (!telemetry.equals(previous) && (telemetry.ageTicks() & 1) == 0) sync();
    }

    public void removeCruiseTelemetry(UUID id) {
        if (id != null && cruiseTelemetry.remove(id) != null) sync();
    }
    public void updateBallisticTelemetry(BallisticTelemetry telemetry) {
        if (telemetry == null || level == null || level.isClientSide) return;
        BallisticTelemetry previous = ballisticTelemetry.put(telemetry.id(), telemetry);
        if (!telemetry.equals(previous) && (telemetry.ageTicks() & 1) == 0) sync();
    }

    public void removeBallisticTelemetry(UUID id) {
        if (id != null && ballisticTelemetry.remove(id) != null) sync();
    }
    public List<Device> getDevices() {
        List<Device> result = new ArrayList<>();
        bindings.forEach((slot, ref) -> result.add(new Device(slot, ref)));
        yawBearings.forEach(ref -> result.add(new Device(BindingSlot.YAW, ref)));
        pitchBearings.forEach(ref -> result.add(new Device(BindingSlot.PITCH, ref)));
        cannonMounts.forEach(ref -> result.add(new Device(BindingSlot.CANNON_MOUNT, ref)));
        aaLaunchers.forEach(ref -> result.add(new Device(BindingSlot.AA_MISSILE_LAUNCHER, ref)));
        cruiseLaunchers.forEach(ref -> result.add(new Device(BindingSlot.CRUISE_MISSILE_LAUNCHER, ref)));
        ballisticLaunchers.forEach(ref -> result.add(new Device(
                BindingSlot.BALLISTIC_MISSILE_LAUNCHER, ref)));
        iffTransponders.forEach(ref -> result.add(new Device(
                BindingSlot.IFF_TRANSPONDER, ref)));
        return List.copyOf(result);
    }

    boolean hasBindings() {
        return !bindings.isEmpty() || !yawBearings.isEmpty() || !pitchBearings.isEmpty()
                || !cannonMounts.isEmpty() || !aaLaunchers.isEmpty()
                || !cruiseLaunchers.isEmpty() || !ballisticLaunchers.isEmpty()
                || !iffTransponders.isEmpty();
    }

    boolean hasConfiguredSettings() {
        return automaticMissileDefense
                || !previewBearingSettings.isEmpty()
                || automaticMissileDefenseRange != UNLIMITED_MISSILE_DEFENSE_RANGE
                || missileProximityRange != DEFAULT_MISSILE_PROXIMITY_RANGE
                || !scanStructureContacts
                || !scanLivingContacts
                || !scanMissileContacts
                || Math.abs(radarRange - 2_000.0) >= 0.01
                || Math.abs(cannonFireRange - MAX_CANNON_FIRE_RANGE) >= 0.01
                || Math.abs(yawSpeed - 120.0) >= 0.01
                || Math.abs(pitchSpeed - 90.0) >= 0.01
                || Math.abs(depressionLimit - 20.0) >= 0.01
                || Math.abs(elevationLimit - 45.0) >= 0.01
                || mapUpdateIntervalSeconds != DEFAULT_MAP_UPDATE_INTERVAL_SECONDS
                || mapResolution != DEFAULT_MAP_RESOLUTION
                || selectedWeapon != WeaponCategory.CANNON
                || manualAimPoint != null
                || yawReversed
                || pitchReversed;
    }

    public boolean isBound(BindingRef ref) {
        return bindings.containsValue(ref)
                || yawBearings.contains(ref)
                || pitchBearings.contains(ref)
                || cannonMounts.contains(ref)
                || aaLaunchers.contains(ref)
                || cruiseLaunchers.contains(ref)
                || ballisticLaunchers.contains(ref)
                || iffTransponders.contains(ref);
    }

    public List<BindingRef> getIffTransponderBindings() {
        return List.copyOf(iffTransponders);
    }

    public Set<UUID> getOwnStructureIdsForIff() {
        if (level == null) return Set.of();
        return Set.copyOf(ownStructureIds(subLevelTopology(level.getGameTime())));
    }

    public Vec3 getPitchOffset(BindingRef ref) {
        if (previewBearingSettings.containsKey(ref)) return previewBearingSettings.get(ref).offset();
        return ref == null ? Vec3.ZERO : pitchOffsets.getOrDefault(ref, Vec3.ZERO);
    }

    public PreviewBearingSettings getPreviewBearingSettings(BindingSlot slot, BindingRef ref) {
        var stored = previewBearingSettings.get(ref);
        if (stored != null) return stored;
        boolean yaw = slot == BindingSlot.YAW;
        return new PreviewBearingSettings(yaw ? 90 : depressionLimit, yaw ? 89 : elevationLimit,
                yaw ? yawSpeed : pitchSpeed, yaw ? yawReversed : pitchReversed, false,
                yaw ? Vec3.ZERO : getPitchOffset(ref), YawLimits.UNLIMITED);
    }

    public void setPreviewBearingSettings(BindingSlot slot, BindingRef ref, PreviewBearingSettings settings) {
        var refs = slot == BindingSlot.YAW ? yawBearings : slot == BindingSlot.PITCH ? pitchBearings : List.<BindingRef>of();
        if (level == null || !refs.contains(ref) || !ref.isSameTarget(level) || !settings.finite()) return;
        previewBearingSettings.put(ref, settings.clamped());
        if (slot == BindingSlot.PITCH) pitchOffsets.put(ref, settings.clamped().offset());
        maintainBearing(ref, slot == BindingSlot.YAW);
        sync();
    }

    public void setPitchOffset(BindingRef ref, Vec3 offset) {
        if (ref == null || !pitchBearings.contains(ref) || offset == null
                || !Double.isFinite(offset.x) || !Double.isFinite(offset.y)
                || !Double.isFinite(offset.z)) return;
        pitchOffsets.put(ref, new Vec3(
                Mth.clamp(offset.x, -20.0, 20.0),
                Mth.clamp(offset.y, -20.0, 20.0),
                Mth.clamp(offset.z, -20.0, 20.0)));
        if (previewBearingSettings.containsKey(ref)) {
            var s = previewBearingSettings.get(ref);
            previewBearingSettings.put(ref, new PreviewBearingSettings(s.depression(), s.elevation(), s.speed(),
                    s.reversed(), s.headHidden(), pitchOffsets.get(ref), s.limits()));
        }
        sync();
    }

    public void setPitchOffset(BlockPos targetPos, Vec3 offset) {
        if (targetPos == null) return;
        pitchBearings.stream().filter(ref -> targetPos.equals(ref.pos())).findFirst()
                .ifPresent(ref -> setPitchOffset(ref, offset));
    }

    public static List<BindingRef> activeGroundRadarBindings(Level receiverLevel) {
        List<BindingRef> result = new ArrayList<>();
        for (ConnectedDisplayBlockEntity display : Set.copyOf(ACTIVE_DISPLAYS)) {
            if (display == null || display.isRemoved()
                    || !com.hooya.stabilizedturret.content.controller.GroundRadarEmitters
                    .sameWorld(receiverLevel, display.getLevel()) || !display.isRadarActive()) continue;
            result.add(display.bindings.get(BindingSlot.RADAR_BEARING));
        }
        return List.copyOf(result);
    }

    private boolean isRadarActive() {
        if (level == null) return false;
        BindingRef ref = bindings.get(BindingSlot.RADAR_BEARING);
        if (ref == null || !(ref.resolve(level) instanceof MechanicalBearingBlockEntity bearing)) return false;
        return bearing.isRunning() && bearing.getMovedContraption() != null
                && Math.abs(bearing.getAngularSpeed()) > 1.0e-3F;
    }

    private Vec3 radarOrigin() {
        BindingRef ref = bindings.get(BindingSlot.RADAR_BEARING);
        BlockEntity radar = ref == null ? null : ref.resolve(level);
        if (radar != null && radar.getLevel() != null) {
            return Sable.HELPER.projectOutOfSubLevel(radar.getLevel(), Vec3.atCenterOf(radar.getBlockPos()));
        }
        return Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(worldPosition));
    }

    private void scan(long now) {
        Vec3 origin = radarOrigin();
        if (previousRadarOrigin != null) radarVelocity = origin.subtract(previousRadarOrigin).scale(1.0 / SCAN_INTERVAL);
        previousRadarOrigin = origin;
        List<Set<UUID>> topology = subLevelTopology(now);
        Set<UUID> own = ownStructureIds(topology);
        Set<String> iffChannels = com.hooya.stabilizedturret.content.iff
                .IffIdentification.channelsForStructure(level, own);
        List<Contact> found = new ArrayList<>();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        for (Set<UUID> cluster : topology) {
            if (cluster.stream().anyMatch(own::contains)) continue;
            if (com.hooya.stabilizedturret.content.iff.IffIdentification
                    .sharesEnabledChannel(level, iffChannels, cluster)) continue;
            AABB bounds = null;
            Set<UUID> members = new HashSet<>();
            for (UUID memberId : cluster) {
                SubLevel member = container.getSubLevel(memberId);
                if (member == null || member.isRemoved() || own.contains(member.getUniqueId())) continue;
                AABB part = com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity
                        .subLevelWorldBounds(member);
                bounds = bounds == null ? part : union(bounds, part);
                members.add(member.getUniqueId());
            }
            if (bounds == null) continue;
            UUID key = members.stream().min(UUID::compareTo).orElse(null);
            if (key == null) continue;
            Vec3 center = physicalClusterCenter(members, bounds.getCenter());
            addContact(found, new TargetKey(key, false), members,
                    center, bounds, origin, now);
        }
        // A display embedded in a connected physical cluster is otherwise
        // swallowed by the own-structure filter above. Expose other fire
        // control displays as independent radar contacts so two Sable
        // vehicles can detect one another even when their plots are linked.
        SubLevel thisDisplaySubLevel = Sable.HELPER.getContaining(this);
        for (ConnectedDisplayBlockEntity other : Set.copyOf(ACTIVE_DISPLAYS)) {
            if (other == this || other.isRemoved() || other.level != level) continue;
            SubLevel otherSubLevel = Sable.HELPER.getContaining(other);
            if (otherSubLevel == null || otherSubLevel.isRemoved()
                    || (thisDisplaySubLevel != null
                    && thisDisplaySubLevel.getUniqueId().equals(
                    otherSubLevel.getUniqueId()))) continue;
            if (found.stream().anyMatch(contact -> contact.key().id()
                    .equals(otherSubLevel.getUniqueId()))) continue;
            if (com.hooya.stabilizedturret.content.iff.IffIdentification
                    .sharesEnabledChannel(level, iffChannels,
                    Set.of(otherSubLevel.getUniqueId()))) continue;
            AABB otherBounds = com.hooya.stabilizedturret.content.controller
                    .StabilizerControllerBlockEntity.subLevelWorldBounds(otherSubLevel);
            Vec3 otherCenter = physicalClusterCenter(
                    Set.of(otherSubLevel.getUniqueId()), otherBounds.getCenter());
            addContact(found,
                    new TargetKey(otherSubLevel.getUniqueId(), false),
                    Set.of(otherSubLevel.getUniqueId()),
                    otherCenter, otherBounds, origin, now);
        }
        Set<UUID> livingVisited = new HashSet<>();
        Set<UUID> friendlyPlayers = new HashSet<>();
        Level rootLevel = container.getLevel();
        if (rootLevel instanceof ServerLevel root) {
            // Sable plots share the root entity store. Inverse-transforming a
            // radar-sized AABB for every plot can cross SectionPos's signed
            // coordinate boundary and make EntitySectionStorage.subSet throw.
            // Visit loaded entities once and measure their world-space bounds.
            for (Entity entity : root.getAllEntities()) {
                if (!(entity instanceof LivingEntity living)
                        || !living.isAlive() || living.isSpectator()) continue;
                SubLevel containing = Sable.HELPER.getContaining(living);
                if (containing != null && own.contains(containing.getUniqueId())) continue;
                if (!livingVisited.add(living.getUUID())) continue;
                AABB worldBounds = containing == null ? living.getBoundingBox()
                        : projectBounds(root, living.getBoundingBox());
                if (living instanceof Player player
                        && com.hooya.stabilizedturret.content.iff.IffIdentification
                        .isFriendlyPlayerContact(level, iffChannels, player,
                                containing == null ? null : containing.getUniqueId(),
                                worldBounds)) {
                    friendlyPlayers.add(living.getUUID());
                    continue;
                }
                addContact(found, new TargetKey(living.getUUID(), true), Set.of(),
                        worldBounds.getCenter(), worldBounds, origin, now);
            }
            scanMissiles(found, root, origin, new HashSet<>(), now, iffChannels);
        }
        Set<UUID> visible = radarVisibility.scan(level, origin, own, found.stream()
                .map(contact -> new RadarVisibility.Target(contact.key().id(), contact.center(),
                        contact.bounds(), contact.members())).toList());
        found.removeIf(contact -> {
            if (visible.contains(contact.key().id())) return false;
            // A blocked/unconfirmed plot must not survive the ordinary 40-tick track hold,
            // become an automatic-fire target, or remain selected on the display.
            trackedContacts.remove(contact.key());
            contactLastSeen.remove(contact.key());
            return true;
        });
        updateTrackedContacts(found, now, iffChannels, friendlyPlayers);
    }

    private void addContact(List<Contact> found, TargetKey key, Set<UUID> members,
                            Vec3 center, AABB bounds, Vec3 origin, long now) {
        addContact(found, key, members, center, bounds, origin, now, false);
    }

    private void addContact(List<Contact> found, TargetKey key, Set<UUID> members,
                            Vec3 center, AABB bounds, Vec3 origin, long now,
                            boolean missile) {
        if (!isContactTypeEnabled(key, missile)) return;
        if (!Double.isFinite(center.x) || !Double.isFinite(center.y)
                || !Double.isFinite(center.z)
                || !Double.isFinite(bounds.minX) || !Double.isFinite(bounds.minY)
                || !Double.isFinite(bounds.minZ) || !Double.isFinite(bounds.maxX)
                || !Double.isFinite(bounds.maxY) || !Double.isFinite(bounds.maxZ)) return;
        double distance = center.distanceTo(origin);
        if (distance > radarRange || distance < 1.0) return;
        Vec3 previous = previousCenters.put(key, center);
        Vec3 velocity = previous == null ? Vec3.ZERO : center.subtract(previous).scale(1.0 / SCAN_INTERVAL);
        Vec3 los = center.subtract(origin).normalize();
        double closing = -(velocity.subtract(radarVelocity)).dot(los) * 20.0;
        found.add(new Contact(key, members, center, velocity, bounds, distance, closing, missile));
    }

    /**
     * A rotated asymmetric plot has a world AABB whose center moves even when
     * the structure itself only changes orientation. That false translation
     * becomes a large alternating yaw command at short range. Aim at the
     * mass-weighted physical centers instead; retain the AABB separately for
     * radar geometry, occlusion and hit-entry tests.
     */
    private Vec3 physicalClusterCenter(Set<UUID> members, Vec3 fallback) {
        if (level == null || members == null || members.isEmpty()) return fallback;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        Vector3d weighted = new Vector3d();
        Vector3d unweighted = new Vector3d();
        double totalMass = 0.0;
        int centers = 0;
        for (UUID memberId : members) {
            SubLevel member = container.getSubLevel(memberId);
            Vec3 center = SableMissileFuze.physicalCenterOfMass(member);
            if (center == null || !Double.isFinite(center.x)
                    || !Double.isFinite(center.y)
                    || !Double.isFinite(center.z)) continue;
            unweighted.add(center.x, center.y, center.z);
            centers++;
            var massData = member instanceof ServerSubLevel server
                    ? server.getMassTracker() : null;
            double mass = massData == null ? 0.0 : massData.getMass();
            if (Double.isFinite(mass) && mass > 1.0e-8) {
                weighted.add(center.x * mass, center.y * mass, center.z * mass);
                totalMass += mass;
            }
        }
        if (totalMass > 1.0e-8) {
            weighted.div(totalMass);
            return new Vec3(weighted.x, weighted.y, weighted.z);
        }
        if (centers > 0) {
            unweighted.div(centers);
            return new Vec3(unweighted.x, unweighted.y, unweighted.z);
        }
        return fallback;
    }

    private void updateTrackedContacts(
            List<Contact> scanned,
            long now,
            Set<String> iffChannels,
            Set<UUID> friendlyPlayers
    ) {
        for (Contact contact : scanned) {
            trackedContacts.put(contact.key(), contact);
            contactLastSeen.put(contact.key(), now);
        }
        trackedContacts.entrySet().removeIf(entry -> {
            long lastSeen = contactLastSeen.getOrDefault(entry.getKey(), Long.MIN_VALUE);
            boolean vanishedMissile = entry.getValue().missile()
                    && !AutoDefenseMissileGuidance.isThreatAlive(this, entry.getValue());
            boolean vanishedLiving = entry.getValue().key().living()
                    && !isLivingContactAlive(entry.getValue());
            boolean disabledType = !isContactTypeEnabled(entry.getValue());
            boolean friendlyStructure = !entry.getValue().missile()
                    && !entry.getValue().key().living()
                    && !entry.getValue().members().isEmpty()
                    && com.hooya.stabilizedturret.content.iff.IffIdentification
                    .sharesEnabledChannel(level, iffChannels,
                            entry.getValue().members());
            boolean friendlyPlayer = entry.getValue().key().living()
                    && (friendlyPlayers.contains(entry.getValue().key().id())
                    || isFriendlyPlayerContact(entry.getValue(), iffChannels));
            Entity missileEntity = entry.getValue().missile()
                    ? findEntity(entry.getValue().key().id()) : null;
            boolean friendlyMissile = entry.getValue().missile()
                    && FireControlMissileOwnership.isFriendly(
                    level, iffChannels, missileEntity);
            boolean expired = disabledType || vanishedMissile || vanishedLiving
                    || friendlyStructure || friendlyPlayer || friendlyMissile
                    || lastSeen == Long.MIN_VALUE
                    || now - lastSeen > CONTACT_HOLD_TICKS;
            if (expired) contactLastSeen.remove(entry.getKey());
            return expired;
        });
        previousCenters.keySet().retainAll(trackedContacts.keySet());

        List<Contact> stable = new ArrayList<>(trackedContacts.values());
        // Keep the 64 transmitted plots deterministic as range grows. The old
        // closing-rate sort let tiny velocity noise continually swap contacts
        // around the cutoff, which appeared as distant targets flickering.
        stable.sort(Comparator.comparing(Contact::missile).reversed()
                .thenComparingDouble(Contact::distance)
                .thenComparing(contact -> contact.key().id()));
        updateContacts(stable.size() > 64
                ? List.copyOf(stable.subList(0, 64))
                : List.copyOf(stable));
    }

    private boolean isContactTypeEnabled(Contact contact) {
        return contact != null && isContactTypeEnabled(contact.key(), contact.missile());
    }

    private boolean isContactTypeEnabled(TargetKey key, boolean missile) {
        if (missile) return scanMissileContacts;
        return key != null && key.living() ? scanLivingContacts : scanStructureContacts;
    }

    private void scanMissiles(List<Contact> found, ServerLevel source,
                                    Vec3 worldOrigin, Set<UUID> visited,
                                    long now, Set<String> iffChannels) {
        List<Entity> missileCandidates = new ArrayList<>();
        source.getAllEntities().forEach(missileCandidates::add);
        missileCandidates.addAll(com.hooya.stabilizedturret.content.controller
                .MathematicalMissileManager.missiles(source));
        for (Entity missile : missileCandidates) {
            if (!missile.isAlive()
                    || (!MianbaoCruiseMissileCompat.isRadarMissile(missile)
                    && !MianbaoAircraftCompat.isAirToGroundMissile(missile))
                    || cruiseOwnershipToken().equals(missile.getPersistentData()
                    .getString("CreateFireControlCruiseOwner"))) continue;
            if (!visited.add(missile.getUUID())) continue;
            if (FireControlMissileOwnership.isFriendly(
                    level, iffChannels, missile)) continue;
            AABB bounds = com.hooya.stabilizedturret.content.controller
                    .MathematicalMissileManager.contains(missile)
                    ? missile.getBoundingBox() : projectBounds(source, missile.getBoundingBox());
            Vec3 center = bounds.getCenter();
            addContact(found, new TargetKey(missile.getUUID(), false), Set.of(),
                    center, bounds, worldOrigin, now, true);
        }
    }

    private void engage(Contact contact, long now, boolean fire, boolean missileDefense) {
        double cannonReferenceDistance = cannonReferenceDistance(contact.center());
        boolean cannonInRange = cannonReferenceDistance <= cannonFireRange;
        boolean cannonEligible = canAnyCannonAttack(contact);
        boolean missileInRange = isWithinMissileDefenseRange(contact);
        boolean cannonFire = (fire && selectedWeapon == WeaponCategory.CANNON)
                || (missileDefense && contact.missile() && cannonEligible);
        boolean airDefenseFire = (fire && selectedWeapon == WeaponCategory.AIR_DEFENSE)
                || (missileDefense && contact.missile()
                && !cannonInRange && missileInRange);
        boolean useCannonLead = !cannonMounts.isEmpty()
                && (!missileDefense || cannonInRange);
        aimTurretGroups(contact, useCannonLead);

        if (!cannonMounts.isEmpty()) {
            for (BindingRef mount : cannonMounts) {
                if (!CannonMountCompat.hasAssembledCannon(level, mount)) {
                    CannonMountCompat.setAutomaticFire(level, mount, false);
                    continue;
                }
                CannonBallisticsCompat.ShotProfile profile = CannonBallisticsCompat.analyze(level, mount);
                AimSolution mountSolution = solveAim(contact, profile);
                Vec3 predicted = mountSolution.target();
                Vec3 requiredDirection =
                        mountSolution.launchDirection() == null
                                ? null
                                : mountSolution.launchDirection().normalize();
                // An automatic-defense display treats a cannon mount as a
                // fixed ballistic sensor and trigger only. It must never
                // write the mount's own yaw/pitch: the bound Aeronautics
                // physical bearings are the sole aiming actuators.
                Vec3 muzzle = CannonBallisticsCompat.getMuzzlePosition(level, mount);
                Vec3 direction = CannonMountCompat.getAssembledCannonDirection(level, mount);
                if (muzzle != null && requiredDirection == null
                        && predicted.distanceToSqr(muzzle) > 1.0e-8) {
                    requiredDirection = predicted.subtract(muzzle).normalize();
                }
                Vec3 normalizedDirection = direction == null
                        || direction.lengthSqr() < 1.0e-8
                        ? null : direction.normalize();
                double targetDistance = muzzle == null
                        ? Double.POSITIVE_INFINITY
                        : muzzle.distanceTo(contact.center());
                double alignmentDot = normalizedDirection == null
                        || requiredDirection == null
                        ? -1.0
                        : Mth.clamp(
                        normalizedDirection.dot(requiredDirection),
                        -1.0,
                        1.0
                );
                double angleError = Math.toDegrees(Math.acos(alignmentDot));
                boolean safe = cannonFire && muzzle != null
                        && normalizedDirection != null
                        && requiredDirection != null
                        // Fire range belongs to the connected display/vehicle,
                        // not to an individual mount. All bound turrets enter
                        // and leave firing range together; muzzle position is
                        // still used for each gun's ballistic/alignment check.
                        && cannonInRange
                        // Compare the barrel against the gravity/drag
                        // compensated launch vector. Comparing it with the
                        // straight line to the target rejects every correctly
                        // elevated gun and made both manual and automatic fire
                        // appear non-functional.
                        && alignmentDot
                        > Math.cos(Math.toRadians(3.0));
                // Do not use the own-structure clip as a firing gate. Sable
                // nested structures can report the assembled barrel/turret
                // plot along this ray even when the measured CBC muzzle is
                // already clear, which permanently blocked every shot.
                // CBC remains authoritative for the projectile's real block
                // and entity collision after it leaves the muzzle.
                boolean queued = CannonMountCompat.setAutomaticFire(
                        level, mount, safe, cannonFirePower);
                if (safe
                        && automaticAttack
                        && !contact.key().living()
                        && automaticStructureFireStartedAt == Long.MIN_VALUE) {
                    automaticStructureFireStartedAt = now;
                }
                long lastDiagnostic = lastCannonFireDiagnostics.getOrDefault(
                        mount, Long.MIN_VALUE);
                if (cannonFire && (lastDiagnostic == Long.MIN_VALUE
                        || now - lastDiagnostic >= 20L)) {
                    StabilizedTurret.LOGGER.info(
                            "Display cannon fire request: display={} mount={} assembled=true referenceDistance={} muzzleDistance={} limit={} angleError={} safe={} queued={}",
                            worldPosition,
                            mount.pos(),
                            String.format(java.util.Locale.ROOT, "%.2f", cannonReferenceDistance),
                            String.format(java.util.Locale.ROOT, "%.2f", targetDistance),
                            String.format(java.util.Locale.ROOT, "%.2f", cannonFireRange),
                            String.format(java.util.Locale.ROOT, "%.2f", angleError),
                            safe,
                            queued
                    );
                    lastCannonFireDiagnostics.put(mount, now);
                }
            }
            cannonPulseTicks = cannonFire ? 2 : 0;
        }
        if (airDefenseFire && !aaLaunchers.isEmpty()
                && (lastMissileFire == Long.MIN_VALUE
                || now - lastMissileFire >= (missileDefense
                ? MISSILE_DEFENSE_FIRE_INTERVAL_TICKS
                : MANUAL_MISSILE_FIRE_INTERVAL_TICKS))
                && (!missileDefense || AutoDefenseMissileGuidance.canLaunch(this, contact))) {
            for (BindingRef launcher : aaLaunchers) {
                Vec3 muzzle = MianbaoAirDefenseCompat.getLauncherMuzzlePosition(level, launcher);
                if (muzzle == null) continue;
                Vec3 launchDirection = contact.center().subtract(muzzle);
                if (launchDirection.lengthSqr() < 1.0e-8) continue;
                launchDirection = launchDirection.normalize();
                // Sable's nested-structure ray test can report the launcher's
                // own plot even though Mianbao spawns the missile 2.6 blocks
                // ahead of it. Anti-missile launches use that native spawn
                // safety and must not be permanently blocked by this false
                // positive.
                if (!missileDefense && !clearOfOwnStructure(
                        muzzle.add(launchDirection.scale(0.35)),
                        muzzle.add(launchDirection.scale(12.0)))) continue;
                if (MianbaoAirDefenseCompat.getRadarAmmoCount(level, launcher) > 0
                        && (!missileDefense || !isLauncherOnBoundAimingAssembly(launcher)
                        || approximatelyAimed(launcher, launchDirection, 5.0))) {
                    AutoDefenseMissileGuidance.armNext(this, launcher, contact);
                    if (!MianbaoAirDefenseCompat.fireOne(level, launcher)) {
                        AutoDefenseMissileGuidance.cancelNext(this);
                        continue;
                    }
                    lastMissileFire = now;
                    if (missileDefense) AutoDefenseMissileGuidance.noteLaunch(this, contact);
                    if (automaticAttack
                            && !contact.key().living()
                            && automaticStructureFireStartedAt == Long.MIN_VALUE) {
                        automaticStructureFireStartedAt = now;
                    }
                    break;
                }
            }
        }
    }

    private void aimTurretGroups(Contact contact, boolean useCannonLead) {
        aimTurretGroups(contact, null, useCannonLead);
    }

    private void aimTurretGroups(Vec3 fixedTarget) {
        aimTurretGroups(null, fixedTarget, true);
    }

    private void engageFixedCannonPoint(Vec3 target, boolean fire) {
        aimTurretGroups(target);
        if (!fire) return;
        boolean targetInRange = isCannonPointInRange(target);
        for (BindingRef mount : cannonMounts) {
            if (!CannonMountCompat.hasAssembledCannon(level, mount)) {
                CannonMountCompat.setAutomaticFire(level, mount, false);
                continue;
            }
            CannonBallisticsCompat.ShotProfile profile =
                    CannonBallisticsCompat.analyze(level, mount);
            AimSolution solution = solveFixedAim(target, profile);
            Vec3 requiredDirection = solution.launchDirection();
            Vec3 muzzle = CannonBallisticsCompat.getMuzzlePosition(level, mount);
            if (requiredDirection == null && muzzle != null
                    && target.distanceToSqr(muzzle) > 1.0e-8) {
                requiredDirection = target.subtract(muzzle).normalize();
            }
            Vec3 measuredDirection =
                    CannonMountCompat.getAssembledCannonDirection(level, mount);
            Vec3 normalizedMeasured = measuredDirection == null
                    || measuredDirection.lengthSqr() < 1.0e-8
                    ? null : measuredDirection.normalize();
            Vec3 normalizedRequired = requiredDirection == null
                    || requiredDirection.lengthSqr() < 1.0e-8
                    ? null : requiredDirection.normalize();
            double alignmentDot = normalizedMeasured == null
                    || normalizedRequired == null
                    ? -1.0
                    : Mth.clamp(normalizedMeasured.dot(normalizedRequired), -1.0, 1.0);
            boolean safe = muzzle != null
                    && normalizedMeasured != null
                    && normalizedRequired != null
                    && targetInRange
                    && alignmentDot > Math.cos(Math.toRadians(3.0));
            boolean queued = CannonMountCompat.setAutomaticFire(level, mount, safe, cannonFirePower);
            long now = level.getGameTime();
            long lastDiagnostic = lastCannonFireDiagnostics.getOrDefault(mount, Long.MIN_VALUE);
            if (lastDiagnostic == Long.MIN_VALUE || now - lastDiagnostic >= 20L) {
                StabilizedTurret.LOGGER.info(
                        "Display map cannon fire request: display={} mount={} target={} inRange={} angleError={} safe={} queued={}",
                        worldPosition, mount.pos(), target, targetInRange,
                        Math.toDegrees(Math.acos(alignmentDot)), safe, queued);
                lastCannonFireDiagnostics.put(mount, now);
            }
        }
        cannonPulseTicks = 2;
    }

    private void aimTurretGroups(
            @Nullable Contact contact,
            @Nullable Vec3 fixedTarget,
            boolean useCannonLead
    ) {
        if (contact == null && fixedTarget == null) return;
        List<TurretGroup> groups = turretGroups();
        // Several elevation bearings may share one direction bearing.  They
        // are one physical azimuth assembly and must receive one command;
        // otherwise each elevation group overwrites the previous yaw command.
        for (List<TurretGroup> cluster : aimClusters(groups)) {
            List<GroupAimRequest> requests = new ArrayList<>();
            for (TurretGroup group : cluster) {
                GroupAimRequest request = groupAimRequest(
                        group, contact, fixedTarget, useCannonLead);
                if (request != null) requests.add(request);
            }
            if (requests.isEmpty()) continue;

            Vec3 measuredYaw = averageDirections(
                    requests.stream().map(request -> request.measured().direction()).toList());
            Vec3 requestedYaw = averageDirections(
                    requests.stream().map(GroupAimRequest::requested).toList());
            if (measuredYaw != null && requestedYaw != null) {
                Set<BindingRef> commandedYaw = new HashSet<>();
                for (TurretGroup group : cluster) {
                    if (group.yaw() != null && commandedYaw.add(group.yaw())) {
                        commandBearing(group.yaw(), true, measuredYaw, requestedYaw);
                    }
                }
            }
            // Each elevation bearing still receives its own request, based on
            // the references attached to that bearing.
            for (GroupAimRequest request : requests) {
                commandBearing(request.group().pitch(), false,
                        request.measured().direction(), request.requested());
            }
        }
    }

    private @Nullable GroupAimRequest groupAimRequest(
            TurretGroup group,
            @Nullable Contact contact,
            @Nullable Vec3 fixedTarget,
            boolean useCannonLead
    ) {
        Vec3 target = contact == null ? fixedTarget : contact.center();
        if (target == null) return null;
        AimReference measured = measureAimReference(group);
        if (measured == null) return null;
        Vec3 aimOrigin = measured.origin();
        BindingRef referenceMount = aimReferences(group).stream()
                .filter(ref -> cannonMounts.contains(ref))
                .filter(ref -> CannonMountCompat.hasAssembledCannon(level, ref))
                .min(bindingOrder()).orElse(null);
        Vec3 requested;
        if (useCannonLead && referenceMount != null) {
            CannonBallisticsCompat.ShotProfile profile =
                    CannonBallisticsCompat.analyze(level, referenceMount);
            AimSolution solution = contact == null
                    ? solveFixedAim(target, profile)
                    : solveAim(contact, profile);
            requested = solution.launchDirection() == null
                    ? solution.target().subtract(aimOrigin)
                    : solution.launchDirection();
        } else {
            requested = target.subtract(aimOrigin);
        }
        if (requested.lengthSqr() < 1.0e-8) return null;
        return new GroupAimRequest(group, measured, requested.normalize());
    }

    private Vec3 resolveCannonMapAimPoint(Vec3 point) {
        Level rootLevel = SubLevelContainer.getContainer(level).getLevel();
        if (!(rootLevel instanceof ServerLevel root)) return point;
        int blockX = Mth.floor(point.x);
        int blockZ = Mth.floor(point.z);
        // A map target only carries X/Z. Use the terrain surface when that
        // chunk is already available, without synchronously loading a distant
        // chunk every server tick. Once it becomes loaded the gun smoothly
        // updates from the radar-origin height to the real ground point.
        if (root.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4) == null) {
            return point;
        }
        int surfaceY = root.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        // Heightmap#getHeight returns the first air Y above the highest
        // blocking block, which is also that block's collision top surface.
        // Aiming half a block lower puts the ballistic target inside terrain:
        // a descending shell then intersects the top face early, producing a
        // potentially large horizontal shortfall at shallow impact angles.
        return new Vec3(point.x, surfaceY, point.z);
    }

    private List<BindingRef> aimReferences(TurretGroup group) {
        List<BindingRef> refs = new ArrayList<>(
                group.cannonMounts().size() + group.aaLaunchers().size());
        refs.addAll(group.cannonMounts());
        refs.addAll(group.aaLaunchers());
        if (group.forwardIndicator() != null) refs.add(group.forwardIndicator());
        int bestPriority = refs.stream()
                .filter(ref -> measureAimReference(ref) != null)
                .mapToInt(this::aimReferencePriority)
                .min().orElse(Integer.MAX_VALUE);
        if (bestPriority == Integer.MAX_VALUE) return List.of();
        return refs.stream().filter(ref -> aimReferencePriority(ref) == bestPriority).toList();
    }

    private int aimReferencePriority(BindingRef ref) {
        if (cannonMounts.contains(ref)) return 0;
        if (aaLaunchers.contains(ref)) return 1;
        return 2;
    }

    private @Nullable AimReference measureAimReference(TurretGroup group) {
        List<BindingRef> refs = aimReferences(group);
        Vec3 originSum = Vec3.ZERO;
        Vec3 directionSum = Vec3.ZERO;
        int count = 0;
        UUID bodyId = null;
        boolean sameBody = true;
        for (BindingRef ref : refs) {
            AimReference measured = measureAimReference(ref);
            if (measured == null) continue;
            originSum = originSum.add(measured.origin());
            directionSum = directionSum.add(measured.direction());
            if (count == 0) bodyId = measured.bodyId();
            else if (!java.util.Objects.equals(bodyId, measured.bodyId())) sameBody = false;
            count++;
        }
        if (count == 0 || directionSum.lengthSqr() < 1.0e-8) return null;
        return new AimReference(originSum.scale(1.0 / count), directionSum.normalize(), sameBody ? bodyId : null);
    }

    private @Nullable AimReference measureAimReference(BindingRef ref) {
        if (ref == null || level == null) return null;
        Vec3 origin;
        Vec3 direction;
        if (cannonMounts.contains(ref) && CannonMountCompat.hasAssembledCannon(level, ref)) {
            origin = CannonBallisticsCompat.getMuzzlePosition(level, ref);
            direction = CannonMountCompat.getAssembledCannonDirection(level, ref);
        } else if (aaLaunchers.contains(ref)) {
            origin = MianbaoAirDefenseCompat.getLauncherMuzzlePosition(level, ref);
            direction = MianbaoAirDefenseCompat.getLauncherDirection(level, ref);
        } else if (ref.equals(bindings.get(BindingSlot.FORWARD_INDICATOR))
                && ForwardIndicatorCompat.isPresent(level, ref)) {
            origin = ref.worldCenter(level);
            direction = ForwardIndicatorCompat.worldDirection(level, ref);
        } else {
            return null;
        }
        if (origin == null || direction == null || direction.lengthSqr() < 1.0e-8) return null;
        return new AimReference(origin, direction.normalize(), ref.subLevelId());
    }

    private static @Nullable Vec3 averageDirections(List<Vec3> directions) {
        Vec3 sum = Vec3.ZERO;
        for (Vec3 direction : directions) {
            if (direction != null && direction.lengthSqr() >= 1.0e-8) sum = sum.add(direction);
        }
        return sum.lengthSqr() < 1.0e-8 ? null : sum.normalize();
    }

    private List<List<TurretGroup>> aimClusters(List<TurretGroup> groups) {
        List<List<TurretGroup>> clusters = new ArrayList<>();
        Set<TurretGroup> remaining = new LinkedHashSet<>(groups);
        while (!remaining.isEmpty()) {
            TurretGroup seed = remaining.iterator().next();
            List<TurretGroup> cluster = new ArrayList<>();
            cluster.add(seed);
            remaining.remove(seed);
            Set<BindingRef> yaws = new HashSet<>();
            if (seed.yaw() != null) yaws.add(seed.yaw());
            boolean expanded;
            do {
                expanded = false;
                for (var iterator = remaining.iterator(); iterator.hasNext();) {
                    TurretGroup candidate = iterator.next();
                    if (candidate.yaw() == null || !yaws.contains(candidate.yaw())) continue;
                    cluster.add(candidate);
                    yaws.add(candidate.yaw());
                    iterator.remove();
                    expanded = true;
                }
            } while (expanded);
            clusters.add(List.copyOf(cluster));
        }
        return List.copyOf(clusters);
    }

    private @Nullable Contact selectedContact() {
        if (selectedTarget == null) return null;
        for (Contact contact : contacts) {
            if (selectedTarget.equals(contact.key())) return contact;
        }
        return null;
    }

    private @Nullable Contact nextAutomaticContact(
            @Nullable TargetKey previous
    ) {
        List<Contact> eligible = contacts.stream().filter(this::canAutomaticallyAttack).toList();
        if (eligible.isEmpty()) return null;
        if (previous == null) return eligible.get(0);
        for (int index = 0; index < eligible.size(); index++) {
            if (previous.equals(eligible.get(index).key())) {
                return eligible.get((index + 1) % eligible.size());
            }
        }
        return eligible.get(0);
    }

    private @Nullable Contact nextMissileThreat() {
        return contacts.stream().filter(this::isIncomingMissileThreat)
                .filter(contact -> AutoDefenseMissileGuidance.canLaunch(this, contact))
                .min(Comparator.comparingDouble(Contact::distance)
                        .thenComparing(Comparator.comparingDouble(Contact::closingRate).reversed()))
                .orElse(null);
    }

    private @Nullable Contact nextTrackedMissileThreat() {
        return contacts.stream()
                .filter(contact -> AutoDefenseMissileGuidance.hasLaunched(this, contact))
                .min(Comparator.comparingDouble(Contact::distance))
                .orElse(null);
    }

    private boolean isIncomingMissileThreat(Contact contact) {
        return contact != null && contact.missile()
                && contact.closingRate() > 1.0
                && !isFriendlyFireControlMissile(contact)
                && !cruiseOwnershipToken().equals(
                findCruiseEntity(contact.key().id())
                        .map(entity -> entity.getPersistentData()
                                .getString("CreateFireControlCruiseOwner"))
                        .orElse(""))
                && !cruiseTelemetry.containsKey(contact.key().id())
                && AutoDefenseMissileGuidance.isThreatAlive(this, contact);
    }

    boolean isFriendlyFireControlMissile(@Nullable Contact contact) {
        if (contact == null || !contact.missile() || level == null) return false;
        Entity missile = findEntity(contact.key().id());
        if (missile == null) return false;
        Set<String> channels = com.hooya.stabilizedturret.content.iff
                .IffIdentification.channelsForStructure(
                        level, getOwnStructureIdsForIff());
        return FireControlMissileOwnership.isFriendly(level, channels, missile);
    }

    private boolean isWithinMissileDefenseRange(Contact contact) {
        return contact != null && (automaticMissileDefenseRange <= 0.0
                || contact.distance() <= automaticMissileDefenseRange);
    }

    private java.util.Optional<Entity> findCruiseEntity(UUID id) {
        return java.util.Optional.ofNullable(findEntity(id));
    }

    private @Nullable Entity findEntity(UUID id) {
        if (id == null || level == null) return null;
        Level rootLevel = SubLevelContainer.getContainer(level).getLevel();
        if (!(rootLevel instanceof ServerLevel root)) return null;
        Entity entity = root.getEntity(id);
        if (entity == null) entity = com.hooya.stabilizedturret.content.controller.MathematicalMissileManager.find(root, id);
        if (entity != null) return entity;
        for (SubLevel sub : SubLevelContainer.getContainer(root).getAllSubLevels()) {
            if (sub != null && !sub.isRemoved() && sub.getLevel() instanceof ServerLevel server
                    && (entity = server.getEntity(id)) != null) {
                return entity;
            }
        }
        return null;
    }

    private boolean canAutomaticallyAttack(Contact contact) {
        if (contact == null || contact.missile() || !isLivingContactAlive(contact)
                || isCreativePlayerContact(contact)) return false;
        return switch (selectedWeapon) {
            case CANNON -> canAnyCannonAttack(contact);
            case AIR_DEFENSE -> !aaLaunchers.isEmpty()
                    && canReachPitch((cannonMounts.isEmpty()
                    ? directAim(contact) : solveAim(contact)).launchDirection())
                    && clearLineToContact(measuredWeaponOrigin(), contact);
            case CRUISE, BALLISTIC -> false;
        };
    }

    private boolean isCreativePlayerContact(Contact contact) {
        if (contact == null || !contact.key().living()) return false;
        Entity entity = findEntity(contact.key().id());
        // Preserve the existing safety rule even though the old attack
        // whitelist has been replaced by radar contact-type filters.
        return entity instanceof Player player && player.getAbilities().instabuild;
    }

    private boolean isFriendlyPlayerContact(
            Contact contact, Set<String> iffChannels) {
        if (contact == null || !contact.key().living()) return false;
        Entity entity = findEntity(contact.key().id());
        if (!(entity instanceof Player player)) return false;
        SubLevel containing = Sable.HELPER.getContaining(player);
        AABB worldBounds = containing == null
                ? player.getBoundingBox()
                : projectBounds(player.level(), player.getBoundingBox());
        return com.hooya.stabilizedturret.content.iff.IffIdentification
                .isFriendlyPlayerContact(level, iffChannels, player,
                        containing == null ? null : containing.getUniqueId(),
                        worldBounds);
    }

    private boolean isLivingContactAlive(Contact contact) {
        if (contact == null || !contact.key().living()) return true;
        Entity entity = findEntity(contact.key().id());
        return entity instanceof LivingEntity living && living.isAlive()
                && !living.isRemoved();
    }

    private boolean canAnyCannonAttack(Contact contact) {
        if (contact == null || !isCannonPointInRange(contact.center())) return false;
        for (BindingRef mount : cannonMounts) {
            if (!CannonMountCompat.hasAssembledCannon(level, mount)) continue;
            CannonBallisticsCompat.ShotProfile profile = CannonBallisticsCompat.analyze(level, mount);
            if (profile == null) continue;
            AimSolution solution = solveAim(contact, profile);
            Vec3 muzzle = CannonBallisticsCompat.getMuzzlePosition(level, mount);
            Vec3 direction = solution.launchDirection();
            if (muzzle != null && direction != null && direction.lengthSqr() > 1.0e-8
                    && canReachPitch(direction, mount)
                    && clearLineToContact(muzzle.add(direction.normalize().scale(0.75)), contact)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCannonPointInRange(@Nullable Vec3 target) {
        return target != null && cannonReferenceDistance(target) <= cannonFireRange;
    }

    private double cannonReferenceDistance(@Nullable Vec3 target) {
        if (level == null || target == null) return Double.POSITIVE_INFINITY;
        Vec3 displayPosition = Sable.HELPER.projectOutOfSubLevel(
                level, Vec3.atCenterOf(worldPosition));
        return displayPosition.distanceTo(target);
    }

    private boolean canReachPitch(@Nullable Vec3 direction) {
        if (direction == null || direction.lengthSqr() < 1.0e-8) return false;
        Vec3 normalized = direction.normalize();
        double elevation = Math.toDegrees(Math.atan2(normalized.y, normalized.horizontalDistance()));
        if (pitchBearings.isEmpty()) return elevation >= -depressionLimit - 0.25 && elevation <= elevationLimit + 0.25;
        return pitchBearings.stream().map(ref -> getPreviewBearingSettings(BindingSlot.PITCH, ref))
                .anyMatch(s -> elevation >= -s.depression() - .25 && elevation <= s.elevation() + .25);
    }

    private boolean canReachPitch(@Nullable Vec3 direction, BindingRef mount) {
        if (direction == null || direction.lengthSqr() < 1e-8) return false;
        double angle = Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance()));
        var groups = turretGroups().stream().filter(g -> g.cannonMounts().contains(mount) && g.pitch() != null).toList();
        if (groups.isEmpty()) return canReachPitch(direction);
        return groups.stream().map(g -> getPreviewBearingSettings(BindingSlot.PITCH, g.pitch()))
                .anyMatch(s -> angle >= -s.depression() - .25 && angle <= s.elevation() + .25);
    }

    private boolean approximatelyAimed(BindingRef launcher, Vec3 requested,
                                       double toleranceDegrees) {
        Vec3 measured = MianbaoAirDefenseCompat.getLauncherDirection(level, launcher);
        if (measured == null || measured.lengthSqr() < 1.0e-8
                || requested == null || requested.lengthSqr() < 1.0e-8) return false;
        return measured.normalize().dot(requested.normalize())
                >= Math.cos(Math.toRadians(toleranceDegrees));
    }

    private boolean isLauncherOnBoundAimingAssembly(BindingRef launcher) {
        return launcher != null && turretGroups().stream()
                .anyMatch(group -> group.aaLaunchers().contains(launcher));
    }

    private List<TurretGroup> turretGroups() {
        long now = level == null ? Long.MIN_VALUE : level.getGameTime();
        int topologyHash = turretTopologyHash();
        if (cachedTurretGroupsAt != Long.MIN_VALUE && now >= cachedTurretGroupsAt
                && now - cachedTurretGroupsAt < 10L
                && topologyHash == cachedTurretTopologyHash) {
            return cachedTurretGroups;
        }
        List<TurretGroup> result = new ArrayList<>();
        Set<BindingRef> usedYaw = new HashSet<>();
        List<BindingRef> sortedPitch = pitchBearings.stream()
                .sorted(bindingOrder()).toList();
        Map<BindingRef, List<BindingRef>> mountsByPitch = new HashMap<>();
        Map<BindingRef, List<BindingRef>> launchersByPitch = new HashMap<>();
        Set<BindingRef> assignedMounts = new HashSet<>();
        Set<BindingRef> assignedLaunchers = new HashSet<>();
        BindingRef indicator = bindings.get(BindingSlot.FORWARD_INDICATOR);
        if (!ForwardIndicatorCompat.isPresent(level, indicator)) indicator = null;
        BindingRef indicatorPitch = indicator == null ? null : closestParentBearing(indicator, sortedPitch);
        for (BindingRef mount : cannonMounts) {
            BindingRef pitch = closestParentBearing(mount, sortedPitch);
            if (pitch != null) {
                mountsByPitch.computeIfAbsent(pitch, ignored -> new ArrayList<>()).add(mount);
                assignedMounts.add(mount);
            }
        }
        for (BindingRef launcher : aaLaunchers) {
            BindingRef pitch = closestParentBearing(launcher, sortedPitch);
            if (pitch != null) {
                launchersByPitch.computeIfAbsent(pitch, ignored -> new ArrayList<>()).add(launcher);
                assignedLaunchers.add(launcher);
            }
        }
        for (BindingRef pitch : sortedPitch) {
            BindingRef yaw = closestParentBearing(pitch, yawBearings);
            if (yaw != null) usedYaw.add(yaw);
            List<BindingRef> mounts = mountsByPitch.getOrDefault(pitch, List.of())
                    .stream().sorted(bindingOrder()).toList();
            List<BindingRef> launchers = launchersByPitch.getOrDefault(pitch, List.of())
                    .stream().sorted(bindingOrder()).toList();
            BindingRef groupIndicator = pitch.equals(indicatorPitch) ? indicator : null;
            if (!mounts.isEmpty() || !launchers.isEmpty() || groupIndicator != null) {
                result.add(new TurretGroup(yaw, pitch, mounts, launchers, groupIndicator));
            }
        }
        // Also support a single-axis mount placed directly on a yaw child.
        // Do not reuse a yaw already owning a pitch group: one physical yaw
        // cannot independently satisfy two different requested directions.
        BindingRef indicatorYaw = indicator == null || indicatorPitch != null
                ? null : closestParentBearing(indicator, yawBearings);
        for (BindingRef yaw : yawBearings.stream().sorted(bindingOrder()).toList()) {
            if (usedYaw.contains(yaw)) continue;
            List<BindingRef> mounts = cannonMounts.stream()
                    .filter(ref -> !assignedMounts.contains(ref))
                    .filter(ref -> yaw.equals(closestParentBearing(ref, yawBearings)))
                    .sorted(bindingOrder()).toList();
            List<BindingRef> launchers = aaLaunchers.stream()
                    .filter(ref -> !assignedLaunchers.contains(ref))
                    .filter(ref -> yaw.equals(closestParentBearing(ref, yawBearings)))
                    .sorted(bindingOrder()).toList();
            BindingRef groupIndicator = yaw.equals(indicatorYaw) ? indicator : null;
            if (!mounts.isEmpty() || !launchers.isEmpty() || groupIndicator != null) {
                result.add(new TurretGroup(yaw, null, mounts, launchers, groupIndicator));
            }
        }
        if (indicator != null) {
            boolean assigned = false;
            for (TurretGroup group : result) {
                if (indicator.equals(group.forwardIndicator())) assigned = true;
            }
            // A static/detached indicator can supply a single fallback ray.
            // Never create a competing command for a turret with a usable weapon reference.
            if (!assigned && result.isEmpty()) {
                result.add(new TurretGroup(yawBearings.stream().sorted(bindingOrder()).findFirst().orElse(null),
                        sortedPitch.stream().findFirst().orElse(null), List.of(), List.of(), indicator));
            } else if (!assigned) {
                for (int index = 0; index < result.size(); index++) {
                    TurretGroup group = result.get(index);
                    if (measureAimReference(group) != null) continue;
                    result.set(index, new TurretGroup(group.yaw(), group.pitch(), group.cannonMounts(),
                            group.aaLaunchers(), indicator));
                    break;
                }
            }
        }
        cachedTurretGroups = List.copyOf(result);
        cachedTurretGroupsAt = now;
        cachedTurretTopologyHash = topologyHash;
        return cachedTurretGroups;
    }

    private int turretTopologyHash() {
        int hash = 31 * yawBearings.hashCode() + pitchBearings.hashCode();
        hash = 31 * hash + cannonMounts.hashCode();
        hash = 31 * hash + aaLaunchers.hashCode();
        hash = 31 * hash + java.util.Objects.hashCode(bindings.get(BindingSlot.FORWARD_INDICATOR));
        for (BindingRef ref : yawBearings) hash = 31 * hash + bearingChildHash(ref);
        for (BindingRef ref : pitchBearings) hash = 31 * hash + bearingChildHash(ref);
        return hash;
    }

    private int bearingChildHash(BindingRef ref) {
        if (ref != null && ref.resolve(level) instanceof SwivelBearingBlockEntity bearing
                && bearing.isAssembled() && bearing.getSubLevelID() != null) {
            return bearing.getSubLevelID().hashCode();
        }
        return 0;
    }

    private @Nullable BindingRef closestParentBearing(
            BindingRef device, List<BindingRef> candidates) {
        return candidates.stream()
                .filter(ref -> bearingChildDistance(device, ref) != Integer.MAX_VALUE)
                .min(Comparator.comparingInt((BindingRef ref) -> bearingChildDistance(device, ref))
                        .thenComparing(bindingOrder()))
                .orElse(null);
    }

    private int bearingChildDistance(BindingRef device, BindingRef bearingRef) {
        if (device == null || device.subLevelId() == null || bearingRef == null
                || !(bearingRef.resolve(level) instanceof SwivelBearingBlockEntity bearing)
                || !bearing.isAssembled() || bearing.getSubLevelID() == null) {
            return Integer.MAX_VALUE;
        }
        UUID childId = bearing.getSubLevelID();
        if (childId.equals(device.subLevelId())) return 0;
        if (level instanceof ServerLevel serverLevel) {
            int weldedDistance = SynaxisWeldCompat.weldedDistance(
                    serverLevel, childId, device.subLevelId());
            if (weldedDistance >= 0) return weldedDistance;
        }
        SubLevel child = SubLevelContainer.getContainer(level).getSubLevel(childId);
        if (child == null || child.isRemoved()) return Integer.MAX_VALUE;
        List<SubLevel> queue = new ArrayList<>();
        Map<UUID, Integer> distance = new HashMap<>();
        queue.add(child);
        distance.put(childId, 0);
        for (int index = 0; index < queue.size(); index++) {
            SubLevel current = queue.get(index);
            int currentDistance = distance.get(current.getUniqueId());
            for (dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor actor
                    : current.getPlot().getBlockEntityActors()) {
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
        return Comparator
                .comparing((BindingRef ref) -> ref.subLevelId() == null
                        ? "" : ref.subLevelId().toString())
                .thenComparingLong(ref -> ref.pos().asLong());
    }

    private boolean clearLineToContact(@Nullable Vec3 start, Contact contact) {
        if (level == null || start == null || contact == null) return false;
        Vec3 end = contact.center();
        if (start.distanceToSqr(end) < 1.0e-8) return true;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (!(container.getLevel() instanceof ServerLevel root)
                || !isSegmentLoaded(root, start, end)) return false;
        double targetEntryDistance = contact.bounds().clip(start, end)
                .map(start::distanceTo).orElse(start.distanceTo(end));
        BlockHitResult terrain = root.clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (terrain.getType() != HitResult.Type.MISS
                && start.distanceTo(terrain.getLocation()) + 0.5 < targetEntryDistance) return false;
        SableMissileFuze.Contact structureHit = SableMissileFuze.firstBlockContact(root, start, end);
        if (structureHit == null || contact.members().contains(structureHit.subLevelId())) return true;
        return start.distanceTo(structureHit.position()) + 0.5 >= targetEntryDistance;
    }

    private AimSolution solveAim(Contact contact) {
        CannonBallisticsCompat.ShotProfile profile = cannonMounts.stream()
                .filter(ref -> CannonMountCompat.hasAssembledCannon(level, ref))
                .map(ref -> CannonBallisticsCompat.analyze(level, ref))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        return solveAim(contact, profile);
    }

    private AimSolution directAim(Contact contact) {
        Vec3 origin = measuredWeaponOrigin();
        return directAim(contact, origin);
    }

    private AimSolution directAim(Contact contact, @Nullable Vec3 origin) {
        Vec3 direction = origin == null ? null : contact.center().subtract(origin);
        return new AimSolution(contact.center(), direction == null || direction.lengthSqr() < 1.0e-8
                ? null : direction.normalize());
    }

    private AimSolution solveFixedAim(
            Vec3 target,
            @Nullable CannonBallisticsCompat.ShotProfile profile
    ) {
        Vec3 direction = profile == null
                ? null
                : CannonBallisticsCompat.solveLaunchDirection(profile, target);
        return new AimSolution(target, direction);
    }

    /**
     * Iterates a moving-target intercept against CBC's exact discrete
     * trajectory solver. The profile already contains the firing structure's
     * inherited Sable velocity, while the contact velocity supplies target
     * motion, so stationary, carrier-moving and target-moving cases share one
     * solution.
     */
    private AimSolution solveAim(
            Contact contact,
            @Nullable CannonBallisticsCompat.ShotProfile profile
    ) {
        Vec3 target = contact.center();
        if (profile == null) {
            double ticks = Math.min(200.0,
                    contact.distance() / 8.0);
            Vec3 predicted = target.add(contact.velocity().scale(ticks));
            Vec3 origin = measuredWeaponOrigin();
            return new AimSolution(
                    predicted,
                    origin == null ? null : predicted.subtract(origin).normalize()
            );
        }
        CannonBallisticsCompat.MovingTargetSolution intercept =
                CannonBallisticsCompat.solveMovingTarget(
                        profile, target, contact.velocity());
        if (intercept != null) {
            return new AimSolution(
                    intercept.target(), intercept.launchDirection());
        }

        // A target that outruns the projectile has no valid future intercept.
        // Keep the gun on its current position instead of extrapolating an
        // arbitrary maximum-flight-time point that may lie behind the muzzle.
        Vec3 direction = CannonBallisticsCompat.solveLaunchDirection(
                profile, target);
        if (direction == null && target.distanceToSqr(profile.muzzle()) > 1.0e-8) {
            direction = target.subtract(profile.muzzle()).normalize();
        }
        return new AimSolution(target, direction);
    }

    private void commandBearing(BindingRef ref, boolean yaw, Vec3 measured, Vec3 requested) {
        if (ref != null && ref.resolve(level) instanceof SwivelBearingBlockEntity bearing && bearing.isAssembled()) {
            var s = getPreviewBearingSettings(yaw ? BindingSlot.YAW : BindingSlot.PITCH, ref);
            maintainBearing(ref, yaw);
            Vec3 target = s.reversed() ? reversedBearingRequest(bearing, measured, requested) : requested;
            BearingStabilizer.commandFiniteAim(bearing, measured, target, yaw, s.speed(),
                    s.depression(), s.elevation(), s.offset(), List.of(), null, false);
        }
    }

    private static Vec3 reversedBearingRequest(SwivelBearingBlockEntity bearing, Vec3 measured, Vec3 requested) {
        var facing = bearing.getBlockState().getValue(dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock.FACING);
        Vector3d axis = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        var body = Sable.HELPER.getContaining(bearing);
        if (body != null) body.logicalPose().transformNormal(axis);
        axis.normalize();
        Vector3d from = new Vector3d(measured.x, measured.y, measured.z).normalize();
        Vector3d to = new Vector3d(requested.x, requested.y, requested.z).normalize();
        Vector3d a = new Vector3d(from).sub(new Vector3d(axis).mul(from.dot(axis)));
        Vector3d b = new Vector3d(to).sub(new Vector3d(axis).mul(to.dot(axis)));
        if (a.lengthSquared() < 1e-8 || b.lengthSquared() < 1e-8) return requested;
        a.normalize(); b.normalize();
        double angle = -Math.atan2(axis.dot(new Vector3d(a).cross(b)), Mth.clamp(a.dot(b), -1, 1));
        Vector3d result = new org.joml.Quaterniond().rotationAxis(angle, axis.x, axis.y, axis.z).transform(from);
        return new Vec3(result.x, result.y, result.z);
    }

    /**
     * Keeps an armed automatic-defense vehicle ticking independently of any
     * player's view/simulation distance. Incoming Mianbao missiles already
     * ticket their own moving chunks; without the matching vehicle-side
     * tickets, the missile only wakes the display and turrets after its ticket
     * overlaps their chunks at very short range.
     */
    private void maintainDefenseVehicleTickets() {
        if (!automaticMissileDefense || level == null || level.isClientSide) {
            releaseDefenseVehicleTickets();
            return;
        }
        SubLevelContainer rawContainer = SubLevelContainer.getContainer(level);
        Level rootLevel = rawContainer.getLevel();
        if (!(rootLevel instanceof ServerLevel root)) {
            releaseDefenseVehicleTickets();
            return;
        }

        // Sable plots have a separate server-side force-loading system. A
        // physical body may remain simulated/renderable while its embedded
        // block entities no longer receive full ticks, especially on a LAN
        // integrated server when only a remote player is nearby. Use Sable's
        // own ticket API for the complete display/bearing/weapon closure.
        Set<UUID> wantedSubLevels = ownStructureIds(
                subLevelTopology(level.getGameTime()));
        String ticketKey = defenseSubLevelTicketKey();
        if (rawContainer instanceof ServerSubLevelContainer serverContainer) {
            if (defenseSubLevelTicketContainer != serverContainer
                    || !ticketKey.equals(defenseSubLevelTicketKey)) {
                releaseDefenseSubLevelTickets();
                defenseSubLevelTicketContainer = serverContainer;
                defenseSubLevelTicketKey = ticketKey;
            }
            for (UUID id : Set.copyOf(defenseSubLevelTicketIds)) {
                if (wantedSubLevels.contains(id)) continue;
                SubLevel sub = serverContainer.getSubLevel(id);
                if (sub instanceof ServerSubLevel serverSub) {
                    serverContainer.removeForceLoadTicket(
                            serverSub, DEFENSE_SUBLEVEL_TICKET, ticketKey);
                }
                defenseSubLevelTicketIds.remove(id);
            }
            for (UUID id : wantedSubLevels) {
                if (defenseSubLevelTicketIds.contains(id)) continue;
                SubLevel sub = serverContainer.getSubLevel(id);
                if (!(sub instanceof ServerSubLevel serverSub)) continue;
                serverContainer.addForceLoadTicket(
                        serverSub, DEFENSE_SUBLEVEL_TICKET, ticketKey);
                defenseSubLevelTicketIds.add(id);
            }
        } else {
            releaseDefenseSubLevelTickets();
        }

        // Ordinary root-world blocks still use vanilla region tickets. Do not
        // duplicate Sable plots here: their force-load ticket above is the
        // authoritative mechanism and avoids loading a moving 3x3 chunk area.
        Set<ChunkPos> wantedRootChunks = new HashSet<>();
        if (Sable.HELPER.getContaining(this) == null) {
            addDefenseTicketPosition(wantedRootChunks, Vec3.atCenterOf(worldPosition));
        }
        bindings.values().forEach(ref -> addRootBindingTicket(wantedRootChunks, ref));
        yawBearings.forEach(ref -> addRootBindingTicket(wantedRootChunks, ref));
        pitchBearings.forEach(ref -> addRootBindingTicket(wantedRootChunks, ref));
        cannonMounts.forEach(ref -> addRootBindingTicket(wantedRootChunks, ref));
        aaLaunchers.forEach(ref -> addRootBindingTicket(wantedRootChunks, ref));
        cruiseLaunchers.forEach(ref -> addRootBindingTicket(wantedRootChunks, ref));
        if (defenseVehicleTicketLevel != root) {
            releaseDefenseRootTickets();
            defenseVehicleTicketLevel = root;
        }
        for (ChunkPos chunk : Set.copyOf(defenseVehicleTicketChunks)) {
            if (wantedRootChunks.contains(chunk)) continue;
            root.getChunkSource().removeRegionTicket(
                    DEFENSE_VEHICLE_TICKET, chunk, 0,
                    defenseVehicleTicketOwner, true);
            defenseVehicleTicketChunks.remove(chunk);
        }
        for (ChunkPos chunk : wantedRootChunks) {
            if (!defenseVehicleTicketChunks.add(chunk)) continue;
            root.getChunkSource().addRegionTicket(
                    DEFENSE_VEHICLE_TICKET, chunk, 0,
                    defenseVehicleTicketOwner, true);
        }
    }

    private void addRootBindingTicket(Set<ChunkPos> wanted, BindingRef ref) {
        if (ref == null || ref.subLevelId() != null || !ref.isInDimension(level)) return;
        addDefenseTicketPosition(wanted, Vec3.atCenterOf(ref.pos()));
    }

    private static void addDefenseTicketPosition(
            Set<ChunkPos> wanted, @Nullable Vec3 position) {
        if (position == null || !Double.isFinite(position.x)
                || !Double.isFinite(position.z)) return;
        ChunkPos center = new ChunkPos(Mth.floor(position.x) >> 4,
                Mth.floor(position.z) >> 4);
        // A one-chunk guard ring prevents a moving Sable body from dropping
        // out between its physics move and the display's next ticket update.
        for (int x = center.x - 1; x <= center.x + 1; x++) {
            for (int z = center.z - 1; z <= center.z + 1; z++) {
                wanted.add(new ChunkPos(x, z));
            }
        }
    }

    private String defenseSubLevelTicketKey() {
        SubLevel containing = Sable.HELPER.getContaining(this);
        return level.dimension().location() + ":"
                + (containing == null ? "root" : containing.getUniqueId())
                + ":" + worldPosition.asLong();
    }

    private void releaseDefenseSubLevelTickets() {
        if (defenseSubLevelTicketContainer != null
                && defenseSubLevelTicketKey != null) {
            for (UUID id : defenseSubLevelTicketIds) {
                SubLevel sub = defenseSubLevelTicketContainer.getSubLevel(id);
                if (sub instanceof ServerSubLevel serverSub) {
                    defenseSubLevelTicketContainer.removeForceLoadTicket(
                            serverSub, DEFENSE_SUBLEVEL_TICKET,
                            defenseSubLevelTicketKey);
                }
            }
        }
        defenseSubLevelTicketIds.clear();
        defenseSubLevelTicketContainer = null;
        defenseSubLevelTicketKey = null;
    }

    private void releaseDefenseRootTickets() {
        if (defenseVehicleTicketLevel != null) {
            for (ChunkPos chunk : defenseVehicleTicketChunks) {
                defenseVehicleTicketLevel.getChunkSource().removeRegionTicket(
                        DEFENSE_VEHICLE_TICKET, chunk, 0,
                        defenseVehicleTicketOwner, true);
            }
        }
        defenseVehicleTicketChunks.clear();
        defenseVehicleTicketLevel = null;
    }

    private void releaseDefenseVehicleTickets() {
        releaseDefenseSubLevelTickets();
        releaseDefenseRootTickets();
    }

    private void maintainBoundBearings() {
        yawBearings.forEach(ref -> maintainBearing(ref, true));
        pitchBearings.forEach(ref -> maintainBearing(ref, false));
        // Calibrate from this turret's actual weapons before either aiming or returning to idle.
        for (BindingRef yaw : yawBearings) {
            if (yaw.resolve(level) instanceof SwivelBearingBlockEntity bearing)
                BearingStabilizer.maintainAzimuthObservations(bearing, List.of());
        }
        for (List<TurretGroup> cluster : aimClusters(turretGroups())) {
            List<AimReference> rays = cluster.stream().map(this::measureAimReference)
                    .filter(java.util.Objects::nonNull).toList();
            Vec3 centre = averageDirections(rays.stream().map(AimReference::direction).toList());
            if (centre == null) continue;
            List<BearingStabilizer.AzimuthObservation> observations = rays.stream()
                    .filter(ray -> ray.bodyId() != null)
                    .map(ray -> new BearingStabilizer.AzimuthObservation(ray.bodyId(), ray.direction())).toList();
            Set<BindingRef> measuredYaw = new HashSet<>();
            for (TurretGroup group : cluster) {
                if (group.yaw() != null && measuredYaw.add(group.yaw())
                        && group.yaw().resolve(level) instanceof SwivelBearingBlockEntity bearing) {
                    BearingStabilizer.maintainDirectionReference(bearing, centre);
                    BearingStabilizer.maintainAzimuthObservations(bearing,
                            observations.size() == rays.size() ? observations : List.of());
                }
                AimReference measured = measureAimReference(group);
                if (measured != null && group.pitch() != null
                        && group.pitch().resolve(level) instanceof SwivelBearingBlockEntity bearing)
                    BearingStabilizer.maintainDirectionReference(bearing, measured.direction(), measured.bodyId());
            }
        }
    }

    private void returnBearingsToNeutral() {
        yawBearings.forEach(ref -> returnBearingToNeutral(ref, true));
        pitchBearings.forEach(ref -> returnBearingToNeutral(ref, false));
    }

    private void returnBearingToNeutral(BindingRef ref, boolean yaw) {
        if (ref == null
                || !(ref.resolve(level) instanceof SwivelBearingBlockEntity bearing)
                || !bearing.isAssembled()) return;
        maintainBearing(ref, yaw);
        var s = getPreviewBearingSettings(yaw ? BindingSlot.YAW : BindingSlot.PITCH, ref);
        Double neutral = bearingNeutralAngles.get(ref);
        if (neutral != null) BearingStabilizer.commandPhysicalNeutral(bearing, yaw,
                s.speed(), s.depression(), s.elevation(), s.offset(), neutral);
    }

    private void maintainBearing(BindingRef ref, boolean yaw) {
        if (ref == null || !(ref.resolve(level) instanceof SwivelBearingBlockEntity bearing)
                || !bearing.isAssembled()) return;
        var s = getPreviewBearingSettings(yaw ? BindingSlot.YAW : BindingSlot.PITCH, ref);
        // Defaults and per-device overrides use the controller's physical stops alike.
        BearingStabilizer.setImplementation(bearing, true);
        if (yaw) BearingStabilizer.setYawLimits(bearing, s.limits());
        BearingStabilizer.maintainBinding(bearing, yaw, true, false, false, s.speed(),
                s.depression(), s.elevation(), s.offset());
        if (!bearingNeutralAngles.containsKey(ref)) {
            double angle = BearingStabilizer.capturePhysicalJointAngle(bearing);
            if (Double.isFinite(angle)) {
                bearingNeutralAngles.put(ref, angle);
                setChanged();
            }
        }
        BlockPos plate = bearing.getPlatePos();
        if (plate != null && bearing.getLevel().getBlockEntity(plate) instanceof BearingHeadVisibility visibility)
            visibility.stabilizedTurret$setBearingHeadHidden(s.headHidden());
    }

    private @Nullable Vec3 measuredWeaponDirection() {
        for (BindingRef mount : cannonMounts) {
            Vec3 direction = CannonMountCompat.getAssembledCannonDirection(level, mount);
            if (direction != null) return direction;
        }
        BindingRef launcher = aaLaunchers.isEmpty() ? null : aaLaunchers.get(0);
        Vec3 direction = launcher == null ? null
                : MianbaoAirDefenseCompat.getLauncherDirection(level, launcher);
        return direction != null ? direction : ForwardIndicatorCompat.worldDirection(
                level, bindings.get(BindingSlot.FORWARD_INDICATOR));
    }

    private @Nullable Vec3 measuredWeaponOrigin() {
        for (BindingRef mount : cannonMounts) {
            Vec3 muzzle = CannonBallisticsCompat.getMuzzlePosition(level, mount);
            if (muzzle != null) return muzzle;
        }
        Vec3 origin = aaLaunchers.isEmpty() ? null
                : MianbaoAirDefenseCompat.getLauncherMuzzlePosition(level, aaLaunchers.get(0));
        if (origin != null) return origin;
        BindingRef indicator = bindings.get(BindingSlot.FORWARD_INDICATOR);
        return ForwardIndicatorCompat.isPresent(level, indicator) ? indicator.worldCenter(level) : null;
    }

    private void setCannonFire(boolean powered) {
        for (BindingRef mount : cannonMounts) CannonMountCompat.setAutomaticFire(level, mount, powered);
    }

    private boolean clearOfOwnStructure(Vec3 start, Vec3 end) {
        if (level == null || start == null || end == null) return false;
        Vec3 limitedEnd = limitSegment(
                start, end, OWN_STRUCTURE_CLEARANCE_DISTANCE);
        for (UUID id : ownStructureIds(subLevelTopology(level == null ? 0L : level.getGameTime()))) {
            SubLevel sub = SubLevelContainer.getContainer(level).getSubLevel(id);
            if (sub == null || sub.isRemoved()) continue;
            Vector3d localStart = new Vector3d(start.x, start.y, start.z);
            Vector3d localEnd = new Vector3d(
                    limitedEnd.x, limitedEnd.y, limitedEnd.z);
            sub.logicalPose().transformPositionInverse(localStart);
            sub.logicalPose().transformPositionInverse(localEnd);
            Vec3 localStartVec = new Vec3(
                    localStart.x, localStart.y, localStart.z);
            Vec3 localEndVec = new Vec3(
                    localEnd.x, localEnd.y, localEnd.z);
            // Level.clip obtains block states along the complete segment.
            // On C2ME, touching an unloaded chunk can synchronously wait for
            // that chunk on the server thread and reduce TPS to zero. This
            // safety check only needs the vehicle immediately in front of the
            // muzzle, so never request or wait for missing chunks.
            if (!isSegmentLoaded(sub.getLevel(), localStartVec, localEndVec)) {
                return false;
            }
            ClipContext context = new ClipContext(
                    localStartVec, localEndVec,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    CollisionContext.empty());
            BlockHitResult hit = sub.getLevel().clip(context);
            if (hit.getType() != HitResult.Type.MISS) return false;
        }
        return true;
    }

    private static Vec3 limitSegment(
            Vec3 start, Vec3 end, double maxDistance) {
        Vec3 delta = end.subtract(start);
        double lengthSqr = delta.lengthSqr();
        double maximum = Math.max(0.0, maxDistance);
        if (!Double.isFinite(lengthSqr)
                || !Double.isFinite(maximum)
                || lengthSqr <= 1.0e-12) {
            return start;
        }
        if (lengthSqr <= maximum * maximum) return end;
        return start.add(delta.scale(maximum / Math.sqrt(lengthSqr)));
    }

    private static boolean isSegmentLoaded(
            Level level, Vec3 start, Vec3 end) {
        if (level == null
                || !Double.isFinite(start.x)
                || !Double.isFinite(start.y)
                || !Double.isFinite(start.z)
                || !Double.isFinite(end.x)
                || !Double.isFinite(end.y)
                || !Double.isFinite(end.z)) {
            return false;
        }
        Vec3 delta = end.subtract(start);
        int samples = Math.max(1, Mth.ceil(delta.length() / 4.0));
        for (int index = 0; index <= samples; index++) {
            double fraction = (double) index / samples;
            double x = Mth.lerp(fraction, start.x, end.x);
            double z = Mth.lerp(fraction, start.z, end.z);
            int chunkX = Mth.floor(x) >> 4;
            int chunkZ = Mth.floor(z) >> 4;
            if (!level.hasChunk(chunkX, chunkZ)) return false;
        }
        return true;
    }

    private Set<UUID> ownStructureIds(List<Set<UUID>> topology) {
        Set<UUID> seeds = new HashSet<>();
        SubLevel containing = Sable.HELPER.getContaining(this);
        if (containing != null) seeds.add(containing.getUniqueId());
        bindings.values().forEach(ref -> { if (ref.subLevelId() != null) seeds.add(ref.subLevelId()); });
        yawBearings.forEach(ref -> { if (ref.subLevelId() != null) seeds.add(ref.subLevelId()); });
        pitchBearings.forEach(ref -> { if (ref.subLevelId() != null) seeds.add(ref.subLevelId()); });
        cannonMounts.forEach(ref -> { if (ref.subLevelId() != null) seeds.add(ref.subLevelId()); });
        aaLaunchers.forEach(ref -> { if (ref.subLevelId() != null) seeds.add(ref.subLevelId()); });
        cruiseLaunchers.forEach(ref -> { if (ref.subLevelId() != null) seeds.add(ref.subLevelId()); });
        ballisticLaunchers.forEach(ref -> { if (ref.subLevelId() != null) seeds.add(ref.subLevelId()); });
        iffTransponders.forEach(ref -> { if (ref.subLevelId() != null) seeds.add(ref.subLevelId()); });
        Set<UUID> result = new HashSet<>(seeds);
        for (Set<UUID> cluster : topology) {
            if (cluster.stream().anyMatch(seeds::contains)) result.addAll(cluster);
        }
        return result;
    }

    private List<Set<UUID>> subLevelTopology(long now) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        List<SubLevel> loaded = new ArrayList<>();
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub != null && !sub.isRemoved()) loaded.add(sub);
        }
        Set<UUID> ids = loaded.stream().map(SubLevel::getUniqueId).collect(java.util.stream.Collectors.toSet());
        if (cachedTopologyAt != Long.MIN_VALUE && now >= cachedTopologyAt
                && now - cachedTopologyAt < 100L && cachedTopologyIds.equals(ids)) return cachedTopology;
        Map<UUID, UUID> parent = new HashMap<>();
        ids.forEach(id -> parent.put(id, id));
        for (SubLevel sub : loaded) {
            for (SubLevel connected : SubLevelHelper.getConnectedChain(sub)) {
                if (connected != null && ids.contains(connected.getUniqueId()))
                    union(parent, sub.getUniqueId(), connected.getUniqueId());
            }
            for (dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor actor
                    : sub.getPlot().getBlockEntityActors()) {
                if (actor instanceof SwivelBearingBlockEntity bearing && bearing.isAssembled()
                        && bearing.getSubLevelID() != null && ids.contains(bearing.getSubLevelID())) {
                    union(parent, sub.getUniqueId(), bearing.getSubLevelID());
                }
            }
        }
        Map<UUID, Set<UUID>> groups = new java.util.LinkedHashMap<>();
        for (UUID id : ids) groups.computeIfAbsent(find(parent, id), ignored -> new HashSet<>()).add(id);
        cachedTopology = groups.values().stream().map(Set::copyOf).toList();
        cachedTopologyIds = Set.copyOf(ids);
        cachedTopologyAt = now;
        return cachedTopology;
    }

    private static UUID find(Map<UUID, UUID> parent, UUID id) {
        UUID root = parent.get(id);
        if (root == null || root.equals(id)) return id;
        root = find(parent, root);
        parent.put(id, root);
        return root;
    }

    private static void union(Map<UUID, UUID> parent, UUID a, UUID b) {
        UUID rootA = find(parent, a), rootB = find(parent, b);
        if (!rootA.equals(rootB)) parent.put(rootB, rootA);
    }

    private static AABB projectBounds(Level source, AABB local) {
        AABB result = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 world = Sable.HELPER.projectOutOfSubLevel(source, new Vec3(
                    (corner & 1) == 0 ? local.minX : local.maxX,
                    (corner & 2) == 0 ? local.minY : local.maxY,
                    (corner & 4) == 0 ? local.minZ : local.maxZ));
            AABB point = new AABB(world, world);
            result = result == null ? point : union(result, point);
        }
        return result == null ? local : result;
    }

    private void updateCruiseAvailability() {
        updateCruiseAvailability(false);
    }

    private void updateCruiseAvailability(boolean force) {
        if (level == null || level.isClientSide) return;
        long now = level.getGameTime();
        if (!force && lastCruiseAvailabilityCheck != Long.MIN_VALUE
                && now - lastCruiseAvailabilityCheck < 10L) return;
        lastCruiseAvailabilityCheck = now;
        EnumSet<MianbaoCruiseMissileCompat.Type> next =
                EnumSet.noneOf(MianbaoCruiseMissileCompat.Type.class);
        for (BindingRef launcher : cruiseLaunchers) {
            MianbaoCruiseMissileCompat.Type type =
                    MianbaoCruiseMissileCompat.availableType(level, launcher);
            if (type != null) next.add(type);
        }
        Set<MianbaoCruiseMissileCompat.Type> immutable = next.isEmpty()
                ? Set.of() : Set.copyOf(next);
        EnumSet<MianbaoBallisticMissileCompat.Type> nextBallistic =
                EnumSet.noneOf(MianbaoBallisticMissileCompat.Type.class);
        for (BindingRef launcher : ballisticLaunchers) {
            MianbaoBallisticMissileCompat.Type type =
                    MianbaoBallisticMissileCompat.availableType(level, launcher);
            if (type != null) nextBallistic.add(type);
        }
        Set<MianbaoBallisticMissileCompat.Type> immutableBallistic =
                nextBallistic.isEmpty() ? Set.of() : Set.copyOf(nextBallistic);
        int nextCapacity = 0;
        if (selectedCruiseType != null) {
            for (BindingRef launcher : cruiseLaunchers) {
                if (MianbaoCruiseMissileCompat.hasType(level, launcher, selectedCruiseType)) {
                    nextCapacity++;
                }
            }
        }
        boolean changed = !immutable.equals(availableCruiseTypes)
                || !immutableBallistic.equals(availableBallisticTypes)
                || cruiseSalvoCapacity != nextCapacity;
        availableCruiseTypes = immutable;
        availableBallisticTypes = immutableBallistic;
        cruiseSalvoCapacity = nextCapacity;
        if (!availableCruiseTypes.isEmpty()
                && !availableCruiseTypes.contains(selectedCruiseType)) {
            selectedCruiseType = MianbaoCruiseMissileCompat.Type.values()[0];
            for (MianbaoCruiseMissileCompat.Type type : MianbaoCruiseMissileCompat.Type.values()) {
                if (availableCruiseTypes.contains(type)) {
                    selectedCruiseType = type;
                    break;
                }
            }
            changed = true;
        }
        if (!availableBallisticTypes.isEmpty()
                && !availableBallisticTypes.contains(selectedBallisticType)) {
            for (MianbaoBallisticMissileCompat.Type type
                    : MianbaoBallisticMissileCompat.Type.values()) {
                if (availableBallisticTypes.contains(type)) {
                    selectedBallisticType = type;
                    changed = true;
                    break;
                }
            }
        }
        int capacityAfterTypeCorrection = 0;
        for (BindingRef launcher : cruiseLaunchers) {
            if (MianbaoCruiseMissileCompat.hasType(level, launcher, selectedCruiseType)) {
                capacityAfterTypeCorrection++;
            }
        }
        if (cruiseSalvoCapacity != capacityAfterTypeCorrection) {
            cruiseSalvoCapacity = capacityAfterTypeCorrection;
            changed = true;
        }
        if (trimCruiseStrikeTargets()) changed = true;
        if (changed) sync();
    }

    private void toggleCruiseStrikeTarget(@Nullable TargetKey targetKey, Vec3 point) {
        int existing = -1;
        for (int i = 0; i < cruiseStrikeTargets.size(); i++) {
            CruiseStrikeTarget target = cruiseStrikeTargets.get(i);
            if (targetKey != null ? targetKey.equals(target.targetKey())
                    : target.targetKey() == null && target.point().distanceToSqr(point)
                    <= Math.pow(Math.max(2.0, radarRange / Math.max(64.0, mapResolution)), 2.0)) {
                existing = i;
                break;
            }
        }
        if (existing >= 0) {
            cruiseStrikeTargets.remove(existing);
        } else if (cruiseStrikeTargets.size() < cruiseSalvoCapacity) {
            cruiseStrikeTargets.add(new CruiseStrikeTarget(targetKey, point));
        }
        if (cruiseStrikeTargets.isEmpty()) {
            selectedTarget = null;
            manualAimPoint = null;
        } else {
            CruiseStrikeTarget last = cruiseStrikeTargets.get(cruiseStrikeTargets.size() - 1);
            selectedTarget = last.targetKey();
            manualAimPoint = last.targetKey() == null ? last.point() : null;
        }
    }

    private boolean trimCruiseStrikeTargets() {
        boolean changed = false;
        int capacity = Math.max(0, cruiseSalvoCapacity);
        while (cruiseStrikeTargets.size() > capacity) {
            cruiseStrikeTargets.remove(cruiseStrikeTargets.size() - 1);
            changed = true;
        }
        return changed;
    }

    private void removeInvalidBindings() {
        if (level == null) return;
        boolean changed = bindings.entrySet().removeIf(entry -> !entry.getValue().isSameTarget(level));
        changed |= yawBearings.removeIf(ref -> !ref.isSameTarget(level));
        changed |= pitchBearings.removeIf(ref -> !ref.isSameTarget(level));
        pitchOffsets.keySet().removeIf(ref -> !pitchBearings.contains(ref));
        previewBearingSettings.keySet().removeIf(ref -> !pitchBearings.contains(ref) && !yawBearings.contains(ref));
        bearingNeutralAngles.keySet().removeIf(ref -> !pitchBearings.contains(ref) && !yawBearings.contains(ref));
        changed |= cannonMounts.removeIf(ref -> !ref.isSameTarget(level));
        changed |= aaLaunchers.removeIf(ref ->
                !MianbaoAirDefenseCompat.isBoundLauncherPresent(level, ref));
        changed |= cruiseLaunchers.removeIf(ref -> !ref.isSameTarget(level));
        changed |= ballisticLaunchers.removeIf(ref ->
                !MianbaoBallisticMissileCompat.isBindingTarget(level.getBlockState(ref.pos()))
                        || !ref.isSameTarget(level));
        changed |= iffTransponders.removeIf(ref -> !ref.isSameTarget(level));
        if (changed) {
            lastCruiseAvailabilityCheck = Long.MIN_VALUE;
            sync();
        }
    }

    private void updateContacts(List<Contact> next) {
        if (contacts.equals(next)) return;
        contacts = next;
        sync();
    }

    private void sync() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private static AABB union(AABB a, AABB b) {
        return new AABB(Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
                Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("AutomaticAttack", automaticAttack);
        tag.putBoolean("ScanStructureContacts", scanStructureContacts);
        tag.putBoolean("ScanLivingContacts", scanLivingContacts);
        tag.putBoolean("ScanMissileContacts", scanMissileContacts);
        tag.putBoolean("AutomaticMissileDefense", automaticMissileDefense);
        tag.putDouble("AutomaticMissileDefenseRange", automaticMissileDefenseRange);
        tag.putDouble("MissileProximityRange", missileProximityRange);
        tag.putDouble("RadarRange", radarRange);
        tag.putDouble("CannonFireRange", cannonFireRange);
        tag.putInt("CannonFirePower", cannonFirePower);
        tag.putInt("MapUpdateIntervalSeconds", mapUpdateIntervalSeconds);
        tag.putInt("MapResolution", mapResolution);
        tag.putDouble("YawSpeed", yawSpeed);
        tag.putDouble("PitchSpeed", pitchSpeed);
        tag.putDouble("DepressionLimit", depressionLimit);
        tag.putDouble("ElevationLimit", elevationLimit);
        tag.putBoolean("YawReversed", yawReversed);
        tag.putBoolean("PitchReversed", pitchReversed);
        tag.putBoolean("GodModeVideoPlaying", godModeVideoPlaying);
        tag.putLong("GodModeVideoStartedAt", godModeVideoStartedAt);
        tag.putString("SelectedWeapon", selectedWeapon.name());
        tag.putString("SelectedCruiseType", selectedCruiseType.key());
        tag.putString("SelectedBallisticType", selectedBallisticType.key());
        tag.putBoolean("CruiseMultiSelect", cruiseMultiSelect);
        tag.putInt("CruiseSalvoCapacity", cruiseSalvoCapacity);
        tag.putString("AvailableCruiseTypes", availableCruiseTypes.stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(MianbaoCruiseMissileCompat.Type::key)
                .collect(java.util.stream.Collectors.joining(",")));
        tag.putString("AvailableBallisticTypes", availableBallisticTypes.stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(MianbaoBallisticMissileCompat.Type::key)
                .collect(java.util.stream.Collectors.joining(",")));
        tag.putBoolean("BallisticAvailable", hasBallisticMissileAvailable());
        if (manualAimPoint != null) {
            tag.putDouble("ManualAimX", manualAimPoint.x);
            tag.putDouble("ManualAimY", manualAimPoint.y);
            tag.putDouble("ManualAimZ", manualAimPoint.z);
        }
        if (selectedTarget != null) {
            tag.putUUID("SelectedTarget", selectedTarget.id());
            tag.putBoolean("SelectedTargetLiving", selectedTarget.living());
        }
        ListTag strikeTargets = new ListTag();
        for (CruiseStrikeTarget target : cruiseStrikeTargets) {
            CompoundTag targetTag = new CompoundTag();
            if (target.targetKey() != null) {
                targetTag.putUUID("Target", target.targetKey().id());
                targetTag.putBoolean("Living", target.targetKey().living());
            }
            targetTag.putDouble("X", target.point().x);
            targetTag.putDouble("Y", target.point().y);
            targetTag.putDouble("Z", target.point().z);
            strikeTargets.add(targetTag);
        }
        tag.put("CruiseStrikeTargets", strikeTargets);
        bindings.forEach((slot, ref) -> tag.put("Binding_" + slot.name(), ref.save()));
        ListTag yaw = new ListTag(); yawBearings.forEach(ref -> yaw.add(ref.save())); tag.put("YawBearings", yaw);
        ListTag pitch = new ListTag(); pitchBearings.forEach(ref -> pitch.add(ref.save())); tag.put("PitchBearings", pitch);
        ListTag pitchOffsetsTag = new ListTag();
        pitchOffsets.forEach((ref, offset) -> {
            if (ref == null || offset == null) return;
            CompoundTag entry = new CompoundTag();
            entry.put("Ref", ref.save());
            entry.putDouble("X", offset.x);
            entry.putDouble("Y", offset.y);
            entry.putDouble("Z", offset.z);
            pitchOffsetsTag.add(entry);
        });
        tag.put("PitchBearingOffsets", pitchOffsetsTag);
        ListTag overrides = new ListTag();
        previewBearingSettings.forEach((ref, settings) -> {
            CompoundTag entry = settings.save();
            entry.put("Ref", ref.save());
            overrides.add(entry);
        });
        tag.put("PreviewBearingSettings", overrides);
        ListTag neutralAngles = new ListTag();
        bearingNeutralAngles.forEach((ref, angle) -> {
            CompoundTag entry = new CompoundTag();
            entry.put("Ref", ref.save());
            entry.putDouble("Angle", angle);
            neutralAngles.add(entry);
        });
        tag.put("BearingNeutralAngles", neutralAngles);
        ListTag mounts = new ListTag(); cannonMounts.forEach(ref -> mounts.add(ref.save())); tag.put("CannonMounts", mounts);
        ListTag launchers = new ListTag(); aaLaunchers.forEach(ref -> launchers.add(ref.save())); tag.put("AaLaunchers", launchers);
        tag.remove("LaserDesignators");
        ListTag cruise = new ListTag(); cruiseLaunchers.forEach(ref -> cruise.add(ref.save())); tag.put("CruiseLaunchers", cruise);
        ListTag ballistic = new ListTag(); ballisticLaunchers.forEach(ref -> ballistic.add(ref.save())); tag.put("BallisticLaunchers", ballistic);
        ListTag iff = new ListTag(); iffTransponders.forEach(ref -> iff.add(ref.save())); tag.put("IffTransponders", iff);
        ListTag contactTags = new ListTag(); contacts.forEach(contact -> contactTags.add(contact.save())); tag.put("Contacts", contactTags);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        automaticAttack = tag.getBoolean("AutomaticAttack");
        scanStructureContacts = !tag.contains("ScanStructureContacts")
                || tag.getBoolean("ScanStructureContacts");
        scanLivingContacts = !tag.contains("ScanLivingContacts")
                || tag.getBoolean("ScanLivingContacts");
        scanMissileContacts = !tag.contains("ScanMissileContacts")
                || tag.getBoolean("ScanMissileContacts");
        automaticMissileDefense = tag.getBoolean("AutomaticMissileDefense");
        automaticMissileDefenseRange = normalizeMissileDefenseRange(
                tag.contains("AutomaticMissileDefenseRange")
                        ? tag.getDouble("AutomaticMissileDefenseRange")
                        : UNLIMITED_MISSILE_DEFENSE_RANGE);
        missileProximityRange = Mth.clamp(
                tag.contains("MissileProximityRange")
                        ? tag.getDouble("MissileProximityRange")
                        : DEFAULT_MISSILE_PROXIMITY_RANGE,
                MIN_MISSILE_PROXIMITY_RANGE, MAX_MISSILE_PROXIMITY_RANGE);
        radarRange = Mth.clamp(tag.contains("RadarRange") ? tag.getDouble("RadarRange") : 2000.0, MIN_RANGE, MAX_RANGE);
        cannonFireRange = Mth.clamp(
                tag.contains("CannonFireRange")
                        ? tag.getDouble("CannonFireRange")
                        : MAX_CANNON_FIRE_RANGE,
                MIN_CANNON_FIRE_RANGE,
                MAX_CANNON_FIRE_RANGE);
        cannonFirePower = Mth.clamp(
                tag.contains("CannonFirePower")
                        ? tag.getInt("CannonFirePower")
                        : DEFAULT_CANNON_FIRE_POWER,
                MIN_CANNON_FIRE_POWER,
                MAX_CANNON_FIRE_POWER);
        mapUpdateIntervalSeconds = Mth.clamp(
                tag.contains("MapUpdateIntervalSeconds")
                        ? tag.getInt("MapUpdateIntervalSeconds")
                        : DEFAULT_MAP_UPDATE_INTERVAL_SECONDS,
                MIN_MAP_UPDATE_INTERVAL_SECONDS,
                MAX_MAP_UPDATE_INTERVAL_SECONDS);
        mapResolution = normalizeMapResolution(
                tag.contains("MapResolution")
                        ? tag.getInt("MapResolution")
                        : DEFAULT_MAP_RESOLUTION);
        yawSpeed = Mth.clamp(tag.contains("YawSpeed") ? tag.getDouble("YawSpeed") : 120.0, 0.0, 720.0);
        pitchSpeed = Mth.clamp(tag.contains("PitchSpeed") ? tag.getDouble("PitchSpeed") : 90.0, 0.0, 720.0);
        depressionLimit = Mth.clamp(tag.contains("DepressionLimit") ? tag.getDouble("DepressionLimit") : 20.0, 0.0, 90.0);
        elevationLimit = Mth.clamp(tag.contains("ElevationLimit") ? tag.getDouble("ElevationLimit") : 45.0, 0.0, 89.0);
        yawReversed = tag.getBoolean("YawReversed");
        pitchReversed = tag.getBoolean("PitchReversed");
        godModeVideoPlaying = tag.getBoolean("GodModeVideoPlaying");
        godModeVideoStartedAt = godModeVideoPlaying
                ? tag.getLong("GodModeVideoStartedAt") : Long.MIN_VALUE;
        try {
            selectedWeapon = WeaponCategory.valueOf(tag.getString("SelectedWeapon"));
        } catch (IllegalArgumentException exception) {
            selectedWeapon = WeaponCategory.CANNON;
        }
        MianbaoCruiseMissileCompat.Type loadedCruiseType =
                MianbaoCruiseMissileCompat.Type.byKey(tag.getString("SelectedCruiseType"));
        selectedCruiseType = loadedCruiseType == null
                ? MianbaoCruiseMissileCompat.Type.CRUISE : loadedCruiseType;
        MianbaoBallisticMissileCompat.Type loadedBallisticType =
                MianbaoBallisticMissileCompat.Type.byKey(
                        tag.getString("SelectedBallisticType"));
        selectedBallisticType = loadedBallisticType == null
                ? MianbaoBallisticMissileCompat.Type.NATIVE : loadedBallisticType;
        cruiseMultiSelect = tag.getBoolean("CruiseMultiSelect");
        cruiseSalvoCapacity = Math.max(0, tag.getInt("CruiseSalvoCapacity"));
        EnumSet<MianbaoCruiseMissileCompat.Type> loadedAvailable =
                EnumSet.noneOf(MianbaoCruiseMissileCompat.Type.class);
        for (String key : tag.getString("AvailableCruiseTypes").split(",")) {
            MianbaoCruiseMissileCompat.Type type = MianbaoCruiseMissileCompat.Type.byKey(key);
            if (type != null) loadedAvailable.add(type);
        }
        availableCruiseTypes = loadedAvailable.isEmpty() ? Set.of() : Set.copyOf(loadedAvailable);
        EnumSet<MianbaoBallisticMissileCompat.Type> loadedBallisticAvailable =
                EnumSet.noneOf(MianbaoBallisticMissileCompat.Type.class);
        for (String key : tag.getString("AvailableBallisticTypes").split(",")) {
            MianbaoBallisticMissileCompat.Type type =
                    MianbaoBallisticMissileCompat.Type.byKey(key);
            if (type != null) loadedBallisticAvailable.add(type);
        }
        availableBallisticTypes = loadedBallisticAvailable.isEmpty()
                ? Set.of() : Set.copyOf(loadedBallisticAvailable);
        manualAimPoint = tag.contains("ManualAimX")
                ? new Vec3(tag.getDouble("ManualAimX"), tag.getDouble("ManualAimY"),
                tag.getDouble("ManualAimZ")) : null;
        selectedTarget = tag.hasUUID("SelectedTarget")
                ? new TargetKey(
                tag.getUUID("SelectedTarget"),
                tag.getBoolean("SelectedTargetLiving"))
                : null;
        cruiseStrikeTargets.clear();
        for (Tag value : tag.getList("CruiseStrikeTargets", Tag.TAG_COMPOUND)) {
            CompoundTag targetTag = (CompoundTag) value;
            TargetKey targetKey = targetTag.hasUUID("Target")
                    ? new TargetKey(targetTag.getUUID("Target"), targetTag.getBoolean("Living"))
                    : null;
            cruiseStrikeTargets.add(new CruiseStrikeTarget(targetKey, new Vec3(
                    targetTag.getDouble("X"), targetTag.getDouble("Y"), targetTag.getDouble("Z"))));
        }
        manualFireTarget = null;
        manualFireUntil = Long.MIN_VALUE;
        bindings.clear();
        for (BindingSlot slot : List.of(BindingSlot.RADAR_BEARING, BindingSlot.FORWARD_INDICATOR)) {
            BindingRef ref = BindingRef.load(tag, "Binding_" + slot.name()); if (ref != null) bindings.put(slot, ref);
        }
        yawBearings.clear();
        for (Tag value : tag.getList("YawBearings", Tag.TAG_COMPOUND)) yawBearings.add(BindingRef.loadTag((CompoundTag)value));
        BindingRef legacyYaw = BindingRef.load(tag, "Binding_" + BindingSlot.YAW.name());
        if (legacyYaw != null && !yawBearings.contains(legacyYaw)) yawBearings.add(legacyYaw);
        pitchBearings.clear();
        for (Tag value : tag.getList("PitchBearings", Tag.TAG_COMPOUND)) pitchBearings.add(BindingRef.loadTag((CompoundTag)value));
        BindingRef legacyPitch = BindingRef.load(tag, "Binding_" + BindingSlot.PITCH.name());
        if (legacyPitch != null && !pitchBearings.contains(legacyPitch)) pitchBearings.add(legacyPitch);
        pitchOffsets.clear();
        for (Tag value : tag.getList("PitchBearingOffsets", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            if (!entry.contains("Ref", Tag.TAG_COMPOUND)) continue;
            BindingRef ref = BindingRef.loadTag(entry.getCompound("Ref"));
            if (!pitchBearings.contains(ref)) continue;
            pitchOffsets.put(ref, new Vec3(
                    Mth.clamp(entry.getDouble("X"), -20.0, 20.0),
                    Mth.clamp(entry.getDouble("Y"), -20.0, 20.0),
                    Mth.clamp(entry.getDouble("Z"), -20.0, 20.0)));
        }
        pitchBearings.forEach(ref -> pitchOffsets.putIfAbsent(ref, Vec3.ZERO));
        bearingNeutralAngles.clear();
        for (Tag value : tag.getList("BearingNeutralAngles", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag)value;
            if (!entry.contains("Ref", Tag.TAG_COMPOUND)) continue;
            BindingRef ref = BindingRef.loadTag(entry.getCompound("Ref"));
            double angle = entry.getDouble("Angle");
            if ((yawBearings.contains(ref) || pitchBearings.contains(ref)) && Double.isFinite(angle))
                bearingNeutralAngles.put(ref, angle);
        }
        previewBearingSettings.clear();
        for (Tag value : tag.getList("PreviewBearingSettings", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag)value;
            if (!entry.contains("Ref", Tag.TAG_COMPOUND)) continue;
            BindingRef ref = BindingRef.loadTag(entry.getCompound("Ref"));
            var settings = PreviewBearingSettings.load(entry);
            if ((yawBearings.contains(ref) || pitchBearings.contains(ref)) && settings.finite())
                previewBearingSettings.put(ref, settings.clamped());
        }
        cannonMounts.clear(); for (Tag value : tag.getList("CannonMounts", Tag.TAG_COMPOUND)) cannonMounts.add(BindingRef.loadTag((CompoundTag)value));
        aaLaunchers.clear(); for (Tag value : tag.getList("AaLaunchers", Tag.TAG_COMPOUND)) aaLaunchers.add(BindingRef.loadTag((CompoundTag)value));
        cruiseLaunchers.clear(); for (Tag value : tag.getList("CruiseLaunchers", Tag.TAG_COMPOUND)) cruiseLaunchers.add(BindingRef.loadTag((CompoundTag)value));
        ballisticLaunchers.clear(); for (Tag value : tag.getList("BallisticLaunchers", Tag.TAG_COMPOUND)) ballisticLaunchers.add(BindingRef.loadTag((CompoundTag)value));
        iffTransponders.clear(); for (Tag value : tag.getList("IffTransponders", Tag.TAG_COMPOUND)) iffTransponders.add(BindingRef.loadTag((CompoundTag)value));
        contacts = tag.getList("Contacts", Tag.TAG_COMPOUND).stream()
                .map(value -> Contact.load((CompoundTag)value))
                .filter(this::isContactTypeEnabled)
                .toList();
        cruiseTelemetry.clear();
        for (Tag value : tag.getList("CruiseTelemetry", Tag.TAG_COMPOUND)) {
            CruiseTelemetry telemetry = CruiseTelemetry.load((CompoundTag) value);
            cruiseTelemetry.put(telemetry.id(), telemetry);
        }
        ballisticTelemetry.clear();
        for (Tag value : tag.getList("BallisticTelemetry", Tag.TAG_COMPOUND)) {
            BallisticTelemetry telemetry = BallisticTelemetry.load((CompoundTag) value);
            ballisticTelemetry.put(telemetry.id(), telemetry);
        }
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        ListTag telemetry = new ListTag();
        cruiseTelemetry.values().forEach(value -> telemetry.add(value.save()));
        tag.put("CruiseTelemetry", telemetry);
        ListTag ballisticTelemetryTag = new ListTag();
        ballisticTelemetry.values().forEach(value -> ballisticTelemetryTag.add(value.save()));
        tag.put("BallisticTelemetry", ballisticTelemetryTag);
        return tag;
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        if (pkt.getTag() != null) loadAdditional(pkt.getTag(), registries);
    }

    public enum WeaponCategory {
        CANNON("\u706b\u70ae"),
        AIR_DEFENSE("\u9632\u7a7a\u5bfc\u5f39"),
        CRUISE("\u5de1\u822a\u5bfc\u5f39"),
        BALLISTIC("\u5f39\u9053\u5bfc\u5f39");

        private final String displayName;
        WeaponCategory(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
    }

    public record CruiseTelemetry(UUID id, String typeKey, Vec3 position,
                                  Vec3 velocity, Vec3 target, int ageTicks) {
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id);
            tag.putString("Type", typeKey);
            tag.putDouble("X", position.x); tag.putDouble("Y", position.y); tag.putDouble("Z", position.z);
            tag.putDouble("VX", velocity.x); tag.putDouble("VY", velocity.y); tag.putDouble("VZ", velocity.z);
            tag.putDouble("TX", target.x); tag.putDouble("TY", target.y); tag.putDouble("TZ", target.z);
            tag.putInt("Age", ageTicks);
            return tag;
        }

        static CruiseTelemetry load(CompoundTag tag) {
            return new CruiseTelemetry(tag.getUUID("Id"), tag.getString("Type"),
                    new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z")),
                    new Vec3(tag.getDouble("VX"), tag.getDouble("VY"), tag.getDouble("VZ")),
                    new Vec3(tag.getDouble("TX"), tag.getDouble("TY"), tag.getDouble("TZ")),
                    tag.getInt("Age"));
        }
    }

    public record Device(BindingSlot slot, BindingRef ref) {}
    public record BallisticTelemetry(UUID id, Vec3 position, Vec3 velocity,
                                     Vec3 target, int ageTicks) {
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id);
            tag.putDouble("X", position.x); tag.putDouble("Y", position.y); tag.putDouble("Z", position.z);
            tag.putDouble("VX", velocity.x); tag.putDouble("VY", velocity.y); tag.putDouble("VZ", velocity.z);
            tag.putDouble("TX", target.x); tag.putDouble("TY", target.y); tag.putDouble("TZ", target.z);
            tag.putInt("Age", ageTicks);
            return tag;
        }
        static BallisticTelemetry load(CompoundTag tag) {
            return new BallisticTelemetry(tag.getUUID("Id"),
                    new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z")),
                    new Vec3(tag.getDouble("VX"), tag.getDouble("VY"), tag.getDouble("VZ")),
                    new Vec3(tag.getDouble("TX"), tag.getDouble("TY"), tag.getDouble("TZ")),
                    tag.getInt("Age"));
        }
    }
    public record CruiseStrikeTarget(@Nullable TargetKey targetKey, Vec3 point) {}
    private record TurretGroup(@Nullable BindingRef yaw,
                               @Nullable BindingRef pitch,
                               List<BindingRef> cannonMounts,
                               List<BindingRef> aaLaunchers,
                               @Nullable BindingRef forwardIndicator) {}
    private record PendingCruiseLaunch(long launchAt,
                                       MianbaoCruiseMissileCompat.Type type,
                                       @Nullable TargetKey targetKey,
                                       @Nullable Vec3 fixedPoint,
                                       Vec3 initialTarget) {}
    public record TargetKey(UUID id, boolean living) {}
    private record AimSolution(Vec3 target, @Nullable Vec3 launchDirection) {}
    private record AimReference(Vec3 origin, Vec3 direction, UUID bodyId) {}
    private record GroupAimRequest(TurretGroup group, AimReference measured,
                                   Vec3 requested) {}
    public record Contact(TargetKey key, Set<UUID> members, Vec3 center, Vec3 velocity,
                           AABB bounds, double distance, double closingRate,
                           boolean missile) {
        CompoundTag save() { CompoundTag t=new CompoundTag(); t.putUUID("Id",key.id); t.putBoolean("Living",key.living); t.putBoolean("Missile",missile);
            t.putDouble("X",center.x);t.putDouble("Y",center.y);t.putDouble("Z",center.z);
            t.putDouble("VX",velocity.x);t.putDouble("VY",velocity.y);t.putDouble("VZ",velocity.z);
            t.putDouble("MinX",bounds.minX);t.putDouble("MinY",bounds.minY);t.putDouble("MinZ",bounds.minZ);
            t.putDouble("MaxX",bounds.maxX);t.putDouble("MaxY",bounds.maxY);t.putDouble("MaxZ",bounds.maxZ);
            t.putDouble("Distance",distance);t.putDouble("Closing",closingRate); return t; }
        static Contact load(CompoundTag t) { return new Contact(new TargetKey(t.getUUID("Id"),t.getBoolean("Living")),Set.of(),
                new Vec3(t.getDouble("X"),t.getDouble("Y"),t.getDouble("Z")),new Vec3(t.getDouble("VX"),t.getDouble("VY"),t.getDouble("VZ")),
                new AABB(t.getDouble("MinX"),t.getDouble("MinY"),t.getDouble("MinZ"),t.getDouble("MaxX"),t.getDouble("MaxY"),t.getDouble("MaxZ")),
                t.getDouble("Distance"),t.getDouble("Closing"),t.getBoolean("Missile")); }
    }
}
