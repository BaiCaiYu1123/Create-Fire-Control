package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.compat.synaxis.SynaxisNoContactCompat;
import com.hooya.stabilizedturret.compat.synaxis.SynaxisWeldCompat;
import com.hooya.stabilizedturret.compat.simulated.SimulatedMergingGlueCompat;
import com.hooya.stabilizedturret.compat.simulated.SimulatedPhysicsStaffCompat;
import com.hooya.stabilizedturret.mixin.SwivelBearingBlockEntityAccessor;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public final class BearingStabilizer {
    private static final long HEARTBEAT_TOLERANCE_TICKS = 2L;
    private static final long NO_CONTACT_REFRESH_INTERVAL_TICKS = 1L;
    private static final double MAX_BINDING_ANCHOR_DISTANCE_BLOCKS = 64.0;
    private static final double CONSTRAINED_AIM_DEADBAND_DEGREES = 0.5;
    private static final double CONSTRAINED_AIM_RELEASE_DEGREES = 0.85;
    private static final String LEGACY_TOOLGUN_NO_COLLISION_TAG = "AstDisableStructureCollision";
    private static final String LEGACY_OWNED_NO_COLLISION_TAG = "StabilizedTurretOwnsNoCollision";
    private static final String OWNED_NO_CONTACT_PEERS_TAG =
            "CreateFireControlOwnedNoContactPeers";
    private static final String ASSEMBLY_ZERO_TAG = "CreateFireControlAssemblyZero";
    private static final String ASSEMBLY_PIVOT_X_TAG = "CreateFireControlAssemblyPivotX";
    private static final String ASSEMBLY_PIVOT_Y_TAG = "CreateFireControlAssemblyPivotY";
    private static final String ASSEMBLY_PIVOT_Z_TAG = "CreateFireControlAssemblyPivotZ";

    private static final Map<SwivelBearingBlockEntity, BoundState> BOUND =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<SwivelBearingBlockEntity, AxisLock> ACTIVE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<SwivelBearingBlockEntity> LEGACY_BEARINGS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Map<SwivelBearingBlockEntity, ConstrainedServo> CONSTRAINED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<SwivelBearingBlockEntity, AssemblyZero> ASSEMBLY_ZEROS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<SwivelBearingBlockEntity, AssemblyPivot> ASSEMBLY_PIVOTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<UUID, GravityState> GRAVITY_OVERRIDDEN =
            Collections.synchronizedMap(new HashMap<>());
    private static final Map<ServerLevel, Set<BodyPair>> OWNED_NO_CONTACTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ServerLevel, Long> LAST_NO_CONTACT_REFRESH =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ServerLevel, Long> LAST_PITCH_COLLISION_REFRESH = new WeakHashMap<>();
    private static final Map<ServerLevel, CollisionDiscovery> COLLISION_DISCOVERY = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<UUID, CarrierStaffHold>> STAFF_CARRIER_HOLDS = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<UUID, CarrierStaffHold>> STAFF_TURRET_HOLDS = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<UUID, StaffGrabReference>> STAFF_GRAB_REFERENCES = new WeakHashMap<>();
    private static final Map<String, Long> SERVO_PLANNING_RESULTS = new HashMap<>();
    private static long lastFiniteDiagnosticTick = Long.MIN_VALUE;
    private static final Map<String, Long> LAST_FINITE_DIAGNOSTIC_COUNTS = new HashMap<>();
    static java.util.function.Consumer<Map<UUID, ArticulatedServoPredictor.BodyState>> servoPredictionObserver;

    private static final Map<SwivelBearingBlockEntity, YawLimits> YAW_LIMITS =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static void setYawLimits(SwivelBearingBlockEntity bearing, YawLimits limits) {
        YawLimits previous = YAW_LIMITS.put(bearing, limits);
        AxisLock lock = ACTIVE.get(bearing);
        if (lock != null && !limits.equals(previous)) {
            // A free hinge must have no angular limit at all, including at the rear seam.
            if (lock.yawLimits.enabled() != limits.enabled()) lock.releaseJoint(bearing, false);
            lock.yawStops.reset();
            lock.yawLimits = limits;
        }
    }

    static YawLimits yawLimits(SwivelBearingBlockEntity bearing) {
        return YAW_LIMITS.getOrDefault(bearing, YawLimits.UNLIMITED);
    }

    public static void calibrateYawDirection(SwivelBearingBlockEntity bearing, Vec3 measured) {
        if (usesLegacyImplementation(bearing)) {
            LegacyBearingStabilizer.calibrateYawDirection(bearing, measured);
            return;
        }
        AxisLock lock = ACTIVE.get(bearing);
        if (lock != null && lock.yawAxis && lock.initialize(bearing)) lock.measureDirectionReference(bearing, measured);
    }

    public static boolean usesLegacyImplementation(SwivelBearingBlockEntity bearing) {
        return LEGACY_BEARINGS.contains(bearing);
    }

    /** Select per bearing; the previous actuator is retired before the new backend captures its pose. */
    public static void setImplementation(SwivelBearingBlockEntity bearing,boolean reactionEnabled) {
        boolean legacy=usesLegacyImplementation(bearing);
        if (legacy==!reactionEnabled) return;
        if (legacy) LegacyBearingStabilizer.disable(bearing);
        else releasePhysicalServo(bearing,true);
        removeConstrained(bearing);
        if (reactionEnabled) LEGACY_BEARINGS.remove(bearing);
        else LEGACY_BEARINGS.add(bearing);
        LAST_PITCH_COLLISION_REFRESH.remove(rootServerLevel(bearing.getLevel()));
        StabilizedTurret.LOGGER.info("Changed fire-control stabilizer backend: bearing={} reactionEnabled={}",
                bearing.getBlockPos(),reactionEnabled);
    }

    public static void maintainBinding(
            SwivelBearingBlockEntity bearing,
            boolean yawAxis,
            boolean decouplePhysics,
            boolean stabilizeWorld,
            boolean acceptMechanicalInput,
            double maxDegreesPerSecond,
            double depressionLimitDegrees,
            double elevationLimitDegrees,
            Vec3 localOffset
    ) {
        Level level = bearing.getLevel();
        UUID childId = bearing.getSubLevelID();
        if (level == null || level.isClientSide || childId == null || !bearing.isAssembled()) {
            return;
        }

        BoundState previousBinding = BOUND.put(bearing,
                new BoundState(level, childId, yawAxis, decouplePhysics, level.getGameTime()));
        if (previousBinding == null || previousBinding.level != level
                || !previousBinding.childId.equals(childId) || previousBinding.yawAxis != yawAxis) {
            LAST_PITCH_COLLISION_REFRESH.remove(rootServerLevel(level));
        }
        if (usesLegacyImplementation(bearing)) {
            LegacyBearingStabilizer.maintainBinding(bearing,yawAxis,decouplePhysics,stabilizeWorld,
                    acceptMechanicalInput,maxDegreesPerSecond,depressionLimitDegrees,elevationLimitDegrees,localOffset);
            return;
        }
        if (!decouplePhysics) {
            releasePhysicalServo(bearing, true);
            return;
        }
        removeConstrained(bearing);

        AxisLock lock;
        synchronized (ACTIVE) {
            lock = ACTIVE.computeIfAbsent(bearing, ignored -> new AxisLock());
        }
        if (lock.yawAxis != yawAxis && lock.initialized) {
            lock.releaseJoint(bearing, true);
            lock.reset();
        }
        lock.yawAxis = yawAxis;
        lock.yawLimits = yawAxis ? yawLimits(bearing) : YawLimits.UNLIMITED;
        lock.setStabilization(bearing, stabilizeWorld);
        lock.maxDegreesPerSecond = Math.max(0.0, maxDegreesPerSecond);
        lock.setLimits(depressionLimitDegrees, elevationLimitDegrees);
        lock.setLocalOffset(localOffset);
        lock.lastHeartbeat = level.getGameTime();
        lock.driveSelectionDirty = true;
        lock.mechanicalDegreesPerSecond = 0.0;
        boolean lockReady = lock.initialize(bearing);
        if (lockReady && acceptMechanicalInput) {
            double angularStep = KineticBlockEntity.convertToAngular(
                    bearing.getExtraKinetics().getSpeed());
            if (bearing.getBlockState().getValue(SwivelBearingBlock.FACING).getAxisDirection()
                    == Direction.AxisDirection.NEGATIVE) {
                angularStep *= -1.0;
            }
            lock.mechanicalDegreesPerSecond = Mth.clamp(angularStep * 20.0,
                    -lock.maxDegreesPerSecond, lock.maxDegreesPerSecond);
        }
    }

    public static void commandAim(
            SwivelBearingBlockEntity bearing,
            Vec3 measuredWorldDirection,
            Vec3 requestedWorldDirection,
            boolean yawAxis,
            double maxDegreesPerSecond,
            double depressionLimitDegrees,
            double elevationLimitDegrees,
            Vec3 localOffset
    ) {
        commandAim(bearing, measuredWorldDirection, requestedWorldDirection, yawAxis, maxDegreesPerSecond,
                depressionLimitDegrees, elevationLimitDegrees, localOffset, List.of(), null, true);
    }

    public static void commandFiniteAim(SwivelBearingBlockEntity bearing, Vec3 measuredWorldDirection,
            Vec3 requestedWorldDirection, boolean yawAxis, double maxDegreesPerSecond,
            double depressionLimitDegrees, double elevationLimitDegrees, Vec3 localOffset,
            List<FiniteAimTarget> targets, Vec3 operatorDirection) {
        commandAim(bearing, measuredWorldDirection, requestedWorldDirection, yawAxis, maxDegreesPerSecond,
                depressionLimitDegrees, elevationLimitDegrees, localOffset, targets, operatorDirection, true);
    }

    public static void commandFiniteAim(SwivelBearingBlockEntity bearing, Vec3 measuredWorldDirection,
            Vec3 requestedWorldDirection, boolean yawAxis, double maxDegreesPerSecond,
            double depressionLimitDegrees, double elevationLimitDegrees, Vec3 localOffset,
            List<FiniteAimTarget> targets, Vec3 operatorDirection, boolean stabilizeWorld) {
        commandAim(bearing, measuredWorldDirection, requestedWorldDirection, yawAxis, maxDegreesPerSecond,
                depressionLimitDegrees, elevationLimitDegrees, localOffset, targets, operatorDirection, stabilizeWorld);
    }

    private static void commandAim(SwivelBearingBlockEntity bearing, Vec3 measuredWorldDirection,
            Vec3 requestedWorldDirection, boolean yawAxis, double maxDegreesPerSecond,
            double depressionLimitDegrees, double elevationLimitDegrees, Vec3 localOffset,
            List<FiniteAimTarget> targets, Vec3 operatorDirection, boolean stabilizeWorld) {
        Level level = bearing.getLevel();
        if (level == null || level.isClientSide || !bearing.isAssembled()
                || measuredWorldDirection.lengthSqr() < 1.0e-8
                || requestedWorldDirection.lengthSqr() < 1.0e-8) {
            return;
        }

        maintainBinding(bearing, yawAxis, true, stabilizeWorld, false, 0.0,
                depressionLimitDegrees, elevationLimitDegrees, localOffset);
        if (usesLegacyImplementation(bearing)) {
            LegacyBearingStabilizer.commandAim(bearing,measuredWorldDirection,requestedWorldDirection,
                    yawAxis,maxDegreesPerSecond,depressionLimitDegrees,elevationLimitDegrees,localOffset,targets,
                    operatorDirection,stabilizeWorld);
            return;
        }
        AxisLock lock = ACTIVE.get(bearing);
        if (lock != null && lock.initialize(bearing)) {
            lock.maxDegreesPerSecond = Math.max(0.0, maxDegreesPerSecond);
            lock.aimAtDirection(
                    bearing,
                    measuredWorldDirection.normalize(),
                    requestedWorldDirection.normalize(), targets, operatorDirection
            );
        }
    }

    /** Keeps Aeronautics' rotary constraint and only adopts physical properties. */
    public static void maintainConstrainedBinding(
            SwivelBearingBlockEntity bearing,
            boolean yawAxis,
            double depressionLimitDegrees,
            double elevationLimitDegrees
    ) {
        maintainBinding(bearing, yawAxis, false, false, false, 0.0,
                depressionLimitDegrees, elevationLimitDegrees, Vec3.ZERO);
        ConstrainedServo servo;
        synchronized (CONSTRAINED) {
            servo = CONSTRAINED.computeIfAbsent(bearing,
                    ignored -> new ConstrainedServo(bearing, yawAxis));
        }
        if (servo.yawAxis != yawAxis) {
            servo.reset(assemblyZeroOrCurrent(bearing), yawAxis);
        }
        ((SwivelBearingBlockEntityAccessor)bearing).stabilizedTurret$getLockedDefaultOption().setValue(0);
        servo.depression = Mth.clamp(depressionLimitDegrees, 0.0, 180.0);
        servo.elevation = Mth.clamp(elevationLimitDegrees, 0.0, 180.0);
    }

    /** Drives the original Aeronautics servo without removing its physical joint. */
    public static void commandConstrainedAim(
            SwivelBearingBlockEntity bearing,
            Vec3 measuredWorldDirection,
            Vec3 requestedWorldDirection,
            boolean yawAxis,
            double maxDegreesPerSecond,
            double depressionLimitDegrees,
            double elevationLimitDegrees
    ) {
        commandConstrainedAim(
                bearing, measuredWorldDirection, requestedWorldDirection,
                yawAxis, maxDegreesPerSecond, depressionLimitDegrees,
                elevationLimitDegrees, false);
    }

    public static void commandConstrainedAim(
            SwivelBearingBlockEntity bearing,
            Vec3 measuredWorldDirection,
            Vec3 requestedWorldDirection,
            boolean yawAxis,
            double maxDegreesPerSecond,
            double depressionLimitDegrees,
            double elevationLimitDegrees,
            boolean reversed
    ) {
        if (bearing.getLevel() == null || bearing.getLevel().isClientSide || !bearing.isAssembled()
                || measuredWorldDirection.lengthSqr() < 1.0e-8
                || requestedWorldDirection.lengthSqr() < 1.0e-8) return;
        maintainConstrainedBinding(bearing, yawAxis, depressionLimitDegrees, elevationLimitDegrees);
        ConstrainedServo servo = CONSTRAINED.get(bearing);
        if (servo == null) return;
        Vector3d axis = worldAxis(bearing, new Vector3d(), null);
        Vector3d measured = new Vector3d(measuredWorldDirection.x, measuredWorldDirection.y,
                measuredWorldDirection.z).normalize();
        Vector3d requested = new Vector3d(requestedWorldDirection.x, requestedWorldDirection.y,
                requestedWorldDirection.z).normalize();
        Vector3d currentProjected = measured.sub(new Vector3d(axis).mul(measured.dot(axis)));
        Vector3d requestedProjected = requested.sub(new Vector3d(axis).mul(requested.dot(axis)));
        if (currentProjected.lengthSquared() < 1.0e-8 || requestedProjected.lengthSquared() < 1.0e-8) return;
        currentProjected.normalize(); requestedProjected.normalize();
        double error = Math.toDegrees(Math.atan2(
                axis.dot(new Vector3d(currentProjected).cross(requestedProjected)),
                Mth.clamp(currentProjected.dot(requestedProjected), -1.0, 1.0)));
        if (reversed) error = -error;
        double absoluteError = Math.abs(error);
        // Once a joint has settled, keep the native motor target fixed until
        // the error has clearly left the aiming dead band. Rewriting the
        // target to the noisy physical pose every tick made a close target
        // alternate across the boundary and visibly shake left/right.
        if (servo.aimSettled
                && absoluteError <= CONSTRAINED_AIM_RELEASE_DEGREES) {
            return;
        }
        if (absoluteError > CONSTRAINED_AIM_RELEASE_DEGREES) {
            servo.aimSettled = false;
        }
        double currentTarget = bearing.getTargetAngleDegrees();
        if (!Double.isFinite(currentTarget)) currentTarget = servo.zeroAngle;
        double physicalAngle = physicalJointAngle(bearing);
        // The requested correction is measured from the barrel's actual
        // direction, so it must also be applied from the joint's actual
        // angle. Applying it from the previous motor target accumulates the
        // same outstanding error every tick while the physical joint is
        // catching up. That target wind-up makes a close-range target cause
        // the yaw bearing to repeatedly overshoot and reverse.
        double currentAngle = Double.isFinite(physicalAngle)
                ? currentTarget + Mth.wrapDegrees(physicalAngle - currentTarget)
                : currentTarget;
        double commandedError = absoluteError <= CONSTRAINED_AIM_DEADBAND_DEGREES
                ? 0.0 : error;
        if (commandedError == 0.0) {
            // Capture the physical pose once on entry. Subsequent ticks hold
            // this target instead of following small physics/measurement
            // noise around the requested direction.
            servo.aimSettled = true;
        }
        double step = Mth.clamp(commandedError,
                -Math.max(0.0, maxDegreesPerSecond) / 20.0,
                Math.max(0.0, maxDegreesPerSecond) / 20.0);
        double candidate = currentAngle + step;
        if (!yawAxis) {
            // A right-side pitch bearing has the opposite relationship
            // between its canonical joint angle and physical barrel
            // elevation. Calibrate that relationship from the current child
            // pose instead of assuming positive joint angle always means up.
            double elevationSign = jointToElevationSign(
                    bearing, measured, axis);
            double relativeElevation = Mth.wrapDegrees(
                    candidate - servo.zeroAngle) * elevationSign;
            double clampedElevation = Mth.clamp(
                    relativeElevation,
                    -servo.depression,
                    servo.elevation);
            double limited = servo.zeroAngle
                    + clampedElevation / elevationSign;
            // Keep the native servo target continuous around +/-180 degrees.
            candidate = currentTarget
                    + Mth.wrapDegrees(limited - currentTarget);
        }
        SwivelBearingBlockEntityAccessor accessor = (SwivelBearingBlockEntityAccessor) bearing;
        accessor.stabilizedTurret$setTargetAngleDegrees(candidate);
        bearing.updateServoCoefficients();
        bearing.setChanged();
    }

    /** Current joint angle in its immediate parent's frame; unavailable until the child is registered. */
    public static double capturePhysicalJointAngle(SwivelBearingBlockEntity bearing) {
        AxisLock lock = ACTIVE.get(bearing);
        if (lock == null || !lock.initialize(bearing)) return Double.NaN;
        var child = SubLevelContainer.getContainer(bearing.getLevel()).getSubLevel(bearing.getSubLevelID());
        return child == null ? Double.NaN : lock.canonicalJointAngleDegrees(bearing, child.logicalPose().orientation());
    }

    /** Returns an independently driven joint to a saved parent-local pose without calibrating a fake barrel ray. */
    public static void commandPhysicalNeutral(SwivelBearingBlockEntity bearing, boolean yaw,
            double speed, double depression, double elevation, Vec3 offset, double jointAngle) {
        maintainBinding(bearing, yaw, true, false, false, speed, depression, elevation, offset);
        AxisLock lock = ACTIVE.get(bearing);
        if (lock == null || !lock.initialize(bearing) || bearing.getPlatePos() == null) return;
        var body = SubLevelContainer.getContainer(bearing.getLevel()).getSubLevel(bearing.getSubLevelID());
        var plateState = bearing.getLevel().getBlockState(bearing.getPlatePos());
        if (body == null || !plateState.hasProperty(SwivelBearingPlateBlock.FACING)) return;
        // Idle is a mechanical joint pose, not a synthetic weapon aim command.
        // In particular, downstream pitch motion must not steer the idle yaw.
        if (yaw) lock.azimuthObservations = List.of();
        if (lock.aimInputWasLive) {
            lock.targetOrientation.set(body.logicalPose().orientation());
            lock.resetFiniteModeHistory();
            lock.sightCompensation.reset();
        }
        double current = lock.canonicalJointAngleDegrees(bearing, lock.targetOrientation);
        double step = Mth.clamp(Mth.wrapDegrees(jointAngle - current), -Math.max(0, speed) / 20.0, Math.max(0, speed) / 20.0);
        lock.targetOrientation.set(lock.orientationForJointAngle(bearing,
                plateState.getValue(SwivelBearingPlateBlock.FACING), current + step));
        lock.targetOrientation.transform(lock.aimLocalForward, lock.heldAimDirection).normalize();
        lock.requestedAimDirection.set(lock.heldAimDirection);
        parentOrientation(bearing, lock.lastParentOrientation);
        lock.parentAimTarget.set(lock.heldAimDirection, lock.lastParentOrientation);
        lock.finiteTargets = List.of();
        lock.finiteOperatorDirection = null;
        lock.finiteCompensationActive = false;
        lock.lastAimCommandTick = Long.MIN_VALUE;
        lock.aimInputWasLive = false;
        lock.operatorMotion.reset();
        lock.maxDegreesPerSecond = Math.max(0.0, speed);
    }

    /** Returns Aeronautics' joint to its pose at the most recent assembly. */
    public static void commandConstrainedNeutral(
            SwivelBearingBlockEntity bearing,
            boolean yawAxis,
            double maxDegreesPerSecond,
            double depressionLimitDegrees,
            double elevationLimitDegrees
    ) {
        if (bearing.getLevel() == null || bearing.getLevel().isClientSide
                || !bearing.isAssembled()) return;
        maintainConstrainedBinding(
                bearing, yawAxis,
                depressionLimitDegrees, elevationLimitDegrees);
        ConstrainedServo servo = CONSTRAINED.get(bearing);
        if (servo == null) return;
        servo.aimSettled = false;
        double current = bearing.getTargetAngleDegrees();
        if (!Double.isFinite(current)) current = servo.zeroAngle;
        double error = Mth.wrapDegrees(servo.zeroAngle - current);
        double step = Mth.clamp(
                error,
                -Math.max(0.0, maxDegreesPerSecond) / 20.0,
                Math.max(0.0, maxDegreesPerSecond) / 20.0
        );
        double candidate = current + step;
        if (!yawAxis) {
            candidate = Mth.clamp(
                    candidate,
                    servo.zeroAngle - servo.depression,
                    servo.zeroAngle + servo.elevation
            );
        }
        SwivelBearingBlockEntityAccessor accessor =
                (SwivelBearingBlockEntityAccessor) bearing;
        accessor.stabilizedTurret$setTargetAngleDegrees(candidate);
        bearing.updateServoCoefficients();
        bearing.setChanged();
    }

    public static void disable(SwivelBearingBlockEntity bearing) {
        YAW_LIMITS.remove(bearing);
        BOUND.remove(bearing);
        LAST_PITCH_COLLISION_REFRESH.remove(rootServerLevel(bearing.getLevel()));
        LegacyBearingStabilizer.disable(bearing);
        LEGACY_BEARINGS.remove(bearing);
        releasePhysicalServo(bearing, true);
        removeConstrained(bearing);
    }

    private static void releasePhysicalServo(SwivelBearingBlockEntity bearing, boolean restoreNative) {
        AxisLock lock = ACTIVE.remove(bearing);
        if (lock != null) lock.releaseJoint(bearing, restoreNative);
    }

    public static boolean isActive(SwivelBearingBlockEntity bearing) {
        if (usesLegacyImplementation(bearing)) return LegacyBearingStabilizer.isActive(bearing);
        AxisLock lock = ACTIVE.get(bearing);
        Level level = bearing.getLevel();
        return lock != null && level != null
                && level.getGameTime() - lock.lastHeartbeat <= HEARTBEAT_TOLERANCE_TICKS;
    }

    public static void calibrateElevationDirection(
            SwivelBearingBlockEntity bearing,
            Vec3 measuredWorldDirection
    ) {
        if (usesLegacyImplementation(bearing)) {
            LegacyBearingStabilizer.calibrateElevationDirection(bearing,measuredWorldDirection);
            return;
        }
        if (measuredWorldDirection.lengthSqr() < 1.0e-8) {
            return;
        }
        AxisLock lock = ACTIVE.get(bearing);
        if (lock != null && !lock.yawAxis && lock.initialize(bearing)) {
            lock.calibrateElevationDirection(bearing, measuredWorldDirection);
        }
    }

    /** Calibrates each axis's own local direction, including passive hold. */
    public static void maintainDirectionReference(SwivelBearingBlockEntity bearing, Vec3 measured) {
        if (usesLegacyImplementation(bearing)) {
            calibrateElevationDirection(bearing,measured);
            return;
        }
        AxisLock lock = ACTIVE.get(bearing);
        if (lock != null && measured.lengthSqr() > 1e-8 && lock.initialize(bearing)) {
            lock.measureDirectionReference(bearing, measured);
        }
    }

    /** A cannon can live on a rigidly welded member rather than the bearing's plate. */
    public static void maintainDirectionReference(SwivelBearingBlockEntity bearing, Vec3 measured, UUID sourceId) {
        if (usesLegacyImplementation(bearing)) {
            maintainDirectionReference(bearing, measured);
            return;
        }
        AxisLock lock = ACTIVE.get(bearing);
        if (lock == null || measured == null || measured.lengthSqr() < 1e-8 || !lock.initialize(bearing)) return;
        Quaterniond rotation = SableServoConstraints.fixedRotation(rootServerLevel(bearing.getLevel()), lock.childId, sourceId);
        SubLevel source = sourceId == null ? null : SubLevelContainer.getContainer(bearing.getLevel()).getSubLevel(sourceId);
        if (rotation != null && source != null && !source.isRemoved()) {
            if (!sourceId.equals(lock.directionSourceId)) lock.elevationFrameInitialized = false;
            lock.directionSourceId = sourceId;
            lock.childFromDirectionSource.set(rotation);
            source.logicalPose().orientation().transformInverse(new Vector3d(measured.x, measured.y, measured.z),
                    lock.directionSourceLocalForward).normalize();
        } else {
            lock.directionSourceId = null;
        }
        lock.measureDirectionReference(bearing, measured);
    }

    /** Read-only physical rays. They are observations, never another axis's control state. */
    public record AzimuthObservation(UUID bodyId, Vec3 worldDirection) {}
    private record LocalAzimuthObservation(UUID bodyId, Vec3 localDirection) {}

    public static void maintainAzimuthObservations(SwivelBearingBlockEntity bearing, List<AzimuthObservation> observations) {
        if (usesLegacyImplementation(bearing)) return;
        AxisLock lock = ACTIVE.get(bearing);
        if (lock == null || !lock.yawAxis || bearing.getLevel() == null) return;
        List<LocalAzimuthObservation> local = new ArrayList<>();
        SubLevelContainer container = SubLevelContainer.getContainer(bearing.getLevel());
        for (AzimuthObservation observation : observations) {
            SubLevel body = observation.bodyId() == null ? null : container.getSubLevel(observation.bodyId());
            Vec3 ray = observation.worldDirection();
            if (body == null || body.isRemoved() || ray == null || ray.lengthSqr() < 1e-8) continue;
            Vector3d direction = body.logicalPose().orientation().transformInverse(new Vector3d(ray.x,ray.y,ray.z));
            if (!direction.isFinite() || direction.lengthSquared() < 1e-8) continue;
            direction.normalize();
            local.add(new LocalAzimuthObservation(observation.bodyId(),new Vec3(direction.x,direction.y,direction.z)));
        }
        lock.azimuthObservations = local.size() == observations.size() ? List.copyOf(local) : List.of();
    }

    public static boolean isBound(SwivelBearingBlockEntity bearing) {
        BoundState state = BOUND.get(bearing);
        Level level = bearing.getLevel();
        return state != null && level != null && state.level == level
                && state.childId.equals(bearing.getSubLevelID())
                && level.getGameTime() - state.lastHeartbeat <= HEARTBEAT_TOLERANCE_TICKS;
    }

    public static void suppressMotor(SwivelBearingBlockEntity bearing) {
        if (usesLegacyImplementation(bearing)) LegacyBearingStabilizer.suppressMotor(bearing);
    }

    /** True when this exact physical body is the live child of a bound bearing. */
    public static boolean isBoundChild(ServerSubLevel body) {
        if (body == null || body.isRemoved()) return false;
        UUID bodyId = body.getUniqueId();
        for (Map.Entry<SwivelBearingBlockEntity, BoundState> entry : boundSnapshot()) {
            SwivelBearingBlockEntity bearing = entry.getKey();
            BoundState state = entry.getValue();
            AxisLock lock = ACTIVE.get(bearing);
            if ((bodyId.equals(state.childId) || lock != null && lock.jointActuationChild == body)
                    && isLiveBinding(bearing, state)
                    && rootServerLevel(bearing.getLevel()) == body.getLevel()) {
                return true;
            }
        }
        return false;
    }

    public static boolean ownsJoint(SwivelBearingBlockEntity bearing) {
        return ((SwivelBearingBlockEntityAccessor) bearing).stabilizedTurret$getConstraintHandle()
                instanceof NativeBearingJoint joint && joint.isValid();
    }

    /** Redstone may request a reconnect; blueprint/reparent changes must actually rebuild it. */
    public static boolean preserveOwnedJoint(SwivelBearingBlockEntity bearing, ServerSubLevel child) {
        AxisLock lock = ACTIVE.get(bearing);
        if (lock == null || !ownsJoint(bearing)) return false;
        Vector3d pivot = new Vector3d();
        if (child != lock.jointChild || Sable.HELPER.getContaining(bearing) != lock.jointParent
                || !bearing.getBlockPos().equals(lock.jointBearingPos)
                || !resolveSimulatedAssemblyPivot(bearing, pivot)
                || pivot.distanceSquared(lock.childLocalPivot) > 1.0e-12
                || bearing.getBlockState().getValue(SwivelBearingBlock.FACING) != lock.jointBearingFacing
                || bearing.getLevel().getBlockState(bearing.getPlatePos())
                        .getValue(SwivelBearingPlateBlock.FACING) != lock.jointPlateFacing) {
            lock.releaseJoint(bearing, false);
            lock.reset();
            return false;
        }
        return true;
    }

    public static void maintainPhysicalProperties(SubLevelPhysicsSystem physicsSystem, double timeStep) {
        ServerLevel level = physicsSystem.getLevel();
        if (!hasBindingState(level) && GRAVITY_OVERRIDDEN.isEmpty()
                && !PitchStructureCollision.hasOverrides(level)
                && !STAFF_CARRIER_HOLDS.containsKey(level) && !STAFF_TURRET_HOLDS.containsKey(level)) return;
        PhysicsPipeline pipeline = physicsSystem.getPipeline();
        Map<UUID, ServerSubLevel> directChildren = collectLiveChildren(level);
        reconcilePitchCollisions(level);
        restoreUnboundGravityTags(level, directChildren.keySet());

        for (ServerSubLevel child : directChildren.values()) {
            var massData = child.getMassTracker();
            if (child.isRemoved() || massData == null || massData.getCenterOfMass() == null) {
                continue;
            }

            GravityState gravityState = gravityStateFor(child);
            gravityState.applyTags();
            cancelGravity(level, pipeline, child, timeStep);
        }
        var locks = activeSnapshot();
        Map<UUID, UUID> boundParents = new HashMap<>();
        for (var entry : locks) {
            if (rootServerLevel(entry.getKey().getLevel()) == level && entry.getValue().childId != null) {
                boundParents.put(entry.getValue().childId, containingBodyId(entry.getKey()));
            }
        }
        Set<UUID> staffDriven = SimulatedPhysicsStaffCompat.drivenBodies(level, boundParents);
        Set<UUID> staffCarriers = SynaxisWeldCompat.expandFixedWeldedSublevels(level,
                SimulatedPhysicsStaffCompat.draggedBodies(level));
        Map<UUID, Quaterniond> staffRotations = new HashMap<>();
        Map<UUID, Quaterniond> staffGoals=SimulatedPhysicsStaffCompat.orientationGoals(level);
        Set<UUID> rotatingStaff=staffRotationRequests(level,staffGoals);
        maintainStaffCarrierHolds(physicsSystem, locks, staffDriven, staffCarriers,rotatingStaff);
        maintainStaffTurretHolds(physicsSystem,locks,staffGoals,rotatingStaff,timeStep);
        var staffMotor = SimulatedPhysicsStaffCompat.angularMotor();
        if (staffMotor.stiffness() > 0.0 && staffMotor.damping() > 0.0
                && Double.isFinite(staffMotor.stiffness()) && Double.isFinite(staffMotor.damping())
                && staffMotor.damping() * staffMotor.damping() >= 4.0 * staffMotor.stiffness()) {
            for (var goal : staffGoals.entrySet()) {
                SubLevel body = SubLevelContainer.getContainer(level).getSubLevel(goal.getKey());
                if (body != null && !body.isRemoved() && isFinite(goal.getValue())) {
                    staffRotations.put(goal.getKey(), StaffCarrierForecast.forecastDelta(
                            body.logicalPose().orientation(), goal.getValue(), timeStep,
                            staffMotor.stiffness(), staffMotor.damping()));
                }
            }
        }
        // All motors read the same logical poses; no pose or velocity is changed here.
        for (Map.Entry<SwivelBearingBlockEntity, AxisLock> entry : locks) {
            if (rootServerLevel(entry.getKey().getLevel()) == level) {
                entry.getValue().drive(entry.getKey(), physicsSystem, timeStep, staffDriven, staffCarriers,
                        staffRotations,staffGoals);
            }
        }
    }

    private record CarrierStaffHold(ServerSubLevel body, GenericConstraintHandle handle,Quaterniond heldOrientation) {}
    private record StaffGrabReference(Quaterniond initialGoal,boolean rotating) {}

    /** Retain translation-only holds until the player explicitly rotates this grab. */
    private static Set<UUID> staffRotationRequests(ServerLevel level,Map<UUID,Quaterniond> goals) {
        var references=STAFF_GRAB_REFERENCES.computeIfAbsent(level,ignored->new HashMap<>());
        references.keySet().retainAll(goals.keySet());
        Set<UUID> rotating=new HashSet<>();
        double unchanged=Math.cos(Math.toRadians(0.01)*0.5);
        for (var entry:goals.entrySet()) {
            var previous=references.get(entry.getKey());
            if (previous==null) previous=new StaffGrabReference(new Quaterniond(entry.getValue()).normalize(),false);
            boolean requested=previous.rotating()
                    || Math.abs(previous.initialGoal().dot(new Quaterniond(entry.getValue()).normalize()))<unchanged;
            references.put(entry.getKey(),new StaffGrabReference(previous.initialGoal(),requested));
            if (requested) rotating.add(entry.getKey());
        }
        return rotating;
    }

    /** A turret grab must not leave the free carrier twist unrestrained. No translation is locked. */
    private static void maintainStaffCarrierHolds(SubLevelPhysicsSystem system,
            Set<Map.Entry<SwivelBearingBlockEntity, AxisLock>> locks, Set<UUID> driven, Set<UUID> grabbed,
            Set<UUID> rotating) {
        Map<UUID,ServerSubLevel> needed=new HashMap<>();
        Set<UUID> rotatingCarriers=new HashSet<>();
        for (var entry:locks) {
            if (rootServerLevel(entry.getKey().getLevel())!=system.getLevel()) continue;
            SubLevel root=aimCarrier(entry.getKey());
            if (root==null) continue;
            // A rotated downstream grab also releases the carrier's artificial hold.
            if (rotating.contains(entry.getValue().childId)) rotatingCarriers.add(root.getUniqueId());
        }
        for (var entry:locks) {
            if (rootServerLevel(entry.getKey().getLevel())!=system.getLevel()
                    || !entry.getValue().stabilizeWorld || !driven.contains(entry.getValue().childId)) continue;
            SubLevel root=aimCarrier(entry.getKey());
            if (root instanceof ServerSubLevel body && !grabbed.contains(body.getUniqueId())
                    && !rotatingCarriers.contains(body.getUniqueId())) needed.put(body.getUniqueId(),body);
        }
        var holds=STAFF_CARRIER_HOLDS.computeIfAbsent(system.getLevel(),ignored->new HashMap<>());
        holds.entrySet().removeIf(entry->{
            if (needed.get(entry.getKey())==entry.getValue().body() && entry.getValue().handle().isValid()) return false;
            if (entry.getValue().handle().isValid()) entry.getValue().handle().remove();
            return true;
        });
        for (var entry:needed.entrySet()) {
            ServerSubLevel body=entry.getValue();
            var mass=body.getMassTracker();
            if (mass==null || mass.getCenterOfMass()==null) continue;
            CarrierStaffHold hold=holds.get(entry.getKey());
            if (hold==null) {
                GenericConstraintHandle handle=system.getPipeline().addConstraint(null,body,
                        new GenericConstraintConfiguration(new Vector3d(),new Vector3d(mass.getCenterOfMass()),
                                new Quaterniond(body.logicalPose().orientation()),new Quaterniond(),
                                Set.of(ConstraintJointAxis.ANGULAR_X,ConstraintJointAxis.ANGULAR_Y,ConstraintJointAxis.ANGULAR_Z)));
                if (handle==null || !handle.isValid()) continue;
                hold=new CarrierStaffHold(body,handle,new Quaterniond(body.logicalPose().orientation())); holds.put(entry.getKey(),hold);
                // Keep this partial generic lock in Rapier's scalar row order.
                handle.setMotor(ConstraintJointAxis.ANGULAR_X,0,0,0,true,0);
            }
        }
    }

    /** Plans after all physics actors have queued their commands, immediately before the native solve. */
    private static void maintainStaffTurretHolds(SubLevelPhysicsSystem system,
            Set<Map.Entry<SwivelBearingBlockEntity,AxisLock>> locks,Map<UUID,Quaterniond> goals,
            Set<UUID> rotating,double dt) {
        var holds=STAFF_TURRET_HOLDS.computeIfAbsent(system.getLevel(),ignored->new HashMap<>());
        Map<UUID,ServerSubLevel> needed=new HashMap<>();
        Map<UUID,SwivelBearingBlockEntity> bearings=new HashMap<>();
        for(var entry:locks) {
            AxisLock lock=entry.getValue();
            if(rootServerLevel(entry.getKey().getLevel())==system.getLevel() && lock.yawAxis && lock.stabilizeWorld
                    && goals.containsKey(lock.childId) && !rotating.contains(lock.childId)
                    && lock.jointChild!=null && !lock.jointChild.isRemoved()) {
                needed.put(lock.childId,lock.jointChild); bearings.put(lock.childId,entry.getKey());
            }
        }
        holds.entrySet().removeIf(entry->{
            if(needed.get(entry.getKey())==entry.getValue().body() && entry.getValue().handle().isValid()) return false;
            if(entry.getValue().handle().isValid()) entry.getValue().handle().remove(); return true;
        });
        var motor=SimulatedPhysicsStaffCompat.angularMotor();
        for(var entry:needed.entrySet()) {
            ServerSubLevel body=entry.getValue(); var hold=holds.get(entry.getKey());
            if(hold==null) {
                SwivelBearingBlockEntity bearing=bearings.get(entry.getKey());
                Direction facing=bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
                SubLevel parent=Sable.HELPER.getContaining(bearing);
                Quaterniond frame=BearingServoMath.hingeFrame(new Quaterniond(facing.getRotation()),0);
                if(parent!=null) frame=new Quaterniond(parent.logicalPose().orientation()).mul(frame);
                Quaterniond childFrame=new Quaterniond(body.logicalPose().orientation()).conjugate().mul(frame);
                var handle=system.getPipeline().addConstraint(null,body,new GenericConstraintConfiguration(new Vector3d(),
                        new Vector3d(body.getMassTracker().getCenterOfMass()),frame,
                        childFrame,Set.of(ConstraintJointAxis.ANGULAR_Y,ConstraintJointAxis.ANGULAR_Z)));
                if(handle==null || !handle.isValid()) continue;
                handle.setMotor(ConstraintJointAxis.ANGULAR_X,0,0,0,true,0);
                hold=new CarrierStaffHold(body,handle,new Quaterniond(body.logicalPose().orientation()));holds.put(entry.getKey(),hold);
            }
            Quaterniond delta=StaffCarrierForecast.forecastDelta(hold.heldOrientation(),goals.get(entry.getKey()),
                    dt,motor.stiffness(),motor.damping());
            delta.mul(hold.heldOrientation(),hold.heldOrientation()).normalize();
        }
    }

    /** Plans after all physics actors have queued their commands, immediately before the native solve. */
    public static void planArticulatedServos(SubLevelPhysicsSystem system, double dt) {
        if (system == null || !(dt > 0.0) || !Double.isFinite(dt)) return;
        if (ACTIVE.isEmpty()) return;
        Map<ServerSubLevel, List<ServoStep>> components = new IdentityHashMap<>();
        for (var entry : activeSnapshot()) {
            AxisLock lock = entry.getValue();
            ServoStep step = lock.servoStep;
            if (step == null || rootServerLevel(entry.getKey().getLevel()) != system.getLevel()) continue;
            lock.servoStep = null; // Each prepared command is consumed only once.
            components.computeIfAbsent(step.carrier, ignored -> new ArrayList<>()).add(step);
        }
        for (var component : components.entrySet()) {
            try {
                List<ServoStep> steps = component.getValue();
                if (steps.stream().anyMatch(step -> step.child.isRemoved()
                        || step.parent == null || step.parent.isRemoved() || step.lock.joint == null
                        || !step.lock.joint.isValid())) {
                    recordServoPlanning("ineligible",steps);
                    continue;
                }
                CarrierStaffHold carrierHold=STAFF_CARRIER_HOLDS.getOrDefault(system.getLevel(),Map.of()).get(component.getKey().getUniqueId());
                if (steps.stream().noneMatch(step -> step.eligible) && carrierHold==null) {
                    recordServoPlanning("no-held-motor",steps);
                    continue;
                }
                steps.sort(Comparator.comparingLong(step -> SableServoConstraints.order(step.lock.joint.hinge())));
                List<ServerSubLevel> bodies = new ArrayList<>();
                bodies.add(component.getKey());
                for (ServoStep step : steps) {
                    if (!bodies.contains(step.parent)) bodies.add(step.parent);
                    if (!bodies.contains(step.child)) bodies.add(step.child);
                }
                SableServoConstraints.expandFixedBodies(system.getLevel(), bodies);
                if (steps.size() > 8 || bodies.size() > ArticulatedServoSolver.MAX_BODIES) {
                    recordServoPlanning("component-size",steps);
                    continue;
                }
                Set<Object> handles = Collections.newSetFromMap(new IdentityHashMap<>());
                for (ServoStep step : steps) {
                    handles.add(step.lock.joint.hinge());
                    handles.add(step.lock.joint.actuator());
                }
                var worlds = SableServoConstraints.worldMotors(system.getLevel(), bodies, handles);
                if (worlds == null) {
                    recordServoPlanning("unmodelled-connection",steps);
                    continue;
                }
                ServerSubLevel carrier = component.getKey();
                boolean finiteAiming = steps.stream().anyMatch(step -> !step.lock.finiteTargets.isEmpty());
                boolean constrainedStaff=carrierHold!=null;
                boolean carrierMoving = false;
                for (ServerSubLevel body : bodies) {
                    if (system.getPipeline().getAngularVelocity(body,new Vector3d()).length()>0.01
                            || system.getPipeline().getLinearVelocity(body,new Vector3d()).length()>0.01) {
                        carrierMoving=true; break;
                    }
                }
                if (!carrierMoving) {
                    // A stopped carrier can leave transient barrel motion, including sky aim.
                    for (ServoStep step : steps) {
                        if (system.getPipeline().getAngularVelocity(step.child, new Vector3d()).length() > 0.01) {
                            carrierMoving = true;
                            break;
                        }
                    }
                }
                Set<UUID> dragged=SimulatedPhysicsStaffCompat.draggedBodies(system.getLevel());
                boolean staffMoving = bodies.stream().anyMatch(body->dragged.contains(body.getUniqueId()));
                boolean queuedMotion = false;
                for (var world : worlds) {
                    ServerSubLevel drivenBody=bodies.get(world.child());
                    Vector3d anchor = drivenBody.logicalPose().orientation().transform(world.childAnchor())
                            .add(drivenBody.logicalPose().position()).sub(world.parentWorldAnchor());
                    world.queuedFrame().transformInverse(anchor);
                    double[] linearGoals=world.linearGoals();
                    double[] componentsOfAnchor={anchor.x,anchor.y,anchor.z};
                    for (int axis=0;axis<3;axis++) if ((world.activeMask() & (1<<axis))!=0
                            && Math.abs(componentsOfAnchor[axis]-linearGoals[axis])>1e-4) queuedMotion=true;
                    Quaterniond actualFrame = new Quaterniond(drivenBody.logicalPose().orientation());
                    Quaterniond childFrame = world.childFrame();
                    if (childFrame.x != 0.0 || childFrame.y != 0.0 || childFrame.z != 0.0 || childFrame.w != 1.0) {
                        actualFrame.mul(childFrame);
                    }
                    Quaterniond error = world.queuedFrame().conjugate().mul(actualFrame).normalize();
                    if (error.w < 0) error.mul(-1);
                    double[] componentsOfError = {error.x, error.y, error.z};
                    double[] goals = world.angularGoals();
                    for (int axis = 0; axis < 3; axis++) {
                        if ((world.activeMask() & (1 << (axis + 3))) != 0
                                && Math.abs(2 * Math.asin(Math.clamp(componentsOfError[axis], -1, 1)) - goals[axis]) > 1e-4) {
                            queuedMotion = true;
                        }
                    }
                }
                // Leave resting assemblies to their existing servo/sleep path. Inverting tiny
                // floating-point pose corrections continuously prevents Sable from sleeping them.
                boolean pendingMotorCorrection = false;
                if (!carrierMoving && !staffMoving && !queuedMotion) {
                    for (ServoStep step : steps) {
                        // Fixed operator motors also produce reactions on held sibling turrets.
                        // A static ray's steady load bias is allowed to use the native sleep path.
                        boolean transientCommand = finiteAiming || step.lock.finiteModeRecoveryRemaining > 0.0
                                || step.lock.motorTarget.hasCarrierPrediction(1e-3)
                                || Math.abs(step.lock.operatorMotion.velocity()) > 1e-6
                                || Math.abs(step.lock.mechanicalDegreesPerSecond) > 1e-6;
                        if (!transientCommand) continue;
                        Quaterniond parentFrame = new Quaterniond(step.parent.logicalPose().orientation()).mul(step.frameA);
                        Quaterniond childFrame = new Quaterniond(step.child.logicalPose().orientation()).mul(step.frameB);
                        Quaterniond error = parentFrame.conjugate().mul(childFrame).normalize();
                        if (error.w < 0.0) error.mul(-1.0);
                        double measuredMotor = 2.0 * Math.asin(Math.clamp(error.x, -1.0, 1.0));
                        if (Math.abs(BearingServoMath.wrapRadians(step.goal - measuredMotor)) > 1e-5) {
                            pendingMotorCorrection = true;
                            break;
                        }
                    }
                }
                if (!carrierMoving && !staffMoving && !queuedMotion && !pendingMotorCorrection) {
                    recordServoPlanning("resting-carrier",steps);
                    continue;
                }
                Vector3d origin = new Vector3d(component.getKey().logicalPose().position());
                List<ArticulatedServoPredictor.BodyState> snapshot = new ArrayList<>();
                List<ArticulatedServoPredictor.HingeMotor> hinges = new ArrayList<>();
                List<ArticulatedServoSolver.RayGoal> rays = new ArrayList<>();
                List<Integer> controlledIndices = new ArrayList<>();
                boolean complete = true;
                for (ServerSubLevel body : bodies) {
                    var mass = body.getMassTracker();
                    if (body.isRemoved() || mass == null || mass.getCenterOfMass() == null) {
                        complete = false;
                        break;
                    }
                    snapshot.add(new ArticulatedServoPredictor.BodyState(mass.getMass(),
                            new Matrix3d(mass.getInertiaTensor()),
                            new Vector3d(body.logicalPose().position()).sub(origin),
                            new Quaterniond(body.logicalPose().orientation()),
                            system.getPipeline().getLinearVelocity(body, new Vector3d()),
                            system.getPipeline().getAngularVelocity(body, new Vector3d())));
                }
                if (!complete) {
                    recordServoPlanning("incomplete-snapshot",steps);
                    continue;
                }
                for (ServoStep step : steps) {
                    int parent = bodies.indexOf(step.parent), child = bodies.indexOf(step.child);
                    int physicalChild = bodies.indexOf(step.lock.jointActuationChild);
                    if (physicalChild < 0) throw new IllegalArgumentException("Missing welded attachment body");
                    var hingeModel = new ArticulatedServoPredictor.HingeMotor(parent, physicalChild,
                            new Vector3d(step.lock.parentLocalAnchor).sub(step.parent.getMassTracker().getCenterOfMass()),
                            new Vector3d(step.lock.actuationLocalPivot).sub(step.lock.jointActuationChild.getMassTracker().getCenterOfMass()),
                            step.frameA, new Quaterniond(step.lock.actuationFromChild).mul(step.frameB), step.goal, step.motor.stiffness(), step.motor.damping(),
                            step.motor.maxTorque(), SableServoConstraints.order(step.lock.joint.hinge()),
                            true, SableServoConstraints.order(step.lock.joint.actuator()));
                    // Limits remain native rows during passive/startup hold too.
                    // An unready axis is a fixed command in this model, including its stops.
                    if (!step.lock.yawAxis)
                        hingeModel=hingeModel.withLimits(step.lock.pitchStops.min(),step.lock.pitchStops.max());
                    else if (step.lock.yawLimits.enabled())
                        hingeModel=hingeModel.withLimits(step.lock.yawStops.nativeMin(),step.lock.yawStops.nativeMax());
                    hinges.add(hingeModel);
                    // Moving operators keep their prepared motor commands, but their physical
                    // response still belongs to the model used by the other held turret axes.
                    if (!step.eligible) continue;
                    controlledIndices.add(hinges.size() - 1);
                    List<ArticulatedServoSolver.RaySource> sources = new ArrayList<>();
                    sources.add(new ArticulatedServoSolver.RaySource(child, step.lock.aimLocalForward));
                    if (!step.lock.yawAxis && step.lock.directionSourceId != null) {
                        for (int i = 0; i < bodies.size(); i++) {
                            if (bodies.get(i).getUniqueId().equals(step.lock.directionSourceId)) {
                                sources = List.of(new ArticulatedServoSolver.RaySource(i, step.lock.directionSourceLocalForward));
                                break;
                            }
                        }
                    }
                    if (step.lock.yawAxis && !step.lock.azimuthObservations.isEmpty()) {
                        List<ArticulatedServoSolver.RaySource> observed = new ArrayList<>();
                        for (LocalAzimuthObservation observation : step.lock.azimuthObservations) {
                            for (int i = 0; i < bodies.size(); i++) {
                                if (!bodies.get(i).getUniqueId().equals(observation.bodyId())) continue;
                                Vec3 ray = observation.localDirection();
                                observed.add(new ArticulatedServoSolver.RaySource(i,new Vector3d(ray.x,ray.y,ray.z)));
                                break;
                            }
                        }
                        if (observed.size() == step.lock.azimuthObservations.size()) sources = observed;
                    }
                    Direction facing = step.bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
                    List<ArticulatedServoSolver.RaySource> pitchSources = sources;
                    ArticulatedServoSolver.DynamicRayTarget finiteGoal = step.lock.yawAxis ? null : predicted ->
                            BearingServoMath.pitchDirection(step.lock.heldAimDirection,
                                    predicted.get(pitchSources.getFirst().barrelIndex()).orientation()
                                            .transform(pitchSources.getFirst().localRay()),
                                    predicted.get(parent).orientation());
                    if (!step.lock.finiteTargets.isEmpty() && step.lock.directionReferenceInitialized) {
                        List<FiniteAimTarget> finiteTargets = step.lock.finiteTargets;
                        List<Integer> targetBodies = new ArrayList<>();
                        List<Vector3d> targetCenters = new ArrayList<>();
                        List<Integer> cameraBodies = new ArrayList<>();
                        List<Vector3d> cameraCenters = new ArrayList<>();
                        List<StableMuzzleVelocity.Prediction> holdingModels = new ArrayList<>();
                        for (FiniteAimTarget target : finiteTargets) {
                            int index = -1;
                            for (int i = 0; i < bodies.size(); i++) {
                                if (bodies.get(i).getUniqueId().equals(target.bodyId())) { index = i; break; }
                            }
                            if (index < 0) { complete = false; break; }
                            targetBodies.add(index);
                            targetCenters.add(new Vector3d(bodies.get(index).getMassTracker().getCenterOfMass()));
                            holdingModels.add(holdingMuzzlePrediction(step.bearing.getLevel(),target.bodyId(),bodies));
                            int cameraIndex = -1;
                            if (target.movingRay() != null) {
                                for (int i=0;i<bodies.size();i++) if (bodies.get(i).getUniqueId()
                                        .equals(target.movingRay().bodyId())) { cameraIndex=i; break; }
                                if (cameraIndex < 0) { complete=false; break; }
                            }
                            cameraBodies.add(cameraIndex);
                            cameraCenters.add(cameraIndex < 0 ? new Vector3d()
                                    : new Vector3d(bodies.get(cameraIndex).getMassTracker().getCenterOfMass()));
                        }
                        if (!complete) break;
                        if (step.lock.yawAxis) {
                            // Finite head aim aligns the actual outgoing trajectory.
                            // This observation never rewrites azimuth's held direction
                            // or elevation's actuator command/history.
                            sources.clear();
                            for (int i = 0; i < finiteTargets.size(); i++) {
                                Vec3 forward = finiteTargets.get(i).localForward();
                                sources.add(new ArticulatedServoSolver.RaySource(targetBodies.get(i),
                                        new Vector3d(forward.x, forward.y, forward.z)));
                            }
                        }
                        double acquisitionOffset=step.lock.operatorError(step.bearing,
                                step.lock.requestedAimDirection,step.lock.heldAimDirection);
                        Vector3d localAxis=new Vector3d(facing.getStepX(),facing.getStepY(),facing.getStepZ());
                        // During range/mode acquisition the head-aim limiter owns traverse.
                        // A moving muzzle's acquisition velocity must not feed a second,
                        // unrestricted ballistic correction back into that same motor.
                        if (step.lock.finiteCompensationActive) finiteGoal = predicted -> {
                            Vector3d requested = new Vector3d();
                            for (int i = 0; i < finiteTargets.size(); i++) {
                                FiniteAimTarget target = finiteTargets.get(i);
                                Vec3 point = target.movingRay() == null ? target.worldTarget()
                                        : target.movingRay().predictedTarget(predicted.get(cameraBodies.get(i)),
                                        origin,cameraCenters.get(i),step.bearing.getLevel());
                                if (point == null) return null;
                                var muzzleBody=predicted.get(targetBodies.get(i));
                                Vector3d muzzleOffset=muzzleBody.orientation().transform(new Vector3d(
                                        target.localMuzzle().x,target.localMuzzle().y,target.localMuzzle().z)
                                        .sub(targetCenters.get(i)));
                                Vector3d muzzleRay=muzzleBody.orientation().transform(new Vector3d(
                                        target.localForward().x,target.localForward().y,target.localForward().z));
                                var holdingModel=holdingModels.get(i);
                                Vector3d holding=holdingModel==null ? null : holdingModel.velocity(predicted,
                                        snapshot.get(holdingModel.carrier()),muzzleBody.position().add(muzzleOffset),muzzleRay);
                                Vector3d direction = target.predictedDirection(
                                        muzzleBody, origin, targetCenters.get(i), point,
                                        holding==null ? null : new Vec3(holding.x/20,holding.y/20,holding.z/20));
                                if (direction == null) return null;
                                requested.add(direction);
                            }
                            if (requested.lengthSquared()<1e-12) return null;
                            requested.normalize();
                            Quaterniond parentPose=predicted.get(parent).orientation();
                            requested=step.lock.limitAimDirection(requested,parentPose);
                            // Preserve this axis's remaining operator traverse while predicting
                            // moving muzzle/eye geometry and inherited velocity on the same step.
                            Vector3d predictedAxis=parentPose.transform(new Vector3d(localAxis)).normalize();
                            Vector3d resultDirection=new Quaterniond().rotationAxis(acquisitionOffset,predictedAxis.x,
                                    predictedAxis.y,predictedAxis.z).transform(requested).normalize();
                            return step.lock.yawAxis ? resultDirection : BearingServoMath.pitchDirection(resultDirection,
                                    predicted.get(child).orientation().transform(new Vector3d(step.lock.aimLocalForward)),parentPose);
                        };
                    }
                    rays.add(new ArticulatedServoSolver.RayGoal(parent,
                            new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ()),
                            step.lock.heldAimDirection, sources, finiteGoal));
                }
                if (!complete) {
                    recordServoPlanning("incomplete-snapshot",steps);
                    continue;
                }
                for (var fixed : SableServoConstraints.fixedJoints(system.getLevel(), bodies)) {
                    hinges.add(fixed.parent() < 0 && !fixed.angularOnly() ? ArticulatedServoPredictor.HingeMotor.fixed(
                            fixed.parent(), fixed.child(), fixed.parentAnchor().sub(origin), fixed.childAnchor(),
                            fixed.parentFrame(), fixed.childFrame(), fixed.rowOrder()) : fixed);
                }
                // Translate the entire model together; large plot/world coordinates never enter the solver.
                List<ArticulatedServoPredictor.WorldMotor> localWorlds = new ArrayList<>();
                for (var world : worlds) {
                    localWorlds.add(new ArticulatedServoPredictor.WorldMotor(world.child(), world.queuedFrame(),
                            world.childAnchor(), new Vector3d(world.parentWorldAnchor()).sub(origin),
                            world.angularGoals(), world.linearGoals(), world.stiffness(), world.damping(),
                            world.cap(), world.activeMask(), world.beforeHinges(), world.rowOrder(), world.childFrame()));
                }
                var config = system.getConfig();
                if (carrierHold!=null) predictStaffCarrierFrame(system,dt,snapshot,hinges,localWorlds,origin,carrierHold);
                for (var hold:STAFF_TURRET_HOLDS.getOrDefault(system.getLevel(),Map.of()).values())
                    if (bodies.contains(hold.body())) predictStaffCarrierFrame(system,dt,snapshot,hinges,localWorlds,origin,hold);
                var result = ArticulatedServoSolver.solveIndependent(snapshot, hinges, null, localWorlds,
                        DimensionPhysicsData.getGravity(system.getLevel(), origin), dt, rays,
                        controlledIndices.stream().mapToInt(Integer::intValue).toArray(),
                        config.solverIterations, config.pgsIterations, config.stabilizationIterations, 550, 4, false);
                recordServoPlanning(result.accepted() ? "accepted" : result.failure().name(),steps);
                if (!result.accepted()) continue;
                double[] targets = result.targets();
                if (!finiteAiming && !constrainedStaff) {
                    // Retain the existing free-ray/staff recovery path for ordinary world holds.
                    boolean legal=true;
                    for (int i=0;i<steps.size();i++) {
                        AxisLock lock=steps.get(i).lock;
                        if (steps.get(i).eligible && !lock.yawAxis
                                && (targets[i]<lock.pitchStops.min() || targets[i]>lock.pitchStops.max())) legal=false;
                    }
                    if (!legal) { recordServoPlanning("pitch-stop",steps); continue; }
                }
                if (constrainedStaff) {
                    List<ArticulatedServoPredictor.HingeMotor> finalCommands=new ArrayList<>();
                    for (int i=0;i<hinges.size();i++) finalCommands.add(hinges.get(i).withGoal(targets[i]));
                    for (int pass=0;pass<3;pass++) {
                        predictStaffCarrierFrame(system,dt,snapshot,finalCommands,localWorlds,origin,carrierHold);
                        for (var hold:STAFF_TURRET_HOLDS.getOrDefault(system.getLevel(),Map.of()).values())
                            if (bodies.contains(hold.body())) predictStaffCarrierFrame(system,dt,snapshot,finalCommands,localWorlds,origin,hold);
                        var refined=ArticulatedServoSolver.solveIndependent(snapshot,finalCommands,null,localWorlds,
                                DimensionPhysicsData.getGravity(system.getLevel(),origin),dt,rays,
                                controlledIndices.stream().mapToInt(Integer::intValue).toArray(),
                                config.solverIterations,config.pgsIterations,config.stabilizationIterations,550,4,false);
                        if (!refined.accepted()) break;
                        double[] nextTargets=refined.targets();
                        boolean bounded=true;
                        for(int i=0;i<steps.size();i++) if(Math.abs(BearingServoMath.wrapRadians(nextTargets[i]-result.targets()[i]))>.15) bounded=false;
                        if (!bounded) break;
                        targets=nextTargets;
                        for(int i=0;i<finalCommands.size();i++) finalCommands.set(i,finalCommands.get(i).withGoal(targets[i]));
                    }
                    for(int i=0;i<hinges.size();i++) if(finalCommands.get(i).angularOnly()) hinges.set(i,finalCommands.get(i));
                }
                // The motor target may lead the physical boundary to overcome load.
                // Actual pitch limits are present in both native physics and every trial.
                if (servoPredictionObserver != null) {
                    List<ArticulatedServoPredictor.HingeMotor> trial = new ArrayList<>();
                    for (int i = 0; i < hinges.size(); i++) trial.add(hinges.get(i).withGoal(targets[i]));
                    var prediction = ArticulatedServoPredictor.predict(snapshot, trial, null, localWorlds,
                            DimensionPhysicsData.getGravity(system.getLevel(), origin), dt,
                            config.solverIterations, config.pgsIterations, config.stabilizationIterations, 550, 4, false);
                    Map<UUID, ArticulatedServoPredictor.BodyState> states = new HashMap<>();
                    for (int i = 0; i < bodies.size(); i++) {
                        var state = prediction.bodies().get(i);
                        states.put(bodies.get(i).getUniqueId(), new ArticulatedServoPredictor.BodyState(state.mass(),
                                state.inertiaLocal(), state.position().add(origin), state.orientation(),
                                state.linearVelocity(), state.angularVelocity()));
                    }
                    servoPredictionObserver.accept(states);
                }
                for (int i = 0; i < steps.size(); i++) {
                    ServoStep step = steps.get(i);
                    if (!step.eligible) continue;
                    // Each axis owns a child-only actuator; the hinge carries attachment loads.
                    step.lock.joint.drive(step.lock.limitYawMotorGoal(targets[i]), step.motor);
                }
                if (controlledIndices.size() < steps.size()) recordServoPlanning("fixed-motor-accepted",steps);
            } catch (IllegalArgumentException | ArithmeticException invalidSnapshot) {
                // Keep the prepared motor commands if this assembly is outside the optional model.
                recordServoPlanning("invalid-snapshot",component.getValue());
            }
        }
    }

    private static void predictStaffCarrierFrame(SubLevelPhysicsSystem system,double dt,
            List<ArticulatedServoPredictor.BodyState> snapshot,List<ArticulatedServoPredictor.HingeMotor> hinges,
            List<ArticulatedServoPredictor.WorldMotor> worlds,Vector3d origin,CarrierStaffHold hold) {
        long order=SableServoConstraints.order(hold.handle());
        int index=-1;
        for (int i=0;i<hinges.size();i++) if (hinges.get(i).angularOnly() && hinges.get(i).rowOrder()==order) {index=i;break;}
        if (index<0) return;
        var original=hinges.get(index); Quaterniond frame=original.parentFrame();
        var config=system.getConfig();
        for (int iteration=0;iteration<3;iteration++) {
            var prediction=ArticulatedServoPredictor.predict(snapshot,hinges,null,worlds,
                    DimensionPhysicsData.getGravity(system.getLevel(),origin),dt,
                    config.solverIterations,config.pgsIterations,config.stabilizationIterations,550,4,false);
            Quaterniond correction=new Quaterniond(hold.heldOrientation()).mul(prediction.bodies().get(original.child()).orientation().conjugate()).normalize();
            if(!original.fixed()) {
                Vector3d axis=original.parentFrame().transform(new Vector3d(1,0,0)).normalize();
                double axial=correction.x*axis.x+correction.y*axis.y+correction.z*axis.z;
                Quaterniond twist=new Quaterniond(axis.x*axial,axis.y*axial,axis.z*axial,correction.w);
                if(twist.lengthSquared()>1e-12) correction.mul(twist.normalize().conjugate()).normalize();
            }
            double angle=2*Math.atan2(Math.sqrt(correction.x*correction.x+correction.y*correction.y+correction.z*correction.z),Math.abs(correction.w));
            if (angle>0.05) {hinges.set(index,original);return;}
            if (angle<1e-7) break;
            frame=correction.mul(frame).normalize();
            hinges.set(index,original.withParentFrame(frame));
        }
        hold.handle().setFrame1(new Vector3d(),frame);
    }

    private static void recordServoPlanning(String result) {
        SERVO_PLANNING_RESULTS.merge(result, 1L, Long::sum);
    }

    private static void recordServoPlanning(String result,List<ServoStep> steps) {
        recordServoPlanning(result);
        for (ServoStep step:steps) step.lock.lastServoPlanning=result
                + (step.modelReady ? "" : ":unready") + (step.eligible ? "" : ":fixed");
    }

    private record ServoStep(SwivelBearingBlockEntity bearing, AxisLock lock, ServerSubLevel parent,
                             ServerSubLevel child, ServerSubLevel carrier, Quaterniond frameA,
                             Quaterniond frameB, double goal, BearingServoMath.Motor motor,
                             boolean modelReady, boolean eligible) {}

    /** Carrier outside the controlled joints, excluding articulation from aim feed-forward. */
    public static SubLevel aimCarrier(SwivelBearingBlockEntity bearing) {
        SubLevel parent=Sable.HELPER.getContaining(bearing);
        var chain=parent==null ? null : controlledBodyChain(bearing.getLevel(),parent.getUniqueId());
        return chain==null ? null : chain.carrier();
    }

    private record ControlledBodyChain(SubLevel carrier,
            List<Map.Entry<SwivelBearingBlockEntity,AxisLock>> axes) {}

    /** Resolve actual tracked welds, including a pitch mount welded onto a yaw child. */
    private static ControlledBodyChain controlledBodyChain(Level level,UUID bodyId) {
        ServerLevel root=rootServerLevel(level);
        if (root==null) return null;
        Map<BearingBodyChain.Joint,Map.Entry<SwivelBearingBlockEntity,AxisLock>> axes=new HashMap<>();
        Set<BearingBodyChain.Joint> duplicates=new HashSet<>();
        for (var entry:activeSnapshot()) {
            if (rootServerLevel(entry.getKey().getLevel())!=root || !isBound(entry.getKey())) continue;
            UUID parentId=containingBodyId(entry.getKey()),childId=entry.getValue().childId;
            if (parentId==null || childId==null) continue;
            var key=new BearingBodyChain.Joint(parentId,childId);
            if (axes.put(key,entry)!=null) duplicates.add(key);
        }
        var resolved=BearingBodyChain.resolve(bodyId,List.copyOf(axes.keySet()),SableServoConstraints.fixedAdjacency(root));
        if (resolved==null || resolved.joints().stream().anyMatch(duplicates::contains)) return null;
        SubLevel carrier=SubLevelContainer.getContainer(level).getSubLevel(resolved.carrier());
        if (carrier==null || carrier.isRemoved()) return null;
        return new ControlledBodyChain(carrier,resolved.joints().stream().map(axes::get).toList());
    }

    private static StableMuzzleVelocity.Prediction holdingMuzzlePrediction(Level level,UUID muzzleBodyId,
                                                                           List<ServerSubLevel> bodies) {
        var chain=controlledBodyChain(level,muzzleBodyId);
        if (chain==null) return null;
        List<StableMuzzleVelocity.JointFrame> frames=new ArrayList<>();
        for (var controlled:chain.axes()) {
            AxisLock lock=controlled.getValue();
            if (lock.staffYielding || !lock.stabilizeWorld || !lock.directionReferenceInitialized) return null;
            SubLevel body=SubLevelContainer.getContainer(level).getSubLevel(lock.childId);
            SubLevel parent=Sable.HELPER.getContaining(controlled.getKey());
            int childIndex=bodies.indexOf(body),parentIndex=bodies.indexOf(parent);
            if (childIndex<0 || parentIndex<0) return null;
            Direction facing=controlled.getKey().getBlockState().getValue(SwivelBearingBlock.FACING);
            frames.add(new StableMuzzleVelocity.JointFrame(parentIndex,childIndex,
                    new Vector3d(facing.getStepX(),facing.getStepY(),facing.getStepZ()),
                    new Vector3d(lock.childLocalPivot).sub(bodies.get(childIndex).getMassTracker().getCenterOfMass())));
        }
        int carrierIndex=bodies.indexOf(chain.carrier());
        return carrierIndex<0 || frames.isEmpty() ? null : new StableMuzzleVelocity.Prediction(carrierIndex,frames);
    }

    /** Remove only acquired axis compensation, not a newly requested ballistic solution. */
    public static Vec3 withoutMuzzleCompensation(Level level,UUID muzzleBodyId,Vec3 measured) {
        Vector3d ray=new Vector3d(measured.x,measured.y,measured.z).normalize();
        var chain=controlledBodyChain(level,muzzleBodyId);
        if (chain==null) return measured;
        for (var controlled:chain.axes()) {
            AxisLock lock=controlled.getValue();
            if (!lock.staffYielding) {
                Vector3d axis=worldAxis(controlled.getKey(),new Vector3d(),null);
                new Quaterniond().rotationAxis(-lock.sightCompensation.acquired(),axis.x,axis.y,axis.z)
                        .transform(ray).normalize();
            }
        }
        return new Vec3(ray.x,ray.y,ray.z);
    }

    /** Nominal velocity of this muzzle while its bore holds a world direction. */
    public static Vec3 stableMuzzleVelocity(Level level, UUID muzzleBodyId, Vec3 worldMuzzle, Vec3 worldRay) {
        if (level == null || muzzleBodyId == null || worldMuzzle == null || worldRay == null) return null;
        var chain=controlledBodyChain(level,muzzleBodyId);
        if (chain==null) return null;
        List<StableMuzzleVelocity.Joint> joints = new ArrayList<>();
        for (var controlled:chain.axes()) {
            AxisLock lock = controlled.getValue();
            if (lock.staffYielding || !lock.stabilizeWorld || !lock.directionReferenceInitialized) return null;
            SubLevel body=SubLevelContainer.getContainer(level).getSubLevel(lock.childId);
            if (body==null || body.isRemoved()) return null;
            joints.add(new StableMuzzleVelocity.Joint(worldAxis(controlled.getKey(), new Vector3d(), null),
                    body.logicalPose().transformPosition(new Vector3d(lock.childLocalPivot))));
        }
        SubLevel body=chain.carrier();
        if (body == null || body.isRemoved() || joints.isEmpty()) return null;
        ServerLevel root = rootServerLevel(level);
        if (root == null || !(body instanceof ServerSubLevel carrierBody)) return null;
        Vector3d muzzle = new Vector3d(worldMuzzle.x, worldMuzzle.y, worldMuzzle.z);
        Vector3d localMuzzle = body.logicalPose().transformPositionInverse(new Vector3d(muzzle));
        Vector3d velocity = Sable.HELPER.getVelocity(level, body, localMuzzle, new Vector3d());
        Vector3d omega = SubLevelPhysicsSystem.require(root).getPipeline().getAngularVelocity(carrierBody, new Vector3d());
        Vector3d stable = StableMuzzleVelocity.sample(velocity, omega, muzzle,
                new Vector3d(worldRay.x, worldRay.y, worldRay.z), joints);
        return stable == null ? null : new Vec3(stable.x / 20.0, stable.y / 20.0, stable.z / 20.0);
    }

    static boolean yieldingToStaff(SwivelBearingBlockEntity bearing) {
        AxisLock lock = ACTIVE.get(bearing);
        return lock != null && lock.staffYielding;
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (hasBindingState(level) || !GRAVITY_OVERRIDDEN.isEmpty()) {
                Map<UUID, ServerSubLevel> directChildren = collectLiveDirectChildren(level);
                restoreUnboundGravityTags(level, directChildren.keySet());
                for (ServerSubLevel child : directChildren.values()) {
                    if (!child.isRemoved()) gravityStateFor(child).applyTags();
                }
            }
            reconcileNoContacts(level);
            reconcilePitchCollisions(level);
        }
        if (ACTIVE.isEmpty()) return;
        long now = event.getServer().overworld().getGameTime();
        if (lastFiniteDiagnosticTick != Long.MIN_VALUE && now >= lastFiniteDiagnosticTick
                && now - lastFiniteDiagnosticTick < 200) return;
        StringBuilder axes = new StringBuilder();
        for (var entry : activeSnapshot()) {
            AxisLock lock = entry.getValue();
            long commandAge=lock.lastAimCommandTick==Long.MIN_VALUE ? -1
                    : entry.getKey().getLevel().getGameTime()-lock.lastAimCommandTick;
            boolean unsettled=lock.jointChild!=null && lock.directionReferenceInitialized
                    && Math.abs(lock.operatorError(entry.getKey(),
                    lock.observedForward(entry.getKey(),lock.jointChild),lock.heldAimDirection))>Math.toRadians(.5);
            if (!lock.directionReferenceInitialized || lock.finiteTargets.isEmpty()
                    && (commandAge<0 || commandAge>20) && !unsettled && lock.directionReferenceMeasured) continue;
            axes.append(lock.yawAxis ? " yaw" : " pitch").append('@').append(entry.getKey().getBlockPos())
                    .append(" active=").append(lock.finiteCompensationActive)
                    .append(" reference=").append(lock.directionReferenceMeasured ? "barrel" : "startup")
                    .append(" operatorDegS=").append(Math.toDegrees(lock.operatorMotion.velocity()))
                    .append(" mechanicalDegS=").append(lock.mechanicalDegreesPerSecond)
                    .append(" feedbackBody=").append(lock.directionSourceId)
                    .append(" driveBody=").append(lock.jointActuationChild == null ? null : lock.jointActuationChild.getUniqueId())
                    .append(" parentTraverse=").append(lock.diagnosticParentTraverse)
                    .append(" parentTraverseDegS=").append(Math.toDegrees(lock.diagnosticTraverseParentRate))
                    .append(" worldCommandDegS=").append(Math.toDegrees(lock.diagnosticWorldTraverseRate))
                    .append(" pendingDeg=").append(Math.toDegrees(lock.operatorError(
                            entry.getKey(),lock.heldAimDirection,lock.requestedAimDirection)))
                    .append(" actualErrorDeg=").append(lock.jointChild==null ? Double.NaN
                            : Math.toDegrees(lock.operatorError(entry.getKey(),
                            lock.observedForward(entry.getKey(),lock.jointChild),lock.requestedAimDirection)))
                    .append(" acquiredCompensationDeg=").append(Math.toDegrees(lock.sightCompensation.acquired()))
                    .append(" requestedCompensationDeg=").append(Math.toDegrees(lock.sightCompensation.requested()))
                    .append(" landingS=").append(lock.aimLandingRemaining)
                    .append(" plan=").append(lock.lastServoPlanning)
                    .append(" commandAge=").append(commandAge)
                    .append(" camera=").append(lock.finiteTargets.isEmpty() ? "direction"
                            : lock.finiteTargets.getFirst().movingRay() == null ? "world-point"
                            : lock.finiteTargets.getFirst().movingRay().bodyId());
        }
        if (axes.isEmpty()) return;
        Map<String, Long> delta = new HashMap<>();
        SERVO_PLANNING_RESULTS.forEach((reason,count) -> {
            long added=count-LAST_FINITE_DIAGNOSTIC_COUNTS.getOrDefault(reason,0L);
            if (added>0) delta.put(reason,added);
        });
        LAST_FINITE_DIAGNOSTIC_COUNTS.clear();
        LAST_FINITE_DIAGNOSTIC_COUNTS.putAll(SERVO_PLANNING_RESULTS);
        lastFiniteDiagnosticTick=now;
        StabilizedTurret.LOGGER.info("Finite aim diagnostics: planning={}{}",delta,axes);
    }

    private static void restoreUnboundGravityTags(
            ServerLevel level,
            Set<UUID> liveChildIds
    ) {
        for (Map.Entry<UUID, GravityState> entry : gravitySnapshot()) {
            GravityState state = entry.getValue();
            if (state.child.getLevel() != level || liveChildIds.contains(entry.getKey())) {
                continue;
            }
            GRAVITY_OVERRIDDEN.remove(entry.getKey());
            state.restoreTags();
        }
    }

    private static GravityState gravityStateFor(ServerSubLevel child) {
        UUID childId = child.getUniqueId();
        synchronized (GRAVITY_OVERRIDDEN) {
            GravityState current = GRAVITY_OVERRIDDEN.get(childId);
            if (current != null && current.child == child) return current;
            if (current != null) current.restoreTags();
            GravityState created = new GravityState(child);
            GRAVITY_OVERRIDDEN.put(childId, created);
            return created;
        }
    }

    /**
     * Static compatibility state outlives an integrated-server world.  Drop every
     * strong reference at shutdown and remove only the tags owned by this mod so
     * the next world cannot inherit an old sublevel with the same UUID.
     */
    public static void onServerStopping(ServerStoppingEvent event) {
        LegacyBearingStabilizer.clearRuntimeState();
        LEGACY_BEARINGS.clear();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            PitchStructureCollision.clear(level);
            clearOwnedNoContacts(level);
            SynaxisNoContactCompat.clearFallback(level);
            for (var hold:STAFF_CARRIER_HOLDS.getOrDefault(level,Map.of()).values())
                if (hold.handle().isValid()) hold.handle().remove();
            for (var hold:STAFF_TURRET_HOLDS.getOrDefault(level,Map.of()).values())
                if (hold.handle().isValid()) hold.handle().remove();
        }
        STAFF_CARRIER_HOLDS.clear();
        STAFF_TURRET_HOLDS.clear();
        STAFF_GRAB_REFERENCES.clear();
        for (GravityState state : gravitySnapshot().stream()
                .map(Map.Entry::getValue).toList()) {
            state.restoreTags();
        }
        GRAVITY_OVERRIDDEN.clear();
        for (Map.Entry<SwivelBearingBlockEntity, AxisLock> entry : activeSnapshot()) {
            entry.getValue().releaseJoint(entry.getKey(), false);
        }
        OWNED_NO_CONTACTS.clear();
        LAST_NO_CONTACT_REFRESH.clear();
        LAST_PITCH_COLLISION_REFRESH.clear();
        COLLISION_DISCOVERY.clear();
        BOUND.clear();
        YAW_LIMITS.clear();
        ACTIVE.clear();
        CONSTRAINED.clear();
        ASSEMBLY_ZEROS.clear();
        ASSEMBLY_PIVOTS.clear();
        lastFiniteDiagnosticTick = Long.MIN_VALUE;
        LAST_FINITE_DIAGNOSTIC_COUNTS.clear();
    }

    /** Keeps reaction-mode pitch loads collision-free, with a once-per-tick weld snapshot. */
    private static void reconcilePitchCollisions(ServerLevel level) {
        if (!hasBindingState(level)) {
            PitchStructureCollision.clear(level);
            return;
        }
        long now = level.getGameTime();
        Long previous = LAST_PITCH_COLLISION_REFRESH.put(level, now);
        if (previous != null && previous == now) return;
        Set<UUID> roots = new HashSet<>();
        Set<UUID> carriers = new HashSet<>();
        for (var entry : boundSnapshot()) {
            SwivelBearingBlockEntity bearing = entry.getKey();
            BoundState state = entry.getValue();
            if (state.yawAxis || usesLegacyImplementation(bearing) || !isLiveBinding(bearing, state)
                    || rootServerLevel(bearing.getLevel()) != level) continue;
            roots.add(state.childId);
            UUID parent = containingBodyId(bearing);
            if (parent != null) carriers.add(parent);
            SubLevel carrier = aimCarrier(bearing);
            if (carrier != null) carriers.add(carrier.getUniqueId());
        }
        if (roots.isEmpty()) {
            PitchStructureCollision.clear(level);
            return;
        }
        Map<UUID, Set<UUID>> fixed = SableServoConstraints.fixedAdjacency(level);
        SynaxisWeldCompat.fixedWeldedAdjacency(level).forEach((id, neighbors) ->
                fixed.computeIfAbsent(id, ignored -> new HashSet<>()).addAll(neighbors));
        PitchStructureCollision.reconcile(level, PitchStructureCollision.selectBodies(roots, carriers, fixed));
    }

    /** Reconciles internal body-pair isolation without changing the carrier's terrain collision. */
    private static void reconcileNoContacts(ServerLevel level) {
        CollisionDiscovery discovery = collisionDiscovery(level);
        discovery.restoreSavedRelations();
        boolean hasBindings = false;
        for (var entry : boundSnapshot()) {
            if (isLiveBinding(entry.getKey(), entry.getValue())
                    && rootServerLevel(entry.getKey().getLevel()) == level) {
                hasBindings = true;
                break;
            }
        }
        Set<BodyPair> owned = OWNED_NO_CONTACTS.get(level);
        if (!hasBindings && (owned == null || owned.isEmpty())) return;
        long now = level.getGameTime();
        synchronized (LAST_NO_CONTACT_REFRESH) {
            Long previous = LAST_NO_CONTACT_REFRESH.get(level);
            if (previous != null && now - previous < NO_CONTACT_REFRESH_INTERVAL_TICKS) return;
            LAST_NO_CONTACT_REFRESH.put(level, now);
        }
        if (!SynaxisNoContactCompat.isAvailable()) return;
        if (owned == null) owned = ownedNoContacts(level);
        Set<BodyPair> desired = Set.of();
        if (hasBindings) {
            Set<UUID> liveBodyIds = new HashSet<>(discovery.bodies.keySet());
            for (Map.Entry<SwivelBearingBlockEntity, BoundState> entry : boundSnapshot()) {
                if (isLiveBinding(entry.getKey(), entry.getValue())
                        && rootServerLevel(entry.getKey().getLevel()) == level) {
                    liveBodyIds.add(entry.getValue().childId);
                    UUID parentId = containingBodyId(entry.getKey());
                    if (parentId != null) liveBodyIds.add(parentId);
                }
            }
            Map<UUID, Set<UUID>> weldGraph = SynaxisWeldCompat.weldedAdjacency(level);
            liveBodyIds.addAll(weldGraph.keySet());
            weldGraph.values().forEach(liveBodyIds::addAll);
            desired = desiredNoContacts(level, liveBodyIds, weldGraph);
        }
        int removed = 0;
        for (BodyPair pair : Set.copyOf(owned)) {
            if (desired.contains(pair)) {
                if (!SynaxisNoContactCompat.exists(level, pair.first, pair.second)) {
                    SynaxisNoContactCompat.put(level, pair.first, pair.second);
                }
                continue;
            }
            if (SynaxisNoContactCompat.remove(level, pair.first, pair.second)) {
                owned.remove(pair);
                writeOwnedNoContactTags(level, pair.first, owned);
                writeOwnedNoContactTags(level, pair.second, owned);
                removed++;
            }
        }

        int added = 0;
        for (BodyPair pair : desired) {
            if (owned.contains(pair)) {
                continue;
            }
            if (SynaxisNoContactCompat.exists(level, pair.first, pair.second)) {
                continue;
            }
            if (SynaxisNoContactCompat.put(level, pair.first, pair.second)) {
                owned.add(pair);
                writeOwnedNoContactTags(level, pair.first, owned);
                writeOwnedNoContactTags(level, pair.second, owned);
                added++;
            }
        }
        if (added > 0 || removed > 0) {
            StabilizedTurret.LOGGER.info(
                    "Reconciled {} turret no-contact relations in {}: +{}, -{}, active {}",
                    SynaxisNoContactCompat.backendName(), level.dimension().location(),
                    added, removed, owned.size());
        }
    }

    /**
     * Reconciles a completed blueprint placement immediately. No fixed
     * quarantine is used: if its controller has not ticked yet, the normal
     * end-of-tick pass applies the relation on the next tick.
     */
    public static void refreshNoContactsNow(ServerLevel level) {
        if (level == null) return;
        // Blueprint code may apply user data after allocating the body.
        collisionDiscovery(level).rescan();
        synchronized (LAST_NO_CONTACT_REFRESH) {
            LAST_NO_CONTACT_REFRESH.remove(level);
        }
        reconcileNoContacts(level);
    }

    private static boolean hasBindingState(ServerLevel level) {
        synchronized (BOUND) {
            for (var entry : BOUND.entrySet()) {
                if (rootServerLevel(entry.getKey().getLevel()) == level) return true;
            }
        }
        return false;
    }

    public static void onSubLevelContainerReady(Level level, SubLevelContainer container) {
        if (level instanceof ServerLevel root)
            COLLISION_DISCOVERY.computeIfAbsent(root, ignored -> new CollisionDiscovery(root, container));
    }

    private static CollisionDiscovery collisionDiscovery(ServerLevel level) {
        return COLLISION_DISCOVERY.computeIfAbsent(level,
                ignored -> new CollisionDiscovery(level, SubLevelContainer.getContainer(level)));
    }

    /** Discover saved ownership on body load, rather than scanning every world's bodies every tick. */
    private static final class CollisionDiscovery implements SubLevelObserver {
        final ServerLevel level;
        final SubLevelContainer container;
        final Map<UUID, ServerSubLevel> bodies = new HashMap<>();
        final Map<UUID, ServerSubLevel> pending = new HashMap<>();

        CollisionDiscovery(ServerLevel level, SubLevelContainer container) {
            this.level = level;
            this.container = container;
            container.addObserver(this);
            rescan();
        }

        void rescan() {
            for (SubLevel body : container.getAllSubLevels()) onSubLevelAdded(body);
        }

        @Override public void onSubLevelAdded(SubLevel body) {
            if (!(body instanceof ServerSubLevel serverBody)) return;
            bodies.put(body.getUniqueId(), serverBody);
            pending.put(body.getUniqueId(), serverBody);
            LAST_NO_CONTACT_REFRESH.remove(level);
        }

        @Override public void onSubLevelRemoved(SubLevel body,
                dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason reason) {
            bodies.remove(body.getUniqueId(), body);
            pending.remove(body.getUniqueId(), body);
            LAST_NO_CONTACT_REFRESH.remove(level);
        }

        void restoreSavedRelations() {
            if (pending.isEmpty()) return;
            for (ServerSubLevel body : pending.values()) {
                if (body.isRemoved()) continue;
                removeLegacyCollisionTags(body);
                CompoundTag tag = body.getUserDataTag();
                if (tag != null && tag.contains(OWNED_NO_CONTACT_PEERS_TAG, Tag.TAG_LIST)) {
                    loadOwnedNoContactTags(level, Set.of(body.getUniqueId()), ownedNoContacts(level));
                }
            }
            pending.clear();
        }
    }

    /**
     * Temporarily removes only Fire Control-owned Synaxis relations while a
     * blueprint is being exported.  Otherwise Photomancy's generic
     * no-contact sidecar would serialize runtime turret isolation as if the
     * player had authored those relations with its tool.
     */
    public static List<SuspendedNoContact> suspendOwnedNoContactsForBlueprint(
            ServerLevel level
    ) {
        if (level == null || !SynaxisNoContactCompat.isPhotomancyBackend()) {
            return List.of();
        }
        List<SuspendedNoContact> suspended = new ArrayList<>();
        for (BodyPair pair : Set.copyOf(ownedNoContacts(level))) {
            if (SynaxisNoContactCompat.exists(level, pair.first, pair.second)
                    && SynaxisNoContactCompat.remove(
                    level, pair.first, pair.second)) {
                suspended.add(new SuspendedNoContact(pair.first, pair.second));
            }
        }
        return List.copyOf(suspended);
    }

    public static void resumeOwnedNoContactsAfterBlueprint(
            ServerLevel level,
            List<SuspendedNoContact> suspended
    ) {
        if (level == null || suspended == null || suspended.isEmpty()) return;
        Set<UUID> liveBodies = SynaxisNoContactCompat.liveBodyIds(level);
        for (SuspendedNoContact relation : suspended) {
            if (!liveBodies.contains(relation.first)
                    || !liveBodies.contains(relation.second)
                    || SynaxisNoContactCompat.exists(
                    level, relation.first, relation.second)) {
                continue;
            }
            SynaxisNoContactCompat.put(level, relation.first, relation.second);
        }
    }

    private static Set<BodyPair> desiredNoContacts(
            ServerLevel level,
            Set<UUID> liveBodyIds,
            Map<UUID, Set<UUID>> weldGraph
    ) {
        Map<UUID, Set<UUID>> graph = new HashMap<>();
        for (Map.Entry<UUID, Set<UUID>> entry : weldGraph.entrySet()) {
            for (UUID neighbor : entry.getValue()) {
                addBodyEdge(graph, entry.getKey(), neighbor);
            }
        }

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        for (UUID parentId : liveBodyIds) {
            SubLevel candidate = container.getSubLevel(parentId);
            if (!(candidate instanceof ServerSubLevel parent) || parent.isRemoved()) continue;
            List<BlockEntitySubLevelActor> actors = new ArrayList<>();
            for (BlockEntitySubLevelActor actor : parent.getPlot().getBlockEntityActors()) {
                actors.add(actor);
            }
            for (BlockEntitySubLevelActor actor : actors) {
                if (actor instanceof SwivelBearingBlockEntity bearing
                        && bearing.isAssembled() && bearing.getSubLevelID() != null) {
                    addBodyEdge(graph, parentId, bearing.getSubLevelID());
                }
            }
        }

        List<BearingEdge> boundEdges = new ArrayList<>();
        for (Map.Entry<SwivelBearingBlockEntity, BoundState> entry : boundSnapshot()) {
            SwivelBearingBlockEntity bearing = entry.getKey();
            BoundState state = entry.getValue();
            if (!isLiveBinding(bearing, state)
                    || rootServerLevel(bearing.getLevel()) != level) {
                continue;
            }
            UUID parentId = containingBodyId(bearing);
            if (parentId == null || parentId.equals(state.childId)) continue;
            addBodyEdge(graph, parentId, state.childId);
            boundEdges.add(new BearingEdge(parentId, state.childId));
        }

        Set<BodyPair> desired = new HashSet<>();
        for (BearingEdge bearingEdge : boundEdges) {
            BodyPair blocked = BodyPair.of(bearingEdge.parent, bearingEdge.child);
            Set<UUID> childSide = connectedBodies(
                    bearingEdge.child, graph, blocked);
            Set<UUID> parentSide = connectedBodies(
                    bearingEdge.parent, graph, blocked);
            // An alternate physical path means this bearing does not divide
            // the assembly into a parent side and child side.  Never disable
            // contacts inside such a single connected component.
            if (!Collections.disjoint(childSide, parentSide)) continue;
            for (UUID child : childSide) {
                for (UUID parent : parentSide) {
                    if (!child.equals(parent)) desired.add(BodyPair.of(child, parent));
                }
            }
        }
        return Set.copyOf(desired);
    }

    private static Set<UUID> connectedBodies(
            UUID root,
            Map<UUID, Set<UUID>> graph,
            BodyPair blockedEdge
    ) {
        Set<UUID> result = new HashSet<>();
        ArrayDeque<UUID> pending = new ArrayDeque<>();
        result.add(root);
        pending.add(root);
        while (!pending.isEmpty()) {
            UUID current = pending.removeFirst();
            for (UUID neighbor : graph.getOrDefault(current, Set.of())) {
                if (BodyPair.of(current, neighbor).equals(blockedEdge)) continue;
                if (result.add(neighbor)) pending.addLast(neighbor);
            }
        }
        return result;
    }

    private static void addBodyEdge(
            Map<UUID, Set<UUID>> graph,
            UUID first,
            UUID second
    ) {
        if (first == null || second == null || first.equals(second)
                || isGround(first) || isGround(second)) return;
        graph.computeIfAbsent(first, ignored -> new HashSet<>()).add(second);
        graph.computeIfAbsent(second, ignored -> new HashSet<>()).add(first);
    }

    private static UUID containingBodyId(SwivelBearingBlockEntity bearing) {
        SubLevel containing = Sable.HELPER.getContaining(bearing);
        if (containing == null || isGround(containing.getUniqueId())) return null;
        return containing.getUniqueId();
    }

    private static boolean isGround(UUID id) {
        return id == null
                || (id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() == 0L);
    }

    private static Set<BodyPair> ownedNoContacts(ServerLevel level) {
        synchronized (OWNED_NO_CONTACTS) {
            return OWNED_NO_CONTACTS.computeIfAbsent(level, ignored -> new HashSet<>());
        }
    }

    private static void loadOwnedNoContactTags(
            ServerLevel level,
            Set<UUID> liveBodyIds,
            Set<BodyPair> owned
    ) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        for (UUID bodyId : liveBodyIds) {
            SubLevel candidate = container.getSubLevel(bodyId);
            if (!(candidate instanceof ServerSubLevel body) || body.isRemoved()) continue;
            CompoundTag tag = body.getUserDataTag();
            if (tag == null) continue;
            for (Tag value : tag.getList(OWNED_NO_CONTACT_PEERS_TAG, Tag.TAG_COMPOUND)) {
                if (value instanceof CompoundTag peerTag && peerTag.hasUUID("Peer")) {
                    UUID peer = peerTag.getUUID("Peer");
                    if (!bodyId.equals(peer) && !isGround(peer)) {
                        owned.add(BodyPair.of(bodyId, peer));
                    }
                }
            }
        }
    }

    private static void writeOwnedNoContactTags(
            ServerLevel level,
            UUID bodyId,
            Set<BodyPair> owned
    ) {
        SubLevel candidate = SubLevelContainer.getContainer(level).getSubLevel(bodyId);
        if (!(candidate instanceof ServerSubLevel body) || body.isRemoved()) return;
        CompoundTag tag = body.getUserDataTag();
        if (tag == null) tag = new CompoundTag();
        ListTag peers = new ListTag();
        for (BodyPair pair : owned) {
            UUID peer = pair.other(bodyId);
            if (peer == null) continue;
            CompoundTag peerTag = new CompoundTag();
            peerTag.putUUID("Peer", peer);
            peers.add(peerTag);
        }
        if (peers.isEmpty()) tag.remove(OWNED_NO_CONTACT_PEERS_TAG);
        else tag.put(OWNED_NO_CONTACT_PEERS_TAG, peers);
        body.setUserDataTag(tag);
    }

    private static void clearOwnedNoContacts(ServerLevel level) {
        Set<BodyPair> owned = ownedNoContacts(level);
        Set<UUID> liveBodyIds = SynaxisNoContactCompat.liveBodyIds(level);
        loadOwnedNoContactTags(level, liveBodyIds, owned);
        for (BodyPair pair : Set.copyOf(owned)) {
            if (SynaxisNoContactCompat.remove(level, pair.first, pair.second)) {
                owned.remove(pair);
                writeOwnedNoContactTags(level, pair.first, owned);
                writeOwnedNoContactTags(level, pair.second, owned);
            }
        }
    }

    /** Removes stale whole-body collision tags written by releases before this migration. */
    private static void migrateLegacyCollisionTags(
            ServerLevel level,
            Set<UUID> liveBodyIds
    ) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        for (UUID bodyId : liveBodyIds) {
            SubLevel candidate = container.getSubLevel(bodyId);
            if (!(candidate instanceof ServerSubLevel body) || body.isRemoved()) continue;
            removeLegacyCollisionTags(body);
        }
    }

    private static void removeLegacyCollisionTags(ServerSubLevel body) {
        CompoundTag tag = body.getUserDataTag();
        if (tag == null || !tag.getBoolean(LEGACY_OWNED_NO_COLLISION_TAG)) return;
        tag.remove(LEGACY_TOOLGUN_NO_COLLISION_TAG);
        tag.remove(LEGACY_OWNED_NO_COLLISION_TAG);
        body.setUserDataTag(tag);
    }

    private static Map<UUID, ServerSubLevel> collectLiveChildren(ServerLevel physicsLevel) {
        // Only the automatic-defense native-servo path keeps its existing gravity override.
        // Expanding a Synaxis weld graph here runs for every
        // physics substep and turns one aimed turret into work proportional to
        // every welded sublevel. The direct child still carries its welded
        // assembly through Sable's own constraints.
        return collectLiveDirectChildren(physicsLevel);
    }

    private static Map<UUID, ServerSubLevel> collectLiveDirectChildren(ServerLevel physicsLevel) {
        Map<UUID, ServerSubLevel> result = new HashMap<>();
        for (Map.Entry<SwivelBearingBlockEntity, BoundState> entry : boundSnapshot()) {
            SwivelBearingBlockEntity bearing = entry.getKey();
            BoundState state = entry.getValue();
            Level bearingLevel = bearing.getLevel();

            boolean stale = !isLiveBinding(bearing, state);
            if (stale) {
                BOUND.remove(bearing);
                LegacyBearingStabilizer.disable(bearing);
                LEGACY_BEARINGS.remove(bearing);
                releasePhysicalServo(bearing, !bearing.isRemoved());
                removeConstrained(bearing);
                continue;
            }
            if (rootServerLevel(bearingLevel) != physicsLevel) {
                continue;
            }

            SubLevel attached = SubLevelContainer.getContainer(bearingLevel).getSubLevel(state.childId);
            if (attached instanceof ServerSubLevel child && !child.isRemoved()) {
                if (!state.decouplePhysics || usesLegacyImplementation(bearing)) {
                    suppressMotor(bearing);
                    result.put(state.childId, child);
                }
                else clearOwnedGravity(child);
            } else {
                BOUND.remove(bearing);
                releasePhysicalServo(bearing, false);
            }
        }

        return result;
    }

    private static void clearOwnedGravity(ServerSubLevel child) {
        GravityState previous = GRAVITY_OVERRIDDEN.remove(child.getUniqueId());
        if (previous != null) previous.restoreTags();
        // Migrate saved overrides from the old stabilizer, preserving third-party tags.
        CompoundTag tag = child.getUserDataTag();
        if (tag != null && tag.getBoolean(GravityState.OWNED_NO_GRAVITY_TAG)) {
            tag.remove(GravityState.NO_GRAVITY_TAG);
            tag.remove(GravityState.OWNED_NO_GRAVITY_TAG);
            child.setUserDataTag(tag);
        }
    }

    private static boolean isLiveBinding(SwivelBearingBlockEntity bearing, BoundState state) {
        Level bearingLevel = bearing.getLevel();
        return bearingLevel != null
                && bearingLevel == state.level
                && bearingLevel.getGameTime() - state.lastHeartbeat <= HEARTBEAT_TOLERANCE_TICKS
                && !bearing.isRemoved()
                && bearing.isAssembled()
                && state.childId.equals(bearing.getSubLevelID());
    }

    private static void cancelGravity(
            ServerLevel level,
            PhysicsPipeline pipeline,
            ServerSubLevel child,
            double timeStep
    ) {
        var massData = child.getMassTracker();
        if (massData == null || massData.getCenterOfMass() == null) {
            return;
        }
        Vector3d localImpulse = DimensionPhysicsData
                .getGravity(level, child.logicalPose().position())
                .mul(-massData.getMass() * timeStep);
        child.logicalPose().orientation().transformInverse(localImpulse);
        pipeline.applyLinearAndAngularImpulse(child, localImpulse, new Vector3d(), false);
    }

    private static Quaterniond parentOrientation(SwivelBearingBlockEntity bearing, Quaterniond destination) {
        SubLevel containing = Sable.HELPER.getContaining(bearing);
        return containing == null
                ? destination.identity()
                : destination.set(containing.logicalPose().orientation()).normalize();
    }

    private static ServerLevel rootServerLevel(Level level) {
        if (level == null) return null;
        Level root = SubLevelContainer.getContainer(level).getLevel();
        return root instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    private static Vector3d worldAxis(
            SwivelBearingBlockEntity bearing,
            Vector3d destination,
            Quaterniond parentOverride
    ) {
        Direction facing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
        destination.set(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        Quaterniond parent = parentOverride == null
                ? parentOrientation(bearing, new Quaterniond())
                : parentOverride;
        return parent.transform(destination).normalize();
    }

    private static Set<Map.Entry<SwivelBearingBlockEntity, BoundState>> boundSnapshot() {
        synchronized (BOUND) {
            if (BOUND.isEmpty()) return Set.of();
            return Set.copyOf(BOUND.entrySet());
        }
    }

    private static Set<Map.Entry<SwivelBearingBlockEntity, AxisLock>> activeSnapshot() {
        synchronized (ACTIVE) {
            if (ACTIVE.isEmpty()) return Set.of();
            return Set.copyOf(ACTIVE.entrySet());
        }
    }

    private static Set<Map.Entry<UUID, GravityState>> gravitySnapshot() {
        synchronized (GRAVITY_OVERRIDDEN) {
            if (GRAVITY_OVERRIDDEN.isEmpty()) return Set.of();
            return Set.copyOf(GRAVITY_OVERRIDDEN.entrySet());
        }
    }

    private BearingStabilizer() {
    }

    /** Captures the neutral angle at the end of Simulated's assembly call. */
    public static void captureAssemblyZero(
            SwivelBearingBlockEntity bearing) {
        LegacyBearingStabilizer.captureAssemblyZero(bearing);
        if (bearing == null || !bearing.isAssembled()
                || bearing.getSubLevelID() == null) return;
        double assemblyAngle = physicalJointAngle(bearing);
        if (!Double.isFinite(assemblyAngle)) {
            assemblyAngle = bearing.getTargetAngleDegrees();
        }
        ASSEMBLY_ZEROS.put(
                bearing,
                new AssemblyZero(
                        bearing.getSubLevelID(),
                        assemblyAngle));
        CompoundTag persistent = bearing.getPersistentData();
        persistent.putDouble(ASSEMBLY_ZERO_TAG, assemblyAngle);
        Vector3d assemblyPivot = new Vector3d();
        if (resolveSimulatedAssemblyPivot(bearing, assemblyPivot)) {
            ASSEMBLY_PIVOTS.put(bearing, new AssemblyPivot(
                    bearing.getSubLevelID(), new Vector3d(assemblyPivot)));
            writeAssemblyPivot(persistent, assemblyPivot);
        }
        bearing.setChanged();
        ConstrainedServo servo = CONSTRAINED.get(bearing);
        if (servo != null) {
            servo.zeroAngle = assemblyAngle;
        }
    }

    public static void forgetAssemblyZero(
            SwivelBearingBlockEntity bearing) {
        LegacyBearingStabilizer.disable(bearing);
        LegacyBearingStabilizer.forgetAssemblyZero(bearing);
        LEGACY_BEARINGS.remove(bearing);
        YAW_LIMITS.remove(bearing);
        BOUND.remove(bearing);
        releasePhysicalServo(bearing, false);
        ASSEMBLY_ZEROS.remove(bearing);
        ASSEMBLY_PIVOTS.remove(bearing);
        if (bearing != null) {
            CompoundTag persistent = bearing.getPersistentData();
            persistent.remove(ASSEMBLY_ZERO_TAG);
            persistent.remove(ASSEMBLY_PIVOT_X_TAG);
            persistent.remove(ASSEMBLY_PIVOT_Y_TAG);
            persistent.remove(ASSEMBLY_PIVOT_Z_TAG);
            bearing.setChanged();
        }
        removeConstrained(bearing);
    }

    /** Mechanical home shared with the scope's vehicle-heading display. */
    public static double getAssemblyZeroDegrees(SwivelBearingBlockEntity bearing) {
        return assemblyZeroOrCurrent(bearing);
    }

    private static double assemblyZeroOrCurrent(
            SwivelBearingBlockEntity bearing) {
        AssemblyZero zero = ASSEMBLY_ZEROS.get(bearing);
        UUID child = bearing.getSubLevelID();
        if (zero != null && child != null && child.equals(zero.childId)) {
            return zero.angle;
        }
        CompoundTag persistent = bearing.getPersistentData();
        if (persistent.contains(ASSEMBLY_ZERO_TAG)) {
            double persisted = persistent.getDouble(ASSEMBLY_ZERO_TAG);
            if (Double.isFinite(persisted)) {
                if (child != null) {
                    ASSEMBLY_ZEROS.put(bearing, new AssemblyZero(child, persisted));
                }
                return persisted;
            }
        }
        // Legacy saves predate the persistent assembly reference. Simulated
        // creates a swivel joint at its mechanical 0-degree pose; adopting
        // the saved target here would redefine home to wherever the turret
        // happened to point when the world was closed.
        double migrated = 0.0;
        persistent.putDouble(ASSEMBLY_ZERO_TAG, migrated);
        if (child != null) {
            ASSEMBLY_ZEROS.put(bearing, new AssemblyZero(child, migrated));
        }
        bearing.setChanged();
        return migrated;
    }

    private static boolean resolveSimulatedAssemblyPivot(
            SwivelBearingBlockEntity bearing,
            Vector3d destination) {
        Level level = bearing.getLevel();
        BlockPos platePos = bearing.getPlatePos();
        if (level == null || platePos == null) {
            return false;
        }
        BlockState plateState = level.getBlockState(platePos);
        if (!plateState.hasProperty(SwivelBearingPlateBlock.FACING)) {
            return false;
        }
        BearingAssemblyPosition.childConstraintPivot(
                platePos,
                plateState.getValue(SwivelBearingPlateBlock.FACING),
                destination);
        return true;
    }

    private static void writeAssemblyPivot(
            CompoundTag persistent,
            Vector3d pivot) {
        persistent.putDouble(ASSEMBLY_PIVOT_X_TAG, pivot.x);
        persistent.putDouble(ASSEMBLY_PIVOT_Y_TAG, pivot.y);
        persistent.putDouble(ASSEMBLY_PIVOT_Z_TAG, pivot.z);
    }

    private static boolean isFinite(Vector3d vector) {
        return Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    private static boolean isFinite(Quaterniond quaternion) {
        return Double.isFinite(quaternion.x)
                && Double.isFinite(quaternion.y)
                && Double.isFinite(quaternion.z)
                && Double.isFinite(quaternion.w);
    }

    private static double physicalJointAngle(
            SwivelBearingBlockEntity bearing) {
        Level level = bearing.getLevel();
        UUID childId = bearing.getSubLevelID();
        BlockPos platePos = bearing.getPlatePos();
        if (level == null || childId == null || platePos == null) {
            return Double.NaN;
        }
        SubLevel child = SubLevelContainer.getContainer(level).getSubLevel(childId);
        if (child == null || child.isRemoved()) return Double.NaN;
        return physicalJointAngle(bearing, child.logicalPose().orientation());
    }

    private static double physicalJointAngle(
            SwivelBearingBlockEntity bearing,
            Quaterniond childOrientation) {
        Level level = bearing.getLevel();
        BlockPos platePos = bearing.getPlatePos();
        if (level == null || platePos == null || childOrientation == null) {
            return Double.NaN;
        }
        BlockState plateState = level.getBlockState(platePos);
        if (!plateState.hasProperty(SwivelBearingPlateBlock.FACING)) {
            return Double.NaN;
        }
        Direction bearingFacing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
        Direction plateFacing = plateState.getValue(SwivelBearingPlateBlock.FACING);
        Quaterniond parentFrame = parentOrientation(bearing, new Quaterniond())
                .mul(new Quaterniond(bearingFacing.getRotation()))
                .normalize();
        Quaterniond childFrame = new Quaterniond(childOrientation)
                .mul(new Quaterniond(plateFacing.getRotation()))
                .normalize();
        Quaterniond relative = parentFrame.conjugate().mul(childFrame).normalize();
        return Mth.wrapDegrees(-2.0 * Math.toDegrees(
                Math.atan2(-relative.y, relative.w)));
    }

    /** Returns +1 when positive joint angle raises the measured barrel. */
    private static double jointToElevationSign(
            SwivelBearingBlockEntity bearing,
            Vector3d measuredWorldDirection,
            Vector3d worldAxis) {
        Level level = bearing.getLevel();
        UUID childId = bearing.getSubLevelID();
        if (level == null || childId == null) return 1.0;
        SubLevel child = SubLevelContainer.getContainer(level).getSubLevel(childId);
        if (child == null || child.isRemoved()) return 1.0;

        double currentJoint = physicalJointAngle(bearing);
        if (!Double.isFinite(currentJoint)) return 1.0;
        double probeDegrees = 0.1;
        Quaterniond probeOrientation = new Quaterniond().rotationAxis(
                        Math.toRadians(probeDegrees),
                        worldAxis.x, worldAxis.y, worldAxis.z)
                .mul(new Quaterniond(child.logicalPose().orientation()))
                .normalize();
        double probeJoint = physicalJointAngle(bearing, probeOrientation);
        if (!Double.isFinite(probeJoint)) return 1.0;
        double jointDerivative = Mth.wrapDegrees(probeJoint - currentJoint);

        Vector3d parentUp = parentOrientation(bearing, new Quaterniond())
                .transform(new Vector3d(0.0, 1.0, 0.0))
                .normalize();
        double elevationDerivative = new Vector3d(worldAxis)
                .cross(new Vector3d(measuredWorldDirection))
                .dot(parentUp);
        if (Math.abs(jointDerivative) < 1.0e-7
                || Math.abs(elevationDerivative) < 1.0e-7) {
            return 1.0;
        }
        return jointDerivative * elevationDerivative > 0.0 ? 1.0 : -1.0;
    }

    private static void removeConstrained(SwivelBearingBlockEntity bearing) {
        ConstrainedServo servo=CONSTRAINED.remove(bearing);
        if(servo!=null&&!bearing.isRemoved()){
            ((SwivelBearingBlockEntityAccessor)bearing).stabilizedTurret$getLockedDefaultOption()
                    .setValue(servo.originalLockMode);
        }
    }

    private static final class ConstrainedServo {
        double zeroAngle;
        boolean yawAxis;
        final int originalLockMode;
        double depression = 20.0;
        double elevation = 45.0;
        boolean aimSettled;
        ConstrainedServo(SwivelBearingBlockEntity bearing, boolean yawAxis) {
            this.originalLockMode=((SwivelBearingBlockEntityAccessor)bearing)
                    .stabilizedTurret$getLockedDefaultOption().getValue();
            reset(assemblyZeroOrCurrent(bearing), yawAxis);
        }
        void reset(double zeroAngle, boolean yawAxis) {
            this.zeroAngle = zeroAngle;
            this.yawAxis = yawAxis;
            this.aimSettled = false;
        }
    }

    private record AssemblyZero(UUID childId, double angle) {}

    private record AssemblyPivot(UUID childId, Vector3d localPivot) {}

    private static final class AxisLock {
        private final Quaterniond targetOrientation = new Quaterniond();
        private final Quaterniond lastParentOrientation = new Quaterniond();
        private final Vector3d childLocalPivot = new Vector3d();
        private final Vector3d localOffset = new Vector3d();
        /**
         * The measured cannon forward vector expressed in the attached
         * child's local frame.  Unlike a raw bearing target angle, this frame
         * has the same elevation meaning for left- and right-side bearings.
         */
        private final Vector3d elevationLocalForward = new Vector3d(0.0, 0.0, 1.0);
        private UUID childId;
        private boolean initialized;
        private NativeBearingJoint joint;
        private ServerSubLevel jointParent;
        private ServerSubLevel jointChild;
        private ServerSubLevel jointActuationChild;
        private final Quaterniond actuationFromChild = new Quaterniond();
        private final Vector3d actuationLocalPivot = new Vector3d();
        private boolean driveSelectionDirty = true;
        private BlockPos jointBearingPos;
        private Direction jointBearingFacing;
        private Direction jointPlateFacing;
        private final Vector3d parentLocalAnchor = new Vector3d();
        private final BearingServoMath.MotorTarget motorTarget = new BearingServoMath.MotorTarget();
        private final BearingServoMath.MotorResidual motorResidual = new BearingServoMath.MotorResidual();
        private final BearingServoMath.AimVelocity aimVelocity = new BearingServoMath.AimVelocity();
        private boolean externalStaffMode;
        private ServoStep servoStep;
        private final BearingServoMath.OperatorMotion operatorMotion = new BearingServoMath.OperatorMotion();
        private final BearingServoMath.ParentTraverse parentTraverse = new BearingServoMath.ParentTraverse();
        private boolean staffYielding;
        private boolean staffParentHolding;
        private boolean staffYawReferenceInitialized;
        private final Vector3d staffYawLocalRay=new Vector3d();
        private final Quaterniond staffAttachmentChildFrame=new Quaterniond();
        private double aimLandingRemaining;
        private boolean diagnosticParentTraverse;
        private double diagnosticTraverseParentRate;
        private double diagnosticWorldTraverseRate;
        private final BearingServoMath.PitchStops pitchStops = new BearingServoMath.PitchStops();
        private final BearingServoMath.YawStops yawStops = new BearingServoMath.YawStops();
        private YawLimits yawLimits = YawLimits.UNLIMITED;
        private final Vector3d aimLocalForward = new Vector3d();
        private UUID directionSourceId;
        private final Quaterniond childFromDirectionSource = new Quaterniond();
        private final Vector3d directionSourceLocalForward = new Vector3d();
        private List<LocalAzimuthObservation> azimuthObservations = List.of();
        private final Vector3d requestedAimDirection = new Vector3d();
        private final BearingServoMath.ParentAimTarget parentAimTarget = new BearingServoMath.ParentAimTarget();
        private final Vector3d heldAimDirection = new Vector3d();
        private final AimCompensation sightCompensation = new AimCompensation();
        private List<FiniteAimTarget> finiteTargets = List.of();
        private Vec3 finiteOperatorDirection;
        private boolean finiteCompensationActive;
        private double finiteModeRecoveryRemaining;
        private boolean directionReferenceInitialized;
        private boolean directionReferenceMeasured;
        private double mechanicalDegreesPerSecond;
        private long lastAimCommandTick = Long.MIN_VALUE;
        private String lastServoPlanning="unplanned";
        private double maxDegreesPerSecond;
        private double nativeZeroDegrees;
        private double elevationAmplitude = 1.0;
        private double lastNativeZeroDegrees = Double.NaN;
        private boolean yawAxis;
        private boolean stabilizeWorld = true;
        private boolean elevationFrameInitialized;
        private double jointToElevationSign = 1.0;
        private double depressionLimitDegrees = 90.0;
        private double elevationLimitDegrees = 90.0;
        private double zeroJointAngleDegrees;
        private long lastHeartbeat = Long.MIN_VALUE;
        private boolean aimInputWasLive;

        private void reset() {
            childId = null;
            initialized = false;
            elevationFrameInitialized = false;
            jointToElevationSign = 1.0;
            zeroJointAngleDegrees = 0.0;
            nativeZeroDegrees = 0.0;
            elevationAmplitude = 1.0;
            pitchStops.reset();
            yawStops.reset();
            motorTarget.reset();
            motorResidual.reset();
            aimVelocity.reset();
            externalStaffMode = false;
            servoStep = null;
            finiteTargets = List.of();
            finiteOperatorDirection = null;
            finiteCompensationActive = false;
            finiteModeRecoveryRemaining = 0.0;
            sightCompensation.reset();
            operatorMotion.reset();
            parentTraverse.reset();
            staffYielding = false;
            staffParentHolding = false;
            staffYawReferenceInitialized = false;
            aimLandingRemaining = 0.0;
            lastAimCommandTick = Long.MIN_VALUE;
            directionReferenceInitialized = false;
            directionReferenceMeasured = false;
            directionSourceId = null;
            azimuthObservations = List.of();
        }

        private void setLimits(double depression, double elevation) {
            depressionLimitDegrees = Mth.clamp(depression, 0.0, 90.0);
            elevationLimitDegrees = Mth.clamp(elevation, 0.0, 90.0);
        }

        private void setStabilization(SwivelBearingBlockEntity bearing, boolean enabled) {
            if (stabilizeWorld == enabled) return;
            stabilizeWorld = enabled;
            StabilizedTurret.LOGGER.info("Changed fire-control self-stabilization: bearing={} enabled={} reactionEnabled=true",
                    bearing.getBlockPos(), enabled);
            if (!initialized) return;
            SubLevel child = SubLevelContainer.getContainer(bearing.getLevel()).getSubLevel(childId);
            if (child != null && !child.isRemoved()) {
                targetOrientation.set(child.logicalPose().orientation());
                if (directionReferenceInitialized) targetOrientation.transform(aimLocalForward, heldAimDirection).normalize();
            }
            parentOrientation(bearing, lastParentOrientation);
            requestedAimDirection.set(heldAimDirection);
            parentTraverse.reset();
            if (directionReferenceInitialized) parentAimTarget.set(requestedAimDirection, lastParentOrientation);
            finiteTargets = List.of();
            finiteOperatorDirection = null;
            finiteCompensationActive = false;
            finiteModeRecoveryRemaining = 0.0;
            lastAimCommandTick = Long.MIN_VALUE;
            operatorMotion.reset();
            motorTarget.reset();
            motorResidual.reset();
            aimVelocity.reset();
            aimLandingRemaining = 0.0;
            servoStep = null;
        }

        private void setLocalOffset(Vec3 offset) {
            localOffset.set(offset.x, offset.y, offset.z);
        }

        private boolean initialize(SwivelBearingBlockEntity bearing) {
            UUID attachedId = bearing.getSubLevelID();
            Level level = bearing.getLevel();
            if (attachedId == null || level == null) {
                releaseJoint(bearing, false);
                reset();
                return false;
            }

            SubLevel attached = SubLevelContainer.getContainer(level).getSubLevel(attachedId);
            if (!(attached instanceof ServerSubLevel child) || child.isRemoved()) {
                releaseJoint(bearing, false);
                reset();
                return false;
            }
            var massData = child.getMassTracker();
            if (massData == null || massData.getCenterOfMass() == null) {
                return false;
            }
            if (initialized && attachedId.equals(childId)) {
                return true;
            }
            releaseJoint(bearing, false);

            BlockPos platePos = bearing.getPlatePos();
            if (platePos == null) {
                return false;
            }
            BlockState plateState = level.getBlockState(platePos);
            if (!plateState.hasProperty(SwivelBearingPlateBlock.FACING)) {
                return false;
            }

            Direction plateFacing = plateState.getValue(SwivelBearingPlateBlock.FACING);
            Pose3d pose = child.logicalPose();
            Vector3d freshAssemblyPivot = new Vector3d();
            if (resolveSimulatedAssemblyPivot(bearing, freshAssemblyPivot)) {
                childLocalPivot.set(freshAssemblyPivot);
                ASSEMBLY_PIVOTS.put(bearing,
                        new AssemblyPivot(attachedId, new Vector3d(freshAssemblyPivot)));
                writeAssemblyPivot(bearing.getPersistentData(), freshAssemblyPivot);
                bearing.setChanged();
            } else {
                return false;
            }
            double initialJointAngle = canonicalJointAngleDegrees(bearing, pose.orientation());
            targetOrientation.set(orientationForJointAngle(bearing, plateFacing, initialJointAngle));
            parentOrientation(bearing, lastParentOrientation);
            zeroJointAngleDegrees = assemblyZeroOrCurrent(bearing);
            nativeZeroDegrees = yawAxis ? 0.0 : zeroJointAngleDegrees;
            lastNativeZeroDegrees = Double.NaN;
            childId = attachedId;
            initialized = true;
            elevationFrameInitialized = false;
            jointToElevationSign = 1.0;
            // A welded blueprint restores its bearings before its cannon entity can
            // supply a ray. Hold the current physical bearing pose immediately;
            // an uncalibrated upstream motor must not disable downstream feedback.
            aimLocalForward.set(BearingServoMath.startupLocalRay(new Vector3d(
                    plateFacing.getStepX(), plateFacing.getStepY(), plateFacing.getStepZ())));
            pose.orientation().transform(aimLocalForward, heldAimDirection).normalize();
            requestedAimDirection.set(heldAimDirection);
            parentAimTarget.set(heldAimDirection, lastParentOrientation);
            directionReferenceInitialized = true;
            directionReferenceMeasured = false;
            return true;
        }

        private void calibrateElevationDirection(
                SwivelBearingBlockEntity bearing,
                Vec3 measuredWorldDirection
        ) {
            if (elevationFrameInitialized) {
                return;
            }
            Vector3d axis = worldAxis(bearing, new Vector3d(), null);
            Vector3d cannonForward = new Vector3d(
                    measuredWorldDirection.x,
                    measuredWorldDirection.y,
                    measuredWorldDirection.z
            ).normalize();
            SubLevel child = SubLevelContainer.getContainer(bearing.getLevel()).getSubLevel(childId);
            if (child == null || child.isRemoved()) return;
            Quaterniond actualOrientation = new Quaterniond(child.logicalPose().orientation());
            // Capture the actual barrel axis in child-local coordinates.
            // The physical elevation limiter can now evaluate the predicted
            // barrel direction after any candidate bearing rotation, without
            // assuming that positive joint angle always means elevation.
            if (directionSourceId != null) childFromDirectionSource.transform(directionSourceLocalForward, elevationLocalForward);
            else actualOrientation.transformInverse(cannonForward, elevationLocalForward);
            if (elevationLocalForward.lengthSquared() > 1.0e-8) {
                elevationLocalForward.normalize();
                elevationFrameInitialized = true;
            }
            Vector3d parentUp = parentOrientation(bearing, new Quaterniond())
                    .transform(new Vector3d(0.0, 1.0, 0.0))
                    .normalize();
            double verticalDerivative = new Vector3d(axis).cross(cannonForward).dot(parentUp);

            double probeDegrees = 0.1;
            double currentJoint = canonicalJointAngleDegrees(bearing, actualOrientation);
            double probeJoint = unwrapNear(
                    canonicalJointAngleDegrees(
                            bearing,
                            rotated(actualOrientation, axis, probeDegrees)
                    ),
                    currentJoint
            );
            double canonicalDerivative = probeJoint - currentJoint;
            if (Math.abs(verticalDerivative) > 1.0e-6 && Math.abs(canonicalDerivative) > 1.0e-6) {
                jointToElevationSign = verticalDerivative * canonicalDerivative > 0.0 ? 1.0 : -1.0;
            }
            // Center the native pitch coordinate on the barrel's horizontal pose.
            // This gives left/right bearings equivalent limits without a +/-pi seam.
            Vector3d atZero = orientationForJointAngle(bearing,
                    bearing.getLevel().getBlockState(bearing.getPlatePos())
                            .getValue(SwivelBearingPlateBlock.FACING), 0.0)
                    .transform(new Vector3d(elevationLocalForward));
            double a = atZero.dot(parentUp);
            double b = new Vector3d(axis).cross(atZero).dot(parentUp);
            elevationAmplitude = Math.hypot(a, b);
            if (elevationAmplitude > 1.0e-6 && Math.abs(axis.dot(parentUp)) < 1.0e-5) {
                double neutral = Math.toDegrees(Math.atan2(-a, b));
                if (jointToElevationSign < 0.0) neutral += 180.0;
                nativeZeroDegrees = unwrapNear(neutral, zeroJointAngleDegrees);
            }
        }

        private void aimAtDirection(
                SwivelBearingBlockEntity bearing,
                Vec3 measured,
                Vec3 requested, List<FiniteAimTarget> targets, Vec3 operatorDirection
        ) {
            aimInputWasLive=true;
            measureDirectionReference(bearing, measured);
            ServerLevel root=rootServerLevel(bearing.getLevel());
            UUID parentId=containingBodyId(bearing);
            if (!yawAxis && root!=null && parentId!=null && SynaxisWeldCompat.expandFixedWeldedSublevels(root,
                    SimulatedPhysicsStaffCompat.draggedBodies(root)).contains(parentId)) return;
            Vector3d next = new Vector3d(requested.x, requested.y, requested.z).normalize();
            next = finiteAxisDirection(bearing, targets, next);
            next=limitAimDirection(next,parentOrientation(bearing,new Quaterniond()));
            if (!stabilizeWorld) {
                // Manual aiming is sampled in parent coordinates. A physics
                // substep must not turn this request into carrier compensation.
                long now = bearing.getLevel().getGameTime();
                if (!BearingServoMath.liveAimCommand(lastAimCommandTick,now)) {
                    operatorMotion.reset();
                }
                // Head aim remains a live target even when the operator keeps
                // the view unchanged. All correction uses the manual ramp.
                parentAimTarget.set(next, parentOrientation(bearing, new Quaterniond()));
                sightCompensation.request(compensationAngle(bearing,
                        new Vector3d(requested.x, requested.y, requested.z),targets),false);
                requestedAimDirection.set(parentAimTarget.worldDirection(parentOrientation(bearing, new Quaterniond())));
                finiteTargets = List.of();
                finiteOperatorDirection = null;
                finiteCompensationActive = false;
                lastAimCommandTick = bearing.getLevel().getGameTime();
                return;
            }
            if (finiteTargets.isEmpty() != targets.isEmpty()) {
                // A new depth is a new operator goal on the existing trajectory.
                // Keep held pose, traverse velocity and measured braking history:
                // resetting them while the barrel is moving creates a one-step kick.
                motorResidual.reset();
                finiteCompensationActive=false;
                aimLandingRemaining=Math.max(aimLandingRemaining,0.15);
                finiteModeRecoveryRemaining=0.5;
            }
            boolean sameSources = finiteTargets.size() == targets.size() && !targets.isEmpty();
            boolean samePoint = sameSources;
            boolean sameSurface = sameSources;
            for (int i = 0; sameSources && i < targets.size(); i++) {
                sameSources = finiteTargets.get(i).sameSource(targets.get(i))
                        && finiteTargets.get(i).compensateCarrierVelocity() == targets.get(i).compensateCarrierVelocity()
                        && finiteTargets.get(i).fixedElevation() == targets.get(i).fixedElevation()
                        && finiteTargets.get(i).elevationCorrection() == targets.get(i).elevationCorrection()
                        && (finiteTargets.get(i).parallelSight() == null) == (targets.get(i).parallelSight() == null);
                samePoint &= finiteTargets.get(i).worldTarget().distanceToSqr(targets.get(i).worldTarget()) < 1e-12;
                MovingAimRay previous=finiteTargets.get(i).movingRay(),current=targets.get(i).movingRay();
                // Older/optical aim sources do not report a physical sight face.
                sameSurface &= previous==null && current==null || previous!=null && previous.sameSurface(current);
            }
            boolean sameInput = operatorDirection != null && finiteOperatorDirection != null
                    && operatorDirection.normalize().distanceToSqr(finiteOperatorDirection) < 1e-12;
            boolean parallel = !targets.isEmpty() && targets.stream().allMatch(target -> target.parallelSight() != null);
            boolean continuing = sameSources && (operatorDirection==null ? samePoint
                    : sameInput && (samePoint || sameSurface || parallel));
            sightCompensation.request(compensationAngle(bearing,
                    new Vector3d(requested.x, requested.y, requested.z),targets),continuing);
            if (continuing) {
                // Changes from moving the platform or resampling an unchanged view ray are automatic.
                shiftFiniteDirection(bearing,next);
            } else requestedAimDirection.set(next);
            if (!continuing) finiteCompensationActive = false;
            finiteTargets = List.copyOf(targets);
            finiteOperatorDirection = operatorDirection == null ? null : operatorDirection.normalize();
            lastAimCommandTick = bearing.getLevel().getGameTime();
        }

        private double compensationAngle(SwivelBearingBlockEntity bearing,Vector3d launch,
                                         List<FiniteAimTarget> targets) {
            if (targets.isEmpty() || targets.stream().noneMatch(target->target.profile()!=null
                    && target.compensateCarrierVelocity())) return 0;
            Vector3d geometric=new Vector3d();
            for (var target:targets) {
                Vec3 direction=target.currentRaisedSightDirection(bearing.getLevel());
                if (direction==null) return sightCompensation.acquired();
                geometric.add(direction.x,direction.y,direction.z);
            }
            if (geometric.lengthSquared()<1e-12) return 0;
            geometric=limitAimDirection(geometric.normalize(),parentOrientation(bearing,new Quaterniond()));
            return operatorError(bearing,geometric,launch);
        }

        private void shiftFiniteDirection(SwivelBearingBlockEntity bearing,Vector3d requested) {
            if (directionReferenceInitialized && requestedAimDirection.lengthSquared() > 1e-12) {
                Vector3d axis=worldAxis(bearing,new Vector3d(),null);
                double delta=operatorError(bearing,requestedAimDirection,requested);
                if (yawAxis) new Quaterniond().rotationAxis(delta,axis.x,axis.y,axis.z).transform(heldAimDirection).normalize();
                else heldAimDirection.set(advancePitchAim(bearing,heldAimDirection,requested,axis,delta));
                heldAimDirection.set(limitAimDirection(heldAimDirection,parentOrientation(bearing,new Quaterniond())));
            }
            requestedAimDirection.set(requested);
        }

        /** Solve the rotating muzzle, not a high-gain ray from its previous position. */
        private Vector3d finiteAxisDirection(SwivelBearingBlockEntity bearing, List<FiniteAimTarget> targets,
                                             Vector3d fallback) {
            if (targets.isEmpty()) return fallback;
            Level level = bearing.getLevel();
            SubLevel child = SubLevelContainer.getContainer(level).getSubLevel(childId);
            if (!(child instanceof ServerSubLevel serverChild) || child.isRemoved()) return fallback;
            Vector3d pivot = child.logicalPose().transformPosition(new Vector3d(childLocalPivot));
            Vector3d axis = worldAxis(bearing, new Vector3d(), null);
            Vector3d up = parentOrientation(bearing, new Quaterniond()).transform(new Vector3d(0, 1, 0));
            List<LegacyFiniteAimSolver.Ray> rays = new ArrayList<>();
            for (FiniteAimTarget target : targets) {
                SubLevel body = SubLevelContainer.getContainer(level).getSubLevel(target.bodyId());
                if (body == null || body.isRemoved()) return fallback;
                Vec3 muzzle = body.logicalPose().transformPosition(target.localMuzzle());
                Vector3d forwardVector = body.logicalPose().transformNormal(new Vector3d(
                        target.localForward().x, target.localForward().y, target.localForward().z));
                Vec3 forward = new Vec3(forwardVector.x, forwardVector.y, forwardVector.z).normalize();
                Vec3 point = target.movingRay() == null ? target.worldTarget() : target.movingRay().currentTarget(level);
                if (point == null) return fallback;
                Vec3 velocity = target.compensateCarrierVelocity() && target.profile() != null
                        ? stableMuzzleVelocity(level, target.bodyId(), muzzle, forward) : Vec3.ZERO;
                if (velocity == null) {
                    Vector3d sample = Sable.HELPER.getVelocity(level, body,
                            new Vector3d(target.localMuzzle().x, target.localMuzzle().y, target.localMuzzle().z), new Vector3d()).div(20);
                    velocity = new Vec3(sample.x, sample.y, sample.z);
                }
                java.util.function.DoubleFunction<Vec3> movingTarget = null;
                MovingAimRay sight = target.movingRay();
                if (sight != null) {
                    SubLevel camera = SubLevelContainer.getContainer(level).getSubLevel(sight.bodyId());
                    var chain = sight.bodyId().equals(childId) ? null : controlledBodyChain(level, sight.bodyId());
                    boolean movesWithAxis = sight.bodyId().equals(childId)
                            || chain != null && chain.axes().stream().anyMatch(entry -> entry.getValue() == this);
                    if (movesWithAxis && camera != null && !camera.isRemoved()) {
                        Vec3 cameraAnchor = camera.logicalPose().transformPosition(sight.localOrigin());
                        var surfaceBody = sight.physicalSurface() == null ? null
                                : SubLevelContainer.getContainer(level).getSubLevel(sight.physicalSurface().bodyId());
                        var surfacePose = surfaceBody == null || surfaceBody.isRemoved() ? null : surfaceBody.logicalPose();
                        movingTarget = angle -> {
                            Vector3d origin = new Quaterniond().rotationAxis(angle, axis.x, axis.y, axis.z)
                                    .transform(new Vector3d(cameraAnchor.x, cameraAnchor.y, cameraAnchor.z).sub(pivot))
                                    .add(pivot).add(sight.worldOffset().x, sight.worldOffset().y, sight.worldOffset().z);
                            return sight.intersect(origin, surfacePose);
                        };
                    }
                }
                rays.add(new LegacyFiniteAimSolver.Ray(muzzle, forward, point, target.profile(), velocity, movingTarget,
                        target.elevationCorrection(), target.fixedElevation(), target.parallelSight()));
            }
            double correction = LegacyFiniteAimSolver.correction(rays, new Vec3(pivot.x, pivot.y, pivot.z),
                    new Vec3(axis.x, axis.y, axis.z), new Vec3(up.x, up.y, up.z), yawAxis);
            if (!Double.isFinite(correction)) return fallback;
            Vector3d forward = observedForward(bearing, serverChild);
            return new Quaterniond().rotationAxis(correction, axis.x, axis.y, axis.z).transform(forward).normalize();
        }

        private Vector3d limitAimDirection(Vector3d direction,Quaterniond parentPose) {
            if (yawAxis) {
                Direction facing=jointBearingFacing==null?Direction.UP:jointBearingFacing;
                Vector3d axis=parentPose.transform(new Vector3d(facing.getStepX(),facing.getStepY(),facing.getStepZ())).normalize();
                Vector3d heading=new Vector3d(direction).sub(new Vector3d(axis).mul(direction.dot(axis)));
                // A nearly vertical sight has no reliable azimuth. Retain the last heading
                // instead of magnifying tiny hinge swing into alternating half-turns.
                if (heading.lengthSquared()>=1e-6) return yawLimits.limitDirection(direction, parentPose);
                Vector3d previous=new Vector3d(requestedAimDirection);
                heading.set(previous).sub(new Vector3d(axis).mul(previous.dot(axis)));
                if (heading.lengthSquared()>=1e-6) return yawLimits.limitDirection(previous.normalize(), parentPose);
                previous.set(heldAimDirection);
                heading.set(previous).sub(new Vector3d(axis).mul(previous.dot(axis)));
                return yawLimits.limitDirection(heading.lengthSquared()<1e-6
                        ? parentPose.transform(yawLimits.localCenter()) : previous.normalize(), parentPose);
            }
            Vector3d up=parentPose.transform(new Vector3d(0,1,0)).normalize();
            double vertical=Math.clamp(direction.dot(up),-1,1);
            double elevation=Math.asin(vertical);
            double bounded=Math.clamp(elevation,-Math.toRadians(depressionLimitDegrees),Math.toRadians(elevationLimitDegrees));
            if (Math.abs(elevation-bounded)<1e-12) return new Vector3d(direction);
            Vector3d horizontal=new Vector3d(direction).sub(new Vector3d(up).mul(vertical));
            if (horizontal.lengthSquared()<1e-12) horizontal=parentPose.transform(new Vector3d(0,0,1));
            return horizontal.normalize(Math.cos(bounded)).add(up.mul(Math.sin(bounded))).normalize();
        }

        private void resetFiniteModeHistory() {
            operatorMotion.reset();
            parentTraverse.reset();
            aimLandingRemaining=0.0;
            motorResidual.reset();
            motorTarget.reset();
            aimVelocity.reset();
            finiteModeRecoveryRemaining = 0.5;
        }

        private void refreshFiniteDirection(SwivelBearingBlockEntity bearing) {
            if (finiteTargets.isEmpty()) return;
            if (!BearingServoMath.liveAimCommand(lastAimCommandTick,bearing.getLevel().getGameTime()) || !stabilizeWorld) {
                resetFiniteModeHistory();
                finiteTargets = List.of();
                finiteOperatorDirection = null;
                finiteCompensationActive = false;
                return;
            }
            Vector3d requested = new Vector3d();
            for (FiniteAimTarget target : finiteTargets) {
                Vec3 direction = target.currentDirection(bearing.getLevel());
                if (direction == null) return;
                requested.add(direction.x, direction.y, direction.z);
            }
            if (requested.lengthSquared() < 1e-12) return;
            double compensation = compensationAngle(bearing, requested.normalize(), finiteTargets);
            requested = finiteAxisDirection(bearing, finiteTargets, requested.normalize());
            requested=limitAimDirection(requested.normalize(),parentOrientation(bearing,new Quaterniond()));
            // This refresh never changes the operator's selected target. Transport the
            // outstanding traverse along with the nominal ballistic reference, even
            // during acquisition, instead of continuously rearming operator braking.
            sightCompensation.request(compensation,true);
            shiftFiniteDirection(bearing,requested);
            if (!finiteCompensationActive) {
                if (finiteTrackingReady(bearing) && Math.abs(operatorMotion.velocity()) < 1e-6
                        && aimLandingRemaining <= 0.0) {
                    // Acquisition must finish, but its physical tracking error is
                    // what the finite inverse corrects. Requiring that error to be
                    // zero first prevents compensation from starting on a moving body.
                    finiteCompensationActive = true;
                }
            }
        }

        private boolean finiteTrackingReady(SwivelBearingBlockEntity bearing) {
            return !finiteTargets.isEmpty() && directionReferenceInitialized
                    && BearingServoMath.liveAimCommand(lastAimCommandTick,bearing.getLevel().getGameTime())
                    && Math.abs(operatorError(bearing,heldAimDirection,requestedAimDirection)) < 1e-5;
        }

        private void measureDirectionReference(SwivelBearingBlockEntity bearing, Vec3 measured) {
            // Once azimuth is calibrated, elevation motion must not redefine its
            // local heading. Each bearing retains its own feedback frame.
            if (yawAxis && directionReferenceMeasured) return;
            if (!yawAxis) calibrateElevationDirection(bearing, measured);
            SubLevel child = SubLevelContainer.getContainer(bearing.getLevel()).getSubLevel(childId);
            if (child == null || child.isRemoved()) return;
            Vector3d direction = new Vector3d(measured.x, measured.y, measured.z).normalize();
            if (yawAxis) {
                Vector3d axis = worldAxis(bearing, new Vector3d(), null);
                direction.sub(new Vector3d(axis).mul(direction.dot(axis)));
                if (direction.lengthSquared() < 1e-8) return;
            }
            if (directionSourceId != null && !yawAxis)
                childFromDirectionSource.transform(directionSourceLocalForward, aimLocalForward).normalize();
            else child.logicalPose().orientation().transformInverse(direction.normalize(), aimLocalForward).normalize();
            if (!directionReferenceMeasured) {
                targetOrientation.transform(aimLocalForward, heldAimDirection).normalize();
                requestedAimDirection.set(heldAimDirection);
                parentAimTarget.set(heldAimDirection, parentOrientation(bearing, new Quaterniond()));
                directionReferenceInitialized = true;
                directionReferenceMeasured = true;
                motorResidual.reset();
                aimVelocity.reset();
            }
        }

        private Vector3d observedForward(SwivelBearingBlockEntity bearing, ServerSubLevel child) {
            if (!yawAxis && directionSourceId != null) {
                SubLevel source = SubLevelContainer.getContainer(bearing.getLevel()).getSubLevel(directionSourceId);
                if (source != null && !source.isRemoved())
                    return source.logicalPose().orientation().transform(new Vector3d(directionSourceLocalForward)).normalize();
            }
            if (yawAxis && !azimuthObservations.isEmpty()) {
                Vector3d sum = new Vector3d();
                SubLevelContainer container = SubLevelContainer.getContainer(bearing.getLevel());
                for (LocalAzimuthObservation observation : azimuthObservations) {
                    SubLevel body = container.getSubLevel(observation.bodyId());
                    if (body == null || body.isRemoved()) return child.logicalPose().orientation().transform(new Vector3d(aimLocalForward));
                    Vec3 ray = observation.localDirection();
                    sum.add(body.logicalPose().orientation().transform(new Vector3d(ray.x,ray.y,ray.z)).normalize());
                }
                if (sum.isFinite() && sum.lengthSquared() > 1e-8) return sum.normalize();
            }
            return child.logicalPose().orientation().transform(new Vector3d(aimLocalForward));
        }

        private void rotateMechanically(SwivelBearingBlockEntity bearing, double angleDegrees) {
            if (Math.abs(angleDegrees) < 1.0e-8) {
                return;
            }
            Vector3d axis = worldAxis(bearing, new Vector3d(), null);
            rotateWithLimit(bearing, axis, angleDegrees);
        }

        private void rotateWithLimit(
                SwivelBearingBlockEntity bearing,
                Vector3d axis,
                double requestedDegrees
        ) {
            if (Math.abs(requestedDegrees) < 1.0e-8) {
                return;
            }
            Quaterniond candidate = rotated(targetOrientation, axis, requestedDegrees);
            if (yawAxis) {
                if (yawLimits.enabled()) {
                    Quaterniond parent = parentOrientation(bearing, new Quaterniond());
                    Vector3d forward = targetOrientation.transform(new Vector3d(aimLocalForward));
                    double sign = bearing.getBlockState().getValue(SwivelBearingBlock.FACING).getStepY() < 0 ? -1.0 : 1.0;
                    Vector3d bounded = yawLimits.advance(forward, parent, Math.toRadians(requestedDegrees), sign);
                    double applied = BearingServoMath.signedDirectionAngle(forward, bounded, axis);
                    targetOrientation.set(rotated(targetOrientation, axis, Math.toDegrees(applied)));
                } else targetOrientation.set(candidate);
                return;
            }

            double currentJoint = canonicalJointAngleDegrees(bearing, targetOrientation);
            double candidateJoint = unwrapNear(
                    canonicalJointAngleDegrees(bearing, candidate),
                    currentJoint
            );
            if (elevationFrameInitialized) {
                double candidateElevation = physicalElevationDegrees(bearing, candidate);
                if (candidateElevation >= -depressionLimitDegrees - 1.0e-8
                        && candidateElevation <= elevationLimitDegrees + 1.0e-8) {
                    targetOrientation.set(candidate);
                    return;
                }

                double currentElevation = physicalElevationDegrees(
                        bearing, targetOrientation);
                if (currentElevation >= -depressionLimitDegrees - 1.0e-8
                        && currentElevation <= elevationLimitDegrees + 1.0e-8) {
                    // A servo step is small and elevation is monotonic across
                    // it. Find the exact last legal orientation so head aim,
                    // stress input and stabilization all stop on the same
                    // target boundary. The native limit stops actual motion.
                    double low = 0.0;
                    double high = 1.0;
                    for (int i = 0; i < 28; i++) {
                        double middle = (low + high) * 0.5;
                        Quaterniond probe = rotated(
                                targetOrientation, axis,
                                requestedDegrees * middle);
                        double elevation = physicalElevationDegrees(
                                bearing, probe);
                        if (elevation >= -depressionLimitDegrees
                                && elevation <= elevationLimitDegrees) {
                            low = middle;
                        } else {
                            high = middle;
                        }
                    }
                    targetOrientation.set(rotated(
                            targetOrientation, axis,
                            requestedDegrees * low));
                }
                // If a previous version/configuration already left the barrel
                // outside the range, the physics-substep hard limiter below
                // brings it back instead of accepting a command that can move
                // it farther out.
                return;
            }
            double currentRelative = (unwrapNear(currentJoint, zeroJointAngleDegrees)
                    - zeroJointAngleDegrees) * jointToElevationSign;
            double candidateRelative = (unwrapNear(candidateJoint, zeroJointAngleDegrees)
                    - zeroJointAngleDegrees) * jointToElevationSign;
            double clampedRelative = Mth.clamp(
                    candidateRelative,
                    -depressionLimitDegrees,
                    elevationLimitDegrees
            );
            double jointDelta = candidateRelative - currentRelative;
            if (Math.abs(jointDelta) < 1.0e-9) {
                return;
            }
            double fraction = Mth.clamp(
                    (clampedRelative - currentRelative) / jointDelta,
                    0.0,
                    1.0
            );
            targetOrientation.set(rotated(targetOrientation, axis, requestedDegrees * fraction));
        }

        private static Quaterniond rotated(Quaterniond source, Vector3d axis, double angleDegrees) {
            Quaterniond delta = new Quaterniond().rotationAxis(
                    Math.toRadians(angleDegrees), axis.x, axis.y, axis.z
            );
            return delta.mul(new Quaterniond(source)).normalize();
        }

        private void inheritParentSwing(SwivelBearingBlockEntity bearing) {
            Quaterniond currentParent = parentOrientation(bearing, new Quaterniond());
            Quaterniond parentDelta = new Quaterniond(currentParent)
                    .mul(new Quaterniond(lastParentOrientation).conjugate()).normalize();
            Vector3d previousAxis = worldAxis(bearing, new Vector3d(), lastParentOrientation);

            double projected = parentDelta.x * previousAxis.x
                    + parentDelta.y * previousAxis.y
                    + parentDelta.z * previousAxis.z;
            Quaterniond twist = new Quaterniond(
                    previousAxis.x * projected,
                    previousAxis.y * projected,
                    previousAxis.z * projected,
                    parentDelta.w);
            if (twist.lengthSquared() < 1.0e-12) {
                twist.identity();
            } else {
                twist.normalize();
            }

            Quaterniond swing = new Quaterniond(parentDelta)
                    .mul(new Quaterniond(twist).conjugate()).normalize();
            swing.mul(targetOrientation, targetOrientation).normalize();
            lastParentOrientation.set(currentParent);
        }

        private void inheritFullParentRotation(SwivelBearingBlockEntity bearing) {
            Quaterniond currentParent = parentOrientation(bearing, new Quaterniond());
            Quaterniond parentDelta = new Quaterniond(currentParent)
                    .mul(new Quaterniond(lastParentOrientation).conjugate()).normalize();
            parentDelta.mul(targetOrientation, targetOrientation).normalize();
            lastParentOrientation.set(currentParent);
        }

        private void clampTargetPitch(SwivelBearingBlockEntity bearing) {
            if (yawAxis) {
                if (yawLimits.enabled()) {
                    Quaterniond parent = parentOrientation(bearing, new Quaterniond());
                    Vector3d forward = targetOrientation.transform(new Vector3d(aimLocalForward));
                    Vector3d bounded = yawLimits.limitDirection(forward, parent);
                    Vector3d axis = worldAxis(bearing, new Vector3d(), null);
                    targetOrientation.set(rotated(targetOrientation, axis,
                            Math.toDegrees(BearingServoMath.signedDirectionAngle(forward, bounded, axis))));
                    if (directionReferenceInitialized) heldAimDirection.set(yawLimits.limitDirection(heldAimDirection, parent));
                    // Center the fixed native hinge frame on the legal corridor, not the current pose.
                    Direction plateFacing = bearing.getLevel().getBlockState(bearing.getPlatePos())
                            .getValue(SwivelBearingPlateBlock.FACING);
                    Vector3d zeroForward = orientationForJointAngle(bearing, plateFacing, 0.0)
                            .transform(new Vector3d(aimLocalForward));
                    double sign = bearing.getBlockState().getValue(SwivelBearingBlock.FACING).getStepY() < 0 ? -1.0 : 1.0;
                    nativeZeroDegrees = yawLimits.nativeCenterDegrees(zeroForward, parent, sign);
                } else nativeZeroDegrees = 0.0;
                return;
            }
            if (elevationFrameInitialized) {
                // Clamp the actual predicted barrel elevation in the turret
                // parent's frame. This is side-independent and remains the
                // final authority after head aim, stabilization and kinetic
                // input have all run.
                for (int i = 0; i < 6; i++) {
                    double elevation = physicalElevationDegrees(
                            bearing, targetOrientation);
                    double clamped = Mth.clamp(
                            elevation,
                            -depressionLimitDegrees,
                            elevationLimitDegrees
                    );
                    double error = clamped - elevation;
                    if (Math.abs(error) <= 1.0e-7) {
                        break;
                    }
                    Vector3d axis = worldAxis(
                            bearing, new Vector3d(), null);
                    double probeDegrees = 0.25;
                    double positive = physicalElevationDegrees(
                            bearing,
                            rotated(targetOrientation, axis, probeDegrees));
                    double negative = physicalElevationDegrees(
                            bearing,
                            rotated(targetOrientation, axis, -probeDegrees));
                    double derivative = (positive - negative)
                            / (probeDegrees * 2.0);
                    if (Math.abs(derivative) < 1.0e-6) {
                        break;
                    }
                    double correction = Mth.clamp(
                            error / derivative, -45.0, 45.0);
                    targetOrientation.set(rotated(
                            targetOrientation, axis, correction));
                }
                return;
            }
            double currentJoint = canonicalJointAngleDegrees(bearing, targetOrientation);
            double relative = (unwrapNear(currentJoint, zeroJointAngleDegrees)
                    - zeroJointAngleDegrees) * jointToElevationSign;
            double clamped = Mth.clamp(
                    relative,
                    -depressionLimitDegrees,
                    elevationLimitDegrees
            );
            if (Math.abs(clamped - relative) > 1.0e-8) {
                Vector3d axis = worldAxis(bearing, new Vector3d(), null);
                double probeDegrees = 0.1;
                Quaterniond probe = rotated(targetOrientation, axis, probeDegrees);
                double probeJoint = unwrapNear(
                        canonicalJointAngleDegrees(bearing, probe),
                        currentJoint
                );
                double jointPerWorldDegree = (probeJoint - currentJoint) / probeDegrees;
                if (Math.abs(jointPerWorldDegree) > 1.0e-6) {
                    targetOrientation.set(rotated(
                            targetOrientation,
                            axis,
                            (clamped - relative) / (jointPerWorldDegree * jointToElevationSign)
                    ));
                }
            }
        }

        private double physicalElevationDegrees(
                SwivelBearingBlockEntity bearing,
                Quaterniond orientation
        ) {
            Vector3d forward = new Quaterniond(orientation)
                    .transform(new Vector3d(elevationLocalForward))
                    .normalize();
            Vector3d parentUp = parentOrientation(
                    bearing, new Quaterniond())
                    .transform(new Vector3d(0.0, 1.0, 0.0))
                    .normalize();
            return Math.toDegrees(Math.asin(Mth.clamp(
                    forward.dot(parentUp), -1.0, 1.0)));
        }

        private double canonicalJointAngleDegrees(
                SwivelBearingBlockEntity bearing,
                Quaterniond childOrientation
        ) {
            Direction bearingFacing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
            BlockPos platePos = bearing.getPlatePos();
            if (platePos == null) {
                return zeroJointAngleDegrees;
            }
            BlockState plateState = bearing.getLevel().getBlockState(platePos);
            if (!plateState.hasProperty(SwivelBearingPlateBlock.FACING)) {
                return zeroJointAngleDegrees;
            }
            Direction plateFacing = plateState.getValue(SwivelBearingPlateBlock.FACING);
            Quaterniond frameA = parentOrientation(bearing, new Quaterniond())
                    .mul(new Quaterniond(bearingFacing.getRotation()))
                    .normalize();
            Quaterniond frameB = new Quaterniond(childOrientation)
                    .mul(new Quaterniond(plateFacing.getRotation()))
                    .normalize();
            Quaterniond relative = new Quaterniond(frameA).conjugate().mul(frameB).normalize();
            double angle = -2.0 * Math.toDegrees(Math.atan2(-relative.y, relative.w));
            return Mth.wrapDegrees(angle);
        }

        private Quaterniond orientationForJointAngle(
                SwivelBearingBlockEntity bearing,
                Direction plateFacing,
                double jointAngleDegrees
        ) {
            Direction bearingFacing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
            Quaterniond parentFrame = parentOrientation(bearing, new Quaterniond())
                    .mul(new Quaterniond(bearingFacing.getRotation()))
                    .normalize();
            Quaterniond jointRotation = new Quaterniond().rotationY(
                    Math.toRadians(jointAngleDegrees)
            );
            Quaterniond inversePlateFrame = new Quaterniond(plateFacing.getRotation())
                    .conjugate();
            return parentFrame.mul(jointRotation).mul(inversePlateFrame).normalize();
        }

        private static double unwrapNear(double angle, double reference) {
            return reference + Mth.wrapDegrees(angle - reference);
        }

        private void drive(SwivelBearingBlockEntity bearing, SubLevelPhysicsSystem physicsSystem,
                           double timeStep, Set<UUID> staffDriven, Set<UUID> staffCarriers,
                           Map<UUID, Quaterniond> staffRotations, Map<UUID, Quaterniond> staffGoals) {
            servoStep = null;
            Level level = bearing.getLevel();
            if (!initialized || level == null
                    || level.getGameTime() - lastHeartbeat > HEARTBEAT_TOLERANCE_TICKS
                    || !isBound(bearing)) {
                releasePhysicalServo(bearing, !bearing.isRemoved());
                return;
            }
            // Physics callbacks run once for every loaded server
            // dimension. A lock belonging to another physics system is still
            // live; deleting it here makes the controller recreate it from
            // the already-offset pose next tick, turning a positional offset
            // into an endlessly accumulated velocity. Only the owning root
            // physics system may enforce this lock.
            if (rootServerLevel(level) != physicsSystem.getLevel()) {
                return;
            }
            SubLevel attached = SubLevelContainer.getContainer(level).getSubLevel(childId);
            if (!(attached instanceof ServerSubLevel child) || child.isRemoved()) {
                releasePhysicalServo(bearing, false);
                return;
            }

            SubLevel containing = Sable.HELPER.getContaining(bearing);
            ServerSubLevel parent = containing instanceof ServerSubLevel body ? body : null;
            if (parent == child) {
                releasePhysicalServo(bearing, false);
                return;
            }
            boolean merging = SimulatedMergingGlueCompat.isActivelyMerging(child)
                    || jointActuationChild != null && SimulatedMergingGlueCompat.isActivelyMerging(jointActuationChild)
                    || SimulatedMergingGlueCompat.isActivelyMerging(parent);
            if (merging) {
                if (joint != null && joint.isValid()) {
                    // Retain the native connection, but let Simulated's alignment motor work.
                    joint.relax();
                }
                parentOrientation(bearing, lastParentOrientation);
                targetOrientation.set(child.logicalPose().orientation());
                directionReferenceInitialized = false;
                directionReferenceMeasured = false;
                motorTarget.reset();
                motorResidual.reset();
                aimVelocity.reset();
                return;
            }

            if (joint != null && (jointParent != parent || jointChild != child)) {
                releaseJoint(bearing, false);
            }
            if (driveSelectionDirty && joint != null) {
                ServerSubLevel selected = selectDriveBody(child, parent);
                Quaterniond rotation = SableServoConstraints.fixedRotation(physicsSystem.getLevel(),
                        selected.getUniqueId(), childId);
                if (selected != jointActuationChild || rotation == null
                        || Math.abs(rotation.dot(actuationFromChild)) < 1.0 - 1e-12) releaseJoint(bearing, false);
            }
            driveSelectionDirty = false;
            if (joint == null || !joint.isValid() || !ownsJoint(bearing)) {
                if (!createJoint(bearing, physicsSystem, parent, child)) return;
            }

            boolean liveAim=BearingServoMath.liveAimCommand(lastAimCommandTick,level.getGameTime());
            if(aimInputWasLive&&!liveAim&&directionReferenceInitialized) {
                // Loss of input ends the unfinished traverse at the actual barrel.
                // Holding an old ahead-of-body trajectory can keep a stalled turret
                // rotating long after the last accepted mouse packet.
                targetOrientation.set(child.logicalPose().orientation());
                heldAimDirection.set(observedForward(bearing,child)).normalize();
                requestedAimDirection.set(heldAimDirection);
                parentAimTarget.set(heldAimDirection,parentOrientation(bearing,new Quaterniond()));
                resetFiniteModeHistory();
                finiteTargets=List.of();finiteOperatorDirection=null;finiteCompensationActive=false;
                sightCompensation.reset();
            }
            aimInputWasLive=liveAim;

            SubLevel carrier = aimCarrier(bearing);
            boolean externalStaff = carrier != null && staffCarriers.contains(carrier.getUniqueId());
            Quaterniond staffRotation = carrier == null ? null : staffRotations.get(carrier.getUniqueId());
            boolean staffForecast = externalStaff && staffRotation != null && stabilizeWorld && directionReferenceInitialized;
            if (externalStaff != externalStaffMode) {
                motorTarget.reset();
                motorResidual.reset();
                aimVelocity.reset();
                parentTraverse.reset();
                externalStaffMode = externalStaff;
            }
            staffYielding = staffDriven.contains(childId);
            if (!staffYielding) staffYawReferenceInitialized=false;
            if (staffYielding) {
                // Keep the hinge and physical reactions, but relinquish the driven twist.
                joint.relax();
                targetOrientation.set(child.logicalPose().orientation());
                parentOrientation(bearing, lastParentOrientation);
                if (directionReferenceInitialized) targetOrientation.transform(aimLocalForward, heldAimDirection).normalize();
                operatorMotion.reset();
                parentTraverse.reset();
                motorTarget.reset();
                motorResidual.reset();
                aimVelocity.reset();
                aimLandingRemaining = 0.0;
                finiteTargets=List.of(); finiteOperatorDirection=null; finiteCompensationActive=false;
                sightCompensation.reset();
                prepareStaffYieldStep(bearing,parent,child,carrier,staffGoals.get(childId),timeStep);
                return;
            }

            boolean parentGrabbed=!yawAxis && parent!=null && staffCarriers.contains(parent.getUniqueId());
            if (parentGrabbed!=staffParentHolding) {
                resetFiniteModeHistory();
                staffParentHolding=parentGrabbed;
                if (parentGrabbed) {
                    targetOrientation.set(child.logicalPose().orientation());
                    if (directionReferenceInitialized) heldAimDirection.set(observedForward(bearing,child)).normalize();
                    finiteTargets=List.of(); finiteOperatorDirection=null; finiteCompensationActive=false;
                    sightCompensation.reset();
                    lastAimCommandTick=Long.MIN_VALUE;
                }
            }

            refreshFiniteDirection(bearing);
            finiteModeRecoveryRemaining = Math.max(0.0, finiteModeRecoveryRemaining - timeStep);

            if (stabilizeWorld) {
                inheritParentSwing(bearing);
            } else {
                inheritFullParentRotation(bearing);
                if (BearingServoMath.liveAimCommand(lastAimCommandTick,level.getGameTime()))
                    requestedAimDirection.set(parentAimTarget.worldDirection(parentOrientation(bearing, new Quaterniond())));
            }
            projectTargetOntoJoint(bearing);
            boolean recoveringYaw = yawAxis && yawLimits.enabled() && yawStops.transitioning();
            if (recoveringYaw && directionReferenceInitialized) {
                // Keep the operator trajectory at the reachable boundary while
                // the outside body returns. Otherwise it runs ahead and the
                // first unrestricted servo step jumps to that stale trajectory.
                heldAimDirection.set(yawLimits.limitDirection(observedForward(bearing, child),
                        parentOrientation(bearing, new Quaterniond())));
                operatorMotion.reset();
            }
            double commandedDegrees = staffParentHolding || recoveringYaw ? 0.0 : mechanicalDegreesPerSecond * timeStep;
            double operatorVelocity = staffParentHolding || recoveringYaw ? 0.0 : Math.toRadians(mechanicalDegreesPerSecond);
            boolean parentRelativeTraverse = false;
            Vector3d compensatedAimDirection = new Vector3d(heldAimDirection);
            if (directionReferenceInitialized && stabilizeWorld) {
                Vector3d axis = worldAxis(bearing, new Vector3d(), null);
                if (yawAxis && yawLimits.enabled() || !yawAxis && (!finiteTargets.isEmpty() || BearingServoMath.liveAimCommand(lastAimCommandTick,level.getGameTime()))) {
                    Vector3d bounded=limitAimDirection(heldAimDirection,parentOrientation(bearing,new Quaterniond()));
                    if (bounded.distanceSquared(heldAimDirection)>1e-12) operatorMotion.reset();
                    heldAimDirection.set(bounded);
                }
                compensatedAimDirection.set(heldAimDirection);
                if (!recoveringYaw && BearingServoMath.liveAimCommand(lastAimCommandTick,level.getGameTime())) {
                    double error = operatorError(bearing, heldAimDirection, requestedAimDirection);
                    if (BearingServoMath.followParentDuringTraverse(error, Math.toRadians(maxDegreesPerSecond), timeStep)) {
                        Quaterniond currentParent = parentOrientation(bearing, new Quaterniond());
                        Vector3d carried = parentTraverse.carriedDirection(heldAimDirection, currentParent);
                        double inherited = BearingServoMath.boundParentTraverse(error,
                                operatorError(bearing, heldAimDirection, carried));
                        // During acquisition the trajectory belongs to the parent:
                        // 30 deg/s on a parent turning 20 deg/s means 50 deg/s in world.
                        if (yawAxis) new Quaterniond().rotationAxis(inherited, axis.x, axis.y, axis.z)
                                .transform(heldAimDirection).normalize();
                        else heldAimDirection.set(advancePitchAim(bearing, heldAimDirection,
                                requestedAimDirection, axis, inherited));
                        heldAimDirection.set(limitAimDirection(heldAimDirection, currentParent));
                        compensatedAimDirection.set(heldAimDirection);
                        error = operatorError(bearing, heldAimDirection, requestedAimDirection);
                        parentRelativeTraverse = Math.abs(error) > 1e-8;
                    }
                    double step = operatorMotion.advance(error, Math.toRadians(maxDegreesPerSecond), timeStep);
                    Vector3d next = yawAxis ? BearingServoMath.rotateAimDirection(heldAimDirection,
                            requestedAimDirection, axis, step)
                            : advancePitchAim(bearing,heldAimDirection,requestedAimDirection,axis,step);
                    commandedDegrees = Math.toDegrees(operatorError(bearing,heldAimDirection,next));
                    sightCompensation.advance(error,Math.toRadians(commandedDegrees));
                    operatorVelocity = operatorMotion.velocity();
                    heldAimDirection.set(next);
                    heldAimDirection.set(limitAimDirection(heldAimDirection,parentOrientation(bearing,new Quaterniond())));
                } else if (Math.abs(commandedDegrees) > 1e-8) {
                    operatorMotion.reset();
                    new Quaterniond().rotationAxis(Math.toRadians(commandedDegrees), axis.x, axis.y, axis.z).transform(heldAimDirection);
                } else {
                    operatorMotion.reset();
                }
                // The acquired ray stays in world coordinates; head acquisition
                // above follows the parent. Solve each axis without a second limiter.
                Vector3d forward = targetOrientation.transform(new Vector3d(aimLocalForward));
                double correction = Math.toDegrees(operatorError(bearing,forward,heldAimDirection));
                rotateMechanically(bearing, correction);
            } else if (!recoveringYaw && BearingServoMath.liveAimCommand(lastAimCommandTick,level.getGameTime())) {
                Vector3d forward = targetOrientation.transform(new Vector3d(aimLocalForward));
                double error = operatorError(bearing,forward,requestedAimDirection);
                if (!stabilizeWorld && operatorMotion.restartIfReversing(error)) {
                    // The landing envelope rejects old-direction steps. Restart from
                    // the real joint pose so neither obsolete velocity nor an ahead
                    // reference can hold the motor at zero during a quick reversal.
                    // Keep measured velocity history to brake real physical momentum.
                    targetOrientation.set(child.logicalPose().orientation());
                    projectTargetOntoJoint(bearing);
                    forward = targetOrientation.transform(new Vector3d(aimLocalForward));
                    error = operatorError(bearing,forward,requestedAimDirection);
                    motorTarget.reset();
                    motorResidual.reset();
                }
                commandedDegrees = Math.toDegrees(operatorMotion.advanceDamped(error,
                        Math.toRadians(maxDegreesPerSecond), timeStep));
                sightCompensation.advance(error,Math.toRadians(commandedDegrees));
                operatorVelocity = operatorMotion.velocity();
                rotateMechanically(bearing, commandedDegrees);
            } else {
                operatorMotion.reset();
                rotateMechanically(bearing, commandedDegrees);
                if (directionReferenceInitialized) targetOrientation.transform(aimLocalForward, heldAimDirection).normalize();
            }
            parentTraverse.observe(parentOrientation(bearing, new Quaterniond()));
            clampTargetPitch(bearing);

            if (!isFinite(targetOrientation) || !isFinite(localOffset)) return;
            Direction facing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
            Direction plateFacing = bearing.getLevel().getBlockState(bearing.getPlatePos())
                    .getValue(SwivelBearingPlateBlock.FACING);
            Quaterniond frameA = BearingServoMath.hingeFrame(new Quaterniond(facing.getRotation()), 0.0);
            Quaterniond frameB = BearingServoMath.hingeFrame(new Quaterniond(plateFacing.getRotation()),
                    Math.toRadians(nativeZeroDegrees));

            // Offset changes move the anchor along a bounded path, never snap the body.
            Vec3 center = bearing.getBlockPos().relative(facing).getCenter();
            Vector3d desiredAnchor = new Vector3d(center.x, center.y, center.z).add(localOffset);
            Vector3d shift = desiredAnchor.sub(parentLocalAnchor, new Vector3d());
            double maxShift = Math.max(0.0, timeStep); // one local block/second
            if (shift.length() > maxShift) shift.normalize(maxShift);
            parentLocalAnchor.add(shift);
            joint.hinge().setFrame1(parentLocalAnchor, frameA);

            double measured = Math.toRadians(canonicalJointAngleDegrees(bearing,
                    child.logicalPose().orientation()) - nativeZeroDegrees);
            // Calibration roundoff on a moving parent must not reset feed-forward every game tick.
            if (!Double.isFinite(lastNativeZeroDegrees)
                    || Math.abs(Mth.wrapDegrees(lastNativeZeroDegrees - nativeZeroDegrees)) > 1e-7) {
                motorTarget.reset();
                motorResidual.reset();
                pitchStops.reset();
                yawStops.reset();
                aimVelocity.reset();
                lastNativeZeroDegrees = nativeZeroDegrees;
            }
            if (yawAxis && yawLimits.enabled()) {
                double halfRange = Math.min(Math.PI - 1e-7, yawLimits.halfRangeRadians());
                yawStops.update(-halfRange, halfRange, measured, timeStep);
                measured = yawStops.reference();
            }
            // Limited yaw needs the same rebase as free yaw. Both its motor and
            // physical stops otherwise have a singular twist gradient at the rear.
            double motorReference = yawAxis ? measured : 0.0;
            joint.hinge().setFrame2(actuationLocalPivot, new Quaterniond(actuationFromChild).mul(yawAxis
                    ? BearingServoMath.yawMotorFrame(frameB, motorReference) : frameB));
            joint.driveFrame(parent == null ? new Quaterniond(frameA)
                    : new Quaterniond(parent.logicalPose().orientation()).mul(frameA), yawAxis
                    ? new Quaterniond(actuationFromChild).mul(BearingServoMath.yawMotorFrame(frameB, motorReference))
                    : new Quaterniond(actuationFromChild).mul(frameB), new Vector3d(jointActuationChild.getMassTracker().getCenterOfMass()));
            double desired = Math.toRadians(canonicalJointAngleDegrees(bearing, targetOrientation)
                    - nativeZeroDegrees);
            // Rate limiting belongs to the parent-relative operator twist above. Applying
            // it again to the joint angle delayed carrier compensation by entire ticks.
            Vector3d axis = worldAxis(bearing, new Vector3d(), null);
            Vector3d parentVelocity = parent == null ? new Vector3d()
                    : physicsSystem.getPipeline().getAngularVelocity(parent, new Vector3d());
            double stabilizationRate = directionReferenceInitialized
                    ? BearingServoMath.stabilizationRate(heldAimDirection, axis, parentVelocity)
                    : -parentVelocity.dot(axis);
            double averageOperatorRate = Math.toRadians(commandedDegrees) / timeStep;
            if (BearingServoMath.liveAimCommand(lastAimCommandTick,level.getGameTime()) && Math.abs(averageOperatorRate) > 1e-6) {
                aimLandingRemaining = 0.15;
            } else {
                aimLandingRemaining = Math.max(0.0, aimLandingRemaining - timeStep);
            }
            double velocityRate = operatorVelocity
                    + (stabilizeWorld && !parentRelativeTraverse ? stabilizationRate : 0.0);
            double goal = BearingServoMath.wrapRadians(desired);
            if (stabilizeWorld && directionReferenceInitialized && (!externalStaff || staffForecast)) {
                // Locked axes have small physical swing errors under load. Closing the
                // loop on an idealized target orientation leaves those errors in the
                // actual barrel ray. Solve the driven twist from the measured ray.
                Vector3d physicalForward = observedForward(bearing,child);
                goal = BearingServoMath.wrapRadians(measured + operatorError(bearing,physicalForward,heldAimDirection));
            }
            if (!yawAxis) {
                double depression = nativeLimit(depressionLimitDegrees);
                double elevation = nativeLimit(elevationLimitDegrees);
                double min = jointToElevationSign > 0.0 ? -depression : -elevation;
                double max = jointToElevationSign > 0.0 ? elevation : depression;
                pitchStops.update(min, max, measured, timeStep);
                joint.hinge().setLimit(ConstraintJointAxis.ANGULAR_X, pitchStops.min(), pitchStops.max());
                goal = pitchStops.goal(goal, measured, timeStep);
                if (pitchStops.transitioning()) {
                    velocityRate = BearingServoMath.wrapRadians(goal - measured) / timeStep;
                }
            }
            if (yawAxis && yawLimits.enabled()) {
                joint.hinge().setLimit(ConstraintJointAxis.ANGULAR_X, yawStops.nativeMin(), yawStops.nativeMax());
                goal = yawStops.goal(goal, timeStep);
                if (yawStops.transitioning()) velocityRate = BearingServoMath.wrapRadians(goal - measured) / timeStep;
                else if (goal <= yawStops.min() + 1e-7 && velocityRate < 0.0
                        || goal >= yawStops.max() - 1e-7 && velocityRate > 0.0) velocityRate = 0.0;
            }
            boolean yawReturning = yawAxis && yawLimits.enabled() && yawStops.transitioning();
            if (yawReturning && directionReferenceInitialized) {
                heldAimDirection.set(yawLimits.limitDirection(observedForward(bearing, child),
                        parentOrientation(bearing, new Quaterniond())));
                operatorMotion.reset();
            }
            double initialRate = yawReturning || pitchStops.transitioning() && !yawAxis ? velocityRate
                    : velocityRate + averageOperatorRate - operatorVelocity;
            boolean heldPrediction = stabilizeWorld && (!externalStaff || staffForecast)
                    && !yawReturning && !(pitchStops.transitioning() && !yawAxis);
            // Retain local motion history for recovery/sleep detection. The world
            // actuator below has no parent twist feed-forward or upstream axis state.
            if (heldPrediction && !staffForecast) {
                motorTarget.predictHeldRate(goal, initialRate, averageOperatorRate, operatorVelocity, timeStep);
            } else if (!heldPrediction) {
                motorTarget.velocity(goal, initialRate, timeStep);
            }
            double childInertia = assemblyInertiaAtPivot(child, parent,
                    child.logicalPose().transformPosition(new Vector3d(childLocalPivot)), axis);
            BearingServoMath.Motor motor = BearingServoMath.motor(childInertia, Double.POSITIVE_INFINITY);
            boolean movingOperator = Math.abs(averageOperatorRate) > 1e-6;
            boolean landingToAim = BearingServoMath.liveAimCommand(lastAimCommandTick,level.getGameTime()) && maxDegreesPerSecond > 0.0
                    && aimLandingRemaining > 0.0 && !yawReturning && !(pitchStops.transitioning() && !yawAxis);
            // A bounded landing goal must not accumulate a residual that kicks the
            // motor forward as soon as the braking interval ends.
            boolean resetResidual = movingOperator || landingToAim || yawReturning || pitchStops.transitioning() && !yawAxis;
            if (resetResidual) motorResidual.reset();
            double residual = resetResidual
                    ? 0.0 : (heldPrediction ? motorResidual.update(goal, measured, timeStep)
                            : motorResidual.update(goal, measured));
            // Stabilized commands keep their world reference for this native step.
            // Manual commands instead carry the child along with the parent,
            // while their head-aim correction remains a bounded relative turn.
            double manualParentRate = stabilizeWorld ? 0.0 : parentVelocity.dot(axis);
            double manualCarrierPrediction = stabilizeWorld ? 0.0
                    : manualParentRate * (timeStep + motor.damping() / motor.stiffness());
            Vector3d traverseRateDirection = yawAxis ? compensatedAimDirection
                    : BearingServoMath.pitchDirection(compensatedAimDirection, pitchHeading(bearing),
                            parentOrientation(bearing, new Quaterniond()));
            double traverseParentRate = parentRelativeTraverse
                    ? -BearingServoMath.stabilizationRate(traverseRateDirection, axis, parentVelocity) : 0.0;
            double traverseParentPrediction = traverseParentRate * (timeStep + motor.damping() / motor.stiffness());
            double worldTraverseRate = operatorVelocity + traverseParentRate;
            diagnosticParentTraverse = parentRelativeTraverse;
            diagnosticTraverseParentRate = traverseParentRate;
            diagnosticWorldTraverseRate = worldTraverseRate;
            // A returning stop already advances the goal at 60 deg/s. Residual
            // integration or operator feed-forward would accelerate that return.
            double motorGoal = yawReturning ? BearingServoMath.wrapRadians(goal - motorReference) : stabilizeWorld
                    ? BearingServoMath.servoGoal(BearingServoMath.wrapRadians(goal - motorReference + residual),
                            worldTraverseRate, worldTraverseRate, timeStep, motor)
                    : BearingServoMath.manualWorldMotorGoal(BearingServoMath.wrapRadians(goal - motorReference + residual),
                            operatorVelocity, manualParentRate, timeStep, motor);
            Vector3d actualForward = observedForward(bearing,child);
            Quaterniond aimParent = parentOrientation(bearing, new Quaterniond());
            Direction aimFacing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
            Vector3d localAimAxis = new Vector3d(aimFacing.getStepX(), aimFacing.getStepY(), aimFacing.getStepZ());
            double operatorAimRate = stabilizeWorld ? aimVelocity.sampleCompensated(
                    yawAxis ? actualForward : BearingServoMath.pitchDirection(actualForward, actualForward, aimParent),
                    yawAxis ? compensatedAimDirection : BearingServoMath.pitchDirection(compensatedAimDirection, actualForward, aimParent),
                    yawAxis ? heldAimDirection : BearingServoMath.pitchDirection(heldAimDirection, actualForward, aimParent),
                    aimParent, localAimAxis, timeStep)
                    : aimVelocity.sample(aimParent.transformInverse(new Vector3d(actualForward)),
                            aimParent.transformInverse(new Vector3d(axis)), timeStep);
            if (landingToAim && !(yawAxis && yawLimits.enabled()
                    && Math.abs(operatorError(bearing, actualForward, requestedAimDirection)) > Math.PI)) {
                double stabilizationCorrection = stabilizeWorld
                        ? operatorError(bearing, actualForward, compensatedAimDirection) : 0.0;
                if (stabilizeWorld && !yawAxis) stabilizationCorrection = Math.clamp(measured + stabilizationCorrection,
                        pitchStops.min(), pitchStops.max()) - measured;
                double totalCorrection = operatorError(bearing, actualForward, requestedAimDirection);
                double aimGoal = measured + totalCorrection;
                if (!yawAxis) aimGoal = Math.clamp(aimGoal, pitchStops.min(), pitchStops.max());
                double operatorRemaining = BearingServoMath.operatorCorrection(aimGoal - measured, stabilizationCorrection);
                // Add carrier prediction after applying the operator braking envelope.
                // Bounding their sum slows stabilization while the carrier turns.
                double carrierPrediction = stabilizeWorld
                        ? stabilizationCorrection + traverseParentPrediction : manualCarrierPrediction;
                motorGoal = BearingServoMath.boundOperatorMotorGoal(motorGoal,
                        BearingServoMath.wrapRadians(measured + operatorRemaining - motorReference),
                        BearingServoMath.wrapRadians(measured - motorReference), carrierPrediction);
                if (!externalStaff) motorGoal = BearingServoMath.brakeAimMotorGoal(motorGoal,
                        operatorRemaining, operatorAimRate, timeStep, motor);
            }
            motorGoal = limitYawMotorGoal(motorGoal);
            joint.drive(motorGoal, motor);
            if (carrier instanceof ServerSubLevel carrierBody) {
                boolean modelReady = parent != null;
                if (stabilizeWorld && finiteTargets.isEmpty()) modelReady &= heldPrediction && directionReferenceInitialized
                        && !pitchStops.transitioning()
                        && Math.abs(BearingServoMath.wrapRadians(goal-measured))<Math.toRadians(10);
                // A held ray at a mechanical stop may have a small unreachable swing
                // residual. Keep this motor and its limit in the load model, but do
                // not let its rejected inverse discard other axes' compensation.
                boolean atPitchStop = (!finiteTargets.isEmpty() || staffParentHolding
                        || BearingServoMath.liveAimCommand(lastAimCommandTick,level.getGameTime())) && !yawAxis
                        && (goal<=pitchStops.min()+1e-5 || goal>=pitchStops.max()-1e-5);
                boolean eligible = modelReady && directionReferenceInitialized && heldPrediction && !atPitchStop
                        && !(yawAxis && yawLimits.enabled() && (goal <= yawStops.min() + 1e-5 || goal >= yawStops.max() - 1e-5))
                        && Math.abs(BearingServoMath.wrapRadians(goal-measured))<Math.toRadians(10)
                        && (!finiteTargets.isEmpty() || !movingOperator && !landingToAim);
                servoStep = new ServoStep(bearing, this, parent, child, carrierBody,
                        new Quaterniond(frameA), yawAxis
                        ? BearingServoMath.yawMotorFrame(frameB, motorReference) : new Quaterniond(frameB),
                        motorGoal, motor, modelReady, eligible);
            }
            if (Math.abs(BearingServoMath.wrapRadians(goal - measured)) > 1.0e-6
                    || shift.lengthSquared() > 1.0e-12) {
                physicsSystem.getPipeline().wakeUp(child);
                if (parent != null) physicsSystem.getPipeline().wakeUp(parent);
            }
            // Preserve saved/native target and client interpolation without letting native
            // ticks write the physical motor. Renderers also have the actual physical pose.
            SwivelBearingBlockEntityAccessor accessor = (SwivelBearingBlockEntityAccessor) bearing;
            accessor.stabilizedTurret$setLastTargetAngleDegrees(bearing.getTargetAngleDegrees());
            accessor.stabilizedTurret$setTargetAngleDegrees(Math.toDegrees(goal) + nativeZeroDegrees);
        }

        private double limitYawMotorGoal(double goal) {
            return yawAxis && yawLimits.enabled() ? yawStops.motorGoal(goal) : goal;
        }

        /** The head-aim budget is twist in the immediate parent's frame, independent of world compensation. */
        private double operatorError(SwivelBearingBlockEntity bearing,Vector3d from,Vector3d to) {
            Quaterniond parent=parentOrientation(bearing,new Quaterniond());
            Direction facing=bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
            if (yawAxis && yawLimits.enabled())
                return yawLimits.errorRadians(from, to, parent, facing.getStepY() < 0 ? -1.0 : 1.0);
            if (!yawAxis) {
                Vector3d heading=pitchHeading(bearing);
                from=BearingServoMath.pitchDirection(from,heading,parent);
                to=BearingServoMath.pitchDirection(to,heading,parent);
            }
            return BearingServoMath.parentRelativeDirectionAngle(parent,
                    new Vector3d(facing.getStepX(),facing.getStepY(),facing.getStepZ()),from,to);
        }

        private Vector3d pitchHeading(SwivelBearingBlockEntity bearing) {
            SubLevel child=jointChild!=null?jointChild:SubLevelContainer.getContainer(bearing.getLevel()).getSubLevel(childId);
            return child instanceof ServerSubLevel body ? observedForward(bearing,body)
                    : targetOrientation.transform(new Vector3d(aimLocalForward));
        }

        private Vector3d advancePitchAim(SwivelBearingBlockEntity bearing,Vector3d held,Vector3d requested,
                                        Vector3d axis,double step) {
            return BearingServoMath.advancePitchDirection(held,requested,axis,pitchHeading(bearing),
                    parentOrientation(bearing,new Quaterniond()),step);
        }

        /** Keep the relaxed attachment in the prediction graph so downstream axes still compensate staff motion. */
        private void prepareStaffYieldStep(SwivelBearingBlockEntity bearing,ServerSubLevel parent,
                ServerSubLevel child,SubLevel carrier,Quaterniond grabbedGoal,double timeStep) {
            boolean cooperate = parent != null && carrier instanceof ServerSubLevel;
            if (yawAxis && yawLimits.enabled()) clampTargetPitch(bearing);
            Direction facing=bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
            Direction plate=bearing.getLevel().getBlockState(bearing.getPlatePos()).getValue(SwivelBearingPlateBlock.FACING);
            Quaterniond frameA=BearingServoMath.hingeFrame(new Quaterniond(facing.getRotation()),0);
            Quaterniond frameB=BearingServoMath.hingeFrame(new Quaterniond(plate.getRotation()),Math.toRadians(nativeZeroDegrees));
            double measured=Math.toRadians(canonicalJointAngleDegrees(bearing,child.logicalPose().orientation())-nativeZeroDegrees);
            if (yawAxis && yawLimits.enabled()) {
                double halfRange = Math.min(Math.PI - 1e-7, yawLimits.halfRangeRadians());
                yawStops.update(-halfRange, halfRange, measured, timeStep);
                measured = yawStops.reference();
                joint.hinge().setLimit(ConstraintJointAxis.ANGULAR_X, yawStops.nativeMin(), yawStops.nativeMax());
            }
            if (yawAxis) frameB=BearingServoMath.yawMotorFrame(frameB,measured);
            Quaterniond worldFrame=parent == null ? new Quaterniond(frameA)
                    : new Quaterniond(parent.logicalPose().orientation()).mul(frameA);
            if (cooperate && yawAxis && !yawLimits.enabled() && grabbedGoal!=null && stabilizeWorld) {
                if (!staffYawReferenceInitialized) {
                    child.logicalPose().orientation().transformInverse(observedForward(bearing,child),staffYawLocalRay).normalize();
                    staffAttachmentChildFrame.set(child.logicalPose().orientation()).conjugate().mul(worldFrame).normalize();
                    staffYawReferenceInitialized=true;
                }
                double reference=BearingServoMath.jointAngle(worldFrame,
                        new Quaterniond(child.logicalPose().orientation()).mul(staffAttachmentChildFrame));
                frameB=BearingServoMath.yawMotorFrame(staffAttachmentChildFrame,reference);
            }
            joint.hinge().setFrame1(parentLocalAnchor,frameA);
            Quaterniond physicalFrameB = new Quaterniond(actuationFromChild).mul(frameB);
            joint.hinge().setFrame2(actuationLocalPivot,physicalFrameB);
            joint.driveFrame(worldFrame,physicalFrameB,new Vector3d(jointActuationChild.getMassTracker().getCenterOfMass()));
            double goal=0;
            var motor=new BearingServoMath.Motor(0,0,0);
            if (cooperate && yawAxis && !yawLimits.enabled() && grabbedGoal!=null && stabilizeWorld) {
                // Cooperate with the staff's own orientation, rather than leaving
                // azimuth free to twist under its off-centre vertical pull.
                // This is still the child-only actuator, never a parent reaction motor.
                Vector3d axis=worldAxis(bearing,new Vector3d(),null);
                motor=BearingServoMath.motor(assemblyInertiaAtPivot(child,parent,
                        child.logicalPose().transformPosition(new Vector3d(childLocalPivot)),axis),Double.POSITIVE_INFINITY);
                goal=BearingServoMath.wrapRadians(Math.toRadians(canonicalJointAngleDegrees(bearing,grabbedGoal)
                        -nativeZeroDegrees)-measured);
                grabbedGoal.transform(staffYawLocalRay,heldAimDirection).normalize();
                joint.drive(goal,motor);
            }
            if (cooperate) servoStep=new ServoStep(bearing,this,parent,child,(ServerSubLevel)carrier,frameA,frameB,goal,motor,true,
                    yawAxis && grabbedGoal!=null && directionReferenceInitialized && stabilizeWorld);
        }

        private double nativeLimit(double elevationDegrees) {
            double ratio = Math.sin(Math.toRadians(elevationDegrees)) / Math.max(1.0e-6, elevationAmplitude);
            return Math.asin(Mth.clamp(ratio, 0.0, 1.0));
        }

        private static double inertiaAtPivot(ServerSubLevel body, Vector3d anchor, Vector3d axis) {
            var mass = body.getMassTracker();
            Vector3d radius = new Vector3d(anchor).sub(mass.getCenterOfMass());
            return BearingServoMath.pivotInertia(mass.getInertiaTensor().transform(axis, new Vector3d())
                    .dot(axis), mass.getMass(), radius, axis);
        }

        private static double assemblyInertiaAtPivot(ServerSubLevel root, ServerSubLevel excludedParent,
                                                     Vector3d worldAnchor, Vector3d worldAxis) {
            Set<UUID> visited = new HashSet<>();
            ArrayDeque<ServerSubLevel> pending = new ArrayDeque<>();
            pending.add(root);
            double inertia = 0.0;
            ServerLevel level = rootServerLevel(root.getLevel());
            var container = SubLevelContainer.getContainer(level);
            while (!pending.isEmpty()) {
                ServerSubLevel body = pending.removeFirst();
                if (body == excludedParent || body.isRemoved() || !visited.add(body.getUniqueId())) continue;
                var mass = body.getMassTracker();
                if (mass != null && mass.getCenterOfMass() != null) {
                    Vector3d anchor = body.logicalPose().transformPositionInverse(new Vector3d(worldAnchor));
                    Vector3d localAxis = body.logicalPose().orientation().transformInverse(new Vector3d(worldAxis));
                    inertia += inertiaAtPivot(body, anchor, localAxis);
                }
                for (BlockEntitySubLevelActor actor : body.getPlot().getBlockEntityActors()) {
                    if (actor instanceof SwivelBearingBlockEntity bearing && bearing.isAssembled()) {
                        SubLevel descendant = container.getSubLevel(bearing.getSubLevelID());
                        if (descendant instanceof ServerSubLevel child) pending.addLast(child);
                    }
                }
                for (UUID id : SynaxisWeldCompat.expandFixedWeldedSublevels(level, Set.of(body.getUniqueId()))) {
                    if (visited.contains(id)) continue;
                    SubLevel welded = container.getSubLevel(id);
                    if (welded instanceof ServerSubLevel child) pending.addLast(child);
                }
            }
            return Math.max(0.01, inertia);
        }

        /** Drive the principal load of this rigid group instead of an isolated lightweight plate.
         * Never cross another bearing or a rotary weld when selecting the actuator body. */
        private ServerSubLevel selectDriveBody(ServerSubLevel child, ServerSubLevel parent) {
            ServerLevel level = rootServerLevel(child.getLevel());
            Set<UUID> members = SynaxisWeldCompat.expandFixedWeldedSublevels(level, Set.of(child.getUniqueId()));
            if (parent != null && members.contains(parent.getUniqueId())) return child;
            Vector3d localAxis = new Vector3d(
                    jointPlateFacing == null ? 0 : jointPlateFacing.getStepX(),
                    jointPlateFacing == null ? 1 : jointPlateFacing.getStepY(),
                    jointPlateFacing == null ? 0 : jointPlateFacing.getStepZ());
            var geometry = SableServoConstraints.fixedGeometry(level, childId, childLocalPivot, localAxis);
            ServerSubLevel selected = child;
            double largest = 2.0 * inertiaAtPivot(child, childLocalPivot, localAxis);
            // Keep an existing suitable principal load. A tiny weld oscillation must
            // not swap the actuator, recreate its hinge, and reset all braking history.
            if (jointActuationChild != null && members.contains(jointActuationChild.getUniqueId())
                    && !jointActuationChild.isRemoved() && jointActuationChild.getMassTracker() != null) {
                var current = geometry.get(jointActuationChild.getUniqueId());
                if (current != null && jointActuationChild.getPlot().contains(current.anchor())
                        && jointActuationChild.getMassTracker().getCenterOfMass() != null) {
                    double inertia = inertiaAtPivot(jointActuationChild, current.anchor(), current.axis());
                    if (inertia >= largest * 0.5) {
                        selected = jointActuationChild;
                        largest = Math.max(largest, inertia * 1.25);
                    }
                }
            }
            var container = SubLevelContainer.getContainer(level);
            for (UUID id : members) {
                if (!(container.getSubLevel(id) instanceof ServerSubLevel body) || body.isRemoved()
                        || body.getMassTracker() == null || body.getMassTracker().getCenterOfMass() == null) continue;
                var nominal = geometry.get(id);
                if (nominal == null) continue;
                Vector3d pivot = nominal.anchor();
                // Sable rejects anchors outside the selected body's plot. Validate
                // the saved weld geometry rather than a temporarily deflected pose.
                if (!isFinite(pivot) || !body.getPlot().contains(pivot)) continue;
                double inertia = inertiaAtPivot(body, pivot, nominal.axis());
                if (inertia > largest) {
                    selected = body; largest = inertia;
                }
            }
            return selected;
        }

        private boolean createJoint(SwivelBearingBlockEntity bearing, SubLevelPhysicsSystem system,
                                    ServerSubLevel parent, ServerSubLevel child) {
            if (!resolveSimulatedAssemblyPivot(bearing, childLocalPivot)) return false;
            if (!child.getPlot().contains(childLocalPivot)) return false;
            Vector3d anchor = new Vector3d(childLocalPivot);
            child.logicalPose().transformPosition(anchor);
            if (parent != null) parent.logicalPose().transformPositionInverse(anchor);
            Direction facing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
            Vec3 center = bearing.getBlockPos().relative(facing).getCenter();
            if (!isFinite(anchor) || anchor.distance(new Vector3d(center.x, center.y, center.z))
                    > MAX_BINDING_ANCHOR_DISTANCE_BLOCKS) return false;
            if (parent != null && !parent.getPlot().contains(anchor)) return false;
            parentLocalAnchor.set(anchor);
            Direction plateFacing = bearing.getLevel().getBlockState(bearing.getPlatePos())
                    .getValue(SwivelBearingPlateBlock.FACING);
            jointPlateFacing = plateFacing;
            ServerSubLevel driven = selectDriveBody(child, parent);
            Quaterniond driveRotation = SableServoConstraints.fixedRotation(system.getLevel(), driven.getUniqueId(), childId);
            if (driveRotation == null) { driven = child; driveRotation = new Quaterniond(); }
            var nominal = SableServoConstraints.fixedGeometry(system.getLevel(), childId,
                    childLocalPivot, new Vector3d()).get(driven.getUniqueId());
            if (nominal == null) return false;
            actuationLocalPivot.set(nominal.anchor());
            if (!isFinite(actuationLocalPivot) || !driven.getPlot().contains(actuationLocalPivot)
                    || driven.getMassTracker() == null || driven.getMassTracker().getCenterOfMass() == null) return false;
            releaseJoint(bearing, false);
            GenericConstraintHandle created = null;
            GenericConstraintHandle actuator = null;
            try {
                created = system.getPipeline().addConstraint(parent, driven,
                        new GenericConstraintConfiguration(new Vector3d(parentLocalAnchor),
                                new Vector3d(actuationLocalPivot),
                                BearingServoMath.hingeFrame(new Quaterniond(facing.getRotation()), 0.0),
                                new Quaterniond(driveRotation).mul(BearingServoMath.hingeFrame(new Quaterniond(plateFacing.getRotation()),
                                        Math.toRadians(nativeZeroDegrees))),
                                Set.of(ConstraintJointAxis.LINEAR_X, ConstraintJointAxis.LINEAR_Y,
                                        ConstraintJointAxis.LINEAR_Z, ConstraintJointAxis.ANGULAR_Y,
                                        ConstraintJointAxis.ANGULAR_Z)));
                if (created == null || !created.isValid()) return false;
                created.setContactsEnabled(false);
                // Keep the zero-force native motor axis registered so Rapier does
                // not reorder the attachment into its SIMD-only locked-joint bucket.
                // This creates no driven twist row or reaction torque.
                created.setMotor(ConstraintJointAxis.ANGULAR_X, 0, 0, 0, true, 0);
                actuator = system.getPipeline().addConstraint(null, driven,
                        new GenericConstraintConfiguration(new Vector3d(), new Vector3d(driven.getMassTracker().getCenterOfMass()),
                                parent == null ? BearingServoMath.hingeFrame(new Quaterniond(facing.getRotation()), 0.0)
                                        : new Quaterniond(parent.logicalPose().orientation()).mul(
                                                BearingServoMath.hingeFrame(new Quaterniond(facing.getRotation()), 0.0)),
                                new Quaterniond(driveRotation).mul(BearingServoMath.hingeFrame(new Quaterniond(plateFacing.getRotation()),
                                        Math.toRadians(nativeZeroDegrees))), Set.of()));
                if (actuator == null || !actuator.isValid()) {
                    created.remove();
                    return false;
                }
                SwivelBearingBlockEntityAccessor accessor = (SwivelBearingBlockEntityAccessor) bearing;
                accessor.stabilizedTurret$removeConstraintHandle();
                joint = new NativeBearingJoint(created, actuator);
                jointParent = parent;
                jointChild = child;
                jointActuationChild = driven;
                actuationFromChild.set(driveRotation);
                jointBearingPos = bearing.getBlockPos().immutable();
                jointBearingFacing = facing;
                jointPlateFacing = plateFacing;
                accessor.stabilizedTurret$setConstraintHandle(joint);
                motorTarget.reset();
                motorResidual.reset();
                aimVelocity.reset();
                lastNativeZeroDegrees = nativeZeroDegrees;
                pitchStops.reset();
                yawStops.reset();
                return true;
            } catch (RuntimeException failure) {
                if (actuator != null && actuator.isValid()) actuator.remove();
                if (created != null && created.isValid()) created.remove();
                StabilizedTurret.LOGGER.error("Failed to create fire-control hinge for {}", childId, failure);
                releaseJoint(bearing, true);
                return false;
            }
        }

        private void releaseJoint(SwivelBearingBlockEntity bearing, boolean restoreNative) {
            NativeBearingJoint previous = joint;
            if (previous == null) return;
            azimuthObservations = List.of();
            SwivelBearingBlockEntityAccessor accessor = (SwivelBearingBlockEntityAccessor) bearing;
            boolean ownsSlot = accessor.stabilizedTurret$getConstraintHandle() == previous;
            previous.remove();
            joint = null;
            if (!ownsSlot) return;
            accessor.stabilizedTurret$setConstraintHandle(null);
            if (!restoreNative || bearing.isRemoved() || !bearing.isAssembled()
                    || jointChild == null || jointChild.isRemoved()
                    || !jointChild.getUniqueId().equals(bearing.getSubLevelID())
                    || (jointParent != null && jointParent.isRemoved())) return;
            // Restore a rotary joint at the current physical anchor. Unbinding an
            // offset turret must not abruptly pull it back through the vehicle.
            Vector3d anchor = new Vector3d(childLocalPivot);
            jointChild.logicalPose().transformPosition(anchor);
            if (jointParent != null) jointParent.logicalPose().transformPositionInverse(anchor);
            Direction facing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
            BlockState plate = bearing.getLevel().getBlockState(bearing.getPlatePos());
            if (!plate.hasProperty(SwivelBearingPlateBlock.FACING)) return;
            Direction plateFacing = plate.getValue(SwivelBearingPlateBlock.FACING);
            accessor.stabilizedTurret$setConstraintHandle(SubLevelPhysicsSystem
                    .require(rootServerLevel(bearing.getLevel())).getPipeline().addConstraint(
                            jointParent, jointChild, new RotaryConstraintConfiguration(anchor,
                                    new Vector3d(childLocalPivot),
                                    new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ()),
                                    new Vector3d(plateFacing.getStepX(), plateFacing.getStepY(), plateFacing.getStepZ()))));
            double actual = physicalJointAngle(bearing);
            accessor.stabilizedTurret$setTargetAngleDegrees(actual);
            accessor.stabilizedTurret$setLastTargetAngleDegrees(actual);
            bearing.updateServoCoefficients();
        }

        private void projectTargetOntoJoint(SwivelBearingBlockEntity bearing) {
            BlockPos platePos = bearing.getPlatePos();
            if (platePos == null || bearing.getLevel() == null) {
                return;
            }
            BlockState plateState = bearing.getLevel().getBlockState(platePos);
            if (!plateState.hasProperty(SwivelBearingPlateBlock.FACING)) {
                return;
            }
            double jointAngle = canonicalJointAngleDegrees(bearing, targetOrientation);
            targetOrientation.set(orientationForJointAngle(
                    bearing,
                    plateState.getValue(SwivelBearingPlateBlock.FACING),
                    jointAngle
            ));
        }

    }

    private record BoundState(
            Level level,
            UUID childId,
            boolean yawAxis,
            boolean decouplePhysics,
            long lastHeartbeat
    ) {
    }

    private record BearingEdge(UUID parent, UUID child) {
    }

    public record SuspendedNoContact(UUID first, UUID second) {
    }

    private record BodyPair(UUID first, UUID second) {
        private static BodyPair of(UUID first, UUID second) {
            return first.compareTo(second) <= 0
                    ? new BodyPair(first, second)
                    : new BodyPair(second, first);
        }

        private UUID other(UUID body) {
            if (first.equals(body)) return second;
            if (second.equals(body)) return first;
            return null;
        }
    }

    private static final class GravityState {
        private static final String NO_GRAVITY_TAG = "StabilizedTurretNoGravity";
        private static final String OWNED_NO_GRAVITY_TAG = "StabilizedTurretOwnsNoGravity";
        private final ServerSubLevel child;
        private final boolean noGravityBeforeBinding;
        private boolean tagsApplied;

        private GravityState(ServerSubLevel child) {
            this.child = child;
            removeLegacyCollisionTags(child);
            CompoundTag tag = child.getUserDataTag();
            noGravityBeforeBinding = tag != null
                    && tag.getBoolean(NO_GRAVITY_TAG)
                    && !tag.getBoolean(OWNED_NO_GRAVITY_TAG);
            applyTags();
        }

        private void applyTags() {
            CompoundTag tag = child.getUserDataTag();
            boolean alreadyApplied = tag != null
                    && tag.getBoolean(NO_GRAVITY_TAG)
                    && tag.getBoolean(OWNED_NO_GRAVITY_TAG);
            if (tagsApplied && alreadyApplied) {
                return;
            }
            if (tag == null) {
                tag = new CompoundTag();
            }
            tag.putBoolean(NO_GRAVITY_TAG, true);
            tag.putBoolean(OWNED_NO_GRAVITY_TAG, true);
            child.setUserDataTag(tag);
            tagsApplied = true;
        }

        private void restoreTags() {
            CompoundTag tag = child.getUserDataTag();
            if (tag == null) {
                return;
            }
            if (!noGravityBeforeBinding) {
                tag.remove(NO_GRAVITY_TAG);
            }
            tag.remove(OWNED_NO_GRAVITY_TAG);
            child.setUserDataTag(tag);
        }
    }
}
