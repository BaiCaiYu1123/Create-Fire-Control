package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.compat.synaxis.SynaxisNoContactCompat;
import com.hooya.stabilizedturret.compat.synaxis.SynaxisWeldCompat;
import com.hooya.stabilizedturret.mixin.SwivelBearingBlockEntityAccessor;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
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
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** 0.7.8 pose-lock backend with finite-shot aiming; shared physical properties are owned by BearingStabilizer. */
public final class LegacyBearingStabilizer {
    private static final long HEARTBEAT_TOLERANCE_TICKS = 2L;
    private static final long NO_CONTACT_REFRESH_INTERVAL_TICKS = 1L;
    private static final double MAX_INITIAL_LOCK_CORRECTION_BLOCKS = 64.0;
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

    /** This backend has no native joints to release; shared physical properties are cleaned by the facade. */
    public static void clearRuntimeState() {
        BOUND.clear();
        ACTIVE.clear();
        CONSTRAINED.clear();
        ASSEMBLY_ZEROS.clear();
        ASSEMBLY_PIVOTS.clear();
        GRAVITY_OVERRIDDEN.clear();
        OWNED_NO_CONTACTS.clear();
        LAST_NO_CONTACT_REFRESH.clear();
    }

    public static boolean hasWorldLocks(SubLevelPhysicsSystem physicsSystem) {
        for (var entry:activeSnapshot()) {
            if (entry.getValue().initialized && isActive(entry.getKey())
                    && rootServerLevel(entry.getKey().getLevel())==physicsSystem.getLevel()) return true;
        }
        return false;
    }

    public static void calibrateYawDirection(SwivelBearingBlockEntity bearing, Vec3 measured) {
        AxisLock lock = ACTIVE.get(bearing);
        if (lock != null && lock.yawAxis && lock.initialize(bearing) && !lock.yawReferenceInitialized) {
            SubLevel child = SubLevelContainer.getContainer(bearing.getLevel()).getSubLevel(bearing.getSubLevelID());
            if (child != null) {
                child.logicalPose().orientation().transformInverse(new Vector3d(measured.x, measured.y, measured.z), lock.aimLocalForward).normalize();
                lock.yawReferenceInitialized = true;
            }
        }
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

        BOUND.put(bearing, new BoundState(level, childId, yawAxis, decouplePhysics, level.getGameTime()));
        if (!decouplePhysics) {
            ACTIVE.remove(bearing);
            return;
        }
        removeConstrained(bearing);

        AxisLock lock;
        synchronized (ACTIVE) {
            lock = ACTIVE.computeIfAbsent(bearing, ignored -> new AxisLock());
        }
        if (lock.yawAxis != yawAxis && lock.initialized) {
            lock.reset();
        }
        lock.yawAxis = yawAxis;
        lock.yawLimits = yawAxis ? BearingStabilizer.yawLimits(bearing) : YawLimits.UNLIMITED;
        lock.setStabilization(bearing, stabilizeWorld);
        lock.setLimits(depressionLimitDegrees, elevationLimitDegrees);
        lock.setLocalOffset(localOffset);
        lock.lastHeartbeat = level.getGameTime();
        boolean lockReady = lock.initialize(bearing);
        if (lockReady && acceptMechanicalInput) {
            lock.operatorMotion.reset();
            lock.stabilizedAimMotion.reset();
            lock.previousAimTargets = List.of();
            lock.parentRelativeTraverse = false;
            lock.inheritedTraverse = lock.acquisitionRemaining = 0.0;
            double angularStep = KineticBlockEntity.convertToAngular(
                    bearing.getExtraKinetics().getSpeed());
            if (bearing.getBlockState().getValue(SwivelBearingBlock.FACING).getAxisDirection()
                    == Direction.AxisDirection.NEGATIVE) {
                angularStep *= -1.0;
            }
            double maxStep = Math.max(0.0, maxDegreesPerSecond) / 20.0;
            lock.rotateMechanically(bearing, Mth.clamp(angularStep, -maxStep, maxStep));
        }
        if (lockReady && lock.decoupleReady) {
            suppressBoundConstraint(bearing);
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
                depressionLimitDegrees, elevationLimitDegrees, localOffset, List.of());
    }

    public static void commandAim(SwivelBearingBlockEntity bearing, Vec3 measuredWorldDirection,
            Vec3 requestedWorldDirection, boolean yawAxis, double maxDegreesPerSecond,
            double depressionLimitDegrees, double elevationLimitDegrees, Vec3 localOffset,
            List<FiniteAimTarget> targets) {
        commandAim(bearing, measuredWorldDirection, requestedWorldDirection, yawAxis, maxDegreesPerSecond,
                depressionLimitDegrees, elevationLimitDegrees, localOffset, targets, null, true);
    }

    public static void commandAim(SwivelBearingBlockEntity bearing, Vec3 measuredWorldDirection,
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
        AxisLock lock = ACTIVE.get(bearing);
        if (lock != null && lock.initialize(bearing)) {
            lock.aimAtDirection(
                    bearing,
                    measuredWorldDirection.normalize(),
                    requestedWorldDirection.normalize(),
                    Math.max(0.0, maxDegreesPerSecond) / 20.0, targets, operatorDirection
            );
        }
        if (lock != null && lock.initialized && lock.decoupleReady) {
            suppressBoundConstraint(bearing);
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
        ACTIVE.remove(bearing);
        removeConstrained(bearing);
    }

    public static boolean isActive(SwivelBearingBlockEntity bearing) {
        AxisLock lock = ACTIVE.get(bearing);
        Level level = bearing.getLevel();
        return lock != null && level != null
                && level.getGameTime() - lock.lastHeartbeat <= HEARTBEAT_TOLERANCE_TICKS;
    }

    public static void calibrateElevationDirection(
            SwivelBearingBlockEntity bearing,
            Vec3 measuredWorldDirection
    ) {
        if (measuredWorldDirection.lengthSqr() < 1.0e-8) {
            return;
        }
        AxisLock lock = ACTIVE.get(bearing);
        if (lock != null && !lock.yawAxis && lock.initialize(bearing)) {
            lock.calibrateElevationDirection(bearing, measuredWorldDirection);
        }
    }

    public static boolean isBound(SwivelBearingBlockEntity bearing) {
        BoundState state = BOUND.get(bearing);
        Level level = bearing.getLevel();
        return state != null && level != null && state.level == level
                && state.childId.equals(bearing.getSubLevelID())
                && level.getGameTime() - state.lastHeartbeat <= HEARTBEAT_TOLERANCE_TICKS;
    }

    /** True when this exact physical body is the live child of a bound bearing. */
    public static boolean isBoundChild(ServerSubLevel body) {
        if (body == null || body.isRemoved()) return false;
        UUID bodyId = body.getUniqueId();
        for (Map.Entry<SwivelBearingBlockEntity, BoundState> entry : boundSnapshot()) {
            SwivelBearingBlockEntity bearing = entry.getKey();
            BoundState state = entry.getValue();
            if (bodyId.equals(state.childId)
                    && isLiveBinding(bearing, state)
                    && rootServerLevel(bearing.getLevel()) == body.getLevel()) {
                return true;
            }
        }
        return false;
    }

    public static void suppressMotor(SwivelBearingBlockEntity bearing) {
        BoundState state = BOUND.get(bearing);
        AxisLock lock = ACTIVE.get(bearing);
        if (isBound(bearing) && state != null && state.decouplePhysics
                && lock != null && lock.initialized && lock.decoupleReady) {
            suppressBoundConstraint(bearing);
        }
    }

    private static void suppressBoundConstraint(SwivelBearingBlockEntity bearing) {
        SwivelBearingBlockEntityAccessor accessor = (SwivelBearingBlockEntityAccessor) bearing;
        accessor.stabilizedTurret$removeConstraintHandle();
    }

    public static void maintainPhysicalProperties(SubLevelPhysicsSystem physicsSystem, double timeStep) {
        ServerLevel level = physicsSystem.getLevel();
        PhysicsPipeline pipeline = physicsSystem.getPipeline();
        Map<UUID, ServerSubLevel> directChildren = collectLiveChildren(level);
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
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            Map<UUID, ServerSubLevel> directChildren = collectLiveDirectChildren(level);
            restoreUnboundGravityTags(level, directChildren.keySet());
            for (ServerSubLevel child : directChildren.values()) {
                if (!child.isRemoved()) {
                    gravityStateFor(child).applyTags();
                }
            }
            reconcileNoContacts(level);
        }
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
        for (ServerLevel level : event.getServer().getAllLevels()) {
            clearOwnedNoContacts(level);
            SynaxisNoContactCompat.clearFallback(level);
        }
        for (GravityState state : gravitySnapshot().stream()
                .map(Map.Entry::getValue).toList()) {
            state.restoreTags();
        }
        GRAVITY_OVERRIDDEN.clear();
        OWNED_NO_CONTACTS.clear();
        LAST_NO_CONTACT_REFRESH.clear();
        BOUND.clear();
        ACTIVE.clear();
        CONSTRAINED.clear();
        ASSEMBLY_ZEROS.clear();
    }

    /**
     * Reconciles fire-control collision isolation with Synaxis' body-pair
     * no-contact constraints.  This runs once per server tick, never once per
     * physics substep, so the amount of work is independent of TPS substeps.
     */
    private static void reconcileNoContacts(ServerLevel level) {
        long now = level.getGameTime();
        synchronized (LAST_NO_CONTACT_REFRESH) {
            Long previous = LAST_NO_CONTACT_REFRESH.get(level);
            if (previous != null && now - previous < NO_CONTACT_REFRESH_INTERVAL_TICKS) return;
            LAST_NO_CONTACT_REFRESH.put(level, now);
        }
        Set<UUID> liveBodyIds = new HashSet<>(SynaxisNoContactCompat.liveBodyIds(level));
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
        migrateLegacyCollisionTags(level, liveBodyIds);
        if (!SynaxisNoContactCompat.isAvailable()) return;

        Set<BodyPair> owned = ownedNoContacts(level);
        loadOwnedNoContactTags(level, liveBodyIds, owned);
        Set<BodyPair> desired = desiredNoContacts(level, liveBodyIds, weldGraph);
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
        synchronized (LAST_NO_CONTACT_REFRESH) {
            LAST_NO_CONTACT_REFRESH.remove(level);
        }
        reconcileNoContacts(level);
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
        // Only direct bearing children need the gyro's gravity override.
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
                ACTIVE.remove(bearing);
                removeConstrained(bearing);
                continue;
            }
            if (rootServerLevel(bearingLevel) != physicsLevel) {
                continue;
            }

            if (state.decouplePhysics) {
                AxisLock lock = ACTIVE.get(bearing);
                if (lock != null && lock.initialized && lock.decoupleReady) {
                    suppressBoundConstraint(bearing);
                }
            }

            SubLevel attached = SubLevelContainer.getContainer(bearingLevel).getSubLevel(state.childId);
            if (attached instanceof ServerSubLevel child && !child.isRemoved()) {
                result.put(state.childId, child);
            } else {
                BOUND.remove(bearing);
                ACTIVE.remove(bearing);
            }
        }

        return result;
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

    public static void enforceWorldLocks(SubLevelPhysicsSystem physicsSystem) {
        enforceWorldLocks(physicsSystem, Set.of());
    }

    /** Skips bodies that Simulated is actively merging during this physics step. */
    public static void enforceWorldLocks(
            SubLevelPhysicsSystem physicsSystem,
            Set<UUID> suspendedBodyIds
    ) {
        Set<Map.Entry<SwivelBearingBlockEntity, AxisLock>> snapshot = activeSnapshot();
        for (boolean yawPass : new boolean[]{true, false}) {
            for (Map.Entry<SwivelBearingBlockEntity, AxisLock> entry : snapshot) {
                UUID childId = entry.getKey().getSubLevelID();
                if (entry.getValue().yawAxis == yawPass
                        && (childId == null || !suspendedBodyIds.contains(childId))) {
                    entry.getValue().enforce(entry.getKey(), physicsSystem);
                }
            }
        }
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

    private static Vector3d worldAnchor(SwivelBearingBlockEntity bearing, Vector3d destination) {
        Direction facing = bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
        Vec3 local = Vec3.atCenterOf(bearing.getBlockPos().relative(facing));
        Vec3 projected = Sable.HELPER.projectOutOfSubLevel(bearing.getLevel(), local);
        return destination.set(projected.x, projected.y, projected.z);
    }

    private static Set<Map.Entry<SwivelBearingBlockEntity, BoundState>> boundSnapshot() {
        synchronized (BOUND) {
            return Set.copyOf(BOUND.entrySet());
        }
    }

    private static Set<Map.Entry<SwivelBearingBlockEntity, AxisLock>> activeSnapshot() {
        synchronized (ACTIVE) {
            return Set.copyOf(ACTIVE.entrySet());
        }
    }

    private static Set<Map.Entry<UUID, GravityState>> gravitySnapshot() {
        synchronized (GRAVITY_OVERRIDDEN) {
            return Set.copyOf(GRAVITY_OVERRIDDEN.entrySet());
        }
    }

    private LegacyBearingStabilizer() {
    }

    /** Captures the neutral angle at the end of Simulated's assembly call. */
    public static void captureAssemblyZero(
            SwivelBearingBlockEntity bearing) {
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

    private static boolean assemblyPivot(
            SwivelBearingBlockEntity bearing,
            Vector3d destination) {
        UUID child = bearing.getSubLevelID();
        AssemblyPivot cached = ASSEMBLY_PIVOTS.get(bearing);
        if (cached != null && child != null && child.equals(cached.childId)
                && isFinite(cached.localPivot)) {
            destination.set(cached.localPivot);
            return true;
        }

        CompoundTag persistent = bearing.getPersistentData();
        if (persistent.contains(ASSEMBLY_PIVOT_X_TAG)
                && persistent.contains(ASSEMBLY_PIVOT_Y_TAG)
                && persistent.contains(ASSEMBLY_PIVOT_Z_TAG)) {
            destination.set(
                    persistent.getDouble(ASSEMBLY_PIVOT_X_TAG),
                    persistent.getDouble(ASSEMBLY_PIVOT_Y_TAG),
                    persistent.getDouble(ASSEMBLY_PIVOT_Z_TAG));
            if (isFinite(destination)) {
                if (child != null) {
                    ASSEMBLY_PIVOTS.put(bearing,
                            new AssemblyPivot(child, new Vector3d(destination)));
                }
                return true;
            }
        }

        // Legacy saves do not contain the captured pivot. Rebuild exactly the
        // child-side anchor used by Simulated's RotaryConstraintConfiguration;
        // never migrate from the body's pose at fire-control binding time.
        if (!resolveSimulatedAssemblyPivot(bearing, destination)) {
            return false;
        }
        writeAssemblyPivot(persistent, destination);
        if (child != null) {
            ASSEMBLY_PIVOTS.put(bearing,
                    new AssemblyPivot(child, new Vector3d(destination)));
        }
        bearing.setChanged();
        return true;
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

    /**
     * Resolves the child-local point that is physically sitting on the
     * bearing anchor right now. This remains valid after blueprints, glue
     * merges and weld transforms, unlike an old plot-space coordinate stored
     * when the bearing was first assembled.
     */
    private static boolean currentChildPivot(
            ServerSubLevel child,
            Vector3d desiredWorldAnchor,
            Vector3d destination
    ) {
        Vector3d anchor = new Vector3d(desiredWorldAnchor);
        if (!isFinite(anchor)) return false;
        child.logicalPose().transformPositionInverse(anchor);
        if (!isFinite(anchor)) return false;
        destination.set(anchor);
        return true;
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
        private boolean decoupleReady;
        private boolean yawAxis;
        private boolean stabilizeWorld = true;
        private boolean elevationSignInitialized;
        private boolean elevationFrameInitialized;
        private double jointToElevationSign = 1.0;
        private YawLimits yawLimits = YawLimits.UNLIMITED;
        private boolean yawReferenceInitialized;
        private double depressionLimitDegrees = 90.0;
        private double elevationLimitDegrees = 90.0;
        private double zeroJointAngleDegrees;
        private long lastHeartbeat = Long.MIN_VALUE;
        private long lastFiniteDiagnosticTick = Long.MIN_VALUE;
        private long lastAimCommandTick = Long.MIN_VALUE;
        private final BearingServoMath.OperatorMotion operatorMotion = new BearingServoMath.OperatorMotion();
        private final BearingServoMath.ParentAimTarget parentAimTarget = new BearingServoMath.ParentAimTarget();
        private final BearingServoMath.StabilizedAimMotion stabilizedAimMotion = new BearingServoMath.StabilizedAimMotion();
        private final Vector3d previousRequestedDirection = new Vector3d();
        private List<FiniteAimTarget> previousAimTargets = List.of();
        private final Vector3d aimLocalForward = new Vector3d(0, 0, 1);
        private boolean parentRelativeTraverse;
        private double inheritedTraverse;
        private double acquisitionRemaining;
        private double acquisitionMaxSpeed;

        private void reset() {
            childId = null;
            initialized = false;
            decoupleReady = false;
            elevationSignInitialized = false;
            elevationFrameInitialized = false;
            yawReferenceInitialized = false;
            jointToElevationSign = 1.0;
            zeroJointAngleDegrees = 0.0;
            operatorMotion.reset();
            stabilizedAimMotion.reset();
            previousAimTargets = List.of();
            parentRelativeTraverse = false;
            inheritedTraverse = acquisitionRemaining = 0.0;
            lastAimCommandTick = Long.MIN_VALUE;
        }

        private void setLimits(double depression, double elevation) {
            depressionLimitDegrees = Mth.clamp(depression, 0.0, 90.0);
            elevationLimitDegrees = Mth.clamp(elevation, 0.0, 90.0);
        }

        private void setStabilization(SwivelBearingBlockEntity bearing, boolean enabled) {
            if (stabilizeWorld == enabled) return;
            stabilizeWorld = enabled;
            StabilizedTurret.LOGGER.info("Changed fire-control self-stabilization: bearing={} enabled={} reactionEnabled=false",
                    bearing.getBlockPos(), enabled);
            operatorMotion.reset();
            stabilizedAimMotion.reset();
            previousAimTargets = List.of();
            parentRelativeTraverse = false;
            inheritedTraverse = acquisitionRemaining = 0.0;
            lastAimCommandTick = Long.MIN_VALUE;
            if (!initialized) return;
            SubLevel child = SubLevelContainer.getContainer(bearing.getLevel()).getSubLevel(childId);
            if (child != null && !child.isRemoved()) targetOrientation.set(child.logicalPose().orientation());
            parentOrientation(bearing, lastParentOrientation);
        }

        private void setLocalOffset(Vec3 offset) {
            localOffset.set(offset.x, offset.y, offset.z);
        }

        private boolean initialize(SwivelBearingBlockEntity bearing) {
            UUID attachedId = bearing.getSubLevelID();
            Level level = bearing.getLevel();
            if (attachedId == null || level == null) {
                reset();
                return false;
            }

            SubLevel attached = SubLevelContainer.getContainer(level).getSubLevel(attachedId);
            if (!(attached instanceof ServerSubLevel child) || child.isRemoved()) {
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
            Vector3d baseWorldAnchor = worldAnchor(bearing, new Vector3d());
            Vector3d desiredWorldAnchor = offsetWorldAnchor(bearing, new Vector3d());
            Vector3d freshAssemblyPivot = new Vector3d();
            boolean trustedAssemblyPivot = resolveSimulatedAssemblyPivot(
                    bearing, freshAssemblyPivot);
            if (trustedAssemblyPivot) {
                Vector3d projectedPivot = new Vector3d(freshAssemblyPivot);
                pose.transformPosition(projectedPivot);
                double expectedOffset = desiredWorldAnchor.distance(baseWorldAnchor);
                double pivotError = projectedPivot.distance(desiredWorldAnchor);
                // A newly configured offset and the one accidental extra
                // application made by the previous release can both differ
                // from the desired anchor by exactly one saved offset. Allow
                // that correction, but reject a stale blueprint/merge pivot.
                trustedAssemblyPivot = isFinite(projectedPivot)
                        && Double.isFinite(pivotError)
                        && pivotError <= Math.min(
                        MAX_INITIAL_LOCK_CORRECTION_BLOCKS,
                        expectedOffset + 1.0);
            }
            if (trustedAssemblyPivot) {
                childLocalPivot.set(freshAssemblyPivot);
                ASSEMBLY_PIVOTS.put(bearing,
                        new AssemblyPivot(attachedId, new Vector3d(freshAssemblyPivot)));
                writeAssemblyPivot(bearing.getPersistentData(), freshAssemblyPivot);
                bearing.setChanged();
            } else if (!currentChildPivot(
                    child, desiredWorldAnchor, childLocalPivot)) {
                return false;
            }
            double initialJointAngle = canonicalJointAngleDegrees(bearing, pose.orientation());
            targetOrientation.set(orientationForJointAngle(bearing, plateFacing, initialJointAngle));
            parentOrientation(bearing, lastParentOrientation);
            zeroJointAngleDegrees = 0.0;
            childId = attachedId;
            initialized = true;
            decoupleReady = false;
            elevationSignInitialized = false;
            elevationFrameInitialized = false;
            yawReferenceInitialized = false;
            jointToElevationSign = 1.0;
            operatorMotion.reset();
            stabilizedAimMotion.reset();
            previousAimTargets = List.of();
            parentRelativeTraverse = false;
            inheritedTraverse = acquisitionRemaining = 0.0;
            lastAimCommandTick = Long.MIN_VALUE;
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
            // Capture the actual barrel axis in child-local coordinates.
            // The physical elevation limiter can now evaluate the predicted
            // barrel direction after any candidate bearing rotation, without
            // assuming that positive joint angle always means elevation.
            new Quaterniond(targetOrientation)
                    .conjugate()
                    .transform(cannonForward, elevationLocalForward);
            if (elevationLocalForward.lengthSquared() > 1.0e-8) {
                elevationLocalForward.normalize();
                elevationFrameInitialized = true;
            }
            Vector3d parentUp = parentOrientation(bearing, new Quaterniond())
                    .transform(new Vector3d(0.0, 1.0, 0.0))
                    .normalize();
            double verticalDerivative = new Vector3d(axis).cross(cannonForward).dot(parentUp);

            double probeDegrees = 0.1;
            double currentJoint = canonicalJointAngleDegrees(bearing, targetOrientation);
            double probeJoint = unwrapNear(
                    canonicalJointAngleDegrees(
                            bearing,
                            rotated(targetOrientation, axis, probeDegrees)
                    ),
                    currentJoint
            );
            double canonicalDerivative = probeJoint - currentJoint;
            if (Math.abs(verticalDerivative) > 1.0e-6 && Math.abs(canonicalDerivative) > 1.0e-6) {
                jointToElevationSign = verticalDerivative * canonicalDerivative > 0.0 ? 1.0 : -1.0;
                elevationSignInitialized = true;
            }
        }

        private void aimAtDirection(
                SwivelBearingBlockEntity bearing,
                Vec3 measured,
                Vec3 requested,
                double maxAxisDegrees,
                List<FiniteAimTarget> targets, Vec3 operatorDirection
        ) {
            if (!yawAxis) {
                calibrateElevationDirection(bearing, measured);
            }
            inheritParentForControl(bearing);
            long aimTick = bearing.getLevel().getGameTime();
            if (lastAimCommandTick == Long.MIN_VALUE || aimTick - lastAimCommandTick > 1) {
                operatorMotion.reset();
                stabilizedAimMotion.reset();
                previousAimTargets = List.of();
                parentRelativeTraverse = false;
                inheritedTraverse = acquisitionRemaining = 0.0;
            }
            lastAimCommandTick = aimTick;

            Vector3d current = new Vector3d(measured.x, measured.y, measured.z).normalize();
            SubLevel child = SubLevelContainer.getContainer(bearing.getLevel()).getSubLevel(childId);
            if (child != null && !child.isRemoved()) {
                child.logicalPose().orientation().transformInverse(new Vector3d(current), aimLocalForward).normalize();
                targetOrientation.transform(aimLocalForward, current).normalize();
            }
            Vector3d desired = new Vector3d(requested.x, requested.y, requested.z).normalize();
            if (yawAxis) desired = yawLimits.limitDirection(desired, parentOrientation(bearing, new Quaterniond()));
            Vector3d axis = worldAxis(bearing, new Vector3d(), null);
            Quaterniond parent = parentOrientation(bearing, new Quaterniond());
            Vector3d up = parent.transform(new Vector3d(0, 1, 0)).normalize();
            if (!stabilizeWorld) {
                List<LegacyFiniteAimSolver.Ray> manualRays = finiteRays(bearing, targets);
                if (!manualRays.isEmpty()) {
                    double correction = LegacyFiniteAimSolver.correction(manualRays,
                            vec(offsetWorldAnchor(bearing, new Vector3d())), vec(axis), vec(up), yawAxis);
                    desired = new Quaterniond().rotationAxis(correction, axis.x, axis.y, axis.z)
                            .transform(new Vector3d(current)).normalize();
                }
                // Recompute parallax and ballistics on every live head-aim
                // sample; an unchanged view still needs correction after motion.
                parentAimTarget.set(desired, parent);
                desired = parentAimTarget.worldDirection(parent);
                if (!yawAxis) desired = BearingServoMath.pitchDirection(desired, current, parent);
                applyManualAimStep(bearing, axis, Math.toDegrees(yawAxis && yawLimits.enabled()
                                ? yawLimits.errorRadians(current, desired, parent, yawAxisSign(bearing))
                                : BearingServoMath.signedDirectionAngle(current, desired, axis)),
                        maxAxisDegrees);
                return;
            }
            List<LegacyFiniteAimSolver.Ray> rays = finiteRays(bearing, targets);
            double correction = aimCorrection(bearing, current, desired, axis, parent, rays);
            double compensation = 0.0;
            if (!previousAimTargets.isEmpty() && !targets.isEmpty()) {
                boolean sameSources = previousAimTargets.size() == targets.size();
                for (int i = 0; sameSources && i < targets.size(); i++)
                    sameSources = previousAimTargets.get(i).sameSource(targets.get(i))
                            && previousAimTargets.get(i).fixedElevation() == targets.get(i).fixedElevation()
                            && previousAimTargets.get(i).elevationCorrection() == targets.get(i).elevationCorrection();
                if (!sameSources) stabilizedAimMotion.reset();
            }
            if (stabilizedAimMotion.initialized()) {
                List<LegacyFiniteAimSolver.Ray> previousRays = finiteRays(bearing, previousAimTargets);
                if (previousAimTargets.isEmpty() || !previousRays.isEmpty()) {
                    double previousErrorNow = aimCorrection(bearing, current, previousRequestedDirection,
                            axis, parent, previousRays);
                    // Active traverse already inherited this carrier motion on
                    // every physics substep. Do not undo it as world stabilization.
                    compensation = stabilizedAimMotion.compensation(previousErrorNow,
                            !(yawAxis && yawLimits.enabled())) + inheritedTraverse;
                    if (!(yawAxis && yawLimits.enabled())) compensation = BearingServoMath.wrapRadians(compensation);
                } else stabilizedAimMotion.reset();
            }
            // Only the remaining operator correction consumes the head-aim budget.
            // Re-solving the preceding target on the current carrier isolates
            // automatic parallax/ballistics even while head traverse is unfinished.
            double travel = stabilizedAimMotion.advance(correction, compensation, Math.toRadians(maxAxisDegrees),
                    !(yawAxis && yawLimits.enabled()));
            Quaterniond before = new Quaterniond(targetOrientation);
            rotateWithLimit(bearing, axis, Math.toDegrees(travel));
            Quaterniond applied = new Quaterniond(targetOrientation).mul(before.conjugate()).normalize();
            double appliedTravel = BearingServoMath.wrapRadians(2.0 * Math.atan2(
                    applied.x * axis.x + applied.y * axis.y + applied.z * axis.z, applied.w));
            stabilizedAimMotion.record(correction, appliedTravel, !(yawAxis && yawLimits.enabled()));
            acquisitionRemaining = yawAxis && yawLimits.enabled() ? correction - appliedTravel
                    : BearingServoMath.operatorCorrection(correction, appliedTravel);
            acquisitionMaxSpeed = Math.toRadians(maxAxisDegrees * 20.0);
            parentRelativeTraverse = BearingServoMath.followParentDuringTraverse(acquisitionRemaining, acquisitionMaxSpeed, .05)
                    && Math.abs(BearingServoMath.wrapRadians(travel - appliedTravel)) < 1e-7;
            inheritedTraverse = 0.0;
            previousRequestedDirection.set(desired);
            previousAimTargets = List.copyOf(targets);
            if (!rays.isEmpty()) {
                long now = bearing.getLevel().getGameTime();
                if (lastFiniteDiagnosticTick == Long.MIN_VALUE || now < lastFiniteDiagnosticTick
                        || now - lastFiniteDiagnosticTick >= 200) {
                    lastFiniteDiagnosticTick = now;
                    LegacyFiniteAimSolver.Ray ray = rays.getFirst();
                    StabilizedTurret.LOGGER.info("Legacy finite aim diagnostics: bearing={} yaw={} correctionDeg={} stepDeg={}"
                                    + " stabilizeWorld={} parentTraverse={} headStepDeg={}"
                                    + " targets={} muzzle={} target={} gravity={}", bearing.getBlockPos(), yawAxis,
                            Math.toDegrees(correction), Math.toDegrees(appliedTravel), stabilizeWorld, parentRelativeTraverse,
                            Math.toDegrees(BearingServoMath.operatorCorrection(appliedTravel, compensation)),
                            rays.size(), ray.muzzle(), ray.target(),
                            ray.profile() == null ? 0.0 : ray.profile().gravity());
                }
            }
        }

        private double aimCorrection(SwivelBearingBlockEntity bearing, Vector3d current, Vector3d desired,
                                     Vector3d axis, Quaterniond parent, List<LegacyFiniteAimSolver.Ray> rays) {
            if (yawAxis && yawLimits.enabled()) {
                if (!rays.isEmpty()) {
                    double correction = LegacyFiniteAimSolver.correction(rays,
                            vec(offsetWorldAnchor(bearing, new Vector3d())), vec(axis),
                            vec(parent.transform(new Vector3d(0, 1, 0)).normalize()), true);
                    desired = new Quaterniond().rotationAxis(correction, axis.x, axis.y, axis.z).transform(new Vector3d(current));
                }
                return yawLimits.errorRadians(current, desired, parent, yawAxisSign(bearing));
            }
            if (!rays.isEmpty()) return LegacyFiniteAimSolver.correction(rays,
                    vec(offsetWorldAnchor(bearing, new Vector3d())), vec(axis),
                    vec(parent.transform(new Vector3d(0, 1, 0)).normalize()), yawAxis);
            // Elevation tracks elevation independently of yaw acquisition;
            // a sideways or rearward target must not ask it for a half-turn.
            if (!yawAxis) desired = BearingServoMath.pitchDirection(desired, current, parent);
            Vector3d currentProjected = new Vector3d(current)
                    .sub(new Vector3d(axis).mul(current.dot(axis)));
            Vector3d desiredProjected = new Vector3d(desired)
                    .sub(new Vector3d(axis).mul(desired.dot(axis)));
            if (currentProjected.lengthSquared() < 1.0e-8
                    || desiredProjected.lengthSquared() < 1.0e-8) return 0.0;

            currentProjected.normalize();
            desiredProjected.normalize();
            double sine = axis.dot(new Vector3d(currentProjected).cross(desiredProjected));
            double cosine = Mth.clamp(currentProjected.dot(desiredProjected), -1.0, 1.0);
            return Math.atan2(sine, cosine);
        }

        private double applyManualAimStep(SwivelBearingBlockEntity bearing, Vector3d axis,
                                          double errorDegrees, double maxAxisDegrees) {
            double step = Math.toDegrees(operatorMotion.advanceDamped(Math.toRadians(errorDegrees),
                            Math.toRadians(maxAxisDegrees * 20.0), 1.0 / 20.0));
            Quaterniond expected = rotated(targetOrientation, axis, step);
            rotateWithLimit(bearing, axis, step);
            if (1.0 - Math.abs(expected.dot(targetOrientation)) > 1e-12) operatorMotion.reset();
            return step;
        }

        private List<LegacyFiniteAimSolver.Ray> finiteRays(SwivelBearingBlockEntity bearing,
                                                          List<FiniteAimTarget> targets) {
            if (targets.isEmpty()) return List.of();
            Level level = bearing.getLevel();
            SubLevel child = SubLevelContainer.getContainer(level).getSubLevel(childId);
            if (child == null || child.isRemoved()) return List.of();
            Vector3d actualPivot = child.logicalPose().transformPosition(new Vector3d(childLocalPivot));
            Vector3d targetPivot = offsetWorldAnchor(bearing, new Vector3d());
            Quaterniond toTarget = new Quaterniond(targetOrientation)
                    .mul(new Quaterniond(child.logicalPose().orientation()).conjugate()).normalize();
            List<LegacyFiniteAimSolver.Ray> rays = new ArrayList<>();
            for (FiniteAimTarget target : targets) {
                SubLevel body = SubLevelContainer.getContainer(level).getSubLevel(target.bodyId());
                if (body == null || body.isRemoved()) return List.of();
                Vec3 point = target.movingRay() == null ? target.worldTarget() : target.movingRay().currentTarget(level);
                if (point == null) return List.of();
                Vector3d muzzle = body.logicalPose().transformPosition(
                        new Vector3d(target.localMuzzle().x, target.localMuzzle().y, target.localMuzzle().z));
                Vector3d forward = body.logicalPose().transformNormal(
                        new Vector3d(target.localForward().x, target.localForward().y, target.localForward().z));
                toTarget.transform(muzzle.sub(actualPivot)).add(targetPivot);
                toTarget.transform(forward).normalize();
                rays.add(new LegacyFiniteAimSolver.Ray(vec(muzzle), vec(forward), point, target.profile(),
                        Vec3.ZERO, null, target.elevationCorrection(), target.fixedElevation()));
            }
            return rays;
        }

        private static Vec3 vec(Vector3d value) { return new Vec3(value.x, value.y, value.z); }

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
                    Vector3d bounded = yawLimits.advance(forward, parent, Math.toRadians(requestedDegrees), yawAxisSign(bearing));
                    targetOrientation.set(rotated(targetOrientation, axis,
                            Math.toDegrees(BearingServoMath.signedDirectionAngle(forward, bounded, axis))));
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
                    // rigid boundary.
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

        private void inheritParentForControl(SwivelBearingBlockEntity bearing) {
            if (!stabilizeWorld) {
                inheritFullParentRotation(bearing);
                return;
            }
            if (!parentRelativeTraverse) {
                inheritParentSwing(bearing);
                return;
            }
            Vector3d before = targetOrientation.transform(new Vector3d(aimLocalForward)).normalize();
            inheritFullParentRotation(bearing);
            Quaterniond parent = parentOrientation(bearing, new Quaterniond());
            Vector3d axis = worldAxis(bearing, new Vector3d(), null);
            Vector3d after = targetOrientation.transform(new Vector3d(aimLocalForward)).normalize();
            if (!yawAxis) {
                before = BearingServoMath.pitchDirection(before, after, parent);
                after = BearingServoMath.pitchDirection(after, after, parent);
            }
            double carried = BearingServoMath.signedDirectionAngle(before, after, axis);
            double bounded = BearingServoMath.boundParentTraverse(acquisitionRemaining, carried);
            if (Math.abs(carried - bounded) > 1e-10) rotateWithLimit(bearing, axis, Math.toDegrees(bounded - carried));
            inheritedTraverse += bounded;
            acquisitionRemaining = yawAxis && yawLimits.enabled() ? acquisitionRemaining - bounded
                    : BearingServoMath.operatorCorrection(acquisitionRemaining, bounded);
            if (!BearingServoMath.followParentDuringTraverse(acquisitionRemaining, acquisitionMaxSpeed, .05))
                parentRelativeTraverse = false;
        }

        private static double yawAxisSign(SwivelBearingBlockEntity bearing) {
            return bearing.getBlockState().getValue(SwivelBearingBlock.FACING).getStepY() < 0 ? -1.0 : 1.0;
        }

        private void enforceRigidPitchLimit(SwivelBearingBlockEntity bearing) {
            if (yawAxis) {
                if (yawLimits.enabled()) {
                    Quaterniond parent = parentOrientation(bearing, new Quaterniond());
                    Vector3d forward = targetOrientation.transform(new Vector3d(aimLocalForward));
                    Vector3d bounded = yawLimits.limitDirection(forward, parent);
                    Vector3d axis = worldAxis(bearing, new Vector3d(), null);
                    targetOrientation.set(rotated(targetOrientation, axis,
                            Math.toDegrees(BearingServoMath.signedDirectionAngle(forward, bounded, axis))));
                }
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

        private void enforce(SwivelBearingBlockEntity bearing, SubLevelPhysicsSystem physicsSystem) {
            Level level = bearing.getLevel();
            if (!initialized || level == null
                    || level.getGameTime() - lastHeartbeat > HEARTBEAT_TOLERANCE_TICKS
                    || !isBound(bearing)) {
                ACTIVE.remove(bearing);
                return;
            }
            // Physics post-step callbacks run once for every loaded server
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
                ACTIVE.remove(bearing);
                return;
            }

            inheritParentForControl(bearing);
            projectTargetOntoJoint(bearing);
            enforceRigidPitchLimit(bearing);

            Pose3d pose = child.logicalPose();
            Vector3d pivotOffset = new Vector3d(childLocalPivot)
                    .sub(pose.rotationPoint())
                    .mul(pose.scale());
            targetOrientation.transform(pivotOffset);
            Vector3d targetPosition = offsetWorldAnchor(bearing, new Vector3d()).sub(pivotOffset);

            double initialCorrectionSquared = targetPosition.distanceSquared(pose.position());
            if (!isFinite(targetPosition) || !isFinite(targetOrientation)
                    || (!decoupleReady && initialCorrectionSquared
                    > MAX_INITIAL_LOCK_CORRECTION_BLOCKS
                    * MAX_INITIAL_LOCK_CORRECTION_BLOCKS)) {
                StabilizedTurret.LOGGER.error(
                        "Refused unsafe initial fire-control bearing correction for body {}: "
                                + "current {}, target {}, distance {}. Keeping Simulated's native constraint.",
                        childId,
                        pose.position(),
                        targetPosition,
                        Double.isFinite(initialCorrectionSquared)
                                ? Math.sqrt(initialCorrectionSquared)
                                : Double.NaN);
                reset();
                return;
            }

            PhysicsPipeline pipeline = physicsSystem.getPipeline();
            pipeline.resetVelocity(child);
            double orientationDot = Math.abs(targetOrientation.dot(pose.orientation()));
            boolean positionError = targetPosition.distanceSquared(pose.position()) > 1.0e-10;
            boolean orientationError = 1.0 - Mth.clamp(orientationDot, 0.0, 1.0) > 1.0e-12;
            if (positionError || orientationError) {
                pipeline.teleport(child, targetPosition, targetOrientation);
            }
            pipeline.resetVelocity(child);
            decoupleReady = true;
            suppressBoundConstraint(bearing);
        }

        private Vector3d offsetWorldAnchor(SwivelBearingBlockEntity bearing, Vector3d destination) {
            worldAnchor(bearing, destination);
            Vector3d worldOffset = new Vector3d(localOffset);
            SubLevel parent = Sable.HELPER.getContaining(bearing);
            if (parent != null) {
                parent.logicalPose().transformNormal(worldOffset);
                worldOffset.mul(parent.logicalPose().scale());
            }
            return destination.add(worldOffset);
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

        private static void cancelAxialVelocity(
                PhysicsPipeline pipeline,
                ServerSubLevel child,
                Vector3d worldAxis
        ) {
            Vector3d angularVelocity = pipeline.getAngularVelocity(child, new Vector3d());
            double axialVelocity = angularVelocity.dot(worldAxis);
            if (Math.abs(axialVelocity) > 1.0e-8) {
                pipeline.addLinearAndAngularVelocity(
                        child,
                        new Vector3d(),
                        new Vector3d(worldAxis).mul(-axialVelocity)
                );
            }
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
