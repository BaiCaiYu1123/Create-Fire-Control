package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoAircraftGuidanceHandler {
    private static final double NAVIGATION_CONSTANT = 4.0;
    private static final double STANDARD_GRAVITY_BLOCKS_PER_TICK_SQR = 9.80665 / 400.0;
    private static final double TOP_ATTACK_TERMINAL_RANGE = 100.0;
    private static final TicketType<UUID> TICKET = TicketType.<UUID>create(
            "create_fire_control_aircraft_missile", UUID::compareTo);
    private static final Map<Entity, State> GUIDED = new IdentityHashMap<>();

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity missile = event.getEntity();
        MianbaoAircraftCompat.WeaponType type = MianbaoAircraftCompat.typeOf(missile);
        if (type == null) return;

        StabilizerControllerBlockEntity controller =
                ControllerTracker.findForMianbaoAircraftMissile(missile);
        if (controller == null) return;

        UUID radarTarget = null;
        UUID groundTarget = null;
        Set<UUID> groundMembers = Set.of();
        Vec3 laserTarget = null;
        if (MianbaoAircraftCompat.isAirToAir(type)) {
            radarTarget = controller.getAircraftGuidanceTargetId();
            if (radarTarget == null) return;
        } else if (MianbaoAircraftCompat.isAirToGround(type)) {
            StabilizerControllerBlockEntity.GroundTargetSnapshot snapshot =
                    controller.getAirToGroundTargetSnapshot();
            if (snapshot == null) return;
            groundTarget = snapshot.id();
            groundMembers = snapshot.memberIds();
        } else if (MianbaoAircraftCompat.isLaserGuided(type)) {
            laserTarget = controller.getPodGuidanceTarget();
            if (laserTarget == null) return;
        }

        Vec3 nativeVelocity = MianbaoMissileCompat.getWorldVelocity(missile);
        Vec3 inheritedVelocity = controller.getAircraftVelocity();
        Vec3 launchDirection = findLaunchDirection(controller, missile, nativeVelocity, inheritedVelocity);

        // The complete carrier velocity is inherited once at rail separation.
        // Motor speed is then accumulated over five seconds by the guidance
        // state; it is never re-added on later ticks.
        MianbaoMissileCompat.setWorldVelocity(missile, inheritedVelocity);
        missile.getPersistentData().putBoolean("CreateFireControlAircraftGuided", true);
        missile.getPersistentData().putBoolean("\u9501\u5b9a\u6a21\u5f0f", false);
        missile.getPersistentData().putBoolean("\u5750\u6807\u6a21\u5f0f", false);
        missile.getPersistentData().putBoolean("jamed", true);
        double safetySeconds = MianbaoAircraftCompat.isAirToAir(type)
                ? controller.getAircraftFuseSafetySeconds()
                : 1.0;
        missile.getPersistentData().putInt(
                "CreateFireControlSafetyTicks",
                Mth.clamp(
                        (int) Math.round(safetySeconds * 20.0),
                        0,
                        200
                )
        );

        State state = new State(
                controller,
                radarTarget,
                groundTarget,
                groundMembers,
                laserTarget,
                type,
                launchDirection,
                inheritedVelocity,
                MianbaoAircraftCompat.isAirToAir(type)
                        ? controller.getAircraftProximityFuseRange() : 0.0,
                safetySeconds,
                MianbaoMissileCompat.getWorldPosition(missile)
        );
        state.refreshTarget();
        state.updateTickets(missile);
        synchronized (GUIDED) {
            GUIDED.put(missile, state);
        }
    }

    private static Vec3 findLaunchDirection(
            StabilizerControllerBlockEntity controller,
            Entity missile,
            Vec3 nativeVelocity,
            Vec3 inheritedVelocity
    ) {
        Vec3 launchDirection = null;
        double closestRackDistance = Double.POSITIVE_INFINITY;
        for (BindingRef rack : controller.getAircraftMissileRacks()) {
            double distance = MianbaoAircraftCompat.spawnDistanceSqr(
                    controller.getLevel(), rack, missile);
            Vec3 rackDirection = MianbaoAircraftCompat.launchDirection(
                    controller.getLevel(), rack);
            if (rackDirection != null && distance < closestRackDistance) {
                closestRackDistance = distance;
                launchDirection = rackDirection;
            }
        }
        if (launchDirection != null) return launchDirection;
        Vec3 nativeMotorVelocity = nativeVelocity.subtract(inheritedVelocity);
        if (nativeMotorVelocity.lengthSqr() > 1.0e-8) return nativeMotorVelocity.normalize();
        return controller.getAircraftRadarForward();
    }

    public static boolean hasAnyGuidedMissile() {
        synchronized (GUIDED) {
            return !GUIDED.isEmpty();
        }
    }

    public static boolean hasGuidedMissileNear(ServerLevel level, Vec3 position) {
        synchronized (GUIDED) {
            for (Entity missile : GUIDED.keySet()) {
                if (missile != null
                        && MianbaoMissileCompat.getRootServerLevel(missile) == level
                        && MianbaoMissileCompat.getWorldPosition(missile)
                        .distanceToSqr(position) < 0.25) {
                    return true;
                }
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        synchronized (GUIDED) {
            Iterator<Map.Entry<Entity, State>> iterator = GUIDED.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Entity, State> entry = iterator.next();
                Entity missile = entry.getKey();
                State state = entry.getValue();
                if (missile == null || missile.isRemoved() || !missile.isAlive()) {
                    state.releaseTickets();
                    iterator.remove();
                    continue;
                }

                MianbaoMissileCompat.advanceIfNativeTickMissed(missile);
                state.updateTickets(missile);
                state.age++;
                if (state.age >= MianbaoAircraftCompat.lifetimeTicks(state.type)) {
                    explode(missile);
                    state.releaseTickets();
                    iterator.remove();
                    continue;
                }

                state.refreshTarget();
                Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
                if (state.age >= state.safetyTicks
                        && state.targetBounds != null
                        && segmentHitsBounds(
                        state.previousPosition,
                        position,
                        state.targetBounds.inflate(state.fuse)
                )) {
                    explode(missile);
                    state.releaseTickets();
                    iterator.remove();
                    continue;
                }
                state.previousPosition = position;

                Vec3 worldVelocity = state.worldVelocity;
                if (state.age > MianbaoAircraftCompat.maneuverDelayTicks(state.type)
                        && state.targetCenter != null
                        && worldVelocity.lengthSqr() > 1.0e-8) {
                    worldVelocity = guide(state, position, worldVelocity);
                }
                worldVelocity = approachMaximumSpeed(state, worldVelocity);
                if (worldVelocity.lengthSqr() > 1.0e-8) {
                    state.flightDirection = worldVelocity.normalize();
                }
                state.worldVelocity = worldVelocity;
                missile.setNoGravity(true);
                MianbaoMissileCompat.setWorldVelocity(missile, worldVelocity);
            }
        }
    }

    private static Vec3 guide(State state, Vec3 position, Vec3 velocity) {
        if (MianbaoAircraftCompat.isLaserGuided(state.type)) {
            return steerToward(
                    velocity,
                    state.targetCenter.subtract(position),
                    MianbaoAircraftCompat.maxTurnRadians(state.type)
            );
        }
        if (state.type == MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_TOP_ATTACK) {
            Vec3 waypoint = topAttackWaypoint(state, position);
            return steerToward(
                    velocity,
                    waypoint.subtract(position),
                    MianbaoAircraftCompat.maxTurnRadians(state.type)
            );
        }
        return proportionalNavigation(state, position, velocity);
    }

    private static Vec3 proportionalNavigation(State state, Vec3 position, Vec3 velocity) {
        Vec3 relativePosition = state.targetCenter.subtract(position);
        double distanceSqr = relativePosition.lengthSqr();
        if (distanceSqr < 1.0e-8) return velocity;

        Vec3 velocityDirection = velocity.normalize();
        Vec3 lineOfSight = relativePosition.normalize();
        Vec3 relativeVelocity = state.targetVelocity.subtract(velocity);
        double measuredClosingSpeed = -relativeVelocity.dot(lineOfSight);
        double navigationClosingSpeed = Math.max(
                measuredClosingSpeed, velocity.length() * 0.25);
        Vec3 losAngularVelocity = relativePosition.cross(relativeVelocity)
                .scale(1.0 / distanceSqr);
        Vec3 commanded = losAngularVelocity.cross(velocityDirection)
                .scale(NAVIGATION_CONSTANT * navigationClosingSpeed);
        commanded = commanded.subtract(
                velocityDirection.scale(commanded.dot(velocityDirection)));

        double availableAcceleration = Math.min(
                maxLateralAcceleration(state.type),
                velocity.length() * MianbaoAircraftCompat.maxTurnRadians(state.type)
        );
        if (commanded.length() > availableAcceleration) {
            commanded = commanded.normalize().scale(availableAcceleration);
        }
        return velocity.add(commanded);
    }

    /**
     * The top-attack profile climbs to a moving high-altitude lead point, stays
     * on the high arc during mid-course, then changes to the target centre for
     * the terminal dive inside 100 blocks.
     */
    private static Vec3 topAttackWaypoint(State state, Vec3 position) {
        Vec3 target = state.targetCenter;
        double dx = target.x - position.x;
        double dz = target.z - position.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance <= TOP_ATTACK_TERMINAL_RANGE) return target;

        double cruiseHeight = Math.max(
                state.topAttackCruiseAltitude,
                target.y + Mth.clamp(horizontalDistance * 0.12, 45.0, 160.0)
        );
        double lead = Math.min(160.0, Math.max(30.0, horizontalDistance - 80.0));
        double inverse = 1.0 / Math.max(1.0e-6, horizontalDistance);
        return new Vec3(
                position.x + dx * inverse * lead,
                cruiseHeight,
                position.z + dz * inverse * lead
        );
    }

    private static Vec3 steerToward(Vec3 velocity, Vec3 desiredVector, double maxTurnRadians) {
        if (velocity.lengthSqr() < 1.0e-8 || desiredVector.lengthSqr() < 1.0e-8) {
            return velocity;
        }
        double speed = velocity.length();
        Vec3 current = velocity.scale(1.0 / speed);
        Vec3 desired = desiredVector.normalize();
        double angle = Math.acos(Mth.clamp(current.dot(desired), -1.0, 1.0));
        if (angle < 1.0e-6) return velocity;
        double fraction = Math.min(1.0, maxTurnRadians / angle);
        Vec3 blended = current.scale(1.0 - fraction).add(desired.scale(fraction));
        if (blended.lengthSqr() < 1.0e-8) return velocity;
        return blended.normalize().scale(speed);
    }

    private static Vec3 approachMaximumSpeed(State state, Vec3 velocity) {
        double progress = Mth.clamp(
                (double) state.age / MianbaoAircraftCompat.accelerationTicks(state.type),
                0.0,
                1.0
        );
        double scheduledSpeed = Mth.lerp(
                progress, state.initialSpeed, state.maximumSpeed);
        double speed = velocity.length();
        return speed < 1.0e-8
                ? state.flightDirection.scale(scheduledSpeed)
                : velocity.scale(scheduledSpeed / speed);
    }

    private static double maxLateralAcceleration(MianbaoAircraftCompat.WeaponType type) {
        double maximumG = switch (type) {
            case FAR -> 400.0;
            case MEDIUM -> 450.0;
            case CLOSE -> 500.0;
            case AIR_TO_GROUND_DIRECT -> 45.0;
            case AIR_TO_GROUND_TOP_ATTACK -> 40.0;
            case LASER_GUIDED -> 45.0;
        };
        return maximumG * STANDARD_GRAVITY_BLOCKS_PER_TICK_SQR;
    }

    private static boolean segmentHitsBounds(Vec3 from, Vec3 to, AABB bounds) {
        return bounds.contains(from) || bounds.contains(to) || bounds.clip(from, to).isPresent();
    }

    private static void explode(Entity missile) {
        MianbaoMissileEffects.broadcastExplosion(missile, 3.0F, false);
        ServerLevel level = MianbaoMissileCompat.getRootServerLevel(missile);
        if (level != null) {
            Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
            if (MianbaoMissileCompat.areRootChunksLoaded(missile, position, 3.0)) {
                level.explode(
                        null,
                        position.x,
                        position.y,
                        position.z,
                        3.0F,
                        Level.ExplosionInteraction.BLOCK
                );
            }
        }
        missile.discard();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (GUIDED) {
            for (State state : GUIDED.values()) state.releaseTickets();
            GUIDED.clear();
        }
    }

    private static final class State {
        final StabilizerControllerBlockEntity controller;
        final UUID radarTarget;
        final UUID groundTarget;
        final Set<UUID> groundMembers;
        final MianbaoAircraftCompat.WeaponType type;
        final MissileChunkTickets chunkTickets = new MissileChunkTickets(TICKET);
        final double initialSpeed;
        final double maximumSpeed;
        final double fuse;
        final int safetyTicks;
        double topAttackCruiseAltitude;
        int age;
        Vec3 flightDirection;
        Vec3 worldVelocity;
        Vec3 targetCenter;
        Vec3 targetVelocity = Vec3.ZERO;
        AABB targetBounds;
        Vec3 previousPosition;

        State(
                StabilizerControllerBlockEntity controller,
                UUID radarTarget,
                UUID groundTarget,
                Set<UUID> groundMembers,
                Vec3 laserTarget,
                MianbaoAircraftCompat.WeaponType type,
                Vec3 launchDirection,
                Vec3 inheritedVelocity,
                double fuse,
                double safetySeconds,
                Vec3 initialPosition
        ) {
            this.controller = controller;
            this.radarTarget = radarTarget;
            this.groundTarget = groundTarget;
            this.groundMembers = groundMembers == null ? Set.of() : Set.copyOf(groundMembers);
            this.type = type;
            this.flightDirection = launchDirection.lengthSqr() < 1.0e-8
                    ? new Vec3(0.0, 0.0, 1.0)
                    : launchDirection.normalize();
            this.worldVelocity = inheritedVelocity;
            this.previousPosition = initialPosition;
            this.initialSpeed = inheritedVelocity.length();
            this.maximumSpeed = MianbaoAircraftCompat.maximumSpeedBlocksPerTick(type);
            this.fuse = Math.max(0.0, Math.min(10.0, fuse));
            this.safetyTicks = Math.max(
                    0,
                    Math.min(200, (int) Math.round(safetySeconds * 20.0))
            );
            this.targetCenter = laserTarget;
            this.topAttackCruiseAltitude = initialPosition.y + 35.0;
        }

        void refreshTarget() {
            Vec3 oldCenter = targetCenter;
            Vec3 nextCenter = null;
            AABB nextBounds = null;
            if (MianbaoAircraftCompat.isAirToAir(type)) {
                StabilizerControllerBlockEntity.RadarContact contact =
                        controller == null || controller.isRemoved()
                                ? null
                                : controller.getRadarContact(radarTarget);
                if (contact != null) {
                    nextCenter = contact.center();
                    nextBounds = contact.bounds();
                    targetVelocity = contact.velocity();
                }
            } else if (MianbaoAircraftCompat.isAirToGround(type)) {
                StabilizerControllerBlockEntity.GroundTargetSnapshot snapshot =
                        controller == null || controller.isRemoved()
                                ? null
                                : controller.resolveGroundTarget(groundTarget, groundMembers);
                if (snapshot != null) {
                    nextCenter = snapshot.center();
                    nextBounds = snapshot.bounds();
                    if (oldCenter != null) {
                        targetVelocity = nextCenter.subtract(oldCenter);
                    }
                    if (type == MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_TOP_ATTACK
                            && age <= 1) {
                        topAttackCruiseAltitude = Math.max(
                                previousPosition.y + 35.0,
                                nextCenter.y + Mth.clamp(
                                        horizontalDistance(previousPosition, nextCenter) * 0.15,
                                        50.0,
                                        180.0
                                )
                        );
                    }
                }
            } else if (MianbaoAircraftCompat.isLaserGuided(type)
                    && controller != null && !controller.isRemoved()) {
                nextCenter = controller.getPodGuidanceTarget();
                targetVelocity = Vec3.ZERO;
                if (nextCenter != null) {
                    targetBounds = new AABB(
                            nextCenter.x - 0.5,
                            nextCenter.y - 0.5,
                            nextCenter.z - 0.5,
                            nextCenter.x + 0.5,
                            nextCenter.y + 0.5,
                            nextCenter.z + 0.5
                    );
                }
            }
            if (nextCenter != null) {
                targetCenter = nextCenter;
                if (!MianbaoAircraftCompat.isLaserGuided(type)) {
                    targetBounds = nextBounds;
                }
            }
        }

        void updateTickets(Entity missile) {
            chunkTickets.update(missile, worldVelocity);
        }

        void releaseTickets() {
            chunkTickets.release();
        }

        private static double horizontalDistance(Vec3 a, Vec3 b) {
            double dx = a.x - b.x;
            double dz = a.z - b.z;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    private MianbaoAircraftGuidanceHandler() {
    }
}
