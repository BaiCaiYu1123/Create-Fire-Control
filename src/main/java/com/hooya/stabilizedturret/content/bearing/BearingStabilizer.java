package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.mixin.SwivelBearingBlockEntityAccessor;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public final class BearingStabilizer {
    private static final long HEARTBEAT_TOLERANCE_TICKS = 2L;
    private static final String TOOLGUN_NO_COLLISION_TAG = "AstDisableStructureCollision";
    private static final String OWNED_NO_COLLISION_TAG = "StabilizedTurretOwnsNoCollision";

    private static final Map<SwivelBearingBlockEntity, BoundState> BOUND =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<SwivelBearingBlockEntity, AxisLock> ACTIVE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<SwivelBearingBlockEntity, ConstrainedServo> CONSTRAINED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<SwivelBearingBlockEntity, AssemblyZero> ASSEMBLY_ZEROS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<UUID, CollisionState> COLLISION_DISABLED =
            Collections.synchronizedMap(new HashMap<>());
    private static final Map<KinematicContraption, ContraptionCollisionState> CONTRAPTION_COLLISION_DISABLED =
            Collections.synchronizedMap(new WeakHashMap<>());

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
        lock.stabilizeWorld = stabilizeWorld;
        lock.setLimits(depressionLimitDegrees, elevationLimitDegrees);
        lock.setLocalOffset(localOffset);
        lock.lastHeartbeat = level.getGameTime();
        boolean lockReady = lock.initialize(bearing);
        if (lockReady && acceptMechanicalInput) {
            double angularStep = KineticBlockEntity.convertToAngular(
                    bearing.getExtraKinetics().getSpeed());
            if (bearing.getBlockState().getValue(SwivelBearingBlock.FACING).getAxisDirection()
                    == Direction.AxisDirection.NEGATIVE) {
                angularStep *= -1.0;
            }
            double maxStep = Math.max(0.0, maxDegreesPerSecond) / 20.0;
            lock.rotateMechanically(bearing, Mth.clamp(angularStep, -maxStep, maxStep));
        }
        if (lockReady) {
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
        Level level = bearing.getLevel();
        if (level == null || level.isClientSide || !bearing.isAssembled()
                || measuredWorldDirection.lengthSqr() < 1.0e-8
                || requestedWorldDirection.lengthSqr() < 1.0e-8) {
            return;
        }

        maintainBinding(bearing, yawAxis, true, true, false, 0.0,
                depressionLimitDegrees, elevationLimitDegrees, localOffset);
        AxisLock lock = ACTIVE.get(bearing);
        if (lock != null && lock.initialize(bearing)) {
            lock.aimAtDirection(
                    bearing,
                    measuredWorldDirection.normalize(),
                    requestedWorldDirection.normalize(),
                    Math.max(0.0, maxDegreesPerSecond) / 20.0
            );
        }
        if (lock != null && lock.initialized) {
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
        double step = Mth.clamp(error,
                -Math.max(0.0, maxDegreesPerSecond) / 20.0,
                Math.max(0.0, maxDegreesPerSecond) / 20.0);
        double currentTarget = bearing.getTargetAngleDegrees();
        double candidate = currentTarget + step;
        if (yawAxis) {
            // Keep a reversed servo bounded around the angle captured when it
            // was connected. A wrong reversal setting may make the bearing
            // chase away from the requested direction, but it must never
            // accumulate an unbounded target angle.
            candidate = servo.zeroAngle
                    + Mth.wrapDegrees(candidate - servo.zeroAngle);
        } else {
            double relative = servo.zeroAngle + Mth.wrapDegrees(candidate - servo.zeroAngle);
            candidate = Mth.clamp(relative,
                    servo.zeroAngle - servo.depression,
                    servo.zeroAngle + servo.elevation);
        }
        SwivelBearingBlockEntityAccessor accessor = (SwivelBearingBlockEntityAccessor) bearing;
        accessor.stabilizedTurret$setTargetAngleDegrees(candidate);
        bearing.updateServoCoefficients();
        bearing.setChanged();
    }

    /** Returns Aeronautics' original joint to the angle captured at binding. */
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
        double current = bearing.getTargetAngleDegrees();
        double error = Mth.wrapDegrees(servo.zeroAngle - current);
        double step = Mth.clamp(
                error,
                -Math.max(0.0, maxDegreesPerSecond) / 20.0,
                Math.max(0.0, maxDegreesPerSecond) / 20.0
        );
        double candidate = current + step;
        if (!yawAxis) {
            double relative = servo.zeroAngle
                    + Mth.wrapDegrees(candidate - servo.zeroAngle);
            candidate = Mth.clamp(
                    relative,
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

    public static void suppressMotor(SwivelBearingBlockEntity bearing) {
        BoundState state = BOUND.get(bearing);
        AxisLock lock = ACTIVE.get(bearing);
        if (isBound(bearing) && state != null && state.decouplePhysics
                && lock != null && lock.initialized) {
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
        Map<UUID, ServerSubLevel> liveChildren = collectLiveChildren(level);

        for (ServerSubLevel child : liveChildren.values()) {
            UUID childId = child.getUniqueId();
            if (child.isRemoved() || child.getMassTracker().getCenterOfMass() == null) {
                continue;
            }

            CollisionState collisionState;
            synchronized (COLLISION_DISABLED) {
                collisionState = COLLISION_DISABLED.computeIfAbsent(childId, ignored -> {
                    CollisionState created = new CollisionState(child);
                    StabilizedTurret.LOGGER.info(
                            "Applying bound no-collision/no-gravity state to sublevel {}",
                            childId);
                    return created;
                });
            }
            collisionState.applyTags();
            cancelGravity(level, pipeline, child, timeStep);
        }
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
            if (physicsSystem == null) {
                continue;
            }
            PhysicsPipeline pipeline = physicsSystem.getPipeline();
            Map<UUID, ServerSubLevel> liveChildren = collectLiveChildren(level);
            Set<UUID> livePitchChildren = collectLivePitchChildren(liveChildren.keySet());
            restoreUnboundColliders(level, pipeline, liveChildren.keySet());
            for (ServerSubLevel child : liveChildren.values()) {
                if (child.isRemoved()) {
                    continue;
                }
                CollisionState collisionState;
                synchronized (COLLISION_DISABLED) {
                    collisionState = COLLISION_DISABLED.computeIfAbsent(
                            child.getUniqueId(),
                            ignored -> new CollisionState(child)
                    );
                }
                collisionState.applyTags();
                suppressLoadedSections(pipeline, child, collisionState);
            }
            refreshContraptionCollisionState(level, pipeline, liveChildren, livePitchChildren);
        }
    }

    public static boolean shouldDisableContraptionCollision(KinematicContraption contraption) {
        if (!(contraption instanceof Entity entity) || entity.isRemoved()) {
            return false;
        }
        SubLevel containing = Sable.HELPER.getContaining(entity);
        if (containing == null) {
            return false;
        }
        UUID containingId = containing.getUniqueId();
        for (Map.Entry<SwivelBearingBlockEntity, BoundState> entry : boundSnapshot()) {
            BoundState state = entry.getValue();
            if (!state.yawAxis
                    && state.childId.equals(containingId)
                    && isLiveBinding(entry.getKey(), state)) {
                return true;
            }
        }
        return false;
    }

    private static Set<UUID> collectLivePitchChildren(Set<UUID> liveChildIds) {
        Set<UUID> pitchChildren = new HashSet<>();
        for (Map.Entry<SwivelBearingBlockEntity, BoundState> entry : boundSnapshot()) {
            BoundState state = entry.getValue();
            if (!state.yawAxis
                    && liveChildIds.contains(state.childId)
                    && isLiveBinding(entry.getKey(), state)) {
                pitchChildren.add(state.childId);
            }
        }
        return pitchChildren;
    }

    private static void refreshContraptionCollisionState(
            ServerLevel level,
            PhysicsPipeline pipeline,
            Map<UUID, ServerSubLevel> liveChildren,
            Set<UUID> livePitchChildren
    ) {
        Map<KinematicContraption, UUID> shouldBeDisabled = new HashMap<>();
        for (UUID childId : livePitchChildren) {
            ServerSubLevel child = liveChildren.get(childId);
            if (child == null || child.isRemoved()) {
                continue;
            }
            for (KinematicContraption contraption : contraptionSnapshot(child)) {
                if (contraption.sable$isValid()) {
                    shouldBeDisabled.put(contraption, childId);
                }
            }
        }

        for (Map.Entry<KinematicContraption, ContraptionCollisionState> entry
                : contraptionCollisionSnapshot()) {
            KinematicContraption contraption = entry.getKey();
            ContraptionCollisionState state = entry.getValue();
            if (state.level != level) {
                continue;
            }
            if (!contraption.sable$isValid()) {
                CONTRAPTION_COLLISION_DISABLED.remove(contraption);
                continue;
            }
            if (shouldBeDisabled.containsKey(contraption)) {
                continue;
            }

            pipeline.remove(contraption);
            pipeline.add(contraption);
            CONTRAPTION_COLLISION_DISABLED.remove(contraption);
            StabilizedTurret.LOGGER.info(
                    "Restored CBC/Create cannon collision for contraption formerly in sublevel {}",
                    state.childId);
        }

        for (Map.Entry<KinematicContraption, UUID> entry : shouldBeDisabled.entrySet()) {
            KinematicContraption contraption = entry.getKey();
            if (CONTRAPTION_COLLISION_DISABLED.containsKey(contraption)) {
                continue;
            }

            pipeline.remove(contraption);
            pipeline.add(contraption);
            CONTRAPTION_COLLISION_DISABLED.put(
                    contraption,
                    new ContraptionCollisionState(level, entry.getValue()));
            StabilizedTurret.LOGGER.info(
                    "Disabled CBC/Create cannon collision against turret and hull for pitch sublevel {}",
                    entry.getValue());
        }
    }

    private static Set<KinematicContraption> contraptionSnapshot(ServerSubLevel child) {
        Set<KinematicContraption> result = new HashSet<>();
        try {
            Object contraptions = child.getPlot()
                    .getClass()
                    .getMethod("getContraptions")
                    .invoke(child.getPlot());
            if (contraptions instanceof Iterable<?> iterable) {
                for (Object value : iterable) {
                    if (value instanceof KinematicContraption contraption) {
                        result.add(contraption);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.warn(
                    "Unable to enumerate Sable contraptions for collision refresh in sublevel {}",
                    child.getUniqueId(),
                    exception
            );
        }
        return result;
    }

    private static Map<UUID, ServerSubLevel> collectLiveChildren(ServerLevel physicsLevel) {
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
                if (lock != null && lock.initialized) {
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

    private static void suppressLoadedSections(
            PhysicsPipeline pipeline,
            ServerSubLevel child,
            CollisionState state
    ) {
        int removed = 0;
        for (PlotChunkHolder holder : child.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            ChunkPos chunkPos = holder.getPos();
            LevelChunkSection[] sections = chunk.getSections();
            for (int index = 0; index < chunk.getSectionsCount(); index++) {
                LevelChunkSection section = sections[index];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }

                int sectionY = chunk.getSectionYFromSectionIndex(index);
                long sectionKey = SectionPos.asLong(chunkPos.x, sectionY, chunkPos.z);
                if (state.removedSections.add(sectionKey)) {
                    pipeline.handleChunkSectionRemoval(chunkPos.x, sectionY, chunkPos.z);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            pipeline.onStatsChanged(child);
        }
    }

    private static void restoreUnboundColliders(
            ServerLevel level,
            PhysicsPipeline pipeline,
            Set<UUID> liveChildIds
    ) {
        for (Map.Entry<UUID, CollisionState> entry : collisionSnapshot()) {
            CollisionState state = entry.getValue();
            ServerSubLevel child = state.child;
            if (child.getLevel() != level || liveChildIds.contains(entry.getKey())) {
                continue;
            }
            COLLISION_DISABLED.remove(entry.getKey());
            if (!child.isRemoved() && !state.collisionDisabledBeforeBinding) {
                restoreLoadedSections(pipeline, child, state);
            }
            state.restoreTags();
        }
    }

    private static boolean isToolgunCollisionDisabled(ServerSubLevel child) {
        return child.getUserDataTag() != null
                && child.getUserDataTag().getBoolean(TOOLGUN_NO_COLLISION_TAG);
    }

    private static void restoreLoadedSections(
            PhysicsPipeline pipeline,
            ServerSubLevel child,
            CollisionState state
    ) {
        int restored = 0;
        for (PlotChunkHolder holder : child.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            ChunkPos chunkPos = holder.getPos();
            LevelChunkSection[] sections = chunk.getSections();
            for (int index = 0; index < chunk.getSectionsCount(); index++) {
                LevelChunkSection section = sections[index];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }

                int sectionY = chunk.getSectionYFromSectionIndex(index);
                long sectionKey = SectionPos.asLong(chunkPos.x, sectionY, chunkPos.z);
                if (state.removedSections.remove(sectionKey)) {
                    pipeline.handleChunkSectionAddition(
                            section,
                            chunkPos.x,
                            sectionY,
                            chunkPos.z,
                            true
                    );
                    restored++;
                }
            }
        }
        state.removedSections.clear();
        if (restored > 0) {
            pipeline.onStatsChanged(child);
        }
    }

    private static void cancelGravity(
            ServerLevel level,
            PhysicsPipeline pipeline,
            ServerSubLevel child,
            double timeStep
    ) {
        Vector3d localImpulse = DimensionPhysicsData
                .getGravity(level, child.logicalPose().position())
                .mul(-child.getMassTracker().getMass() * timeStep);
        child.logicalPose().orientation().transformInverse(localImpulse);
        pipeline.applyLinearAndAngularImpulse(child, localImpulse, new Vector3d(), false);
    }

    public static void enforceWorldLocks(SubLevelPhysicsSystem physicsSystem) {
        Set<Map.Entry<SwivelBearingBlockEntity, AxisLock>> snapshot = activeSnapshot();
        for (boolean yawPass : new boolean[]{true, false}) {
            for (Map.Entry<SwivelBearingBlockEntity, AxisLock> entry : snapshot) {
                if (entry.getValue().yawAxis == yawPass) {
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
        return level instanceof ServerLevel serverLevel ? serverLevel : null;
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

    private static Set<Map.Entry<UUID, CollisionState>> collisionSnapshot() {
        synchronized (COLLISION_DISABLED) {
            return Set.copyOf(COLLISION_DISABLED.entrySet());
        }
    }

    private static Set<Map.Entry<KinematicContraption, ContraptionCollisionState>>
    contraptionCollisionSnapshot() {
        synchronized (CONTRAPTION_COLLISION_DISABLED) {
            return Set.copyOf(CONTRAPTION_COLLISION_DISABLED.entrySet());
        }
    }

    private BearingStabilizer() {
    }

    /** Captures the neutral angle at the end of Simulated's assembly call. */
    public static void captureAssemblyZero(
            SwivelBearingBlockEntity bearing) {
        if (bearing == null || !bearing.isAssembled()
                || bearing.getSubLevelID() == null) return;
        ASSEMBLY_ZEROS.put(
                bearing,
                new AssemblyZero(
                        bearing.getSubLevelID(),
                        bearing.getTargetAngleDegrees()));
        ConstrainedServo servo = CONSTRAINED.get(bearing);
        if (servo != null) {
            servo.zeroAngle = bearing.getTargetAngleDegrees();
        }
    }

    public static void forgetAssemblyZero(
            SwivelBearingBlockEntity bearing) {
        ASSEMBLY_ZEROS.remove(bearing);
        removeConstrained(bearing);
    }

    private static double assemblyZeroOrCurrent(
            SwivelBearingBlockEntity bearing) {
        AssemblyZero zero = ASSEMBLY_ZEROS.get(bearing);
        UUID child = bearing.getSubLevelID();
        return zero != null && child != null
                && child.equals(zero.childId)
                ? zero.angle
                : bearing.getTargetAngleDegrees();
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
        ConstrainedServo(SwivelBearingBlockEntity bearing, boolean yawAxis) {
            this.originalLockMode=((SwivelBearingBlockEntityAccessor)bearing)
                    .stabilizedTurret$getLockedDefaultOption().getValue();
            reset(assemblyZeroOrCurrent(bearing), yawAxis);
        }
        void reset(double zeroAngle, boolean yawAxis) {
            this.zeroAngle = zeroAngle;
            this.yawAxis = yawAxis;
        }
    }

    private record AssemblyZero(UUID childId, double angle) {}

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
        private boolean yawAxis;
        private boolean stabilizeWorld = true;
        private boolean elevationSignInitialized;
        private boolean elevationFrameInitialized;
        private double jointToElevationSign = 1.0;
        private double depressionLimitDegrees = 90.0;
        private double elevationLimitDegrees = 90.0;
        private double zeroJointAngleDegrees;
        private long lastHeartbeat = Long.MIN_VALUE;

        private void reset() {
            childId = null;
            initialized = false;
            elevationSignInitialized = false;
            elevationFrameInitialized = false;
            jointToElevationSign = 1.0;
            zeroJointAngleDegrees = 0.0;
        }

        private void setLimits(double depression, double elevation) {
            depressionLimitDegrees = Mth.clamp(depression, 0.0, 90.0);
            elevationLimitDegrees = Mth.clamp(elevation, 0.0, 90.0);
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
            if (child.getMassTracker().getCenterOfMass() == null) {
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
            Vec3 plateAnchor = Vec3.atCenterOf(platePos.relative(plateFacing))
                    .subtract(Vec3.atLowerCornerOf(plateFacing.getNormal()).scale(0.001));
            Pose3d pose = child.logicalPose();
            childLocalPivot.set(plateAnchor.x, plateAnchor.y, plateAnchor.z);
            double initialJointAngle = canonicalJointAngleDegrees(bearing, pose.orientation());
            targetOrientation.set(orientationForJointAngle(bearing, plateFacing, initialJointAngle));
            parentOrientation(bearing, lastParentOrientation);
            zeroJointAngleDegrees = 0.0;
            childId = attachedId;
            initialized = true;
            elevationSignInitialized = false;
            elevationFrameInitialized = false;
            jointToElevationSign = 1.0;
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
                double maxAxisDegrees
        ) {
            if (!yawAxis) {
                calibrateElevationDirection(bearing, measured);
            }

            Vector3d current = new Vector3d(measured.x, measured.y, measured.z).normalize();
            Vector3d desired = new Vector3d(requested.x, requested.y, requested.z).normalize();
            Vector3d axis = worldAxis(bearing, new Vector3d(), null);
            Vector3d currentProjected = new Vector3d(current)
                    .sub(new Vector3d(axis).mul(current.dot(axis)));
            Vector3d desiredProjected = new Vector3d(desired)
                    .sub(new Vector3d(axis).mul(desired.dot(axis)));
            if (currentProjected.lengthSquared() < 1.0e-8
                    || desiredProjected.lengthSquared() < 1.0e-8) {
                return;
            }

            currentProjected.normalize();
            desiredProjected.normalize();
            double sine = axis.dot(new Vector3d(currentProjected).cross(desiredProjected));
            double cosine = Mth.clamp(currentProjected.dot(desiredProjected), -1.0, 1.0);
            double errorDegrees = Math.toDegrees(Math.atan2(sine, cosine));

            // Direct position servo: run at the configured speed cap while
            // the error is large, then consume the exact remaining angle on
            // the final tick. There is deliberately no filtering, damping,
            // hysteresis or gameplay dead zone. Once both directions match,
            // atan2 returns exactly zero and the bearing stops immediately.
            double commandDegrees = Mth.clamp(
                    errorDegrees,
                    -maxAxisDegrees,
                    maxAxisDegrees
            );
            if (commandDegrees == 0.0) {
                return;
            }
            rotateWithLimit(bearing, axis, commandDegrees);
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
                targetOrientation.set(candidate);
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

        private void enforceRigidPitchLimit(SwivelBearingBlockEntity bearing) {
            if (yawAxis) {
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
                    || rootServerLevel(level) != physicsSystem.getLevel()
                    || level.getGameTime() - lastHeartbeat > HEARTBEAT_TOLERANCE_TICKS
                    || !isBound(bearing)) {
                ACTIVE.remove(bearing);
                return;
            }

            SubLevel attached = SubLevelContainer.getContainer(level).getSubLevel(childId);
            if (!(attached instanceof ServerSubLevel child) || child.isRemoved()) {
                ACTIVE.remove(bearing);
                return;
            }

            if (!refreshChildPivot(bearing)) {
                ACTIVE.remove(bearing);
                return;
            }

            if (stabilizeWorld) {
                inheritParentSwing(bearing);
            } else {
                inheritFullParentRotation(bearing);
            }
            projectTargetOntoJoint(bearing);
            enforceRigidPitchLimit(bearing);

            Pose3d pose = child.logicalPose();
            Vector3d pivotOffset = new Vector3d(childLocalPivot)
                    .sub(pose.rotationPoint())
                    .mul(pose.scale());
            targetOrientation.transform(pivotOffset);
            Vector3d targetPosition = offsetWorldAnchor(bearing, new Vector3d()).sub(pivotOffset);

            PhysicsPipeline pipeline = physicsSystem.getPipeline();
            pipeline.resetVelocity(child);
            double orientationDot = Math.abs(targetOrientation.dot(pose.orientation()));
            boolean positionError = targetPosition.distanceSquared(pose.position()) > 1.0e-10;
            boolean orientationError = 1.0 - Mth.clamp(orientationDot, 0.0, 1.0) > 1.0e-12;
            if (positionError || orientationError) {
                pipeline.teleport(child, targetPosition, targetOrientation);
            }
            pipeline.resetVelocity(child);
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

        private boolean refreshChildPivot(SwivelBearingBlockEntity bearing) {
            BlockPos platePos = bearing.getPlatePos();
            if (platePos == null || bearing.getLevel() == null) {
                return false;
            }
            BlockState plateState = bearing.getLevel().getBlockState(platePos);
            if (!plateState.hasProperty(SwivelBearingPlateBlock.FACING)) {
                return false;
            }
            Direction plateFacing = plateState.getValue(SwivelBearingPlateBlock.FACING);
            Vec3 plateAnchor = Vec3.atCenterOf(platePos.relative(plateFacing))
                    .subtract(Vec3.atLowerCornerOf(plateFacing.getNormal()).scale(0.001));
            childLocalPivot.set(plateAnchor.x, plateAnchor.y, plateAnchor.z);
            return true;
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

    private record ContraptionCollisionState(ServerLevel level, UUID childId) {
    }

    private static final class CollisionState {
        private static final String NO_GRAVITY_TAG = "StabilizedTurretNoGravity";
        private static final String OWNED_NO_GRAVITY_TAG = "StabilizedTurretOwnsNoGravity";
        private final ServerSubLevel child;
        private final Set<Long> removedSections = new HashSet<>();
        private final boolean collisionDisabledBeforeBinding;
        private final boolean noGravityBeforeBinding;

        private CollisionState(ServerSubLevel child) {
            this.child = child;
            CompoundTag tag = child.getUserDataTag();
            collisionDisabledBeforeBinding = tag != null
                    && tag.getBoolean(TOOLGUN_NO_COLLISION_TAG)
                    && !tag.getBoolean(OWNED_NO_COLLISION_TAG);
            noGravityBeforeBinding = tag != null
                    && tag.getBoolean(NO_GRAVITY_TAG)
                    && !tag.getBoolean(OWNED_NO_GRAVITY_TAG);
            applyTags();
        }

        private void applyTags() {
            CompoundTag tag = child.getUserDataTag();
            if (tag == null) {
                tag = new CompoundTag();
            }
            tag.putBoolean(TOOLGUN_NO_COLLISION_TAG, true);
            tag.putBoolean(OWNED_NO_COLLISION_TAG, true);
            tag.putBoolean(NO_GRAVITY_TAG, true);
            tag.putBoolean(OWNED_NO_GRAVITY_TAG, true);
            child.setUserDataTag(tag);
        }

        private void restoreTags() {
            CompoundTag tag = child.getUserDataTag();
            if (tag == null) {
                return;
            }
            if (!collisionDisabledBeforeBinding) {
                tag.remove(TOOLGUN_NO_COLLISION_TAG);
            }
            tag.remove(OWNED_NO_COLLISION_TAG);
            if (!noGravityBeforeBinding) {
                tag.remove(NO_GRAVITY_TAG);
            }
            tag.remove(OWNED_NO_GRAVITY_TAG);
            child.setUserDataTag(tag);
        }
    }
}
