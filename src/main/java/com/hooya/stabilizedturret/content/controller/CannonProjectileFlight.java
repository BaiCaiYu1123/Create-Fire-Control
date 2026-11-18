package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import java.util.function.BiFunction;

/** CBC's swept per-tick motion, shared by aiming and server/client impact prediction. */
public final class CannonProjectileFlight {
    private CannonProjectileFlight() {}
    public record Step(Vec3 position, Vec3 velocity) {}

    public static Step step(Vec3 position, Vec3 velocity, double gravity, double drag, boolean quadratic) {
        double speed = velocity.length();
        double force = Math.max(0.0, drag) * speed;
        if (quadratic) force *= speed;
        force = Math.min(force, speed);
        Vec3 acceleration = speed > 1e-10 ? velocity.scale(-force / speed) : Vec3.ZERO;
        acceleration = acceleration.add(0, gravity, 0);
        return new Step(position.add(velocity).add(acceleration.scale(0.5)), velocity.add(acceleration));
    }

    public static Vec3 trace(Vec3 origin, Vec3 initialVelocity, double gravity, double drag,
                             boolean quadratic, int maxTicks, double minY,
                             BiFunction<Vec3, Vec3, Vec3> contact) {
        Vec3 position = origin;
        Vec3 velocity = initialVelocity;
        for (int tick = 0; tick < Math.min(1200, maxTicks); tick++) {
            Step next = step(position, velocity, gravity, drag, quadratic);
            if (!finite(next.position) || !finite(next.velocity)) break;
            Vec3 hit = contact.apply(position, next.position);
            if (hit != null) return hit;
            position = next.position;
            velocity = next.velocity;
            if (position.y < minY || position.distanceToSqr(origin) > 4096.0 * 4096.0) break;
        }
        return null;
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
