package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.config.MissileSettings;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.iff.FireControlMissileOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Takes over only missiles spawned at a bound Mianbao launcher muzzle.
 * Redstone remains the sole fire input; this handler observes the actual
 * spawned missile and changes only its flight direction after native ticking.
 */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoMissileGuidanceHandler {
    private static final int MAX_LIFETIME_TICKS = 20 * 20;
    private static final TicketType<UUID> MISSILE_TICKET = TicketType.<UUID>create(
            "create_fire_control_guided_missile", UUID::compareTo);
    private static final Map<Entity, GuidanceState> GUIDED = new IdentityHashMap<>();
    private static final Map<Entity, PendingRecovery> RECOVERING = new IdentityHashMap<>();
    private static final String CONTROLLER_POS_TAG = "CreateFireControlGuidedController";
    private static final String CONTROLLER_SUBLEVEL_TAG = "CreateFireControlGuidedControllerSubLevel";
    private static final String CONTROLLER_SUBLEVEL_KNOWN_TAG =
            "CreateFireControlGuidedControllerSubLevelKnown";
    private static final String AGE_TAG = "CreateFireControlGuidedAge";

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !MianbaoMissileCompat.isGuidedMissile(event.getEntity())) {
            return;
        }
        Entity missile = event.getEntity();
        var data = missile.getPersistentData();
        boolean restoring = data.getBoolean("CreateFireControlGuided")
                && data.contains(CONTROLLER_POS_TAG);
        StabilizerControllerBlockEntity controller = null;
        if (restoring) {
            boolean exact = data.getBoolean(CONTROLLER_SUBLEVEL_KNOWN_TAG);
            UUID subLevelId = data.hasUUID(CONTROLLER_SUBLEVEL_TAG)
                    ? data.getUUID(CONTROLLER_SUBLEVEL_TAG) : null;
            controller = ControllerTracker.findLoadedForMissile(
                    missile, BlockPos.of(data.getLong(CONTROLLER_POS_TAG)),
                    subLevelId, exact);
            if (controller == null) {
                PendingRecovery recovery = new PendingRecovery(
                        BlockPos.of(data.getLong(CONTROLLER_POS_TAG)), subLevelId,
                        exact, Math.max(0, data.getInt(AGE_TAG)));
                recovery.updateTickets(missile);
                synchronized (RECOVERING) { RECOVERING.put(missile, recovery); }
                return;
            }
        }
        if (controller == null) controller = ControllerTracker.findForMianbaoMissile(missile);
        if (controller == null) {
            return;
        }
        GuidanceState state = new GuidanceState(controller);
        state.ageTicks = Math.max(0, missile.getPersistentData().getInt(AGE_TAG));
        state.updateTickets(missile);
        synchronized (GUIDED) {
            GUIDED.put(missile, state);
        }
        missile.getPersistentData().putBoolean("CreateFireControlGuided", true);
        missile.getPersistentData().putLong(CONTROLLER_POS_TAG,
                controller.getBlockPos().asLong());
        saveControllerOwner(missile, controller);
        FireControlMissileOwnership.markController(missile, controller);
        StabilizedTurret.LOGGER.debug(
                "Attached Mianbao missile {} to fire-control computer at {}",
                event.getEntity().getUUID(),
                controller.getBlockPos()
        );
    }

    /**
     * Explicitly adopts a missile after a third-party launcher has completed
     * its native spawn routine. Used by the heavy station because its native
     * guidance registration happens after EntityJoinLevelEvent.
     */
    public static boolean adopt(
            Entity missile, StabilizerControllerBlockEntity controller
    ) {
        if (missile == null || controller == null || missile.level().isClientSide()
                || !MianbaoMissileCompat.isGuidedMissile(missile)) return false;

        PendingRecovery recovery;
        synchronized (RECOVERING) {
            recovery = RECOVERING.remove(missile);
        }
        if (recovery != null) recovery.releaseTickets();

        GuidanceState state = new GuidanceState(controller);
        state.ageTicks = Math.max(0, missile.getPersistentData().getInt(AGE_TAG));
        state.updateTickets(missile);
        synchronized (GUIDED) {
            GuidanceState previous = GUIDED.put(missile, state);
            if (previous != null) previous.releaseTickets();
        }

        var data = missile.getPersistentData();
        data.putBoolean("CreateFireControlGuided", true);
        data.putLong(CONTROLLER_POS_TAG, controller.getBlockPos().asLong());
        saveControllerOwner(missile, controller);
        FireControlMissileOwnership.markController(missile, controller);
        StabilizedTurret.LOGGER.debug(
                "Explicitly adopted Mianbao missile {} for fire-control computer at {}",
                missile.getUUID(), controller.getBlockPos());
        return true;
    }

    /**
     * Mianbao issues persistent forceload commands from the missile position.
     * Fire-control missiles use temporary region tickets instead, so those
     * native commands must be suppressed without touching other projectiles.
     */
    public static boolean hasAnyGuidedMissile() {
        synchronized (GUIDED) {
            return !GUIDED.isEmpty();
        }
    }

    public static boolean hasGuidedMissileNear(ServerLevel level, Vec3 position) {
        synchronized (GUIDED) {
            for (Entity missile : GUIDED.keySet()) {
                if (missile == null) {
                    continue;
                }
                ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
                if (root == level && (missile.position().distanceToSqr(position) < 0.25
                        || MianbaoMissileCompat.getWorldPosition(missile).distanceToSqr(position) < 0.25)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Runs after entity ticks, so Mianbao keeps ownership of acceleration,
     * lifetime, particles and impact/explosion behavior. Only the final
     * velocity direction for the next physics step is replaced.
     */
    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        recoverPending();
        if (GUIDED.isEmpty()) return;
        Map<StabilizerControllerBlockEntity, AimBeam> beamCache = new IdentityHashMap<>();
        synchronized (GUIDED) {
            Iterator<Map.Entry<Entity, GuidanceState>> iterator = GUIDED.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Entity, GuidanceState> entry = iterator.next();
                Entity missile = entry.getKey();
                GuidanceState state = entry.getValue();
                StabilizerControllerBlockEntity controller = state.controller;
                if (missile == null
                        || missile.isRemoved()
                        || !missile.isAlive()) {
                    state.releaseTickets();
                    iterator.remove();
                    continue;
                }

                MianbaoMissileCompat.advanceIfNativeTickMissed(missile);
                state.updateTickets(missile);
                // Prevent Mianbao's 150/200-tick native timeout from deleting
                // an adopted missile or issuing a global forceload removal.
                double nativeAge = missile.getPersistentData().getDouble("fadongji1");
                if (nativeAge > 100.0) {
                    missile.getPersistentData().putDouble("fadongji1", 100.0);
                }
                if (++state.ageTicks >= MissileSettings.lifetimeTicks(missile)) {
                    selfDestruct(missile);
                    state.releaseTickets();
                    iterator.remove();
                    continue;
                }
                missile.getPersistentData().putInt(AGE_TAG, state.ageTicks);

                // A missile already launched remains loaded and expires safely
                // even if its computer/reference is subsequently destroyed.
                if (controller == null
                        || controller.isRemoved()
                        || !controller.isMianbaoMissileGuidanceReady()) {
                    continue;
                }

                AimBeam beam;
                if (beamCache.containsKey(controller)) {
                    beam = beamCache.get(controller);
                } else {
                    Vec3 origin = controller.getMeasuredAimOrigin();
                    Vec3 direction = controller.getMeasuredAimDirection();
                    beam = origin == null || direction == null || direction.lengthSqr() < 1.0e-8
                            ? null
                            : new AimBeam(origin, direction.normalize());
                    beamCache.put(controller, beam);
                }
                if (beam == null) {
                    continue;
                }

                // The first two ticks retain native launch clearance. Do not consume
                // a configured speed multiplier until a velocity command is applied.
                if (state.ageTicks < 3) continue;
                Vec3 missileWorldPosition = MianbaoMissileCompat.getWorldPosition(missile);
                Vec3 currentVelocity = MianbaoMissileCompat.getWorldVelocity(missile);
                // Apply multiplier changes once, rather than compounding them every tick.
                double multiplier = MissileSettings.speedScale(missile);
                double oldMultiplier = missile.getPersistentData().contains("CreateFireControlSpeedScale")
                        ? missile.getPersistentData().getDouble("CreateFireControlSpeedScale") : 1.0;
                currentVelocity = currentVelocity.scale(multiplier / Math.max(.01, oldMultiplier));
                missile.getPersistentData().putDouble("CreateFireControlSpeedScale", multiplier);
                double speed = currentVelocity.length();
                if (speed < 1.0e-4) {
                    speed = 1.0;
                }

                Vec3 guidedVelocity = BeamRidingGuidance.guide(missileWorldPosition, currentVelocity,
                        beam.origin(), beam.direction(), speed);
                missile.setNoGravity(true);
                MianbaoMissileCompat.setWorldVelocity(missile, guidedVelocity);
            }
        }
    }

    private static void selfDestruct(Entity missile) {
        MianbaoMissileEffects.broadcastExplosion(missile, 2.0F, false);
        ServerLevel serverLevel = MianbaoMissileCompat.getRootServerLevel(missile);
        if (serverLevel != null) {
            Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
            if (MianbaoMissileCompat.areRootChunksLoaded(missile, position, 2.0)) {
                serverLevel.explode(
                        null,
                        position.x,
                        position.y,
                        position.z,
                        2.0F,
                        Level.ExplosionInteraction.BLOCK
                );
            }
        }
        missile.discard();
    }

    private record AimBeam(Vec3 origin, Vec3 direction) {
    }

    private static void saveControllerOwner(
            Entity missile, StabilizerControllerBlockEntity controller
    ) {
        UUID subLevelId = ControllerTracker.controllerSubLevelId(controller);
        missile.getPersistentData().putBoolean(CONTROLLER_SUBLEVEL_KNOWN_TAG, true);
        if (subLevelId != null) {
            missile.getPersistentData().putUUID(CONTROLLER_SUBLEVEL_TAG, subLevelId);
        } else {
            missile.getPersistentData().remove(CONTROLLER_SUBLEVEL_TAG);
        }
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
                if (recovery.ageTicks >= MissileSettings.lifetimeTicks(missile)) {
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
                GuidanceState state = new GuidanceState(controller);
                state.ageTicks = recovery.ageTicks;
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
        final MissileChunkTickets chunkTickets = new MissileChunkTickets(MISSILE_TICKET);
        int ageTicks;

        PendingRecovery(BlockPos controllerPos, UUID subLevelId,
                        boolean exactSubLevel, int ageTicks) {
            this.controllerPos = controllerPos;
            this.subLevelId = subLevelId;
            this.exactSubLevel = exactSubLevel;
            this.ageTicks = ageTicks;
        }

        void updateTickets(Entity missile) { chunkTickets.update(missile, null); }
        void releaseTickets() { chunkTickets.release(); }
    }

    private static final class GuidanceState {
        private final StabilizerControllerBlockEntity controller;
        private final MissileChunkTickets chunkTickets = new MissileChunkTickets(MISSILE_TICKET);
        private int ageTicks;

        private GuidanceState(StabilizerControllerBlockEntity controller) {
            this.controller = controller;
        }

        private void updateTickets(Entity missile) {
            chunkTickets.update(missile, null);
        }

        private void releaseTickets() {
            chunkTickets.release();
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

    private MianbaoMissileGuidanceHandler() {
    }
}
