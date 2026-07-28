package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoAirDefenseGuidanceHandler {
    private static final double NAVIGATION_CONSTANT = 5.0;
    private static final double MAX_STEER_RADIANS = Math.toRadians(13.0);
    private static final double MAX_SPEED_BLOCKS_PER_TICK = 34.03;
    private static final int ACCELERATION_TICKS = 10 * 20;
    private static final TicketType<UUID> MISSILE_TICKET = TicketType.<UUID>create(
            "create_fire_control_air_defense_missile", UUID::compareTo);
    private static final Map<Entity, GuidanceState> GUIDED = new IdentityHashMap<>();

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !MianbaoAirDefenseCompat.isMissile(event.getEntity())) {
            return;
        }
        StabilizerControllerBlockEntity controller =
                ControllerTracker.findForMianbaoAaMissile(event.getEntity());
        if (controller == null || controller.getRadarLockedSubLevelId() == null) {
            return;
        }
        GuidanceState state = new GuidanceState(
                controller,
                controller.getRadarLockedSubLevelId(),
                MianbaoMissileCompat.getWorldVelocity(event.getEntity()),
                controller.getAaProximityFuseRange());
        state.updateTickets(event.getEntity());
        synchronized (GUIDED) {
            GUIDED.put(event.getEntity(), state);
        }
        event.getEntity().getPersistentData().putBoolean("CreateFireControlAaGuided", true);
    }

    public static boolean hasAnyGuidedMissile() {
        synchronized (GUIDED) {
            return !GUIDED.isEmpty();
        }
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

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
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
                Vec3 fusePosition = MianbaoMissileCompat.getWorldPosition(missile);
                // Both the contact fuse and this Sable-only proximity fuse are
                // armed at exactly 0.5 seconds (ten ticks). Guidance still
                // waits until the following tick, so the complete first half
                // second remains a straight launch-clearance phase.
                if (state.ageTicks >= 10 && state.proximityFuseRange > 0.0
                        && hasSableSubLevelWithinRange(
                        MianbaoMissileCompat.getRootServerLevel(missile), fusePosition,
                        state.proximityFuseRange)) {
                    selfDestruct(missile);
                    state.releaseTickets();
                    iterator.remove();
                    continue;
                }
                if (state.ageTicks <= 10) {
                    missile.setNoGravity(true);
                    MianbaoMissileCompat.setWorldVelocity(
                            missile, state.initialDirection.scale(state.speedAtAge()));
                    continue;
                }
                StabilizerControllerBlockEntity.RadarContact contact =
                        state.controller == null || state.controller.isRemoved()
                                ? null : state.controller.getRadarContact(state.targetSubLevelId);
                if (contact != null) {
                    state.lastTargetCenter = contact.center();
                    state.lastTargetVelocity = contact.velocity();
                    state.lastTargetBounds = contact.bounds();
                } else if (state.lastTargetCenter == null) {
                    continue;
                }
                Vec3 missilePosition = MianbaoMissileCompat.getWorldPosition(missile);
                Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
                double speed = state.speedAtAge();
                Vec3 currentDirection = velocity.lengthSqr() < 1.0e-8
                        ? state.lastTargetCenter.subtract(missilePosition).normalize()
                        : velocity.normalize();

                Vec3 relativePosition = state.lastTargetCenter.subtract(missilePosition);
                double distanceSqr = relativePosition.lengthSqr();
                if (distanceSqr < 1.0e-8) continue;
                Vec3 relativeVelocity = state.lastTargetVelocity.subtract(velocity);
                Vec3 losRate = relativePosition.cross(relativeVelocity).scale(1.0 / distanceSqr);
                double closingSpeed = Math.max(0.0,
                        -relativeVelocity.dot(relativePosition.normalize()));
                Vec3 normalAcceleration = losRate.cross(currentDirection)
                        .scale(NAVIGATION_CONSTANT * Math.max(1.0, closingSpeed));

                Vec3 desiredVelocity = currentDirection.scale(speed).add(normalAcceleration);
                Vec3 desiredDirection = desiredVelocity.lengthSqr() < 1.0e-8
                        ? currentDirection : desiredVelocity.normalize();
                Vec3 guided = rotateTowards(currentDirection, desiredDirection, MAX_STEER_RADIANS)
                        .scale(speed);
                missile.setNoGravity(true);
                MianbaoMissileCompat.setWorldVelocity(missile, guided);
            }
        }
    }

    private static boolean hasSableSubLevelWithinRange(
            ServerLevel level, Vec3 position, double range
    ) {
        if (level == null || range <= 0.0) return false;
        BoundingBox3d query = new BoundingBox3d(
                position.x - range, position.y - range, position.z - range,
                position.x + range, position.y + range, position.z + range);
        for (SubLevel subLevel : SubLevelContainer.getContainer(level).queryIntersecting(query)) {
            if (subLevel == null || subLevel.isRemoved()) continue;
            AABB bounds = StabilizerControllerBlockEntity.subLevelWorldBounds(subLevel);
            if (distanceToBoundsSqr(position, bounds) <= range * range) return true;
        }
        return false;
    }

    private static double distanceToBoundsSqr(Vec3 point, AABB bounds) {
        double x = Math.max(bounds.minX, Math.min(bounds.maxX, point.x));
        double y = Math.max(bounds.minY, Math.min(bounds.maxY, point.y));
        double z = Math.max(bounds.minZ, Math.min(bounds.maxZ, point.z));
        return point.distanceToSqr(x, y, z);
    }

    private static Vec3 rotateTowards(Vec3 current, Vec3 desired, double maxRadians) {
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, current.dot(desired))));
        if (angle <= maxRadians || angle < 1.0e-6) return desired;
        double blend = maxRadians / angle;
        Vec3 result = current.scale(1.0 - blend).add(desired.scale(blend));
        return result.lengthSqr() < 1.0e-8 ? current : result.normalize();
    }

    private static void selfDestruct(Entity missile) {
        MianbaoMissileEffects.broadcastExplosion(missile, 3.0F, false);
        ServerLevel level = MianbaoMissileCompat.getRootServerLevel(missile);
        if (level != null) {
            Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
            if (MianbaoMissileCompat.areRootChunksLoaded(missile, position, 3.0)) {
                level.explode(null, position.x, position.y, position.z, 3.0F,
                        Level.ExplosionInteraction.BLOCK);
            }
        }
        missile.discard();
    }

    private static final class GuidanceState {
        private final StabilizerControllerBlockEntity controller;
        private final UUID targetSubLevelId;
        private final MissileChunkTickets chunkTickets = new MissileChunkTickets(MISSILE_TICKET);
        private int ageTicks;
        private Vec3 lastTargetCenter;
        private Vec3 lastTargetVelocity = Vec3.ZERO;
        private AABB lastTargetBounds;
        private final Vec3 initialDirection;
        private final double initialSpeed;
        private final double engineAcceleration;
        private final double proximityFuseRange;

        private GuidanceState(
                StabilizerControllerBlockEntity controller,
                UUID targetSubLevelId,
                Vec3 initialVelocity,
                double proximityFuseRange
        ) {
            this.controller = controller;
            this.targetSubLevelId = targetSubLevelId;
            this.initialSpeed = Math.min(MAX_SPEED_BLOCKS_PER_TICK,
                    Math.max(0.1, initialVelocity.length()));
            this.engineAcceleration = Math.max(0.0,
                    (MAX_SPEED_BLOCKS_PER_TICK - this.initialSpeed) / ACCELERATION_TICKS);
            this.proximityFuseRange = Math.max(0.0, Math.min(10.0, proximityFuseRange));
            this.initialDirection = initialVelocity.lengthSqr() < 1.0e-8
                    ? new Vec3(0.0, 0.0, 1.0) : initialVelocity.normalize();
            StabilizerControllerBlockEntity.RadarContact contact = controller.getRadarContact(targetSubLevelId);
            if (contact != null) {
                lastTargetCenter = contact.center();
                lastTargetVelocity = contact.velocity();
                lastTargetBounds = contact.bounds();
            }
        }

        private void updateTickets(Entity missile) {
            chunkTickets.update(missile, null);
        }

        private double speedAtAge() {
            return Math.min(MAX_SPEED_BLOCKS_PER_TICK,
                    initialSpeed + engineAcceleration * ageTicks);
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

    private MianbaoAirDefenseGuidanceHandler() {
    }
}
