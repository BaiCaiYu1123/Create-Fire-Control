package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.AutoDefenseMissileGuidance;
import com.hooya.stabilizedturret.content.iff.FireControlMissileOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
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
public final class MianbaoAirDefenseGuidanceHandler {
    private static final TicketType<UUID> MISSILE_TICKET = TicketType.<UUID>create(
            "create_fire_control_air_defense_missile", UUID::compareTo);
    private static final Map<Entity, GuidanceState> GUIDED = new IdentityHashMap<>();
    private static final Map<Entity, PendingRecovery> RECOVERING = new IdentityHashMap<>();
    private static final String CONTROLLER_POS_TAG = "CreateFireControlAaController";
    private static final String CONTROLLER_SUBLEVEL_TAG =
            "CreateFireControlAaControllerSubLevel";
    private static final String CONTROLLER_SUBLEVEL_KNOWN_TAG =
            "CreateFireControlAaControllerSubLevelKnown";
    private static final String TARGET_TAG = "CreateFireControlAaTarget";
    private static final String AGE_TAG = "CreateFireControlAaAge";
    private static final String INITIAL_SPEED_TAG = "CreateFireControlAaInitialSpeed";
    private static final String FUSE_TAG = "CreateFireControlAaFuse";

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !MianbaoAirDefenseCompat.isMissile(event.getEntity())) {
            return;
        }
        Entity missile = event.getEntity();
        if (OrdinaryAirDefenseGuidance.isManaged(missile)) return;
        var data = missile.getPersistentData();
        if (data.getBoolean("CreateFireControlDisplayGuided")
                || AutoDefenseMissileGuidance.isPendingLaunch(missile)) return;
        boolean restoring = data.getBoolean("CreateFireControlAaGuided")
                && data.contains(CONTROLLER_POS_TAG) && data.hasUUID(TARGET_TAG);
        StabilizerControllerBlockEntity controller;
        if (restoring) {
            boolean exact = data.getBoolean(CONTROLLER_SUBLEVEL_KNOWN_TAG);
            UUID subLevelId = data.hasUUID(CONTROLLER_SUBLEVEL_TAG)
                    ? data.getUUID(CONTROLLER_SUBLEVEL_TAG) : null;
            controller = ControllerTracker.findLoadedForMissile(
                    missile, BlockPos.of(data.getLong(CONTROLLER_POS_TAG)),
                    subLevelId, exact);
        } else {
            controller = ControllerTracker.findForMianbaoAaMissile(missile);
        }
        UUID target = restoring ? data.getUUID(TARGET_TAG)
                : controller == null ? null : controller.getRadarLockedSubLevelId();
        if ((!restoring && controller == null) || target == null) {
            return;
        }
        GuidanceState state = new GuidanceState(
                missile,
                controller,
                target,
                MianbaoMissileCompat.getWorldVelocity(missile),
                launchSpeed(missile),
                restoring ? data.getDouble(FUSE_TAG) : controller.getAaProximityFuseRange(),
                MianbaoMissileCompat.getWorldPosition(missile));
        state.ageTicks = restoring ? Math.max(0, data.getInt(AGE_TAG)) : 0;
        state.updateTickets(missile);
        synchronized (GUIDED) {
            GUIDED.put(missile, state);
        }
        data.putBoolean("CreateFireControlAaGuided", true);
        if (controller != null) {
            data.putLong(CONTROLLER_POS_TAG, controller.getBlockPos().asLong());
            saveControllerOwner(missile, controller);
            FireControlMissileOwnership.markController(missile, controller);
        }
        data.putUUID(TARGET_TAG, target);
        data.putDouble(FUSE_TAG, state.proximityFuseRange);
        data.putDouble(INITIAL_SPEED_TAG, state.speedProfile.initialSpeed());
    }

    public static boolean hasAnyGuidedMissile() {
        synchronized (GUIDED) {
            return !GUIDED.isEmpty();
        }
    }

    public static List<RwrThreat> collectRwrMissileThreats(
            net.minecraft.world.level.Level receiverLevel,
            Set<UUID> receiverStructure,
            Vec3 receiverOrigin,
            double range
    ) {
        if (receiverLevel == null || receiverStructure == null
                || receiverStructure.isEmpty()) return List.of();
        double rangeSqr = range * range;
        List<RwrThreat> result = new ArrayList<>();
        synchronized (GUIDED) {
            for (Map.Entry<Entity, GuidanceState> entry : GUIDED.entrySet()) {
                Entity missile = entry.getKey();
                GuidanceState state = entry.getValue();
                if (missile == null || missile.isRemoved() || !missile.isAlive()
                        || state.countermeasureDecoyed
                        || missile.level().getServer() != receiverLevel.getServer()
                        || !missile.level().dimension().equals(receiverLevel.dimension())
                        || !state.targetsAny(receiverStructure)) continue;
                Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
                if (position.distanceToSqr(receiverOrigin) > rangeSqr) continue;
                result.add(new RwrThreat(
                        missile.getUUID(), RwrThreat.Type.MSL, position, true));
            }
        }
        return List.copyOf(result);
    }

    public static boolean hasGuidedMissileNear(ServerLevel level, Vec3 position) {
        synchronized (GUIDED) {
            for (Entity missile : GUIDED.keySet()) {
                if (missile == null) continue;
                ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
                if (root == level && (missile.position().distanceToSqr(position) < 0.25
                        || MianbaoMissileCompat.getWorldPosition(missile).distanceToSqr(position) < 0.25)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Applies the flare rule only to the vehicle-launched high-speed AA missile. */
    public static int tryCountermeasureDecoy(
            MianbaoCountermeasureManager.TrackedFlare flare
    ) {
        if (flare == null || flare.entity() == null || flare.carrierIds().isEmpty()) return 0;
        Set<UUID> carrierIds = flare.carrierIds();
        Entity countermeasure = flare.entity();
        int decoyed = 0;
        synchronized (GUIDED) {
            for (Map.Entry<Entity, GuidanceState> entry : GUIDED.entrySet()) {
                Entity missile = entry.getKey();
                GuidanceState state = entry.getValue();
                if (missile == null || missile.isRemoved() || !missile.isAlive()
                        || state.countermeasureDecoyed
                        || !MianbaoAirDefenseCompat.isHighSpeedMissile(missile)
                        || !state.targetsAny(carrierIds)) {
                    continue;
                }
                if (!state.countermeasureAttempts.add(countermeasure.getUUID())) continue;
                double approachMismatch =
                        MianbaoCountermeasureManager.approachSpeedMismatchMetersPerSecond(
                                MianbaoMissileCompat.getWorldPosition(missile),
                                MianbaoMissileCompat.getWorldVelocity(missile),
                                state.targetPositionAt(missile.level().getGameTime()),
                                state.lastTargetVelocity);
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

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        recoverPending();
        synchronized (GUIDED) {
            Iterator<Map.Entry<Entity, GuidanceState>> iterator = GUIDED.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Entity, GuidanceState> entry = iterator.next();
                Entity missile = entry.getKey();
                GuidanceState state = entry.getValue();
                if (missile == null || missile.isRemoved() || !missile.isAlive()) {
                    state.releaseTickets();
                    iterator.remove();
                    continue;
                }
                MianbaoMissileCompat.advanceIfNativeTickMissed(missile);
                if (missile.isRemoved()) continue;
                state.updateTickets(missile);
                if (missile.getPersistentData().getDouble("fadongji1") > 100.0) {
                    missile.getPersistentData().putDouble("fadongji1", 100.0);
                }
                if (++state.ageTicks >= MianbaoAirDefenseCompat.getLifetimeTicks(missile)) {
                    selfDestruct(missile);
                    state.releaseTickets();
                    iterator.remove();
                    continue;
                }
                missile.getPersistentData().putInt(AGE_TAG, state.ageTicks);
                Vec3 fusePosition = MianbaoMissileCompat.getWorldPosition(missile);
                if (state.ageTicks >= MianbaoAirDefenseCompat.guidanceDelayTicks()) {
                    ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
                    SableMissileFuze.Contact contact = SableMissileFuze.firstBlockContact(
                            root, state.previousPosition, fusePosition);
                    if (contact != null) {
                        selfDestruct(missile, contact.position());
                        state.releaseTickets();
                        iterator.remove();
                        continue;
                    }
                    // Range zero still disables only the proximity channel;
                    // the exact per-block contact channel remains armed.
                    if (!state.countermeasureDecoyed
                            && state.proximityFuseRange > 0.0
                            && SableMissileFuze.anyCenterOfMassWithinRange(
                            root, state.previousPosition, fusePosition,
                            state.proximityFuseRange)) {
                        selfDestruct(missile, fusePosition);
                        state.releaseTickets();
                        iterator.remove();
                        continue;
                    }
                }
                state.previousPosition = fusePosition;
                if (state.countermeasureDecoyed && state.ageTicks > MianbaoAirDefenseCompat.guidanceDelayTicks()) {
                    missile.setNoGravity(true);
                    MianbaoMissileCompat.setWorldVelocity(
                            missile, state.guideCountermeasure(
                                    missile,
                                    state.countermeasureDirection.scale(state.speedAtAge())));
                    continue;
                }
                if (state.ageTicks <= MianbaoAirDefenseCompat.guidanceDelayTicks()) {
                    missile.setNoGravity(true);
                    MianbaoMissileCompat.setWorldVelocity(
                            missile, state.initialDirection.scale(state.speedAtAge()));
                    continue;
                }
                state.refreshTarget();
                if (state.lastTargetCenter == null) continue;
                Vec3 missilePosition = MianbaoMissileCompat.getWorldPosition(missile);
                double speed = state.speedAtAge();
                // Re-solving after movement/turning accounts for the actual
                // acquisition path. Once aligned, constant target velocity and
                // this same speed profile produce the same collision heading.
                state.flightDirection = AcceleratingMissileIntercept.guide(
                        missilePosition, state.flightDirection,
                        state.targetPositionAt(missile.level().getGameTime()),
                        state.lastTargetVelocity, state.speedProfile, state.ageTicks,
                        MianbaoAirDefenseCompat.getLifetimeTicks(missile) - state.ageTicks,
                        MianbaoAirDefenseCompat.maxTurnRadians(speed));
                missile.setNoGravity(true);
                MianbaoMissileCompat.setWorldVelocity(missile, state.flightDirection.scale(speed));
            }
        }
    }

    private static double launchSpeed(Entity missile) {
        var data = missile.getPersistentData();
        double saved = data.getDouble(INITIAL_SPEED_TAG);
        return data.contains(INITIAL_SPEED_TAG) && Double.isFinite(saved) && saved > 0.0
                ? saved : MianbaoMissileCompat.getWorldVelocity(missile).length();
    }

    private static void selfDestruct(Entity missile) {
        selfDestruct(missile, MianbaoMissileCompat.getWorldPosition(missile));
    }

    private static void selfDestruct(Entity missile, Vec3 position) {
        MianbaoMissileDetonation.detonate(missile, position);
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
                missile.getPersistentData().putInt(AGE_TAG, ++recovery.ageTicks);
                if (recovery.ageTicks >= MianbaoAirDefenseCompat.getLifetimeTicks(missile)) {
                    selfDestruct(missile);
                    recovery.releaseTickets();
                    iterator.remove();
                    continue;
                }
                StabilizerControllerBlockEntity controller =
                        ControllerTracker.findLoadedForMissile(
                                missile, recovery.controllerPos,
                                recovery.subLevelId, recovery.exactSubLevel);
                if (controller == null) continue;
                GuidanceState state = new GuidanceState(
                        missile,
                        controller, recovery.targetSubLevelId,
                        MianbaoMissileCompat.getWorldVelocity(missile),
                        launchSpeed(missile),
                        recovery.proximityFuseRange,
                        MianbaoMissileCompat.getWorldPosition(missile));
                state.ageTicks = recovery.ageTicks;
                missile.getPersistentData().putDouble(INITIAL_SPEED_TAG, state.speedProfile.initialSpeed());
                state.updateTickets(missile);
                recovery.releaseTickets();
                synchronized (GUIDED) { GUIDED.put(missile, state); }
                FireControlMissileOwnership.markController(missile, controller);
                iterator.remove();
            }
        }
    }

    private static final class PendingRecovery {
        final BlockPos controllerPos;
        final UUID subLevelId;
        final boolean exactSubLevel;
        final UUID targetSubLevelId;
        final double proximityFuseRange;
        final MissileChunkTickets chunkTickets = new MissileChunkTickets(MISSILE_TICKET);
        int ageTicks;

        PendingRecovery(BlockPos controllerPos, UUID subLevelId,
                        boolean exactSubLevel, UUID targetSubLevelId,
                        int ageTicks, double proximityFuseRange) {
            this.controllerPos = controllerPos;
            this.subLevelId = subLevelId;
            this.exactSubLevel = exactSubLevel;
            this.targetSubLevelId = targetSubLevelId;
            this.ageTicks = ageTicks;
            this.proximityFuseRange = proximityFuseRange;
        }

        void updateTickets(Entity missile) { chunkTickets.update(missile, null); }
        void releaseTickets() { chunkTickets.release(); }
    }

    private static final class GuidanceState {
        private final Entity missile;
        private final ActiveRadarSeeker seeker;
        private final StabilizerControllerBlockEntity controller;
        private final UUID targetSubLevelId;
        private final MissileChunkTickets chunkTickets = new MissileChunkTickets(MISSILE_TICKET);
        private int ageTicks;
        private Vec3 lastTargetCenter;
        private Vec3 lastTargetVelocity = Vec3.ZERO;
        private long lastTargetSampleTick = Long.MIN_VALUE;
        private final Vec3 initialDirection;
        private Vec3 flightDirection;
        private final AcceleratingMissileIntercept.SpeedProfile speedProfile;
        private final double proximityFuseRange;
        private Vec3 previousPosition;
        private Set<UUID> targetSubLevelIds = Set.of();
        private Set<UUID> decoyedTargetIds = Set.of();
        private Vec3 countermeasureDirection = Vec3.ZERO;
        private boolean countermeasureDecoyed;
        private final Set<UUID> countermeasureAttempts = new HashSet<>();
        private Entity countermeasureTarget;
        private boolean countermeasureMissed;

        private GuidanceState(
                Entity missile,
                StabilizerControllerBlockEntity controller,
                UUID targetSubLevelId,
                Vec3 initialVelocity,
                double launchSpeed,
                double proximityFuseRange,
                Vec3 initialPosition
        ) {
            this.missile = missile;
            this.seeker = new ActiveRadarSeeker(targetSubLevelId, ActiveRadarSeeker.AimPoint.BOUNDS, missile.getPersistentData());
            this.controller = controller;
            this.targetSubLevelId = targetSubLevelId;
            this.speedProfile = MianbaoAirDefenseCompat.speedProfile(missile, launchSpeed);
            this.proximityFuseRange = Math.max(0.0, Math.min(10.0, proximityFuseRange));
            this.previousPosition = initialPosition;
            this.initialDirection = initialVelocity.lengthSqr() < 1.0e-8
                    ? new Vec3(0.0, 0.0, 1.0) : initialVelocity.normalize();
            this.flightDirection = initialDirection;
            refreshTarget();
        }

        private void updateTickets(Entity missile) {
            chunkTickets.update(missile, null);
        }

        private double speedAtAge() {
            return speedProfile.speedAt(ageTicks);
        }

        private void releaseTickets() {
            chunkTickets.release();
        }

        private boolean targetsAny(Set<UUID> carrierIds) {
            refreshTarget();
            if (lastTargetCenter == null) return false;
            for (UUID id : carrierIds) {
                if (targetSubLevelIds.contains(id)) return true;
            }
            return false;
        }

        private void refreshTarget() {
            if (countermeasureDecoyed) return;
            if (seeker.memory() == null && controller != null && !controller.isRemoved()) {
                var cue = controller.getRadarContact(targetSubLevelId);
                if (cue != null) seeker.seed(cue.center(), cue.velocity(), cue.bounds(), cue.memberIds(),
                        controller.getRadarContactSampleTick() == Long.MIN_VALUE
                                ? missile.level().getGameTime() : controller.getRadarContactSampleTick());
            }
            seeker.update(missile);
            var track = seeker.memory();
            if (track != null) {
                lastTargetCenter = track.center(); lastTargetVelocity = track.velocity();
                lastTargetSampleTick = track.tick(); targetSubLevelIds = track.members();
            }
        }

        private Vec3 targetPositionAt(long gameTime) {
            return lastTargetCenter == null ? null : AcceleratingMissileIntercept.targetPositionAt(
                    lastTargetCenter, lastTargetVelocity, lastTargetSampleTick, gameTime);
        }

        private void applyCountermeasureDecoy(Entity missile, Entity countermeasure) {
            Vec3 missilePosition = MianbaoMissileCompat.getWorldPosition(missile);
            Vec3 currentVelocity = MianbaoMissileCompat.getWorldVelocity(missile);
            Vec3 flarePosition = MianbaoMissileCompat.getWorldPosition(countermeasure);
            Vec3 toFlare = flarePosition.subtract(missilePosition);
            boolean canTurnTowardFlare = toFlare.lengthSqr() >= 1.0e-8
                    && currentVelocity.dot(toFlare.normalize()) > 0.0;
            // Leave the actual turn to the ordinary post-tick guidance budget.
            Vec3 diverted = flightDirection.scale(speedAtAge());
            decoyedTargetIds = Set.copyOf(targetSubLevelIds);
            countermeasureDecoyed = true;
            countermeasureTarget = countermeasure;
            countermeasureMissed = !canTurnTowardFlare;
            countermeasureDirection = diverted.normalize();
            previousPosition = missilePosition;
            missile.getPersistentData().putBoolean(
                    "CreateFireControlCountermeasureDecoyed", true);
            missile.getPersistentData().putInt(
                    "CreateFireControlCountermeasureGraceUntil", missile.tickCount + 5);
            missile.setNoGravity(true);
            MianbaoMissileCompat.setWorldVelocity(missile, diverted);
        }

        private Vec3 guideCountermeasure(Entity missile, Vec3 velocity) {
            if (countermeasureMissed || countermeasureTarget == null
                    || countermeasureTarget.isRemoved() || !countermeasureTarget.isAlive()) {
                countermeasureMissed = true;
                return velocity;
            }
            Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
            Vec3 toFlare = MianbaoMissileCompat.getWorldPosition(countermeasureTarget)
                    .subtract(position);
            if (toFlare.lengthSqr() < 1.0e-8
                    || velocity.dot(toFlare.normalize()) <= 0.0) {
                countermeasureMissed = true;
                return velocity;
            }
            countermeasureDirection = AcceleratingMissileIntercept.acquire(velocity, toFlare,
                    toFlare.length() / Math.max(0.1, velocity.length()),
                    MianbaoAirDefenseCompat.maxTurnRadians(velocity.length()));
            return countermeasureDirection
                    .scale(Math.max(0.1, velocity.length()));
        }

        private boolean ignoresCountermeasureContact(UUID subLevelId) {
            return countermeasureDecoyed
                    && subLevelId != null
                    && decoyedTargetIds.contains(subLevelId);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (RECOVERING) {
            for (PendingRecovery state : RECOVERING.values()) state.releaseTickets();
            RECOVERING.clear();
        }
        synchronized (GUIDED) {
            for (GuidanceState state : GUIDED.values()) state.releaseTickets();
            GUIDED.clear();
        }
    }

    private MianbaoAirDefenseGuidanceHandler() {
    }
}
