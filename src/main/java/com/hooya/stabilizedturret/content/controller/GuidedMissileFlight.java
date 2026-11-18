package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

/** The free-flight part of AbstractArrow.tick, before the device's guidance command. */
public final class GuidedMissileFlight {
    private GuidedMissileFlight() {}

    public record Step(Vec3 from, Vec3 to, Vec3 incoming, Vec3 afterArrow) {}

    public static Step step(Vec3 position, Vec3 velocity, boolean gravity, boolean water) {
        Vec3 after = velocity.scale(water ? 0.6F : 0.99F);
        if (gravity) after = after.add(0, -0.05, 0);
        return new Step(position, position.add(velocity), velocity, after);
    }

    /** Native smoke procedure: one sample per block, round(speed * .75) samples. */
    public static int smokeCount(Vec3 velocity) {
        return (int) Math.max(Math.round(velocity.length() * 0.75), 1L);
    }

    public static Vec3 smokePosition(Vec3 end, Vec3 velocity, int index) {
        double speed = velocity.length();
        return speed < 1.0e-12 ? end : end.subtract(velocity.scale(index / speed));
    }

    public static double smokeTimeFraction(Vec3 velocity, int index) {
        double speed = velocity.length();
        return speed < 1.0e-12 ? 1 : Math.max(0, Math.min(1, 1.0 - index / speed));
    }
}
