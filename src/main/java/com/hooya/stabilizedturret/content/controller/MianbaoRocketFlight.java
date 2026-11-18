package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Unguided flight used by Rocket_trail and HeavyRocketTtrail after AbstractArrow. */
public final class MianbaoRocketFlight {
    public static final int LIFETIME_TICKS = 300;
    public static final double AIR_DRAG = (double) 0.99F;
    public static final double WATER_DRAG = (double) 0.6F;
    public static final double ARROW_GRAVITY = 0.05;
    public static final double STANDARD_DROP = 0.025;
    public static final double HEAVY_DROP = 0.03;

    /** The native trail caches the first arrow tick, then ignores later arrow drag. */
    public static Vec3 afterMove(Vec3 velocity, int tick, double firstDrag, double drop) {
        return afterMove(velocity, tick, firstDrag, drop, ARROW_GRAVITY);
    }

    public static Vec3 afterMove(Vec3 velocity, int tick, double firstDrag, double drop, double firstGravity) {
        return tick == 0
                ? velocity.scale(firstDrag).add(0.0, -firstGravity, 0.0)
                : velocity.add(0.0, -drop, 0.0);
    }

    /** Exact piecewise-linear native flight, also used between client render frames. */
    public static Vec3 positionAt(Vec3 origin, Vec3 velocity, int age,
                                  double ticks, double firstDrag, double drop) {
        return positionAt(origin, velocity, age, ticks, firstDrag, drop, ARROW_GRAVITY);
    }

    public static Vec3 positionAt(Vec3 origin, Vec3 velocity, int age,
                                  double ticks, double firstDrag, double drop, double firstGravity) {
        double time = Math.max(0.0, ticks);
        int whole = (int) Math.floor(time);
        double fraction = time - whole;
        if (age == 0 && whole == 0) return origin.add(velocity.scale(fraction));
        double cached = age == 0 ? whole - 1.0 : whole;
        Vec3 position = age == 0
                ? origin.add(velocity.scale(1.0 + firstDrag * cached))
                    .add(0, -firstGravity * cached - drop * cached * (cached - 1) / 2, 0)
                : origin.add(velocity.scale(cached))
                    .add(0, -drop * cached * (cached - 1) / 2, 0);
        return position.add(velocityAt(velocity, age, whole, firstDrag, drop, firstGravity).scale(fraction));
    }

    public static Vec3 velocityAt(Vec3 velocity, int age, int ticks, double firstDrag, double drop) {
        return velocityAt(velocity, age, ticks, firstDrag, drop, ARROW_GRAVITY);
    }

    public static Vec3 velocityAt(Vec3 velocity, int age, int ticks, double firstDrag, double drop, double firstGravity) {
        if (ticks <= 0) return velocity;
        return age == 0
                ? velocity.scale(firstDrag).add(0, -firstGravity - drop * (ticks - 1), 0)
                : velocity.add(0, -drop * ticks, 0);
    }

    /** Native laser rocket adjusts its cached vector towards a nearby laser point. */
    public static Vec3 steer(Vec3 velocity, Vec3 toTarget) {
        double speed = velocity.length(), distance = toTarget.length();
        if (speed < 1e-8 || distance < 1e-8) return velocity;
        double cosine = Math.clamp(velocity.dot(toTarget) / (speed * distance), -1.0, 1.0);
        double divisor = Math.max(Math.toDegrees(Math.acos(cosine)) / 3.0, 1.0);
        return velocity.lerp(toTarget.scale(speed / distance), 1.0 / divisor);
    }

    public static @Nullable Vec3 trace(
            Vec3 origin, Vec3 initialVelocity, int maxTicks, int safetyTicks,
            double firstDrag, double drop, double minY, ContactQuery query
    ) {
        return trace(origin, initialVelocity, maxTicks, safetyTicks, firstDrag, drop, ARROW_GRAVITY, minY, query);
    }

    public static @Nullable Vec3 trace(
            Vec3 origin, Vec3 initialVelocity, int maxTicks, int safetyTicks,
            double firstDrag, double drop, double firstGravity, double minY, ContactQuery query
    ) {
        if (!finite(origin) || !finite(initialVelocity) || !Double.isFinite(firstDrag)
                || !Double.isFinite(drop) || !Double.isFinite(firstGravity)) return null;
        Vec3 position = origin;
        Vec3 velocity = initialVelocity;
        for (int tick = 0; tick < Math.min(maxTicks, LIFETIME_TICKS); tick++) {
            Vec3 next = position.add(velocity);
            if (!finite(next)) return null;
            // The first native entity tick already has tickCount == 1.
            if (tick + 1 >= safetyTicks) {
                Vec3 hit = query.firstContact(position, next);
                if (hit != null) return hit;
            }
            position = next;
            velocity = afterMove(velocity, tick, firstDrag, drop, firstGravity);
            if (position.y < minY || !finite(velocity)) return null;
        }
        return null;
    }

    @FunctionalInterface
    public interface ContactQuery {
        @Nullable Vec3 firstContact(Vec3 from, Vec3 to);
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x)
                && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private MianbaoRocketFlight() {}
}
