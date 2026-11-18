package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/** Shared server/client bomb integrator. No entity ticks, trails or world access. */
public final class VirtualBombFlight {
    private final WeaponType type;
    private Vec3 position, velocity;
    private int age;

    public VirtualBombFlight(WeaponType type, Vec3 position, Vec3 velocity, int age) {
        this.type = type; this.position = position; this.velocity = velocity; this.age = age;
    }

    public Segment advance(boolean coordinates, Vec3 target, List<Vec3> lasers, boolean water) {
        Vec3 from = position, incoming = velocity;
        position = position.add(incoming);
        age++;
        var settings = new MianbaoBombBallistics.Flight(type, from, incoming, coordinates, target, lasers);
        velocity = MianbaoBombBallistics.step(incoming,
                MianbaoBombBallistics.requested(settings, position, incoming, age, water),
                MianbaoBombBallistics.guided(type), type == WeaponType.GLIDE_BOMB, MianbaoBombBallistics.drag(type), water);
        return new Segment(from, position, incoming, age, water);
    }

    public Vec3 position() { return position; }
    public Vec3 velocity() { return velocity; }
    public int age() { return age; }
    public record Segment(Vec3 from, Vec3 to, Vec3 incoming, int age, boolean water) {}
}
