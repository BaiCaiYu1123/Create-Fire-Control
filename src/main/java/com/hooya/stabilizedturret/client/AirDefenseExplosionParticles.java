package com.hooya.stabilizedturret.client;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Mianbao PortableantiairmissileexplodeProcedure's hemisphere and count-one packet scatter. */
record AirDefenseExplosionParticles(float scale) {
    static final String PARTICLE = "explosionsmall";
    static final float ADOPTED_SCALE = 3.0F;
    private static final List<Vec3> CELLS = nativeCells();
    static final int PARTICLE_COUNT = CELLS.size();

    record Sample(Vec3 offset, Vec3 velocity) {}

    Sample sample(int index, RandomSource random) {
        Vec3 cell = CELLS.get(index);
        // The procedure first jitters each grid point by +/-1. Its count-one
        // particle packet then adds Gaussian position (.5) and velocity (.25).
        Vec3 jitter = new Vec3(random.nextDouble() * 2.0 - 1.0,
                random.nextDouble() * 2.0 - 1.0, random.nextDouble() * 2.0 - 1.0);
        Vec3 scatter = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).scale(0.5);
        Vec3 velocity = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).scale(0.25);
        return new Sample(cell.add(jitter).add(scatter).scale(scale), velocity.scale(scale));
    }

    private static List<Vec3> nativeCells() {
        List<Vec3> cells = new ArrayList<>();
        for (int y = 0; y < 4; y++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    if (x * x / 9.0 + y * y / 16.0 + z * z / 9.0 <= 1.0) {
                        cells.add(new Vec3(x, y, z));
                    }
                }
            }
        }
        return List.copyOf(cells);
    }
}
