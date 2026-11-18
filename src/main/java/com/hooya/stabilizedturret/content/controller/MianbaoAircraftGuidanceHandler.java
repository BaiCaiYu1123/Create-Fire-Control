package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.config.MissileSettings;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.iff.FireControlMissileOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoAircraftGuidanceHandler {
    private static final double GROUND_NAVIGATION_CONSTANT = 4.0;
    private static final double AIR_TO_AIR_LAUNCH_MOTOR_SPEED = 50.0 / 20.0;
    private static final double TOP_ATTACK_TERMINAL_RANGE = 100.0;
    private static final TicketType<UUID> TICKET = TicketType.<UUID>create(
            "create_fire_control_aircraft_missile", UUID::compareTo);
    private static final Map<Entity, State> GUIDED = new IdentityHashMap<>();
    private static final Map<Entity, PendingRecovery> RECOVERING = new IdentityHashMap<>();
    private static final String CONTROLLER_POS_TAG = "CreateFireControlAircraftController";
    private static final String CONTROLLER_SUBLEVEL_TAG =
            "CreateFireControlAircraftControllerSubLevel";
    private static final String CONTROLLER_SUBLEVEL_KNOWN_TAG =
            "CreateFireControlAircraftControllerSubLevelKnown";
    private static final String AGE_TAG = "CreateFireControlAircraftAge";
    private static final String RADAR_TARGET_TAG = "CreateFireControlAircraftRadarTarget";
    private static final String GROUND_TARGET_TAG = "CreateFireControlAircraftGroundTarget";
    private static final String GROUND_MEMBERS_TAG = "CreateFireControlAircraftGroundMembers";
    private static final String LASER_X_TAG = "CreateFireControlAircraftLaserX";
    private static final String LASER_Y_TAG = "CreateFireControlAircraftLaserY";
    private static final String LASER_Z_TAG = "CreateFireControlAircraftLaserZ";
    private static final String HAS_LASER_TAG = "CreateFireControlAircraftHasLaser";
    private static final String FUSE_TAG = "CreateFireControlAircraftFuse";
    private static final String SAFETY_TAG = "CreateFireControlAircraftSafetySeconds";
    private static final String INITIAL_SPEED_TAG = "CreateFireControlAircraftInitialSpeed";
    private static final String ANTI_RADIATION_TARGET_TAG = "CreateFireControlAntiRadiationTarget";

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity missile = event.getEntity();
        MianbaoAircraftCompat.WeaponType type = MianbaoAircraftCompat.typeOf(missile);
        if (type == null) return;
        // Television-guided missiles have their own player-input lifecycle.
        if (MianbaoAircraftCompat.isOpticalGuided(type)) return;
        var data = missile.getPersistentData();

        boolean restoring = data.getBoolean("CreateFireControlAircraftGuided")
                && data.contains(CONTROLLER_POS_TAG);
        if (restoring) {
            boolean exact = data.getBoolean(CONTROLLER_SUBLEVEL_KNOWN_TAG);
            UUID subLevelId = data.hasUUID(CONTROLLER_SUBLEVEL_TAG)
                    ? data.getUUID(CONTROLLER_SUBLEVEL_TAG) : null;
            StabilizerControllerBlockEntity controller =
                    ControllerTracker.findLoadedForMissile(
                            missile, BlockPos.of(data.getLong(CONTROLLER_POS_TAG)),
                            subLevelId, exact);
            if (controller == null && !MianbaoAircraftCompat.isAirToAir(type)) {
                PendingRecovery recovery = new PendingRecovery(
                        BlockPos.of(data.getLong(CONTROLLER_POS_TAG)), subLevelId,
                        exact, type, Math.max(0, data.getInt(AGE_TAG)));
                recovery.updateTickets(missile);
                synchronized (RECOVERING) { RECOVERING.put(missile, recovery); }
            } else {
                restoreGuidance(missile, controller, type);
            }
            return;
        }

        ControllerTracker.MianbaoAircraftLaunch launch =
                ControllerTracker.findMianbaoAircraftLaunch(missile);
        if (launch == null || launch.controller().getLevel() == null) return;
        StabilizerControllerBlockEntity controller = launch.controller();

        UUID radarTarget = null;
        UUID groundTarget = null;
        Set<UUID> groundMembers = Set.of();
        Vec3 laserTarget = null;
        if (MianbaoAircraftCompat.isInfrared(type)) {
            var infrared = InfraredTargeting.launchTarget(controller);
            if (infrared == null) return;
            groundTarget = infrared.id();
            groundMembers = infrared.members();
            InfraredSeeker.assign(data, infrared, InfraredTargeting.seekerTangent(controller));
        } else if (MianbaoAircraftCompat.isRadarGuided(type)) {
            radarTarget = controller.getAircraftGuidanceTargetId();
            if (radarTarget == null) return;
        } else if (MianbaoAircraftCompat.isAirToGround(type)) {
            StabilizerControllerBlockEntity.GroundTargetSnapshot snapshot =
                    controller.getAirToGroundLaunchTargetSnapshot();
            if (snapshot == null) return;
            groundTarget = snapshot.id();
            groundMembers = snapshot.memberIds();
        } else if (MianbaoAircraftCompat.isAntiRadiation(type)) {
            BindingRef radar = AntiRadiationTargeting.target(controller);
            if (radar == null) return;
            laserTarget = GroundRadarEmitters.boundPosition(controller.getLevel(), radar);
            if (laserTarget == null) return;
            data.put(ANTI_RADIATION_TARGET_TAG, radar.save());
        } else if (MianbaoAircraftCompat.isLaserGuided(type)) {
            laserTarget = controller.getPodGuidanceTarget();
            if (laserTarget == null) return;
        }

        Vec3 nativeVelocity = MianbaoMissileCompat.getWorldVelocity(missile);
        var mixedLaunch = MianbaoAircraftRackCompat.currentLaunch(missile);
        Vec3 inheritedVelocity = mixedLaunch == null
                ? MianbaoAircraftCompat.carrierVelocityAtMuzzle(controller.getLevel(), launch.rack())
                : MianbaoAircraftCompat.carrierVelocityAtPoint(
                        controller.getLevel(), launch.rack(), mixedLaunch.localMuzzle());
        // Use the rack already selected by launch association. Re-selecting
        // from all nearby racks can pick a different facing on a multi-rail plane.
        Vec3 launchDirection = MianbaoAircraftCompat.launchDirection(controller.getLevel(), launch.rack());
        if (launchDirection == null || launchDirection.lengthSqr() < 1.0e-8)
            launchDirection = findLaunchDirection(controller, missile, nativeVelocity, inheritedVelocity);

        // Air-to-air missiles leave the rail with the carrier's complete
        // world velocity plus 50 m/s along the rack axis. The resulting
        // world-speed magnitude is then scheduled to Mach 2 over five seconds;
        // carrier velocity is inherited only once and is never re-added.
        Vec3 launchVelocity = MianbaoAircraftCompat.isAirToAir(type)
                ? inheritedVelocity.add(launchDirection.scale(AIR_TO_AIR_LAUNCH_MOTOR_SPEED))
                // The native AGM procedure has already applied the motor
                // velocity. Add the carrier motion without replacing it.
                : nativeVelocity.add(inheritedVelocity);
        if (launchVelocity.lengthSqr() < 1.0e-8) {
            launchVelocity = launchDirection.scale(
                    MianbaoAircraftCompat.maximumSpeedBlocksPerTick(type));
        }
        MianbaoMissileCompat.setWorldVelocity(missile, launchVelocity);
        StabilizedTurret.LOGGER.debug(
                "Adopted aircraft missile {} from rack {} in Sable sublevel {}: "
                        + "native velocity={}, carrier muzzle velocity={}, final velocity={}",
                missile.getUUID(), launch.rack().pos(), launch.rack().subLevelId(),
                nativeVelocity, inheritedVelocity,
                MianbaoMissileCompat.getWorldVelocity(missile));
        missile.getPersistentData().putBoolean("CreateFireControlAircraftGuided", true);
        missile.getPersistentData().putLong(CONTROLLER_POS_TAG,
                controller.getBlockPos().asLong());
        saveControllerOwner(missile, controller);
        FireControlMissileOwnership.markController(missile, controller);
        AirborneWeaponViewHandler.launched(controller, missile);
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
        MissileFuseSafety.launched(missile);

        State state = new State(
                missile,
                controller,
                radarTarget,
                groundTarget,
                groundMembers,
                laserTarget,
                type,
                launchDirection,
                launchVelocity,
                MianbaoAircraftCompat.isAirToAir(type)
                        ? controller.getAircraftProximityFuseRange() : 0.0,
                safetySeconds,
                MianbaoMissileCompat.getWorldPosition(missile)
        );
        StabilizedTurret.LOGGER.info(
                "Adopted guided aircraft missile type={} entity={} route={} rack={} axis={} launchVelocity={} acquisitionTick={} safetyTicks={} fuseLaunchTick={}",
                type, missile.getUUID(), GuidedMissileMode.mathematical(missile) ? "math" : "entity",
                launch.rack().pos(), launchDirection, launchVelocity, MianbaoAircraftCompat.maneuverDelayTicks(type) + 1,
                missile.getPersistentData().getInt("CreateFireControlSafetyTicks"),
                missile.getPersistentData().getLong(MissileFuseSafety.LAUNCH_TICK_TAG));
        if (radarTarget != null) missile.getPersistentData().putUUID(RADAR_TARGET_TAG, radarTarget);
        if (groundTarget != null) {
            missile.getPersistentData().putUUID(GROUND_TARGET_TAG, groundTarget);
            saveUuidSet(missile.getPersistentData(), GROUND_MEMBERS_TAG, groundMembers);
        }
        if (laserTarget != null) {
            missile.getPersistentData().putBoolean(HAS_LASER_TAG, true);
            missile.getPersistentData().putDouble(LASER_X_TAG, laserTarget.x);
            missile.getPersistentData().putDouble(LASER_Y_TAG, laserTarget.y);
            missile.getPersistentData().putDouble(LASER_Z_TAG, laserTarget.z);
        }
        missile.getPersistentData().putDouble(FUSE_TAG, state.fuse);
        missile.getPersistentData().putDouble(SAFETY_TAG, safetySeconds);
        missile.getPersistentData().putDouble(INITIAL_SPEED_TAG, state.initialSpeed);
        state.refreshTarget();
        state.updateTickets(missile);
        synchronized (GUIDED) {
            GUIDED.put(missile, state);
        }
        // The missile owns its copied seeker memory before the launcher is reset.
        if (MianbaoAircraftCompat.isInfrared(type)) controller.resetInfraredLockAfterLaunch();
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

    public static List<RwrThreat> collectRwrMissileThreats(
            Level receiverLevel,
            Set<UUID> receiverStructure,
            Vec3 receiverOrigin,
            double range
    ) {
        if (receiverLevel == null || receiverStructure == null
                || receiverStructure.isEmpty()) return List.of();
        double rangeSqr = range * range;
        List<RwrThreat> result = new ArrayList<>();
        synchronized (GUIDED) {
            for (Map.Entry<Entity, State> entry : GUIDED.entrySet()) {
                Entity missile = entry.getKey();
                State state = entry.getValue();
                if (missile == null || missile.isRemoved() || !missile.isAlive()
                        || state.countermeasureDecoyed
                        || missile.level().getServer() != receiverLevel.getServer()
                        || !missile.level().dimension().equals(receiverLevel.dimension())) continue;
                state.refreshTarget();
                if (!state.targetsAny(receiverStructure)
                        && (state.radarTarget == null || !receiverStructure.contains(state.radarTarget))
                        && (state.groundTarget == null || !receiverStructure.contains(state.groundTarget))
                        && state.groundMembers.stream().noneMatch(receiverStructure::contains)) continue;
                Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
                if (position.distanceToSqr(receiverOrigin) > rangeSqr) continue;
                result.add(new RwrThreat(
                        missile.getUUID(), RwrThreat.Type.MSL, position, true));
            }
        }
        return List.copyOf(result);
    }

    /** Applies one independent decoy roll to each eligible pursuer and flare. */
    public static int tryCountermeasureDecoy(
            MianbaoCountermeasureManager.TrackedFlare flare
    ) {
        if (flare == null || flare.entity() == null || flare.carrierIds().isEmpty()) return 0;
        Entity countermeasure = flare.entity();
        Set<UUID> carrierIds = flare.carrierIds();
        int decoyed = 0;
        synchronized (GUIDED) {
            for (Map.Entry<Entity, State> entry : GUIDED.entrySet()) {
                Entity missile = entry.getKey();
                State state = entry.getValue();
                if (missile == null || missile.isRemoved() || !missile.isAlive()
                        || state.countermeasureDecoyed
                        || !MianbaoAircraftCompat.isAirToAir(state.type)) {
                    continue;
                }
                state.refreshTarget();
                if (state.targetCenter == null || !state.targetsAny(carrierIds)) continue;
                if (!state.countermeasureAttempts.add(countermeasure.getUUID())) continue;
                Vec3 missilePosition = MianbaoMissileCompat.getWorldPosition(missile);
                double approachMismatch =
                        MianbaoCountermeasureManager.approachSpeedMismatchMetersPerSecond(
                                missilePosition, state.worldVelocity,
                                state.targetPositionAt(missile.level().getGameTime()), state.targetVelocity);
                if (!Double.isFinite(approachMismatch)
                        || Math.abs(approachMismatch)
                        > MianbaoCountermeasureManager.MAX_DECOY_RELATIVE_SPEED_METERS_PER_SECOND) {
                    continue;
                }
                if (countermeasure.getRandom().nextDouble()
                        >= MianbaoCountermeasureManager.DECOY_CHANCE) {
                    continue;
                }
                state.applyCountermeasureDecoy(missile, countermeasure);
                decoyed++;
            }
        }
        return decoyed;
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
    public static void beforeTick(ServerTickEvent.Pre event) {
        // Mianbao's bridge resumes this State at the end of the ignition tick.
        // Correct it before the first powered native movement, including shots
        // which will be handed to mathematical flight after the visual transition.
        synchronized (GUIDED) {
            for (State state : GUIDED.values()) state.alignIgnitionHeading();
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        recoverPending();
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

                state.alignIgnitionHeading();
                MianbaoMissileCompat.advanceIfNativeTickMissed(missile, !MianbaoAircraftCompat.isAirToAir(state.type));
                if (missile.isRemoved()) continue;
                state.updateTickets(missile);
                state.age++;
                missile.getPersistentData().putInt(AGE_TAG, state.age);
                if (state.age >= MissileSettings.lifetimeTicks(state.missile)) {
                    finishLifetime(missile, state.type, MianbaoMissileCompat.getWorldPosition(missile));
                    state.releaseTickets();
                    iterator.remove();
                    continue;
                }

                state.refreshTarget();
                Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
                if (MissileFuseSafety.armed(missile, state.safetyTicks, state.age)) {
                    ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
                    SableMissileFuze.Contact contact = SableMissileFuze.firstBlockContact(
                            root, state.previousPosition, position);
                    if (contact != null) {
                        explode(missile, state.type, contact.position(), "block-contact");
                        state.releaseTickets();
                        iterator.remove();
                        continue;
                    }
                    if (!state.countermeasureDecoyed
                            && MianbaoAircraftCompat.isAirToAir(state.type)
                            && state.fuse > 0.0
                            && SableMissileFuze.anyCenterOfMassWithinRange(
                            root,
                            state.targetSubLevelIds,
                            state.previousPosition,
                            position,
                            state.fuse
                    )) {
                        explode(missile, state.type, position, "proximity");
                        state.releaseTickets();
                        iterator.remove();
                        continue;
                    }
                }
                state.previousPosition = position;

                Vec3 worldVelocity = state.worldVelocity;
                boolean maneuvering = state.age > MianbaoAircraftCompat.maneuverDelayTicks(state.type);
                if (maneuvering && worldVelocity.lengthSqr() > 1.0e-8) {
                    if (state.countermeasureDecoyed) worldVelocity = state.guideCountermeasure(position, worldVelocity);
                    else if (state.targetCenter != null)
                        worldVelocity = guide(state, position, worldVelocity, missile.level().getGameTime());
                }
                worldVelocity = approachMaximumSpeed(state, worldVelocity);
                // Apply to every branch: PN, top attack, laser and decoys must
                // share the same budget as radar interception, at commanded speed.
                worldVelocity = MissileSteeringLimits.limitVelocity(state.worldVelocity, worldVelocity,
                        maneuvering ? MianbaoAircraftCompat.interceptMaxTurnRadians(state.type, worldVelocity.length()) : 0);
                if (state.age == MianbaoAircraftCompat.maneuverDelayTicks(state.type) + 1) {
                    double turn = Math.acos(Mth.clamp(state.worldVelocity.normalize()
                            .dot(worldVelocity.normalize()), -1.0, 1.0));
                    StabilizedTurret.LOGGER.info(
                            "Aircraft missile acquisition type={} entity={} age={} speed={} turnDegrees={} limitDegrees={}",
                            state.type, missile.getUUID(), state.age, worldVelocity.length(), Math.toDegrees(turn),
                            Math.toDegrees(MianbaoAircraftCompat.interceptMaxTurnRadians(state.type, worldVelocity.length())));
                }
                if (worldVelocity.lengthSqr() > 1.0e-8) {
                    state.flightDirection = worldVelocity.normalize();
                }
                state.worldVelocity = worldVelocity;
                missile.setNoGravity(true);
                MianbaoMissileCompat.setWorldVelocity(missile, worldVelocity);
                if (MianbaoAircraftCompat.isAirToAir(state.type)) MianbaoMissileRemoteSync.broadcast(missile);
            }
        }
    }

    private static Vec3 guide(State state, Vec3 position, Vec3 velocity, long gameTime) {
        if (MianbaoAircraftCompat.isAirToAir(state.type)) {
            var profile = MianbaoAircraftCompat.speedProfile(state.missile, state.initialSpeed);
            double speed = profile.speedAt(state.age);
            Vec3 direction = AcceleratingMissileIntercept.guide(
                    position, velocity, state.targetPositionAt(gameTime), state.targetVelocity,
                    profile, state.age, MissileSettings.lifetimeTicks(state.missile) - state.age,
                    MianbaoAircraftCompat.interceptMaxTurnRadians(state.type, speed));
            return direction.scale(speed);
        }
        if (MianbaoAircraftCompat.isLaserGuided(state.type)) {
            return proportionalNavigation(state, position, velocity, Vec3.ZERO, 3.0);
        }
        if (state.type == MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_TOP_ATTACK) {
            Vec3 waypoint = topAttackWaypoint(state, position);
            return steerToward(
                    velocity,
                    waypoint.subtract(position),
                    MianbaoAircraftCompat.interceptMaxTurnRadians(state.type, velocity.length())
            );
        }
        return proportionalNavigation(state, position, velocity, state.targetVelocity,
                GROUND_NAVIGATION_CONSTANT);
    }

    private static Vec3 proportionalNavigation(State state, Vec3 position, Vec3 velocity,
                                               Vec3 targetVelocity, double navigationConstant) {
        var profile = MianbaoAircraftCompat.speedProfile(state.missile, state.initialSpeed);
        double speed = profile.speedAt(state.age);
        return AcceleratingMissileIntercept.guide(position, velocity, state.targetCenter,
                targetVelocity, profile, state.age, MissileSettings.lifetimeTicks(state.missile) - state.age,
                MianbaoAircraftCompat.interceptMaxTurnRadians(state.type, speed), navigationConstant).scale(speed);
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
        return AcceleratingMissileIntercept.acquire(velocity, desiredVector,
                desiredVector.length() / speed, maxTurnRadians).scale(speed);
    }

    private static Vec3 approachMaximumSpeed(State state, Vec3 velocity) {
        double scheduledSpeed = MianbaoAircraftCompat.speedProfile(
                state.missile, state.initialSpeed).speedAt(state.age);
        double speed = velocity.length();
        return speed < 1.0e-8
                ? state.flightDirection.scale(scheduledSpeed)
                : velocity.scale(scheduledSpeed / speed);
    }

    private static void finishLifetime(Entity missile, MianbaoAircraftCompat.WeaponType type, Vec3 position) {
        if (MianbaoAircraftCompat.isAntiRadiation(type) || MissileFuseSafety.protectedAircraft(missile)) {
            // A lifetime shorter than fuze insurance removes an inert missile;
            // it must not bypass insurance via the timeout explosion path.
            missile.discard();
        } else explode(missile, type, position, "lifetime");
    }

    private static void explode(
            Entity missile, MianbaoAircraftCompat.WeaponType type, Vec3 position, String cause
    ) {
        missile.getPersistentData().putString(MianbaoMissileDetonation.CAUSE_TAG, cause);
        MianbaoMissileDetonation.detonate(missile, position,
                MianbaoMissileDetonation.aircraftStrength(type));
    }

    private static void saveControllerOwner(
            Entity missile, StabilizerControllerBlockEntity controller
    ) {
        UUID subLevelId = ControllerTracker.controllerSubLevelId(controller);
        var data = missile.getPersistentData();
        data.putBoolean(CONTROLLER_SUBLEVEL_KNOWN_TAG, true);
        if (subLevelId != null) data.putUUID(CONTROLLER_SUBLEVEL_TAG, subLevelId);
        else data.remove(CONTROLLER_SUBLEVEL_TAG);
    }

    private static void saveUuidSet(CompoundTag owner, String key, Set<UUID> values) {
        ListTag list = new ListTag();
        if (values != null) {
            values.stream().sorted().forEach(value -> {
                CompoundTag item = new CompoundTag();
                item.putUUID("Id", value);
                list.add(item);
            });
        }
        owner.put(key, list);
    }

    private static Set<UUID> loadUuidSet(CompoundTag owner, String key) {
        if (!owner.contains(key, Tag.TAG_LIST)) return Set.of();
        java.util.HashSet<UUID> values = new java.util.HashSet<>();
        ListTag list = owner.getList(key, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag item = list.getCompound(index);
            if (item.hasUUID("Id")) values.add(item.getUUID("Id"));
        }
        return Set.copyOf(values);
    }

    private static void restoreGuidance(
            Entity missile,
            StabilizerControllerBlockEntity controller,
            MianbaoAircraftCompat.WeaponType type
    ) {
        var data = missile.getPersistentData();
        UUID radarTarget = data.hasUUID(RADAR_TARGET_TAG)
                ? data.getUUID(RADAR_TARGET_TAG) : null;
        UUID groundTarget = data.hasUUID(GROUND_TARGET_TAG)
                ? data.getUUID(GROUND_TARGET_TAG) : null;
        Set<UUID> groundMembers = loadUuidSet(data, GROUND_MEMBERS_TAG);
        Vec3 laserTarget = data.getBoolean(HAS_LASER_TAG)
                ? new Vec3(data.getDouble(LASER_X_TAG), data.getDouble(LASER_Y_TAG),
                data.getDouble(LASER_Z_TAG)) : null;
        Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
        Vec3 direction = velocity.lengthSqr() < 1.0e-8
                ? controller == null ? new Vec3(0, 0, 1) : controller.getAircraftRadarForward() : velocity.normalize();
        State state = new State(missile, controller, radarTarget, groundTarget, groundMembers,
                laserTarget, type, direction, velocity, data.getDouble(FUSE_TAG),
                data.getDouble(SAFETY_TAG), MianbaoMissileCompat.getWorldPosition(missile));
        state.age = Math.max(0, data.getInt(AGE_TAG));
        MissileFuseSafety.restoreClock(missile, state.age);
        if (data.contains(INITIAL_SPEED_TAG)) {
            state.initialSpeed = Math.max(0.0, data.getDouble(INITIAL_SPEED_TAG));
        }
        state.refreshTarget();
        state.updateTickets(missile);
        synchronized (GUIDED) { GUIDED.put(missile, state); }
        if (controller != null) FireControlMissileOwnership.markController(missile, controller);
    }

    private static void recoverPending() {
        synchronized (RECOVERING) {
            Iterator<Map.Entry<Entity, PendingRecovery>> iterator =
                    RECOVERING.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Entity, PendingRecovery> entry = iterator.next();
                Entity missile = entry.getKey();
                PendingRecovery recovery = entry.getValue();
                if (missile == null || missile.isRemoved() || !missile.isAlive()) {
                    recovery.releaseTickets();
                    iterator.remove();
                    continue;
                }
                MianbaoMissileCompat.advanceIfNativeTickMissed(missile);
                recovery.updateTickets(missile);
                missile.getPersistentData().putInt(AGE_TAG, ++recovery.age);
                if (recovery.age >= MissileSettings.lifetimeTicks(missile)) {
                    finishLifetime(missile, recovery.type,
                            MianbaoMissileCompat.getWorldPosition(missile));
                    recovery.releaseTickets();
                    iterator.remove();
                    continue;
                }
                StabilizerControllerBlockEntity controller =
                        ControllerTracker.findLoadedForMissile(
                                missile, recovery.controllerPos,
                                recovery.subLevelId, recovery.exactSubLevel);
                if (controller == null) continue;
                restoreGuidance(missile, controller, recovery.type);
                recovery.releaseTickets();
                iterator.remove();
            }
        }
    }

    private static final class PendingRecovery {
        final BlockPos controllerPos;
        final UUID subLevelId;
        final boolean exactSubLevel;
        final MianbaoAircraftCompat.WeaponType type;
        final MissileChunkTickets chunkTickets = new MissileChunkTickets(TICKET);
        int age;

        PendingRecovery(BlockPos controllerPos, UUID subLevelId,
                        boolean exactSubLevel,
                        MianbaoAircraftCompat.WeaponType type, int age) {
            this.controllerPos = controllerPos;
            this.subLevelId = subLevelId;
            this.exactSubLevel = exactSubLevel;
            this.type = type;
            this.age = age;
        }

        void updateTickets(Entity missile) { chunkTickets.update(missile, null); }
        void releaseTickets() { chunkTickets.release(); }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (RECOVERING) {
            for (PendingRecovery state : RECOVERING.values()) state.releaseTickets();
            RECOVERING.clear();
        }
        synchronized (GUIDED) {
            for (State state : GUIDED.values()) state.releaseTickets();
            GUIDED.clear();
        }
    }

    private static final class State {
        final Entity missile;
        final ActiveRadarSeeker seeker;
        final InfraredSeeker infraredSeeker;
        final StabilizerControllerBlockEntity controller;
        final UUID radarTarget;
        final UUID groundTarget;
        final BindingRef antiRadiationTarget;
        final Set<UUID> groundMembers;
        final MianbaoAircraftCompat.WeaponType type;
        final MissileChunkTickets chunkTickets = new MissileChunkTickets(TICKET);
        double initialSpeed;
        final double fuse;
        final int safetyTicks;
        double topAttackCruiseAltitude;
        int age;
        Vec3 flightDirection;
        Vec3 worldVelocity;
        Vec3 targetCenter;
        Vec3 targetVelocity = Vec3.ZERO;
        long targetSampleTick = Long.MIN_VALUE;
        AABB targetBounds;
        Set<UUID> targetSubLevelIds = Set.of();
        Set<UUID> decoyedTargetIds = Set.of();
        Vec3 previousPosition;
        boolean countermeasureDecoyed;
        final Set<UUID> countermeasureAttempts = new HashSet<>();
        Entity countermeasureTarget;
        boolean countermeasureMissed;

        State(
                Entity missile,
                StabilizerControllerBlockEntity controller,
                UUID radarTarget,
                UUID groundTarget,
                Set<UUID> groundMembers,
                Vec3 laserTarget,
                MianbaoAircraftCompat.WeaponType type,
                Vec3 launchDirection,
                Vec3 launchVelocity,
                double fuse,
                double safetySeconds,
                Vec3 initialPosition
        ) {
            this.missile = missile;
            this.seeker = MianbaoAircraftCompat.isRadarGuided(type) && radarTarget != null
                    ? new ActiveRadarSeeker(radarTarget, ActiveRadarSeeker.AimPoint.BOUNDS, missile.getPersistentData()) : null;
            this.infraredSeeker = MianbaoAircraftCompat.isInfrared(type) ? new InfraredSeeker(missile.getPersistentData()) : null;
            this.controller = controller;
            this.radarTarget = radarTarget;
            this.groundTarget = groundTarget;
            this.antiRadiationTarget = BindingRef.load(missile.getPersistentData(), ANTI_RADIATION_TARGET_TAG);
            this.groundMembers = groundMembers == null ? Set.of() : Set.copyOf(groundMembers);
            this.type = type;
            this.flightDirection = launchDirection.lengthSqr() < 1.0e-8
                    ? new Vec3(0.0, 0.0, 1.0)
                    : launchDirection.normalize();
            this.worldVelocity = launchVelocity;
            this.previousPosition = initialPosition;
            this.initialSpeed = launchVelocity.length();
            this.fuse = Math.max(0.0, Math.min(10.0, fuse));
            this.safetyTicks = Math.max(
                    0,
                    Math.min(200, (int) Math.round(safetySeconds * 20.0))
            );
            this.targetCenter = laserTarget;
            this.topAttackCruiseAltitude = initialPosition.y + 35.0;
        }

        void refreshTarget() {
            if (countermeasureDecoyed) return;
            Vec3 oldCenter = targetCenter;
            Vec3 nextCenter = null;
            AABB nextBounds = null;
            if (infraredSeeker != null) {
                var track = infraredSeeker.update(missile);
                if (track == null) {
                    targetCenter = null;
                    targetBounds = null;
                    targetVelocity = Vec3.ZERO;
                    targetSubLevelIds = Set.of();
                    return;
                }
                nextCenter = track.center(); nextBounds = track.bounds();
                targetVelocity = track.velocity(); targetSampleTick = track.tick();
                targetSubLevelIds = track.members();
            } else if (MianbaoAircraftCompat.isRadarGuided(type)) {
                if (seeker == null) return;
                if (seeker.memory() == null && controller != null && !controller.isRemoved()) {
                    var cue = controller.getRadarContact(radarTarget);
                    if (cue != null) seeker.seed(cue.center(), cue.velocity(), cue.bounds(), cue.memberIds(),
                            controller.getRadarContactSampleTick() == Long.MIN_VALUE
                                    ? missile.level().getGameTime() : controller.getRadarContactSampleTick());
                }
                seeker.update(missile);
                var track = seeker.memory();
                if (track != null) {
                    nextCenter = track.center(); nextBounds = track.bounds();
                    targetVelocity = track.velocity(); targetSampleTick = track.tick();
                    targetSubLevelIds = track.members();
                }
            } else if (MianbaoAircraftCompat.isAntiRadiation(type)) {
                nextCenter = GroundRadarEmitters.boundPosition(missile.level(), antiRadiationTarget);
                if (nextCenter == null) {
                    // A stopped, unbound or destroyed radar cannot continue emitting a guidance cue.
                    targetCenter = null;
                    targetBounds = null;
                    targetVelocity = Vec3.ZERO;
                    targetSubLevelIds = Set.of();
                    return;
                }
                long now = missile.level().getGameTime();
                if (targetSampleTick != now) {
                    targetVelocity = oldCenter == null || targetSampleTick == Long.MIN_VALUE
                            ? Vec3.ZERO : nextCenter.subtract(oldCenter).scale(1.0 / Math.max(1, now - targetSampleTick));
                    targetSampleTick = now;
                }
                nextBounds = new AABB(nextCenter, nextCenter).inflate(0.5);
                targetSubLevelIds = antiRadiationTarget.subLevelId() == null
                        ? Set.of() : Set.of(antiRadiationTarget.subLevelId());
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

        void alignIgnitionHeading() {
            if (type != MianbaoAircraftCompat.WeaponType.FAR || age != 0 || missile.isRemoved()) return;
            CompoundTag data = missile.getPersistentData();
            if (data.getBoolean(FarMissileIgnitionHeading.ALIGNED_TAG)
                    || !data.contains(FarMissileIgnitionHeading.RELEASE_TAG, Tag.TAG_COMPOUND)) return;
            Vec3 direction = FarMissileIgnitionHeading.direction(
                    data.getCompound(FarMissileIgnitionHeading.RELEASE_TAG));
            if (direction == null) return;
            double speed = MianbaoMissileCompat.getWorldVelocity(missile).length();
            if (!Double.isFinite(speed) || speed < 1.0e-8) return;
            worldVelocity = direction.scale(speed);
            flightDirection = direction;
            initialSpeed = speed;
            data.putDouble(INITIAL_SPEED_TAG, speed);
            MianbaoMissileCompat.setWorldVelocity(missile, worldVelocity);
            // Arrow pitch has the same sign as vertical velocity. Reset its
            // interpolation too, so a falling arrow pose cannot dip the nose.
            Vec3 localVelocity = missile.getDeltaMovement();
            float yaw = (float) Math.toDegrees(Math.atan2(localVelocity.x, localVelocity.z));
            float pitch = (float) Math.toDegrees(Math.atan2(localVelocity.y, localVelocity.horizontalDistance()));
            missile.setYRot(yaw); missile.yRotO = yaw;
            missile.setXRot(pitch); missile.xRotO = pitch;
            data.putBoolean(FarMissileIgnitionHeading.ALIGNED_TAG, true);
        }

        void updateTickets(Entity missile) {
            chunkTickets.update(missile, worldVelocity);
        }

        Vec3 targetPositionAt(long gameTime) {
            return targetCenter == null || !MianbaoAircraftCompat.isAirToAir(type)
                    ? targetCenter : AcceleratingMissileIntercept.targetPositionAt(
                    targetCenter, targetVelocity, targetSampleTick, gameTime);
        }

        void releaseTickets() {
            chunkTickets.release();
        }

        boolean targetsAny(Set<UUID> carrierIds) {
            for (UUID id : carrierIds) {
                if (targetSubLevelIds.contains(id)) return true;
            }
            return false;
        }

        void applyCountermeasureDecoy(Entity missile, Entity countermeasure) {
            Vec3 missilePosition = MianbaoMissileCompat.getWorldPosition(missile);
            Vec3 flarePosition = MianbaoMissileCompat.getWorldPosition(countermeasure);
            Vec3 toFlare = flarePosition.subtract(missilePosition);
            boolean canTurnTowardFlare = toFlare.lengthSqr() >= 1.0e-8
                    && worldVelocity.dot(toFlare.normalize()) > 0.0;
            // Selecting a decoy must not spend a second steering budget in an
            // event callback. The regular flight tick performs the bounded turn.
            Vec3 diverted = worldVelocity;
            decoyedTargetIds = Set.copyOf(targetSubLevelIds);
            countermeasureDecoyed = true;
            countermeasureTarget = countermeasure;
            countermeasureMissed = !canTurnTowardFlare;
            targetCenter = null;
            targetVelocity = Vec3.ZERO;
            targetBounds = null;
            targetSubLevelIds = Set.of();
            previousPosition = missilePosition;
            worldVelocity = diverted;
            flightDirection = diverted.normalize();
            missile.getPersistentData().putBoolean(
                    "CreateFireControlCountermeasureDecoyed", true);
            missile.getPersistentData().putInt(
                    "CreateFireControlCountermeasureGraceUntil", missile.tickCount + 5);
            missile.setNoGravity(true);
            MianbaoMissileCompat.setWorldVelocity(missile, diverted);
        }

        Vec3 guideCountermeasure(Vec3 position, Vec3 velocity) {
            if (countermeasureMissed || countermeasureTarget == null
                    || countermeasureTarget.isRemoved() || !countermeasureTarget.isAlive()) {
                countermeasureMissed = true;
                return velocity;
            }
            Vec3 toFlare = MianbaoMissileCompat.getWorldPosition(countermeasureTarget)
                    .subtract(position);
            if (toFlare.lengthSqr() < 1.0e-8
                    || velocity.dot(toFlare.normalize()) <= 0.0) {
                countermeasureMissed = true;
                return velocity;
            }
            return steerToward(velocity, toFlare,
                    MianbaoAircraftCompat.interceptMaxTurnRadians(type, velocity.length()));
        }

        boolean ignoresCountermeasureContact(UUID subLevelId) {
            return countermeasureDecoyed
                    && subLevelId != null
                    && decoyedTargetIds.contains(subLevelId);
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
