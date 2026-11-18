package com.hooya.stabilizedturret.content.missile;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.missile.impact.CollisionDetector;
import com.hooya.stabilizedturret.content.missile.impact.ImpactAreaManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.Set;

/** Entity-free missile state. Midcourse ticks perform mathematical work only. */
public final class VirtualMissile {
    public enum Phase { LAUNCH, BOOST, MIDCOURSE, TERMINAL, IMPACT, DEAD }

    private final UUID id;
    private final ConnectedDisplayBlockEntity display;
    private final @Nullable ConnectedDisplayBlockEntity.TargetKey targetKey;
    private final @Nullable Vec3 fixedTarget;
    private final Vec3 launchPosition;
    private final double initialHorizontalDistance;
    private final double desiredApogee;
    private Vec3 position;
    private Vec3 previousPosition;
    private Vec3 velocity;
    private Vec3 direction;
    private Vec3 lastTarget;
    private Vec3 lastTargetVelocity = Vec3.ZERO;
    private Phase phase = Phase.LAUNCH;
    private double speed;
    private int age;

    VirtualMissile(UUID id, ConnectedDisplayBlockEntity display,
                   @Nullable ConnectedDisplayBlockEntity.TargetKey targetKey,
                   @Nullable Vec3 fixedTarget, Vec3 position, Vec3 velocity,
                   Vec3 direction, Vec3 initialTarget) {
        this.id = id;
        this.display = display;
        this.targetKey = targetKey;
        this.fixedTarget = fixedTarget;
        this.position = position;
        this.previousPosition = position;
        this.velocity = velocity;
        // Preserve the launcher's inherited carrier velocity when leaving the
        // native entity phase; subsequent turns are rate limited from it.
        this.direction = velocity.lengthSqr() >= 1.0e-8 ? velocity.normalize()
                : direction.lengthSqr() < 1.0e-8
                ? new Vec3(0.0, 1.0, 0.0) : direction.normalize();
        this.speed = Math.max(velocity.length(),
                MissilePhysicsConfig.speedPerTick(MissilePhysicsConfig.INITIAL_SPEED));
        this.launchPosition = position;
        this.lastTarget = initialTarget;
        this.initialHorizontalDistance = Math.max(1.0,
                horizontalDistance(position, initialTarget));
        this.desiredApogee = Mth.clamp(
                MissilePhysicsConfig.MIN_APOGEE.get()
                        + initialHorizontalDistance
                        * MissilePhysicsConfig.APOGEE_DISTANCE_FACTOR.get(),
                MissilePhysicsConfig.MIN_APOGEE.get(),
                MissilePhysicsConfig.MAX_APOGEE.get());
    }

    boolean tick(ServerLevel level, ImpactAreaManager impacts, FlareManager flares) {
        if (++age > MissileManager.MAX_LIFETIME_TICKS || display.isRemoved()) {
            phase = Phase.DEAD;
            return true;
        }
        updateTarget(level);
        Vec3 predictedTarget = predictTarget();
        double distance = position.distanceTo(predictedTarget);
        double prepareDistance = speed * 20.0
                * MissilePhysicsConfig.IMPACT_PREPARE_SECONDS.get();
        if (distance <= Math.max(MissilePhysicsConfig.TERMINAL_START_DISTANCE.get(),
                prepareDistance)) {
            phase = Phase.TERMINAL;
            impacts.prepare(id, predictedTarget, requiredImpactChunkRadius());
        } else if (age <= 8) {
            phase = Phase.LAUNCH;
        } else if (age <= 100) {
            phase = Phase.BOOST;
        } else {
            phase = Phase.MIDCOURSE;
        }

        updateSpeed();
        flares.maybeDispense(this);
        Vec3 desired = phase == Phase.LAUNCH ? direction : desiredDirection(predictedTarget);
        direction = rotateTowards(direction, desired, turnRateRadiansPerTick());
        velocity = direction.scale(speed);
        previousPosition = position;
        Vec3 next = position.add(velocity);

        if (phase == Phase.TERMINAL) {
            if (impacts.ready(id) && impacts.contains(id, next)) {
                Vec3 sweepStart = impacts.segmentStart(id, position, next);
                double proximityRange = display.getMissileProximityRange();
                Vec3 hit = CollisionDetector.sweep(level, sweepStart, next, lastTarget,
                        proximityRange, targetKey != null && proximityRange > 0.0);
                if (hit != null) {
                    position = hit;
                    phase = Phase.IMPACT;
                    return MissileManager.detonate(level, id, position);
                }
            } else if (impacts.contains(id, position) && !impacts.ready(id)) {
                velocity = Vec3.ZERO;
                return false;
            }
        }
        position = next;
        if (phase == Phase.TERMINAL && position.distanceToSqr(lastTarget) <= 1.0) {
            phase = Phase.IMPACT;
            return MissileManager.detonate(level, id, position);
        }
        return false;
    }

    private void updateTarget(ServerLevel root) {
        if (targetKey == null) {
            if (fixedTarget != null) lastTarget = resolveFixedTarget(root, fixedTarget);
            lastTargetVelocity = Vec3.ZERO;
            return;
        }
        ConnectedDisplayBlockEntity.Contact contact = display.getContact(targetKey);
        if (contact != null) {
            lastTarget = contact.center();
            lastTargetVelocity = contact.velocity();
            return;
        }
        // Match cruise guidance: retain the radar contact when available, then
        // fall back to a still-loaded living entity, otherwise use last known.
        if (targetKey.living()) {
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
            if (entity instanceof LivingEntity living && living.isAlive()) {
                lastTarget = MianbaoMissileCompat.getWorldPosition(living);
                lastTargetVelocity = MianbaoMissileCompat.getWorldVelocity(living);
            }
        }
    }

    boolean targetsAny(Set<UUID> structure) {
        if (targetKey == null) return false;
        if (structure.contains(targetKey.id())) return true;
        ConnectedDisplayBlockEntity.Contact contact = display.getContact(targetKey);
        return contact != null && !contact.missile()
                && contact.members().stream().anyMatch(structure::contains);
    }

    private static Vec3 resolveFixedTarget(ServerLevel root, Vec3 point) {
        int blockX = Mth.floor(point.x);
        int blockZ = Mth.floor(point.z);
        if (root.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4) == null) return point;
        int surface = root.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        return new Vec3(point.x, surface - 0.5, point.z);
    }

    private Vec3 predictTarget() {
        double ticks = position.distanceTo(lastTarget) / Math.max(0.01, speed);
        return lastTarget.add(lastTargetVelocity.scale(Math.min(200.0, ticks)));
    }

    private Vec3 desiredDirection(Vec3 target) {
        if (phase == Phase.TERMINAL) return normalized(target.subtract(position), direction);
        double remaining = horizontalDistance(position, target);
        double progress = Mth.clamp(1.0 - remaining / initialHorizontalDistance, 0.0, 1.0);
        double ahead = Math.min(1.0, progress + 0.04);
        double arcY = Mth.lerp(ahead, launchPosition.y, target.y)
                + Math.sin(Math.PI * ahead) * desiredApogee;
        Vec3 waypoint = new Vec3(Mth.lerp(ahead, launchPosition.x, target.x), arcY,
                Mth.lerp(ahead, launchPosition.z, target.z));
        return normalized(waypoint.subtract(position), direction);
    }

    private void updateSpeed() {
        if (phase == Phase.TERMINAL) {
            speed = Math.min(MissilePhysicsConfig.speedPerTick(
                            MissilePhysicsConfig.TERMINAL_MAX_SPEED),
                    speed + MissilePhysicsConfig.accelerationPerTickSquared(
                            MissilePhysicsConfig.TERMINAL_ACCELERATION));
        } else if (phase == Phase.BOOST || phase == Phase.MIDCOURSE) {
            speed = Math.min(MissilePhysicsConfig.speedPerTick(
                            MissilePhysicsConfig.CRUISE_SPEED),
                    speed + MissilePhysicsConfig.accelerationPerTickSquared(
                            MissilePhysicsConfig.BOOST_ACCELERATION));
        }
    }

    private double turnRateRadiansPerTick() {
        double degrees = switch (phase) {
            case LAUNCH, BOOST -> MissilePhysicsConfig.BOOST_TURN_RATE.get();
            case MIDCOURSE -> MissilePhysicsConfig.MIDCOURSE_TURN_RATE.get();
            case TERMINAL, IMPACT, DEAD -> MissilePhysicsConfig.TERMINAL_TURN_RATE.get();
        };
        return Math.toRadians(degrees / 20.0);
    }

    private int requiredImpactChunkRadius() {
        // Mianbao's 2x ballistic nuclear crater reaches 68 blocks horizontally.
        // Six chunks also covers a worst-case target lying at a chunk edge;
        // high-speed sweep begins at the prepared square boundary.
        return Math.min(ImpactAreaManager.MAX_RADIUS_CHUNKS,
                Math.max(MissilePhysicsConfig.IMPACT_CHUNK_RADIUS.get(), 6));
    }

    private static Vec3 rotateTowards(Vec3 current, Vec3 desired, double maxAngle) {
        current = normalized(current, desired);
        desired = normalized(desired, current);
        double angle = Math.acos(Mth.clamp(current.dot(desired), -1.0, 1.0));
        if (angle <= maxAngle || angle < 1.0e-8) return desired;
        double blend = maxAngle / angle;
        return normalized(current.scale(1.0 - blend).add(desired.scale(blend)), desired);
    }

    private static Vec3 normalized(Vec3 vector, Vec3 fallback) {
        return vector.lengthSqr() < 1.0e-12 ? fallback : vector.normalize();
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    public UUID id() { return id; }
    public Vec3 position() { return position; }
    public Vec3 previousPosition() { return previousPosition; }
    public Vec3 velocity() { return velocity; }
    public Vec3 direction() { return direction; }
    public Vec3 target() { return lastTarget; }
    public Phase phase() { return phase; }
    public int age() { return age; }
    ConnectedDisplayBlockEntity display() { return display; }
}
