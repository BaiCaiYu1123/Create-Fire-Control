package com.hooya.stabilizedturret.content.display;

import com.hooya.stabilizedturret.config.MissileSettings;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.iff.FireControlMissileOwnership;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight;
import com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoCountermeasureCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileRemoteSync;
import com.hooya.stabilizedturret.content.controller.MissileChunkTickets;
import com.hooya.stabilizedturret.content.missile.MissilePhysicsConfig;
import com.hooya.stabilizedturret.content.missile.impact.BallisticImpact;
import com.hooya.stabilizedturret.content.missile.impact.ImpactAreaManager;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Native ballistic missile flight with bounded chunk loading and remote rendering. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class BallisticMissileGuidance {
    public static final String GUIDED_TAG = "CreateFireControlBallisticGuided";
    private static final String OWNER_TAG = "CreateFireControlBallisticOwner";
    private static final int MAX_LIFETIME_TICKS = LandAttackMissileFlight.LIFETIME_TICKS;
    private static final int POST_DETACH_STRAIGHT_TICKS = 6;
    private static final int DF26_STRAIGHT_LAUNCH_TICKS = 8 * 20;
    private static final int FLARE_INTERVAL_TICKS = 20;
    private static final int FLARES_PER_BURST = 5;
    private static final double MACH_1_BLOCKS_PER_TICK = 17.015;
    private static final double CLIMB_SPEED = MACH_1_BLOCKS_PER_TICK * 0.65;
    private static final double CRUISE_SPEED = MACH_1_BLOCKS_PER_TICK * 2.0;
    private static final double TERMINAL_SPEED = MACH_1_BLOCKS_PER_TICK * 3.0;
    private static final double CLIMB_ACCELERATION = 0.045;
    private static final double CRUISE_ACCELERATION = 0.12;
    private static final double TERMINAL_ACCELERATION = 0.22;
    private static final double CLIMB_END_PROGRESS = 0.30;
    private static final double MIN_TERMINAL_DISTANCE = 3_500.0;
    private static final double FLIGHT_CHUNK_LOOKAHEAD_BLOCKS = 384.0;
    private static final double MIN_TERMINAL_START_PROGRESS = 0.65;
    private static final double MAX_TERMINAL_START_PROGRESS = 0.86;
    private static final double DF26_PATH_TURN_DEGREES_PER_TICK = 1.25;
    private static final double BALLISTIC_PATH_TURN_DEGREES_PER_TICK = 2.0;
    private static final double TARGET_SWEEP_RADIUS = 10.0;
    private static final TicketType<UUID> FLIGHT_TICKET = TicketType.create(
            "create_fire_control_ballistic_missile", UUID::compareTo);

    private static final Map<Entity, State> GUIDED = new IdentityHashMap<>();
    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static final ArrayList<RetainedImpact> RETAINED_IMPACTS = new ArrayList<>();

    public static void armNext(ConnectedDisplayBlockEntity display, BindingRef launcher,
                               @Nullable ConnectedDisplayBlockEntity.TargetKey targetKey,
                               @Nullable Vec3 fixedPoint, Vec3 initialTarget) {
        if (display == null || launcher == null || !finite(initialTarget)) return;
        PENDING.addLast(new Pending(display, launcher, targetKey, fixedPoint,
                initialTarget, display.getLevel() == null
                ? Long.MIN_VALUE : display.getLevel().getGameTime()));
        StabilizedTurret.LOGGER.info("Armed fire-control ballistic launcher at {}",
                launcher.pos());
    }

    public static void cancelPending(ConnectedDisplayBlockEntity display) {
        PENDING.removeIf(pending -> pending.display() == display);
    }

    public static boolean hasGuided(Entity entity) {
        return entity != null && GUIDED.containsKey(entity);
    }

    /** Records the exact native swept-collision point before the entity discards itself. */
    public static void noteNativeImpact(Entity missile, Vec3 worldImpact) {
        if (missile == null || !finite(worldImpact)) return;
        State state = GUIDED.get(missile);
        if (state != null) state.lastPosition = worldImpact;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        tickRetainedImpacts();
        adoptPending();
        Iterator<Map.Entry<Entity, State>> iterator = GUIDED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Entity, State> entry = iterator.next();
            Entity missile = entry.getKey();
            State state = entry.getValue();
            if (missile == null || missile.isRemoved() || !missile.isAlive()
                    || state.display.isRemoved()) {
                state.release(missile == null ? null : missile.getUUID(), true);
                iterator.remove();
                continue;
            }
            if (++state.ageTicks >= MissileSettings.lifetimeTicks(missile)) {
                StabilizedTurret.LOGGER.info(
                        "Removed timed-out ballistic missile {} after {} ticks",
                        missile.getUUID(), state.ageTicks);
                missile.discard();
                state.release(missile.getUUID(), false);
                iterator.remove();
                continue;
            }

            MianbaoBallisticMissileCompat.Telemetry telemetry =
                    MianbaoBallisticMissileCompat.telemetry(missile);
            if (telemetry == null || !finite(telemetry.position())
                    || !finite(telemetry.velocity())
                    || !finite(telemetry.warheadDirection())) {
                continue;
            }
            ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
            if (root == null) {
                missile.discard();
                state.release(missile.getUUID(), false);
                iterator.remove();
                continue;
            }
            Vec3 current = telemetry.position();
            Vec3 target = state.resolveTarget(root);
            if (finite(target)) state.lastTarget = target;
            else target = state.lastTarget;
            if (!finite(target)) continue;

            state.display.updateBallisticTelemetry(
                    new ConnectedDisplayBlockEntity.BallisticTelemetry(
                            missile.getUUID(), current, telemetry.velocity(),
                            target, state.ageTicks));
            if (telemetry.docked()) {
                state.previousPosition = current;
                MianbaoMissileRemoteSync.broadcast(missile);
                continue;
            }

            if (!state.flightStarted) {
                state.beginFlight(root, current, telemetry.velocity(),
                        telemetry.warheadDirection(), target);
                state.flightTickets.update(missile, telemetry.velocity(),
                        FLIGHT_CHUNK_LOOKAHEAD_BLOCKS);
                MianbaoMissileRemoteSync.broadcast(missile);
                StabilizedTurret.LOGGER.info(
                        "Fire-control took over ballistic missile {} at speed {}",
                        missile.getUUID(), state.speed);
                continue;
            }

            state.impactArea.tick();
            Vec3 previousWarhead = state.previousPosition;
            Vec3 currentWarhead = current;
            if (MianbaoBallisticMissileCompat.isDf26(missile)) {
                previousWarhead = previousWarhead.add(state.direction.scale(
                        MianbaoBallisticMissileCompat.DF26_NOSE_OFFSET));
                currentWarhead = currentWarhead.add(
                        telemetry.warheadDirection().normalize().scale(
                                MianbaoBallisticMissileCompat.DF26_NOSE_OFFSET));
            }
            if (crossedTarget(previousWarhead, currentWarhead, target,
                    Math.max(TARGET_SWEEP_RADIUS, state.speed * 0.30))) {
                MianbaoBallisticMissileCompat.detonateAt(missile, target);
                state.lastPosition = target;
                state.release(missile.getUUID(), true);
                iterator.remove();
                continue;
            }

            state.previousPosition = current;
            state.lastPosition = current;
            boolean df26StraightLaunch = MianbaoBallisticMissileCompat.isDf26(missile)
                    && MianbaoBallisticMissileCompat.launchAge(missile)
                    < DF26_STRAIGHT_LAUNCH_TICKS;
            int detachedTicks = state.detachedTicks++;
            if (state.trajectory == null && !df26StraightLaunch
                    && detachedTicks >= POST_DETACH_STRAIGHT_TICKS) {
                state.beginTrajectory(current, target);
            }
            double progress = state.trajectory == null ? 0.0 : state.pathProgress;
            double targetDistance = current.distanceTo(target);
            double terminalDistance = Math.max(MIN_TERMINAL_DISTANCE,
                    Math.max(state.speed, CRUISE_SPEED * MissileSettings.speedScale(missile)) * 100.0);
            double terminalStartProgress = state.terminalStartProgress(terminalDistance);
            double climbEndProgress = state.climbEndProgress(target);
            state.phase = progress < climbEndProgress
                    ? Phase.CLIMB
                    : progress < terminalStartProgress ? Phase.CRUISE : Phase.TERMINAL;

            if (targetDistance <= terminalDistance
                    || targetDistance <= state.speed * 120.0) {
                state.impactArea.prepare(missile.getUUID(), target,
                        state.requiredImpactChunkRadius());
            }

            state.speed = speedCommand(missile, state.speed, state.phase);

            Vec3 command;
            double nextPathProgress = state.pathProgress;
            if (df26StraightLaunch
                    || detachedTicks < POST_DETACH_STRAIGHT_TICKS
                    || state.trajectory == null) {
                command = state.direction.scale(state.speed);
            } else {
                nextPathProgress = state.trajectory.advance(
                        state.pathProgress, state.speed, target);
                Vec3 nextPoint = state.trajectory.point(nextPathProgress, target);
                Vec3 desiredDirection = normalized(nextPoint.subtract(current),
                        state.trajectory.tangent(state.pathProgress, target));
                double turnRate = MianbaoBallisticMissileCompat.isDf26(missile)
                        ? DF26_PATH_TURN_DEGREES_PER_TICK
                        : BALLISTIC_PATH_TURN_DEGREES_PER_TICK;
                state.direction = rotateTowards(state.direction, desiredDirection,
                        Math.toRadians(turnRate));
                command = state.direction.scale(state.speed);
            }
            Vec3 next = current.add(command);
            state.flightTickets.update(missile, command,
                    FLIGHT_CHUNK_LOOKAHEAD_BLOCKS);
            boolean movementPathLoaded =
                    state.flightTickets.movementPathLoaded(missile, command);
            if (!movementPathLoaded) {
                // Keep the authoritative entity and its remote proxy still
                // until the already-ticketed next segment is actually ready.
                // Sending a zero velocity avoids client extrapolation followed
                // by an authoritative snap-back at the view-distance boundary.
                if (!state.chunkLoadHold) {
                    StabilizedTurret.LOGGER.info(
                            "Holding ballistic missile {} for flight chunk loading at {}",
                            missile.getUUID(), current);
                }
                state.chunkLoadHold = true;
                state.speed = 0.0;
                command = Vec3.ZERO;
            } else if (state.chunkLoadHold) {
                state.chunkLoadHold = false;
                StabilizedTurret.LOGGER.info(
                        "Resumed ballistic missile {} after flight chunks loaded",
                        missile.getUUID());
            }
            if (state.impactArea.contains(missile.getUUID(), next)
                    && !state.impactArea.ready(missile.getUUID())) {
                command = Vec3.ZERO;
            }

            if (command.lengthSqr() > 1.0e-12) {
                state.pathProgress = nextPathProgress;
            }

            if (!MianbaoBallisticMissileCompat.setUnrestrictedFlightControl(
                    missile, state.direction, command.length())) {
                StabilizedTurret.LOGGER.warn(
                        "Lost ballistic flight control for missile {}", missile.getUUID());
                missile.discard();
                state.release(missile.getUUID(), false);
                iterator.remove();
                continue;
            }
            MianbaoMissileRemoteSync.broadcast(missile);
            if (state.phase == Phase.TERMINAL
                    && state.terminalTicks++ % FLARE_INTERVAL_TICKS == 0) {
                MianbaoCountermeasureCompat.spawnBurst(root,
                        current.subtract(state.direction.scale(2.5)),
                        FLARES_PER_BURST);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Map.Entry<Entity, State> entry : GUIDED.entrySet()) {
            entry.getValue().release(entry.getKey().getUUID(), false);
        }
        GUIDED.clear();
        PENDING.clear();
        for (RetainedImpact retained : RETAINED_IMPACTS) retained.area.close();
        RETAINED_IMPACTS.clear();
    }

    private static void adoptPending() {
        Iterator<Pending> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            ConnectedDisplayBlockEntity display = pending.display();
            if (display.isRemoved() || display.getLevel() == null
                    || display.getLevel().getGameTime() - pending.armedAt() > 10L) {
                StabilizedTurret.LOGGER.warn(
                        "Timed out adopting fire-control ballistic missile from launcher {}",
                        pending.launcher().pos());
                iterator.remove();
                continue;
            }
            Entity missile = MianbaoBallisticMissileCompat.findMissile(
                    display.getLevel(), pending.launcher());
            if (missile == null || GUIDED.containsKey(missile)) continue;
            ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
            if (root == null) continue;
            State state = new State(root, display, pending.targetKey(),
                    pending.fixedPoint(), pending.initialTarget(),
                    MianbaoMissileCompat.getWorldPosition(missile));
            GUIDED.put(missile, state);
            missile.getPersistentData().putBoolean(GUIDED_TAG, true);
            missile.getPersistentData().putBoolean(OWNER_TAG, true);
            missile.getPersistentData().putString(OWNER_TAG + "Id",
                    display.cruiseOwnershipToken());
            FireControlMissileOwnership.markDisplay(missile, display);
            iterator.remove();
            StabilizedTurret.LOGGER.info(
                    "Adopted fire-control ballistic missile entity={}", missile.getUUID());
        }
    }

    private static void tickRetainedImpacts() {
        Iterator<RetainedImpact> iterator = RETAINED_IMPACTS.iterator();
        while (iterator.hasNext()) {
            RetainedImpact retained = iterator.next();
            retained.area.tick();
            if (!BallisticImpact.isTerrainWorkActive(retained.level, retained.position)) {
                retained.area.release(retained.id);
                retained.area.close();
                iterator.remove();
            }
        }
    }

    private static double approach(double current, double target, double amount) {
        if (current < target) return Math.min(target, current + amount);
        return Math.max(target, current - amount);
    }

    private static boolean crossedTarget(Vec3 previous, Vec3 current,
                                         Vec3 target, double radius) {
        if (!finite(previous) || !finite(current) || !finite(target)) return false;
        Vec3 segment = current.subtract(previous);
        double lengthSqr = segment.lengthSqr();
        if (lengthSqr < 1.0e-8) return current.distanceToSqr(target) <= radius * radius;
        double fraction = Mth.clamp(target.subtract(previous).dot(segment) / lengthSqr,
                0.0, 1.0);
        return previous.add(segment.scale(fraction)).distanceToSqr(target) <= radius * radius;
    }

    private static Vec3 normalized(Vec3 vector, Vec3 fallback) {
        return finite(vector) && vector.lengthSqr() >= 1.0e-12
                ? vector.normalize() : fallback;
    }

    private static Vec3 rotateTowards(Vec3 current, Vec3 desired,
                                       double maxAngle) {
        current = normalized(current, desired);
        desired = normalized(desired, current);
        double angle = Math.acos(Mth.clamp(current.dot(desired), -1.0, 1.0));
        if (angle <= maxAngle || angle < 1.0e-8) return desired;
        double blend = maxAngle / angle;
        return normalized(current.scale(1.0 - blend).add(desired.scale(blend)),
                desired);
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        return Math.hypot(second.x - first.x, second.z - first.z);
    }

    private static boolean finite(@Nullable Vec3 value) {
        return value != null && Double.isFinite(value.x)
                && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private enum Phase { CLIMB, CRUISE, TERMINAL }

    private record Pending(ConnectedDisplayBlockEntity display, BindingRef launcher,
                           @Nullable ConnectedDisplayBlockEntity.TargetKey targetKey,
                           @Nullable Vec3 fixedPoint, Vec3 initialTarget, long armedAt) {}

    private record RetainedImpact(ServerLevel level, ImpactAreaManager area,
                                  UUID id, Vec3 position) {}

    private static double speedCommand(Entity missile, double current, Phase phase) {
        double target = switch (phase) {
            case CLIMB -> CLIMB_SPEED;
            case CRUISE -> CRUISE_SPEED;
            case TERMINAL -> TERMINAL_SPEED;
        };
        double acceleration = switch (phase) {
            case CLIMB -> CLIMB_ACCELERATION;
            case CRUISE -> CRUISE_ACCELERATION;
            case TERMINAL -> TERMINAL_ACCELERATION;
        };
        double scale = MissileSettings.speedScale(missile);
        return approach(current, target * scale, acceleration * scale / MissileSettings.poweredScale(missile));
    }

    private static final class State {
        private final ServerLevel root;
        private final ConnectedDisplayBlockEntity display;
        private final @Nullable ConnectedDisplayBlockEntity.TargetKey targetKey;
        private final @Nullable Vec3 fixedPoint;
        private final Vec3 initialTarget;
        private final MissileChunkTickets flightTickets =
                new MissileChunkTickets(FLIGHT_TICKET, true);
        private final ImpactAreaManager impactArea;
        private Vec3 lastTarget;
        private Vec3 previousPosition;
        private Vec3 lastPosition;
        private Vec3 launchPosition;
        private Vec3 direction = new Vec3(0.0, 1.0, 0.0);
        private double initialHorizontalDistance = 1.0;
        private @Nullable BallisticTrajectory trajectory;
        private double pathProgress;
        private double speed;
        private int ageTicks;
        private int detachedTicks;
        private int terminalTicks;
        private boolean flightStarted;
        private boolean chunkLoadHold;
        private Phase phase = Phase.CLIMB;

        private State(ServerLevel root, ConnectedDisplayBlockEntity display,
                      @Nullable ConnectedDisplayBlockEntity.TargetKey targetKey,
                      @Nullable Vec3 fixedPoint, Vec3 initialTarget,
                      Vec3 previousPosition) {
            this.root = root;
            this.display = display;
            this.targetKey = targetKey;
            this.fixedPoint = fixedPoint;
            this.initialTarget = initialTarget;
            this.lastTarget = initialTarget;
            this.previousPosition = previousPosition;
            this.lastPosition = previousPosition;
            this.launchPosition = previousPosition;
            this.impactArea = new ImpactAreaManager(root);
        }

        private void beginFlight(ServerLevel root, Vec3 position, Vec3 velocity,
                                 Vec3 warheadDirection, Vec3 target) {
            flightStarted = true;
            launchPosition = position;
            previousPosition = position;
            lastPosition = position;
            speed = Math.max(0.0, velocity.length());
            direction = normalized(warheadDirection,
                    normalized(velocity, new Vec3(0.0, 1.0, 0.0)));
        }

        private void beginTrajectory(Vec3 position, Vec3 target) {
            launchPosition = position;
            initialHorizontalDistance = Math.max(1.0,
                    horizontalDistance(position, target));
            double requestedArcHeight = Mth.clamp(
                    Math.max(220.0, MissilePhysicsConfig.MIN_APOGEE.get())
                            + initialHorizontalDistance
                            * Math.max(0.10,
                            MissilePhysicsConfig.APOGEE_DISTANCE_FACTOR.get()),
                    64.0,
                    Math.max(3600.0, MissilePhysicsConfig.MAX_APOGEE.get()));
            trajectory = BallisticTrajectory.fromArc(
                    position, target, requestedArcHeight);
            pathProgress = 0.0;

            StabilizedTurret.LOGGER.info(
                    "Ballistic missile trajectory range={} blocks lifetime={} ticks",
                    Mth.floor(initialHorizontalDistance), MAX_LIFETIME_TICKS);
        }

        private double terminalStartProgress(double terminalDistance) {
            return Mth.clamp(1.0 - terminalDistance / initialHorizontalDistance,
                    MIN_TERMINAL_START_PROGRESS, MAX_TERMINAL_START_PROGRESS);
        }

        private double climbEndProgress(Vec3 target) {
            if (trajectory == null) return CLIMB_END_PROGRESS;
            double apex = trajectory.apexParameter(target);
            return apex > 1.0e-4 ? apex : 0.0;
        }

        private int requiredImpactChunkRadius() {
            return Math.min(ImpactAreaManager.MAX_RADIUS_CHUNKS,
                    Math.max(MissilePhysicsConfig.IMPACT_CHUNK_RADIUS.get(), 6));
        }

        private @Nullable Vec3 resolveTarget(ServerLevel root) {
            if (targetKey == null) {
                Vec3 point = fixedPoint == null ? initialTarget : fixedPoint;
                int blockX = Mth.floor(point.x);
                int blockZ = Mth.floor(point.z);
                if (root.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4) == null) {
                    return point;
                }
                int surface = root.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
                return new Vec3(point.x, surface - 0.5, point.z);
            }
            ConnectedDisplayBlockEntity.Contact contact = display.getContact(targetKey);
            if (contact != null) return contact.center();
            if (!targetKey.living()) return lastTarget;
            Entity entity = root.getEntity(targetKey.id());
            if (entity == null) {
                for (SubLevel sub : SubLevelContainer.getContainer(root).getAllSubLevels()) {
                    if (sub != null && !sub.isRemoved()
                            && sub.getLevel() instanceof ServerLevel server) {
                        entity = server.getEntity(targetKey.id());
                        if (entity != null) break;
                    }
                }
            }
            return entity instanceof LivingEntity living && living.isAlive()
                    ? MianbaoMissileCompat.getWorldPosition(living) : lastTarget;
        }

        private void release(@Nullable UUID id, boolean retainImpact) {
            flightTickets.release();
            if (id != null) display.removeBallisticTelemetry(id);
            if (id != null && retainImpact && finite(lastPosition)
                    && BallisticImpact.isTerrainWorkActive(root, lastPosition)) {
                RETAINED_IMPACTS.add(new RetainedImpact(
                        root, impactArea, id, lastPosition));
            } else {
                impactArea.close();
            }
        }
    }

    private BallisticMissileGuidance() {}
}
