package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
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
    private static final double CROSS_TRACK_GAIN = 0.08;
    private static final double MAX_LATERAL_SPEED_RATIO = 0.22;
    private static final double MAX_STEER_RADIANS_PER_TICK = Math.toRadians(4.0);
    private static final TicketType<UUID> MISSILE_TICKET = TicketType.<UUID>create(
            "create_fire_control_guided_missile", UUID::compareTo);
    private static final Map<Entity, GuidanceState> GUIDED = new IdentityHashMap<>();

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !MianbaoMissileCompat.isGuidedMissile(event.getEntity())) {
            return;
        }
        StabilizerControllerBlockEntity controller =
                ControllerTracker.findForMianbaoMissile(event.getEntity());
        if (controller == null) {
            return;
        }
        GuidanceState state = new GuidanceState(controller);
        state.updateTickets(event.getEntity());
        synchronized (GUIDED) {
            GUIDED.put(event.getEntity(), state);
        }
        event.getEntity().getPersistentData().putBoolean("CreateFireControlGuided", true);
        StabilizedTurret.LOGGER.debug(
                "Attached Mianbao missile {} to fire-control computer at {}",
                event.getEntity().getUUID(),
                controller.getBlockPos()
        );
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
                if (++state.ageTicks >= MAX_LIFETIME_TICKS) {
                    selfDestruct(missile);
                    state.releaseTickets();
                    iterator.remove();
                    continue;
                }

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

                Vec3 missileWorldPosition = MianbaoMissileCompat.getWorldPosition(missile);
                Vec3 currentVelocity = MianbaoMissileCompat.getWorldVelocity(missile);
                double speed = currentVelocity.length();
                // Preserve the launcher's initial clearance and native launch
                // animation for two ticks before engaging guidance.
                if (state.ageTicks < 3) {
                    continue;
                }
                if (speed < 1.0e-4) {
                    speed = 1.0;
                }

                // Beam-riding guidance: forward motion always dominates. A
                // bounded lateral component closes cross-track error, while a
                // per-tick steering limit prevents a large error from causing
                // an instant side turn. No distant target point is pursued.
                double alongBeam = Math.max(
                        0.0,
                        missileWorldPosition.subtract(beam.origin()).dot(beam.direction())
                );
                Vec3 closestOnBeam = beam.origin().add(beam.direction().scale(alongBeam));
                Vec3 crossTrackCorrection = closestOnBeam.subtract(missileWorldPosition);
                double crossTrackDistance = crossTrackCorrection.length();
                double lateralSpeed = Math.min(
                        speed * MAX_LATERAL_SPEED_RATIO,
                        crossTrackDistance * CROSS_TRACK_GAIN
                );
                Vec3 desiredVelocity = beam.direction().scale(speed);
                if (crossTrackDistance > 1.0e-6) {
                    desiredVelocity = desiredVelocity.add(
                            crossTrackCorrection.scale(lateralSpeed / crossTrackDistance)
                    );
                }
                Vec3 desiredDirection = desiredVelocity.normalize();
                Vec3 currentDirection = currentVelocity.lengthSqr() < 1.0e-8
                        ? beam.direction()
                        : currentVelocity.normalize();
                Vec3 guidedVelocity = rotateTowards(
                        currentDirection,
                        desiredDirection,
                        MAX_STEER_RADIANS_PER_TICK
                ).scale(speed);
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

    private static Vec3 rotateTowards(Vec3 current, Vec3 desired, double maxRadians) {
        double dot = Math.max(-1.0, Math.min(1.0, current.dot(desired)));
        double angle = Math.acos(dot);
        if (angle <= maxRadians || angle < 1.0e-6) {
            return desired;
        }
        double blend = maxRadians / angle;
        Vec3 blended = current.scale(1.0 - blend).add(desired.scale(blend));
        return blended.lengthSqr() < 1.0e-8 ? current : blended.normalize();
    }

    private record AimBeam(Vec3 origin, Vec3 direction) {
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
        synchronized (GUIDED) {
            for (GuidanceState state : GUIDED.values()) state.releaseTickets();
            GUIDED.clear();
        }
    }

    private MianbaoMissileGuidanceHandler() {
    }
}
