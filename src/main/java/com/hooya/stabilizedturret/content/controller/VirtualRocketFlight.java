package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Mathematical flight state; has no entity, chunk or rendering dependency. */
public final class VirtualRocketFlight {
    private Vec3 position;
    private Vec3 velocity;
    private Vec3 cachedVelocity;
    private int age;
    private final double firstDrag;
    private final double drop;
    private final double firstGravity;
    private final boolean visualGuided;

    public VirtualRocketFlight(Vec3 position, Vec3 velocity, int age, double firstDrag, double drop) {
        this(position, velocity, age, firstDrag, drop, MianbaoRocketFlight.ARROW_GRAVITY, false);
    }

    public VirtualRocketFlight(Vec3 position, Vec3 velocity, int age, double firstDrag, double drop,
                               double firstGravity, boolean visualGuided) {
        this.position = position;
        this.velocity = velocity;
        this.cachedVelocity = velocity;
        this.age = age;
        this.firstDrag = firstDrag;
        this.drop = drop;
        this.firstGravity = firstGravity;
        this.visualGuided = visualGuided;
    }

    public Segment advance(@Nullable Vec3 laserTarget) {
        Vec3 previous = position;
        position = position.add(velocity);
        if (visualGuided) {
            // 2.7's sight-guided round has no gravity and applies arrow drag on every tick.
            velocity = velocity.scale(age == 0 ? firstDrag : MianbaoRocketFlight.AIR_DRAG);
            if (laserTarget != null && age + 1 > 10 && age + 1 < 300) {
                Vec3 toTarget = laserTarget.subtract(position);
                double speed = velocity.length(), distance = toTarget.length();
                if (speed > 1e-8 && distance > 1e-8) {
                    double cosine = Math.clamp(velocity.dot(toTarget) / (speed * distance), -1.0, 1.0);
                    double divisor = Math.max(Math.toDegrees(Math.acos(cosine)) / 8.0, 1.0);
                    velocity = velocity.lerp(toTarget.scale(6.0 / distance), 1.0 / divisor);
                }
            }
            return new Segment(previous, position, ++age);
        }
        cachedVelocity = age == 0
                ? MianbaoRocketFlight.afterMove(velocity, 0, firstDrag, drop, firstGravity)
                : cachedVelocity.add(0, -drop, 0);
        velocity = cachedVelocity;
        // The native laser procedure writes its correction to the cache after
        // assigning delta movement, so guidance takes effect one tick later.
        if (laserTarget != null) cachedVelocity = MianbaoRocketFlight.steer(
                cachedVelocity, laserTarget.subtract(position));
        return new Segment(previous, position, ++age);
    }

    public Vec3 position() { return position; }
    public Vec3 velocity() { return velocity; }
    public int age() { return age; }
    public double firstDrag() { return firstDrag; }
    public double drop() { return drop; }
    public record Segment(Vec3 from, Vec3 to, int age) {}
}
