package com.hooya.stabilizedturret.content.display;

import com.hooya.stabilizedturret.config.MissileSettings;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.iff.FireControlMissileOwnership;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight;
import com.hooya.stabilizedturret.content.controller.MianbaoCruiseMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileEffects;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileRemoteSync;
import com.hooya.stabilizedturret.network.MissileImpactRegionPayload;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-authoritative boost, curved midcourse and terminal-dive guidance. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class CruiseMissileGuidance {
    private static final int MAX_LIFETIME_TICKS = LandAttackMissileFlight.LIFETIME_TICKS;
    private static final int MIN_NUCLEAR_IMPACT_FLIGHT_TICKS = 20;
    private static final int ENGINE_IGNITION_TICK = 10;
    private static final int GUIDANCE_START_TICK = 50;
    /** A vertical cold launch clears the silo before beginning its turn. */
    private static final double MIN_VERTICAL_TURN_CLIMB = 32.0;
    private static final int ACCELERATION_TICKS = 10 * 20;
    /** Minecraft blocks per tick for Mach 1 (340.3 m/s at 20 ticks/second). */
    private static final double MACH_1_SPEED = 17.015;
    /** Purpose-built ground VLS families retain their existing Mach-2 cap. */
    private static final double GROUND_VLS_MAX_SPEED = 34.03;
    private static final double MAX_ARC_HEIGHT = 200.0;
    private static final double MAX_STEER = Math.toRadians(2.25);
    private static final double TERMINAL_MAX_STEER = Math.toRadians(3.5);
    private static final double TERMINAL_GUIDANCE_DISTANCE = 1_200.0;
    /** Mach 2 can move over thirty blocks between guidance ticks. */
    private static final double TERMINAL_TARGET_RADIUS = 12.0;
    private static final TicketType<UUID> TICKET = TicketType.create(
            "create_fire_control_cruise_missile", UUID::compareTo);
    private static final TicketType<UUID> IMPACT_TICKET = TicketType.create(
            "create_fire_control_cruise_impact", UUID::compareTo);
    /** Keep a square of 25x25 root-world chunks around every final impact. */
    private static final int IMPACT_CHUNK_RADIUS = 12;
    /** One non-rendered neighbor ring lets all edge sections compile normally. */
    private static final int IMPACT_LOAD_RADIUS = IMPACT_CHUNK_RADIUS + 1;
    /** Limit remote chunk work so one impact cannot monopolize an integrated server. */
    private static final int IMPACT_TICKETS_PER_TICK = 3;
    /**
     * Full chunk packets are only a final consistency repair. Keeping this
     * budget global avoids reintroducing the mesh-rebuild spikes that occurred
     * when every paced terrain mutation re-streamed its chunk immediately.
     */
    private static final int IMPACT_FINAL_CHUNKS_PER_TICK = 2;
    /** Outermost native ring is 150 blocks from impact plus a strength-10 blast. */
    private static final int NATIVE_NUCLEAR_TERRAIN_RADIUS = 170;
    private static final double IMPACT_VIEWER_DISTANCE_SQR = 20000.0 * 20000.0;
    /** Start loading the impact square during the final three seconds of flight. */
    private static final int IMPACT_PRELOAD_TICKS = 60;
    private static final Map<Entity, State> GUIDED = new IdentityHashMap<>();
    private static final Set<ImpactChunkReservation> RETAINED_IMPACTS =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<UUID, ImpactChunkReservation> IMPACTS_BY_MISSILE = new HashMap<>();
    private static @Nullable Pending pending;

    public static void armNext(ConnectedDisplayBlockEntity display, BindingRef launcher,
                               MianbaoCruiseMissileCompat.Type type,
                               @Nullable ConnectedDisplayBlockEntity.TargetKey targetKey,
                               @Nullable Vec3 fixedPoint, Vec3 initialTarget) {
        Vec3 launchDirection = MianbaoCruiseMissileCompat.getLaunchDirection(
                display.getLevel(), launcher);
        if (!isFinite(launchDirection) || launchDirection.lengthSqr() < 1.0e-8) {
            launchDirection = new Vec3(0.0, 1.0, 0.0);
        }
        pending = new Pending(display, launcher, type, targetKey, fixedPoint, initialTarget,
                launchDirection.normalize(),
                display.getLevel() == null ? Long.MIN_VALUE : display.getLevel().getGameTime());
    }

    public static void cancelNext(ConnectedDisplayBlockEntity display) {
        if (pending != null && pending.display == display) pending = null;
    }

    public static boolean hasAnyGuidedMissile() { return !GUIDED.isEmpty(); }

    public static boolean adoptClusterWarhead(Entity warhead) {
        if (warhead == null || warhead.level().isClientSide()) return false;
        Vec3 position = MianbaoMissileCompat.getWorldPosition(warhead);
        for (Map.Entry<Entity, State> entry : GUIDED.entrySet()) {
            Entity missile = entry.getKey();
            State state = entry.getValue();
            if (state.type != MianbaoCruiseMissileCompat.Type.GROUND_CLUSTER
                    || missile.isRemoved() || !missile.isAlive()) continue;
            if (MianbaoMissileCompat.getWorldPosition(missile)
                    .distanceToSqr(position) <= 16.0 * 16.0) {
                warhead.getPersistentData().putBoolean(
                        "CreateFireControlCruiseClusterWarhead", true);
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) adoptPending(event.getEntity(), true);
    }

    /**
     * EntityJoin can be raised before a Sable-projected spawn has its final
     * world coordinates. The direct launch path calls this again after the
     * entity is accepted by the root level, where the pending launch is
     * unambiguous and no coordinate association is needed.
     */
    public static void adoptSpawnedMissile(Entity missile) {
        if (missile != null && !missile.level().isClientSide()) adoptPending(missile, false);
    }

    private static void adoptPending(Entity missile, boolean verifySpawnDistance) {
        Pending armed = pending;
        if (armed == null || armed.display.isRemoved()
                || MianbaoCruiseMissileCompat.missileType(missile) != armed.type) return;
        LevelTime time = levelTime(armed.display);
        if (!time.valid || time.gameTime - armed.armedAt > 2L
                || (verifySpawnDistance && MianbaoCruiseMissileCompat.spawnDistanceSqr(
                armed.display.getLevel(), armed.launcher, missile) > 24.0 * 24.0)) return;
        Vec3 launch = MianbaoMissileCompat.getWorldPosition(missile);
        Vec3 initialVelocity = MianbaoMissileCompat.getWorldVelocity(missile);
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        Vec3 resolvedInitialTarget = armed.initialTarget;
        if (root != null && armed.targetKey == null) {
            Vec3 surfaceTarget = State.resolveGroundAim(root, armed.fixedPoint);
            if (isFinite(surfaceTarget)) resolvedInitialTarget = surfaceTarget;
        }
        State state = new State(armed.display, armed.type, armed.targetKey,
                armed.fixedPoint, resolvedInitialTarget, launch, initialVelocity,
                armed.launchDirection,
                flightCeiling(missile, launch, resolvedInitialTarget));
        GUIDED.put(missile, state);
        missile.getPersistentData().putBoolean("CreateFireControlCruiseGuided", true);
        missile.getPersistentData().putString("CreateFireControlCruiseOwner",
                state.display.cruiseOwnershipToken());
        FireControlMissileOwnership.markDisplay(missile, state.display);
        missile.getPersistentData().putInt("CreateFireControlSafetyTicks", 10);
        if (armed.type.nuclear()) missile.noPhysics = true;
        missile.setNoGravity(true);
        if (root != null && state.lowFlight != null
                && !com.hooya.stabilizedturret.content.controller.GuidedMissileMode.mathematical(missile)) {
            state.chunkLoader.update(root, missile.getUUID(), launch,
                    initialVelocity, state.lowFlight);
        }
        StabilizedTurret.LOGGER.info("Adopted fire-control cruise missile type={} entity={}",
                armed.type.key(), missile.getUUID());
        pending = null;
    }

    @SubscribeEvent
    public static void onTickPre(ServerTickEvent.Pre event) {
        // Native Mianbao trail procedures run from the entity tick and can
        // leave a non-finite velocity behind. Repair it before the next
        // AbstractArrow tick has a chance to move the entity with that value.
        for (Map.Entry<Entity, State> entry : GUIDED.entrySet()) {
            Entity missile = entry.getKey();
            State state = entry.getValue();
            if (missile.isRemoved() || !missile.isAlive()) continue;
            Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
            if (!isFinite(position)) {
                noteNonFiniteRecovery(state, missile, "position");
                if (!MianbaoMissileCompat.setWorldPosition(missile, state.previousPosition)) {
                    continue;
                }
            }
            Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
            if (!isFinite(velocity)) {
                noteNonFiniteRecovery(state, missile, "velocity");
                MianbaoMissileCompat.setWorldVelocity(missile, state.lastSafeVelocity);
            }
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        tickRetainedImpactChunks(event.getServer().overworld());
        Iterator<Map.Entry<Entity, State>> iterator = GUIDED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Entity, State> entry = iterator.next();
            Entity missile = entry.getKey();
            State state = entry.getValue();
            if (missile.isRemoved() || !missile.isAlive()) {
                boolean nativeDetonation = false;
                if (!MianbaoCruiseMissileCompat.wasDetonated(missile)) {
                    Vec3 removedAt = MianbaoMissileCompat.getWorldPosition(missile);
                    nativeDetonation = (!state.type.nuclear()
                            || state.ageTicks >= MIN_NUCLEAR_IMPACT_FLIGHT_TICKS)
                            && missile.getRemovalReason()
                            == Entity.RemovalReason.DISCARDED
                            && state.type != MianbaoCruiseMissileCompat.Type.GROUND_CLUSTER
                            && state.lastKnownTarget != null
                            && removedAt.distanceToSqr(state.lastKnownTarget) <= 4.0;
                    if (nativeDetonation) {
                        MianbaoMissileEffects.broadcastExplosion(missile, removedAt,
                                MianbaoCruiseMissileCompat.visualStrength(missile), false);
                        StabilizedTurret.LOGGER.info(
                                "Relayed native cruise missile explosion type={} entity={} at {}",
                                state.type.key(), missile.getUUID(), removedAt);
                    } else {
                        StabilizedTurret.LOGGER.warn(
                                "Fire-control cruise missile removed before detonation type={} entity={} reason={} age={} position={} target={}",
                                state.type.key(), missile.getUUID(), missile.getRemovalReason(),
                                state.ageTicks, removedAt, state.lastKnownTarget);
                    }
                }
                ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
                if (!com.hooya.stabilizedturret.content.controller.GuidedMissileMode.mathematical(missile)
                        && root != null && state.lastKnownTarget != null
                        && (nativeDetonation
                        || MianbaoCruiseMissileCompat.wasDetonated(missile))) {
                    // A native collision can remove the entity before the next
                    // guidance tick. Arm the root-world impact area here as a
                    // final fallback so the explosion remains in loaded terrain.
                    state.impactChunks.arm(root, missile.getUUID(), state.lastKnownTarget);
                }
                state.release(missile.getUUID(), nativeDetonation
                        || MianbaoCruiseMissileCompat.wasDetonated(missile)
                        || state.impactChunks.isArmed());
                iterator.remove();
                continue;
            }
            // A blind fallback move can put a Mach-2 arrow inside terrain.
            // Mianbao then sees inGround on the next native tick and discards
            // it without producing a HitResult. Hold position while its ticket
            // activates; the native AbstractArrow tick remains collision-safe.
            boolean nativeTickMissed =
                    MianbaoMissileCompat.advanceIfNativeTickMissed(missile);
            if (missile.isRemoved()) continue;
            state.ageTicks++;
            if (state.ageTicks >= MissileSettings.lifetimeTicks(missile)) {
                MianbaoCruiseMissileCompat.detonate(missile);
                state.release(missile.getUUID(), true);
                iterator.remove();
                continue;
            }
            ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
            if (root == null) {
                state.release(missile.getUUID(), false);
                missile.discard();
                iterator.remove();
                continue;
            }
            Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
            if (!isFinite(position)) {
                // The Mianbao ground-missile procedures calculate acos(dot /
                // length) without clamping it. Near-perfect alignment can
                // therefore write NaN into an arrow and Minecraft discards it
                // on the following tick. Restore the last known root position
                // before it can poison chunk tickets or entity movement.
                noteNonFiniteRecovery(state, missile, "position");
                if (!MianbaoMissileCompat.setWorldPosition(missile, state.previousPosition)) {
                    state.release(missile.getUUID(), false);
                    missile.discard();
                    iterator.remove();
                    continue;
                }
                position = state.previousPosition;
            }
            Vec3 target = state.resolveTarget(root);
            if (!isFinite(target)) target = isFinite(state.lastKnownTarget)
                    ? state.lastKnownTarget : null;
            if (target != null) state.lastKnownTarget = target;
            double targetDistance = target == null
                    ? Double.POSITIVE_INFINITY : position.distanceTo(target);
            Vec3 measuredVelocity = MianbaoMissileCompat.getWorldVelocity(missile);
            if (!isFinite(measuredVelocity)) {
                noteNonFiniteRecovery(state, missile, "velocity");
                measuredVelocity = state.lastSafeVelocity;
                MianbaoMissileCompat.setWorldVelocity(missile, measuredVelocity);
            }
            double currentSpeed = Math.max(0.5, measuredVelocity.length());
            if (!com.hooya.stabilizedturret.content.controller.MathematicalMissileManager.contains(missile)
                    && target != null && (targetDistance <= IMPACT_CHUNK_RADIUS * 16.0
                    || targetDistance <= currentSpeed * IMPACT_PRELOAD_TICKS)) {
                // target is always expressed in root-world coordinates by
                // resolveTarget(), including entities inside Sable plots.
                state.impactChunks.arm(root, missile.getUUID(), target);
            }
            Vec3 previous = state.previousPosition;
            if ((!state.type.nuclear()
                    || state.ageTicks >= MIN_NUCLEAR_IMPACT_FLIGHT_TICKS)
                    && target != null && crossedTarget(previous, position, target,
                    Math.max(TERMINAL_TARGET_RADIUS, currentSpeed * 0.9))) {
                // The native procedure only detonates at dis <= 1.0. At the
                // requested Mach-2 cap an entity can step over that condition;
                // detonate at the authoritative root-world target instead of
                // allowing the missile to continue past it.
                MianbaoCruiseMissileCompat.detonate(missile, target);
                state.release(missile.getUUID(), true);
                iterator.remove();
                continue;
            }
            state.travelledHorizontal += horizontalDistance(previous, position);
            state.previousPosition = position;

            // Native trails keep ignition/effects; low cruise steering is
            // independently rate limited in yaw and pitch below.
            // A skipped entity tick still runs the native trail once here.
            MianbaoCruiseMissileCompat.writeNativeTarget(missile, state.type, target);
            // The low-altitude missile's native procedure owns its engine
            // timer. Synchronizing fadongji to our guidance age would alter
            // Mianbao's original acceleration and guidance transition.
            if (!state.type.lowAltitude()) {
                MianbaoCruiseMissileCompat.synchronizeNativeFlightAge(
                        missile, state.type, state.ageTicks);
            }
            boolean trailMissed = MianbaoMissileCompat.consumeCruiseTrailMissed(missile);
            if (nativeTickMissed || trailMissed) {
                MianbaoCruiseMissileCompat.runNativeFlight(missile, state.type);
            }
            if (missile.isRemoved() || !missile.isAlive()) {
                state.release(missile.getUUID(), true);
                iterator.remove();
                continue;
            }
            missile.setNoGravity(true);
            if (state.type.nuclear()) missile.noPhysics = true;
            Vec3 nativeVelocity = MianbaoMissileCompat.getWorldVelocity(missile);
            if (!isFinite(nativeVelocity)) {
                noteNonFiniteRecovery(state, missile, "native flight output");
                nativeVelocity = state.lastSafeVelocity;
                MianbaoMissileCompat.setWorldVelocity(missile, nativeVelocity);
            }
            Vec3 nativeDirection = nativeVelocity.lengthSqr() < 1.0e-8
                    ? state.initialDirection : nativeVelocity.normalize();
            double speed = state.speedAtAge();
            // Low cruise does not use the ordinary midcourse arc.
            if (!state.type.lowAltitude()
                    && target != null && state.guidanceStarted(position, target)) {
                state.captureMinimumTurnHeight(position);
                boolean terminal = position.distanceTo(target)
                        <= TERMINAL_GUIDANCE_DISTANCE
                        || position.distanceTo(target) <= speed * 40.0;
                Vec3 guidancePoint = target;
                double steeringLimit = TERMINAL_MAX_STEER;
                if (!terminal && state.initialRange > 1.0e-8) {
                    // Hold the missile at the original minimum turn altitude
                    // for low targets, while allowing the turn waypoint to
                    // rise with a high or moving target. This applies equally
                    // to fixed map points, living entities and Sable centers.
                    double progress = Mth.clamp(
                            state.travelledHorizontal / state.initialRange,
                            0.0, 1.0);
                    double lookAhead = Mth.clamp(
                            Math.max(24.0, speed * 12.0) / state.initialRange,
                            0.02, 0.20);
                    guidancePoint = state.curvePoint(
                            Math.min(1.0, progress + lookAhead), target);
                    steeringLimit = MAX_STEER;
                }
                Vec3 desiredPath = guidancePoint.subtract(position);
                if (desiredPath.lengthSqr() > 1.0e-8) {
                    Vec3 previousCommand = state.lastSafeVelocity;
                    Vec3 steeringDirection = isFinite(previousCommand)
                            && previousCommand.lengthSqr() >= 1.0e-8
                            ? previousCommand.normalize() : nativeDirection;
                    nativeDirection = rotateTowards(steeringDirection,
                            desiredPath.normalize(), steeringLimit);
                }
            }
            Vec3 commandedVelocity = nativeDirection.scale(
                    Math.min(state.maxSpeed, speed));
            if (state.lowFlight != null) {
                Vec3 lowTarget = target != null ? target
                        : position.add(state.lastSafeVelocity.scale(1000.0));
                commandedVelocity = state.lowFlight.step(position, state.lastSafeVelocity,
                        lowTarget, Math.min(state.maxSpeed, speed), state.ageTicks,
                        (x, z) -> com.hooya.stabilizedturret.content.controller.MathematicalMissileManager.contains(missile)
                                ? com.hooya.stabilizedturret.content.controller.MathematicalMissileManager.groundHeight(root, x, z)
                                : loadedGroundHeight(root, x, z));
            }
            MianbaoMissileCompat.setWorldVelocity(missile, commandedVelocity);
            state.lastSafeVelocity = commandedVelocity;
            if (!com.hooya.stabilizedturret.content.controller.MathematicalMissileManager.contains(missile)) {
                state.chunkLoader.update(root, missile.getUUID(), position, commandedVelocity, state.lowFlight);
            }
            if (state.lowFlight != null) MianbaoMissileRemoteSync.broadcast(missile);
            MianbaoCruiseMissileCompat.refreshNativeFlightOrigin(missile);
            state.display.updateCruiseTelemetry(new ConnectedDisplayBlockEntity.CruiseTelemetry(
                    missile.getUUID(), state.type.key(), position, commandedVelocity,
                    target, state.ageTicks));
        }
    }

    /** Non-loading terrain lookup: a missing chunk is unknown, never empty ground. */
    public static double loadedGroundHeight(ServerLevel root, double x, double z) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        LevelChunk chunk = root.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4);
        return chunk == null ? Double.NaN : chunk.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX & 15, blockZ & 15) + 1.0;
    }

    private static Vec3 rotateTowards(Vec3 current, Vec3 desired, double maxRadians) {
        if (!isFinite(current)) return isFinite(desired) ? desired : new Vec3(0.0, 1.0, 0.0);
        if (!isFinite(desired)) return current;
        current = current.normalize();
        desired = desired.normalize();
        double dot = Math.max(-1.0, Math.min(1.0, current.dot(desired)));
        double angle = Math.acos(dot);
        if (angle <= maxRadians || angle < 1.0e-7) return desired;
        Vec3 axis = current.cross(desired);
        if (axis.lengthSqr() < 1.0e-10) {
            Vec3 reference = Math.abs(current.y) < 0.9
                    ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
            axis = current.cross(reference);
        }
        axis = axis.normalize();
        double cosine = Math.cos(maxRadians);
        double sine = Math.sin(maxRadians);
        Vec3 result = current.scale(cosine)
                .add(axis.cross(current).scale(sine))
                .add(axis.scale(axis.dot(current) * (1.0 - cosine)));
        return result.lengthSqr() < 1.0e-8 ? current : result.normalize();
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        if (!isFinite(first) || !isFinite(second)) return 0.0;
        double x = second.x - first.x;
        double z = second.z - first.z;
        return Math.sqrt(x * x + z * z);
    }

    private static boolean crossedTarget(Vec3 previous, Vec3 current,
                                         Vec3 target, double radius) {
        if (!isFinite(previous) || !isFinite(current) || !isFinite(target)
                || !Double.isFinite(radius)) return false;
        Vec3 segment = current.subtract(previous);
        double lengthSqr = segment.lengthSqr();
        if (lengthSqr < 1.0e-8) {
            return current.distanceToSqr(target) <= radius * radius;
        }
        double fraction = target.subtract(previous).dot(segment) / lengthSqr;
        fraction = Mth.clamp(fraction, 0.0, 1.0);
        Vec3 closest = previous.add(segment.scale(fraction));
        return closest.distanceToSqr(target) <= radius * radius;
    }

    private static double flightCeiling(Entity missile, Vec3 launch,
                                        @Nullable Vec3 target) {
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        double targetY = isFinite(target) ? target.y : launch.y;
        double desiredCeiling = Math.max(launch.y, targetY) + MAX_ARC_HEIGHT;
        if (root == null) return desiredCeiling;
        // Mianbao's LegacyProjectileArrow removes projectiles above the world
        // build ceiling plus 64 blocks. A high target must itself remain below
        // the chosen ceiling; the old launch+200 / buildHeight-48 cap could
        // clamp the final curve point below a mountain or elevated structure.
        return Math.max(targetY, Math.min(desiredCeiling,
                root.getMaxBuildHeight() + 48.0));
    }

    private static LevelTime levelTime(ConnectedDisplayBlockEntity display) {
        return display.getLevel() == null
                ? new LevelTime(false, Long.MIN_VALUE)
                : new LevelTime(true, display.getLevel().getGameTime());
    }

    private static boolean isFinite(@Nullable Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    private static void noteNonFiniteRecovery(State state, Entity missile, String component) {
        if (state.nonFiniteRecoveryLogged) return;
        state.nonFiniteRecoveryLogged = true;
        StabilizedTurret.LOGGER.warn(
                "Recovering fire-control cruise missile from non-finite {} type={} entity={} age={}",
                component, state.type.key(), missile.getUUID(), state.ageTicks);
    }

    private record Pending(ConnectedDisplayBlockEntity display, BindingRef launcher,
                           MianbaoCruiseMissileCompat.Type type,
                           @Nullable ConnectedDisplayBlockEntity.TargetKey targetKey,
                           @Nullable Vec3 fixedPoint, Vec3 initialTarget,
                           Vec3 launchDirection, long armedAt) {}

    private record LevelTime(boolean valid, long gameTime) {}

    private static final class State {
        final ConnectedDisplayBlockEntity display;
        final MianbaoCruiseMissileCompat.Type type;
        final @Nullable ConnectedDisplayBlockEntity.TargetKey targetKey;
        final @Nullable Vec3 fixedPoint;
        final Vec3 launch;
        final Vec3 initialDirection;
        final boolean verticalLaunch;
        final double initialSpeed;
        final double maxSpeed;
        final double initialRange;
        final double flightCeiling;
        final CruiseChunkLoader chunkLoader = new CruiseChunkLoader();
        final @Nullable LowAltitudeCruiseFlight lowFlight;
        final ImpactChunkReservation impactChunks = new ImpactChunkReservation();
        Vec3 lastKnownTarget;
        Vec3 previousPosition;
        Vec3 lastSafeVelocity;
        boolean nonFiniteRecoveryLogged;
        boolean guidanceEngaged;
        double minimumTurnHeight = Double.NaN;
        double travelledHorizontal;
        int ageTicks;

        State(ConnectedDisplayBlockEntity display, MianbaoCruiseMissileCompat.Type type,
              @Nullable ConnectedDisplayBlockEntity.TargetKey targetKey,
              @Nullable Vec3 fixedPoint, Vec3 initialTarget, Vec3 launch,
              Vec3 initialVelocity, Vec3 launchDirection, double flightCeiling) {
            this.display = display;
            this.type = type;
            this.targetKey = targetKey;
            this.fixedPoint = fixedPoint;
            this.launch = launch;
            this.lastKnownTarget = isFinite(initialTarget) ? initialTarget : null;
            this.previousPosition = launch;
            this.initialRange = Math.max(24.0, horizontalDistance(launch, this.lastKnownTarget));
            this.flightCeiling = flightCeiling;
            Vec3 safeLaunchDirection = isFinite(launchDirection)
                    && launchDirection.lengthSqr() >= 1.0e-8
                    ? launchDirection.normalize() : null;
            Vec3 safeInitialVelocity = isFinite(initialVelocity)
                    && initialVelocity.lengthSqr() >= 1.0e-8
                    ? initialVelocity : Vec3.ZERO;
            this.initialDirection = safeLaunchDirection != null
                    ? safeLaunchDirection
                    : (safeInitialVelocity.lengthSqr() < 1.0e-8
                    ? new Vec3(0.0, 1.0, 0.0) : safeInitialVelocity.normalize());
            // The already-resolved world launch vector is enough to identify
            // a true cold vertical launch. Avoid carrying launcher-type state
            // through the global pending-adoption handoff during game startup.
            // Speed classification follows the missile type, not the launch
            // direction or the block used to fire it. An ordinary cruise
            // missile fired from a vertical silo therefore still uses the
            // Mach-1 cap; only the five dedicated ground-launch families retain
            // Mach 2.
            this.maxSpeed = MissileSettings.speedBlocksPerTick(type.entityPath());
            this.verticalLaunch = Math.abs(this.initialDirection.y) >= 0.75;
            this.initialSpeed = Math.min(maxSpeed, Math.max(0.2, safeInitialVelocity.length()));
            this.lastSafeVelocity = this.initialDirection.scale(this.initialSpeed);
            this.lowFlight = type.lowAltitude() ? new LowAltitudeCruiseFlight(
                    launch, this.initialDirection, initialTarget) : null;
        }

        double speedAtAge() {
            if (ageTicks <= ENGINE_IGNITION_TICK) return initialSpeed;
            double engineProgress = Math.min(1.0,
                    (double) (ageTicks - ENGINE_IGNITION_TICK) / Math.max(1.0, ACCELERATION_TICKS * MissileSettings.poweredScale(type.entityPath())));
            // The engine ramps to this missile type's cap over ten seconds.
            // Cold-launch momentum is retained until the commanded speed
            // overtakes it.
            return Math.max(initialSpeed, maxSpeed * engineProgress);
        }

        boolean guidanceStarted(Vec3 position, Vec3 target) {
            if (guidanceEngaged) return true;
            if (!verticalLaunch) {
                guidanceEngaged = ageTicks >= 1;
                return guidanceEngaged;
            }
            boolean nativeGroundVerticalMissile = switch (type) {
                case GROUND_NUCLEAR, GROUND_HIGH_EXPLOSIVE,
                     GROUND_CLUSTER, GROUND_CRUISE, GROUND_LOW_ALTITUDE -> true;
                case CRUISE, LOW_ALTITUDE_CRUISE, NUCLEAR_CRUISE -> false;
            };
            if (nativeGroundVerticalMissile) {
                // Preserve Mianbao's original minimum climb for vertically
                // placed purpose-built ground-launch missile families.
                guidanceEngaged = ageTicks >= GUIDANCE_START_TICK;
                return guidanceEngaged;
            }
            // Do not use a fixed 50-tick vertical flight as the normal turn
            // condition for an ordinary cruise missile loaded into a silo:
            // once acceleration is active that carries it hundreds of blocks
            // upward. Clear the silo by a small minimum, then raise the turn
            // point only when the live target itself is higher. The old tick
            // threshold remains a fail-safe.
            double turnHeight = Math.max(
                    launch.y + MIN_VERTICAL_TURN_CLIMB, target.y);
            guidanceEngaged = position.y >= turnHeight
                    || ageTicks >= GUIDANCE_START_TICK;
            return guidanceEngaged;
        }

        void captureMinimumTurnHeight(Vec3 position) {
            if (Double.isNaN(minimumTurnHeight) && isFinite(position)) {
                // Vertical launch keeps GUIDANCE_START_TICK unchanged. A
                // horizontal launch records its current height immediately.
                minimumTurnHeight = position.y;
            }
        }

        Vec3 curvePoint(double t, Vec3 target) {
            double x = launch.x + (target.x - launch.x) * t;
            double z = launch.z + (target.z - launch.z) * t;
            double turnFloor = Double.isFinite(minimumTurnHeight)
                    ? minimumTurnHeight : launch.y;
            double turnHeight = Math.min(flightCeiling,
                    Math.max(turnFloor, target.y));
            // Cruise level is held through the first 60% of the route. The
            // remaining segment blends smoothly toward the current target
            // height instead of adding a fixed 200-block parabolic arch.
            double descent = Mth.clamp((t - 0.60) / 0.40, 0.0, 1.0);
            descent = descent * descent * (3.0 - 2.0 * descent);
            double y = Mth.lerp(descent, turnHeight, target.y);
            return new Vec3(x, y, z);
        }

        @Nullable Vec3 resolveTarget(ServerLevel root) {
            if (targetKey == null) return resolveGroundAim(root, fixedPoint);
            ConnectedDisplayBlockEntity.Contact contact = display.getContact(targetKey);
            if (contact != null) return contact.center();
            if (!targetKey.living()) return lastKnownTarget;
            Entity entity = root.getEntity(targetKey.id());
            if (entity == null) {
                for (SubLevel sub : SubLevelContainer.getContainer(root).getAllSubLevels()) {
                    if (sub != null && !sub.isRemoved() && sub.getLevel() instanceof ServerLevel server) {
                        entity = server.getEntity(targetKey.id());
                        if (entity != null) break;
                    }
                }
            }
            return entity instanceof LivingEntity living && living.isAlive()
                    ? MianbaoMissileCompat.getWorldPosition(living) : lastKnownTarget;
        }

        private static @Nullable Vec3 resolveGroundAim(ServerLevel root, @Nullable Vec3 point) {
            if (point == null) return null;
            int blockX = Mth.floor(point.x);
            int blockZ = Mth.floor(point.z);
            if (root.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4) == null) {
                return point;
            }
            int surfaceY = root.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
            return new Vec3(point.x, surfaceY - 0.5, point.z);
        }

        void release(UUID missileId) {
            release(missileId, false);
        }

        void release(UUID missileId, boolean retainImpactChunks) {
            chunkLoader.release();
            if (retainImpactChunks) {
                impactChunks.deferRelease(display.getLevel() == null
                        ? Long.MIN_VALUE : display.getLevel().getGameTime());
            } else {
                impactChunks.release();
            }
            display.removeCruiseTelemetry(missileId);
        }
    }

    private static final class CruiseChunkLoader {
        private ServerLevel level;
        private UUID owner;
        private Set<ChunkPos> chunks = Set.of();
        private Set<ChunkPos> tickingChunks = Set.of();

        void update(ServerLevel nextLevel, UUID nextOwner,
                    Vec3 position, Vec3 velocity, @Nullable LowAltitudeCruiseFlight lowFlight) {
            Set<ChunkPos> wanted = new HashSet<>();
            addFlightSegment(wanted, position, velocity);
            Set<ChunkPos> nextTicking = lowFlight == null ? Set.of() : Set.copyOf(wanted);
            if (lowFlight != null) {
                // Prefetch the upcoming horizontal turn (even during VLS
                // ascent), plus a neighbor ring for changes of heading.
                for (double ahead = 0; ahead <= LowAltitudeCruiseFlight.PREFETCH_DISTANCE; ahead += 8) {
                    Vec3 probe = position.add(lowFlight.prefetchOffset(ahead));
                    int cx = Mth.floor(probe.x) >> 4;
                    int cz = Mth.floor(probe.z) >> 4;
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        wanted.add(new ChunkPos(cx + dx, cz + dz));
                    }
                }
            }
            if (level != nextLevel || !nextOwner.equals(owner)) release();
            level = nextLevel;
            owner = nextOwner;
            // FULL-only tickets cannot tick arrows: promote the imminent
            // segment so remote flight uses the same physics as nearby flight.
            for (ChunkPos chunk : nextTicking) if (!tickingChunks.contains(chunk)) {
                level.getChunkSource().addRegionTicket(TICKET, chunk, 2, owner, true);
            }
            for (ChunkPos chunk : tickingChunks) if (!nextTicking.contains(chunk)) {
                level.getChunkSource().removeRegionTicket(TICKET, chunk, 2, owner, true);
            }
            tickingChunks = nextTicking;
            for (ChunkPos chunk : chunks) if (!wanted.contains(chunk)) {
                level.getChunkSource().removeRegionTicket(TICKET, chunk, 0, owner, true);
            }
            for (ChunkPos chunk : wanted) if (!chunks.contains(chunk)) {
                level.getChunkSource().addRegionTicket(TICKET, chunk, 0, owner, true);
            }
            chunks = Set.copyOf(wanted);
        }

        private static void addFlightSegment(Set<ChunkPos> destination,
                                             Vec3 start, Vec3 movement) {
            int samples = Math.max(1, (int) Math.ceil(Math.max(
                    Math.abs(movement.x), Math.abs(movement.z)) / 8.0));
            for (int i = 0; i <= samples; i++) {
                double fraction = (double) i / samples;
                int blockX = (int) Math.floor(start.x + movement.x * fraction);
                int blockZ = (int) Math.floor(start.z + movement.z * fraction);
                destination.add(new ChunkPos(blockX >> 4, blockZ >> 4));
            }
        }

        void release() {
            if (level != null && owner != null) {
                for (ChunkPos chunk : tickingChunks) {
                    level.getChunkSource().removeRegionTicket(TICKET, chunk, 2, owner, true);
                }
                for (ChunkPos chunk : chunks) {
                    level.getChunkSource().removeRegionTicket(TICKET, chunk, 0, owner, true);
                }
            }
            level = null;
            owner = null;
            chunks = Set.of();
            tickingChunks = Set.of();
        }
    }

    /** Keeps the root-world impact area alive around the final target. */
    private static final class ImpactChunkReservation {
        private ServerLevel level;
        private UUID owner;
        /** Entire requested remote view. Tickets are admitted to it gradually. */
        private Set<ChunkPos> chunks = Set.of();
        private final Set<ChunkPos> ticketedChunks = new HashSet<>();
        private List<ChunkPos> orderedChunks = List.of();
        private int nextTicketIndex;
        private @Nullable ChunkPos center;
        private @Nullable BlockPos impactBlock;
        private final Map<UUID, ChunkPos> announcedCenters = new HashMap<>();
        /** Chunks changed by paced work, retained until one final full sync. */
        private final Set<ChunkPos> dirtyChunks = new LinkedHashSet<>();
        private List<ChunkPos> finalSyncChunks = List.of();
        private int nextFinalSyncIndex;
        private boolean finalSyncScheduled;
        private boolean finalSyncLogged;
        private long effectsCompleteAt = Long.MIN_VALUE;
        private boolean lifecycleStarted;
        private boolean destructionComplete;

        void arm(ServerLevel nextLevel, UUID nextOwner, @Nullable Vec3 impact) {
            if (impact == null) return;
            boolean newOwner = level != nextLevel || !nextOwner.equals(owner);
            if (newOwner) release();
            level = nextLevel;
            owner = nextOwner;
            int centerX = net.minecraft.util.Mth.floor(impact.x) >> 4;
            int centerZ = net.minecraft.util.Mth.floor(impact.z) >> 4;
            ChunkPos nextCenter = new ChunkPos(centerX, centerZ);
            Set<ChunkPos> wanted = new HashSet<>();
            for (int x = centerX - IMPACT_LOAD_RADIUS;
                 x <= centerX + IMPACT_LOAD_RADIUS; x++) {
                for (int z = centerZ - IMPACT_LOAD_RADIUS;
                     z <= centerZ + IMPACT_LOAD_RADIUS; z++) {
                    wanted.add(new ChunkPos(x, z));
                }
            }
            if (!wanted.equals(chunks)) {
                for (ChunkPos chunk : Set.copyOf(ticketedChunks)) {
                    if (!wanted.contains(chunk)) {
                        level.getChunkSource().removeRegionTicket(
                                IMPACT_TICKET, chunk, 0, owner, true);
                        ticketedChunks.remove(chunk);
                    }
                }
                chunks = Set.copyOf(wanted);
                orderedChunks = new ArrayList<>(chunks);
                orderedChunks.sort(Comparator.comparingInt(chunk ->
                        Math.max(Math.abs(chunk.x - nextCenter.x),
                                Math.abs(chunk.z - nextCenter.z))));
                nextTicketIndex = 0;
            }
            center = nextCenter;
            impactBlock = BlockPos.containing(impact);
            IMPACTS_BY_MISSILE.put(owner, this);
            if (newOwner) {
                dirtyChunks.clear();
                finalSyncChunks = List.of();
                nextFinalSyncIndex = 0;
                finalSyncScheduled = false;
                finalSyncLogged = false;
                effectsCompleteAt = Long.MIN_VALUE;
                lifecycleStarted = false;
                destructionComplete = false;
            }
            issueTickets();
            streamChunks();
        }

        void beginLifecycle(long now, int visualDurationTicks) {
            if (level == null || owner == null) return;
            lifecycleStarted = true;
            destructionComplete = false;
            effectsCompleteAt = now + Math.max(1, visualDurationTicks);
            RETAINED_IMPACTS.add(this);
        }

        void completeDestruction(boolean nativeNuclearTerrain) {
            if (nativeNuclearTerrain) markNativeNuclearTerrainDirty();
            scheduleFinalSync();
            destructionComplete = true;
        }

        void deferRelease(long now) {
            if (level == null || owner == null || chunks.isEmpty()) {
                release();
                return;
            }
            if (now == Long.MIN_VALUE) {
                release();
                return;
            }
            if (!lifecycleStarted) {
                release();
                return;
            }
            RETAINED_IMPACTS.add(this);
            issueTickets();
            streamChunks();
        }

        boolean finished(long now) {
            return lifecycleStarted && destructionComplete
                    && finalSyncComplete()
                    && effectsCompleteAt != Long.MIN_VALUE
                    && now >= effectsCompleteAt;
        }

        boolean isArmed() {
            return !chunks.isEmpty();
        }

        void markDirty(Set<ChunkPos> dirtyChunks) {
            if (destructionComplete || dirtyChunks == null || dirtyChunks.isEmpty()) return;
            for (ChunkPos chunk : dirtyChunks) {
                if (chunks.contains(chunk)) this.dirtyChunks.add(chunk);
            }
        }

        private void markNativeNuclearTerrainDirty() {
            if (impactBlock == null) return;
            int minX = (impactBlock.getX() - NATIVE_NUCLEAR_TERRAIN_RADIUS) >> 4;
            int maxX = (impactBlock.getX() + NATIVE_NUCLEAR_TERRAIN_RADIUS) >> 4;
            int minZ = (impactBlock.getZ() - NATIVE_NUCLEAR_TERRAIN_RADIUS) >> 4;
            int maxZ = (impactBlock.getZ() + NATIVE_NUCLEAR_TERRAIN_RADIUS) >> 4;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    ChunkPos chunk = new ChunkPos(x, z);
                    if (chunks.contains(chunk)) dirtyChunks.add(chunk);
                }
            }
        }

        private void scheduleFinalSync() {
            if (finalSyncScheduled) return;
            finalSyncScheduled = true;
            finalSyncChunks = new ArrayList<>(dirtyChunks);
            if (center != null) {
                finalSyncChunks.sort(Comparator.comparingInt(chunk ->
                        Math.max(Math.abs(chunk.x - center.x),
                                Math.abs(chunk.z - center.z))));
            }
            dirtyChunks.clear();
            StabilizedTurret.LOGGER.info(
                    "Scheduled final impact chunk sync missile={} chunks={}",
                    owner, finalSyncChunks.size());
        }

        private boolean finalSyncComplete() {
            return !finalSyncScheduled || nextFinalSyncIndex >= finalSyncChunks.size();
        }

        int flushFinalChunkSync(int budget) {
            if (budget <= 0 || level == null || !finalSyncScheduled
                    || finalSyncComplete()) return 0;
            int sentChunks = 0;
            while (sentChunks < budget && nextFinalSyncIndex < finalSyncChunks.size()) {
                ChunkPos chunkPos = finalSyncChunks.get(nextFinalSyncIndex++);
                LevelChunk chunk = level.getChunkSource().getChunkNow(
                        chunkPos.x, chunkPos.z);
                if (chunk == null) continue;

                List<ServerPlayer> tracked = level.getChunkSource().chunkMap
                        .getPlayers(chunkPos, false);
                if (tracked.isEmpty() && announcedCenters.isEmpty()) continue;
                ClientboundLevelChunkWithLightPacket packet =
                        new ClientboundLevelChunkWithLightPacket(
                                chunk, level.getChunkSource().getLightEngine(),
                                (BitSet) null, (BitSet) null);
                for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                    if (MianbaoMissileCompat.getRootServerLevel(player) != level) continue;
                    boolean vanillaTracked = tracked.contains(player);
                    boolean remoteViewer = announcedCenters.containsKey(player.getUUID());
                    if (vanillaTracked || remoteViewer) player.connection.send(packet);
                }
                sentChunks++;
            }
            if (finalSyncComplete() && !finalSyncLogged) {
                finalSyncLogged = true;
                StabilizedTurret.LOGGER.info(
                        "Completed final impact chunk sync missile={} chunks={}",
                        owner, finalSyncChunks.size());
            }
            return sentChunks;
        }

        private void issueTickets() {
            if (level == null || owner == null || orderedChunks.isEmpty()) return;
            int budget = IMPACT_TICKETS_PER_TICK;
            while (budget > 0 && nextTicketIndex < orderedChunks.size()) {
                ChunkPos chunk = orderedChunks.get(nextTicketIndex++);
                if (!ticketedChunks.add(chunk)) continue;
                level.getChunkSource().addRegionTicket(IMPACT_TICKET, chunk, 0, owner, true);
                budget--;
            }
        }

        void streamChunks() {
            if (level == null || owner == null || center == null || ticketedChunks.isEmpty()) return;
            Vec3 impactCenter = new Vec3(
                    SectionPos.sectionToBlockCoord(center.x, 8), 0.0,
                    SectionPos.sectionToBlockCoord(center.z, 8));
            Set<UUID> currentViewers = new HashSet<>();
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                if (MianbaoMissileCompat.getRootServerLevel(player) != level) continue;
                Vec3 playerPosition = MianbaoMissileCompat.getWorldPosition(player);
                double dx = playerPosition.x - impactCenter.x;
                double dz = playerPosition.z - impactCenter.z;
                if (dx * dx + dz * dz > IMPACT_VIEWER_DISTANCE_SQR) continue;
                // A nearby player already owns this chunk through vanilla's
                // normal client cache. Giving the same player a second remote
                // region can leave a stale visual LevelChunk behind as the
                // player crosses the boundary, making the blast area appear
                // impossible to place into or mine. Remote regions are only
                // for impacts that vanilla is not currently tracking.
                if (level.getChunkSource().chunkMap
                        .getPlayers(center, false).contains(player)) continue;
                currentViewers.add(player.getUUID());

                ChunkPos announced = announcedCenters.get(player.getUUID());
                if (!center.equals(announced)) {
                    PacketDistributor.sendToPlayer(player,
                            new MissileImpactRegionPayload(owner, center.x, center.z,
                                    IMPACT_LOAD_RADIUS, true));
                    announcedCenters.put(player.getUUID(), center);
                }
            }

            for (UUID playerId : Set.copyOf(announcedCenters.keySet())) {
                if (currentViewers.contains(playerId)) continue;
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
                if (player != null) {
                    PacketDistributor.sendToPlayer(player,
                            new MissileImpactRegionPayload(owner, center.x, center.z,
                                    IMPACT_LOAD_RADIUS, false));
                }
                announcedCenters.remove(playerId);
            }
        }

        void release() {
            if (level != null && owner != null) {
                if (center != null) {
                    for (UUID playerId : announcedCenters.keySet()) {
                        ServerPlayer player = level.getServer().getPlayerList()
                                .getPlayer(playerId);
                        if (player != null) {
                            PacketDistributor.sendToPlayer(player,
                                    new MissileImpactRegionPayload(owner,
                                            center.x, center.z,
                                            IMPACT_LOAD_RADIUS, false));
                        }
                    }
                }
                for (ChunkPos chunk : ticketedChunks) {
                    level.getChunkSource().removeRegionTicket(
                            IMPACT_TICKET, chunk, 0, owner, true);
                }
            }
            RETAINED_IMPACTS.remove(this);
            if (owner != null) IMPACTS_BY_MISSILE.remove(owner, this);
            level = null;
            owner = null;
            chunks = Set.of();
            ticketedChunks.clear();
            orderedChunks = List.of();
            nextTicketIndex = 0;
            center = null;
            impactBlock = null;
            announcedCenters.clear();
            dirtyChunks.clear();
            finalSyncChunks = List.of();
            nextFinalSyncIndex = 0;
            finalSyncScheduled = false;
            finalSyncLogged = false;
            effectsCompleteAt = Long.MIN_VALUE;
            lifecycleStarted = false;
            destructionComplete = false;
        }
    }

    private static void tickRetainedImpactChunks(@Nullable ServerLevel clockLevel) {
        if (clockLevel == null || RETAINED_IMPACTS.isEmpty()) return;
        long now = clockLevel.getGameTime();
        int finalSyncBudget = IMPACT_FINAL_CHUNKS_PER_TICK;
        for (ImpactChunkReservation reservation : Set.copyOf(RETAINED_IMPACTS)) {
            reservation.issueTickets();
            reservation.streamChunks();
            int used = reservation.flushFinalChunkSync(finalSyncBudget);
            finalSyncBudget -= used;
            if (reservation.finished(now)) reservation.release();
        }
    }

    /** Re-stream only the impact chunks whose terrain changed after a paced nuclear blast. */
    public static void markImpactChunksDirty(UUID missileId, Set<ChunkPos> dirtyChunks) {
        ImpactChunkReservation reservation = IMPACTS_BY_MISSILE.get(missileId);
        if (reservation != null) reservation.markDirty(dirtyChunks);
    }

    /** Starts completion-based retention for the automatically loaded impact square. */
    public static void beginImpactLifecycle(UUID missileId, long now,
                                            int visualDurationTicks) {
        ImpactChunkReservation reservation = IMPACTS_BY_MISSILE.get(missileId);
        if (reservation != null) reservation.beginLifecycle(now, visualDurationTicks);
    }

    /** Releases the impact square as soon as its detached effects are also finished. */
    public static void completeImpactDestruction(UUID missileId) {
        ImpactChunkReservation reservation = IMPACTS_BY_MISSILE.get(missileId);
        if (reservation != null) reservation.completeDestruction(false);
    }

    /** Native Mianbao nuclear work cannot report its changed chunks individually. */
    public static void completeNativeNuclearImpactDestruction(UUID missileId) {
        ImpactChunkReservation reservation = IMPACTS_BY_MISSILE.get(missileId);
        if (reservation != null) reservation.completeDestruction(true);
    }

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent event) {
        for (Map.Entry<Entity, State> entry : GUIDED.entrySet()) {
            entry.getValue().release(entry.getKey().getUUID(), false);
        }
        GUIDED.clear();
        for (ImpactChunkReservation reservation : Set.copyOf(RETAINED_IMPACTS)) {
            reservation.release();
        }
        RETAINED_IMPACTS.clear();
        IMPACTS_BY_MISSILE.clear();
        pending = null;
    }

    private CruiseMissileGuidance() {}
}
