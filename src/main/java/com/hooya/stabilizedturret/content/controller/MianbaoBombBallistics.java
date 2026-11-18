package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Mianbao 2026-10-18 BombFlightPhysics, including its post-move update order. */
public final class MianbaoBombBallistics {
    public static final int MAX_TICKS = 1200;
    public static final double GRAVITY = 9.81 / 400.0;
    public record Flight(WeaponType type, Vec3 origin, Vec3 velocity, boolean coordinates,
                         Vec3 target, List<Vec3> lasers) {
        public Flight { lasers = List.copyOf(lasers); }
    }

    public static double drag(WeaponType type) {
        return type == WeaponType.RETARDED_BOMB ? 0.02
                : type == WeaponType.SMALL_BOMB ? 0.0025
                : type == WeaponType.GLIDE_BOMB ? 0.0006 : 0.0008;
    }

    public static boolean guided(WeaponType type) {
        return type == WeaponType.LASER_BOMB || type == WeaponType.JDAM_BOMB
                || type == WeaponType.GLIDE_BOMB || type == WeaponType.EARTH_PENETRATOR_BOMB;
    }

    public static Vec3 step(Vec3 velocity, Vec3 requested, boolean guided, boolean glide, double drag, boolean water) {
        if (!finite(velocity)) return Vec3.ZERO;
        double speed = velocity.length();
        if (guided && speed > 1.0e-8 && finite(requested) && requested.lengthSqr() > 1.0e-16) {
            Vec3 direction = velocity.scale(1.0 / speed);
            Vec3 desired = requested.normalize();
            double cosine = Mth.clamp(direction.dot(desired), -1.0, 1.0);
            double angle = Math.acos(cosine);
            Vec3 tangent = desired.subtract(direction.scale(cosine));
            if (angle > 1.0e-8 && tangent.lengthSqr() > 1.0e-16) {
                double turn = Math.min(angle, Math.toRadians(glide ? 1.5 : 2.0));
                velocity = direction.scale(Math.cos(turn)).add(tangent.normalize().scale(Math.sin(turn))).scale(speed);
            }
        }
        double lift = glide && !water ? Math.min(0.8, velocity.horizontalDistanceSqr() * 0.05) : 0.0;
        velocity = velocity.add(0.0, -GRAVITY * (1.0 - lift), 0.0);
        double resistance = water ? Math.max(drag, 0.12) : drag;
        return velocity.scale(1.0 / (1.0 + resistance * velocity.length()));
    }

    /** Requested steering from the native trail, evaluated after Arrow has moved and applied 0.99 drag. */
    static Vec3 requested(Flight flight, Vec3 position, Vec3 velocity, int age) {
        return requested(flight, position, velocity, age, false);
    }

    public static Vec3 requested(Flight flight, Vec3 position, Vec3 velocity, int age, boolean water) {
        Vec3 requested = velocity.scale(water ? 0.6F : 0.99F);
        if (!guided(flight.type)) return requested;
        boolean glide = flight.type == WeaponType.GLIDE_BOMB;
        boolean earlyGuide = flight.type == WeaponType.LASER_BOMB || flight.type == WeaponType.JDAM_BOMB;
        if (age < (earlyGuide ? 20 : 21)) return requested;
        Vec3 target = null;
        if (flight.coordinates && flight.type != WeaponType.LASER_BOMB) target = flight.target;
        else if (earlyGuide) {
            double nearest = Double.POSITIVE_INFINITY;
            for (Vec3 laser : flight.lasers) {
                Vec3 offset = laser.subtract(position);
                if (Math.abs(offset.x) > 300 || Math.abs(offset.y) > 300 || Math.abs(offset.z) > 300) continue;
                double distance = offset.lengthSqr();
                if (distance < nearest) { target = laser; nearest = distance; }
            }
        }
        if (glide && !flight.coordinates) return requested.add(0, -0.01, 0);
        if (target == null || target.distanceToSqr(position) < 1.0e-16 || requested.lengthSqr() < 1.0e-16) return requested;
        Vec3 direction = target.subtract(position).normalize();
        double angle = Math.toDegrees(Math.acos(Mth.clamp(direction.dot(requested.normalize()), -1.0, 1.0)));
        if (angle > 90) return glide ? requested.add(0, -0.01, 0) : requested;
        if (glide) requested = requested.add(0, -0.001, 0);
        return requested.add(direction.scale(3.0).subtract(requested).scale(1.0 / Math.max(angle / (glide ? 3.0 : 1.5), 1.0)));
    }

    /** A null result means no known contact, never an invented ground plane. */
    public static @Nullable Vec3 trace(Flight flight, double minY, BiFunction<Vec3, Vec3, Vec3> collision) {
        return trace(flight, minY, collision, ignored -> false);
    }

    public static @Nullable Vec3 trace(Flight flight, double minY, BiFunction<Vec3, Vec3, Vec3> collision, Predicate<Vec3> inWater) {
        if (!MianbaoBombCompat.isBomb(flight.type) || !finite(flight.origin) || !finite(flight.velocity)) return null;
        Vec3 position = flight.origin, velocity = flight.velocity;
        for (int age = 1; age <= MAX_TICKS; age++) {
            Vec3 next = position.add(velocity);
            if (!finite(next) || next.distanceToSqr(flight.origin) > 4096.0 * 4096.0) return null;
            Vec3 contact = collision.apply(position, next);
            if (contact != null) return contact;
            if (next.y < minY) return null;
            boolean water = inWater.test(next);
            velocity = step(velocity, requested(flight, next, velocity, age, water), guided(flight.type),
                    flight.type == WeaponType.GLIDE_BOMB, drag(flight.type), water);
            position = next;
        }
        return null;
    }

    public static @Nullable Vec3 predict(ServerLevel root, Flight flight, Set<UUID> ignored) {
        boolean[] unknown = {false};
        Vec3 hit = trace(flight, root.getMinBuildHeight() - 64.0, (from, to) -> {
            if (unknown[0]) return null;
            // Probe every crossed chunk before Level.clip can request one synchronously.
            int samples = Math.max(1, Mth.ceil(Math.max(Math.abs(to.x - from.x), Math.abs(to.z - from.z))));
            for (int i = 0; i <= samples; i++) {
                Vec3 point = from.lerp(to, (double) i / samples);
                if (root.getChunkSource().getChunkNow(Mth.floor(point.x) >> 4, Mth.floor(point.z) >> 4) == null) {
                    unknown[0] = true;
                    return null;
                }
            }
            var context = new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, CollisionContext.empty());
            // Structures are tested below in world coordinates, honoring the releasing carrier's exclusion.
            ((ClipContextExtension) context).sable$setDoNotProject(true);
            var terrain = root.clip(context);
            Vec3 closest = terrain.getType() == HitResult.Type.MISS ? null : terrain.getLocation();
            var structure = SableMissileFuze.firstBlockContact(root, from, to, ignored);
            if (structure != null && (closest == null || from.distanceToSqr(structure.position()) < from.distanceToSqr(closest))) {
                closest = structure.position();
            }
            return closest;
        }, point -> !unknown[0] && root.getFluidState(net.minecraft.core.BlockPos.containing(point)).is(net.minecraft.tags.FluidTags.WATER));
        return unknown[0] ? null : hit;
    }

    public static boolean finite(Vec3 value) { return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z); }
    private MianbaoBombBallistics() {}
}
