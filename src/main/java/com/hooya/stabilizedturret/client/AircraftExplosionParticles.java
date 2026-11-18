package com.hooya.stabilizedturret.client;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** Enlarges the unboosted aircraft effect about its impact, without adding particles. */
record AircraftExplosionParticles(float scale) {
    private static final float NATIVE_STRENGTH = 6.0F;
    static final int PARTICLE_COUNT = 84; // Unboosted effect: strength 6 * 14.
    private static final double NATIVE_RADIUS = NATIVE_STRENGTH * 0.75;

    static AircraftExplosionParticles forStrength(float strength) {
        return new AircraftExplosionParticles(strength / NATIVE_STRENGTH);
    }

    Vec3 offset(RandomSource random) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radial = Math.sqrt(random.nextDouble()) * NATIVE_RADIUS;
        double height = (random.nextDouble() - 0.5) * NATIVE_RADIUS * 1.7;
        return new Vec3(Math.cos(angle) * radial, height, Math.sin(angle) * radial)
                .scale(scale);
    }

    Vec3 velocity(RandomSource random) {
        return new Vec3((random.nextDouble() - 0.5) * 0.25,
                (random.nextDouble() - 0.5) * 0.25,
                (random.nextDouble() - 0.5) * 0.25).scale(scale);
    }
}
