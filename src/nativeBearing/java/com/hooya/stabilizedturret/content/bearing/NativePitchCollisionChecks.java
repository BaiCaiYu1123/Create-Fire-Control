package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Uses real Sable voxel colliders, rather than treating the floor as another dynamic box. */
public final class NativePitchCollisionChecks {
    public static void main(String[] args) throws Exception {
        StringBuilder result = new StringBuilder("PASS: native pitch collision removal and restoration\n");
        for (int hz : new int[]{40, 120}) result.append(run(hz));
        Files.writeString(Path.of("pitch-collision-native-result.txt"), result);
        System.out.print(result);
    }

    private static String run(int hz) {
        long scene = Rapier3D.initialize(0, -11, 0, .09);
        try {
            Rapier3D.configFrequencyAndDamping(40, 5);
            Rapier3D.configSolverIterations(18, 2, 2);
            Rapier3D.configMinIslandSize(128);
            int collider = Rapier3D.newVoxelCollider(.6, 1, 0, false, null);
            Rapier3D.addVoxelColliderBox(collider, new double[]{0, 0, 0, 1, 1, 1});
            int[] floor = new int[4096];
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                floor[x + (z << 4) + (15 << 8)] = ((collider + 1) << 16) | 1;
            for (int x = -1; x <= 0; x++) for (int z = -1; z <= 0; z++)
                Rapier3D.addChunk(scene, x, -1, z, floor, true, -1);
            int[] cube = new int[4096];
            cube[0] = ((collider + 1) << 16) | 3;
            for (int body = 1; body <= 2; body++) {
                int x = body * 1024;
                Rapier3D.createSubLevel(scene, body, pose((body - 1) * 2, 2, 0));
                Rapier3D.setCenterOfMass(scene, body, x + .5, .5, .5);
                Rapier3D.setLocalBounds(scene, body, x, 0, 0, x + 1, 1, 1);
                Rapier3D.addChunk(scene, x >> 4, 0, 0, cube, false, body);
                mass(scene, body);
            }
            long joint = Rapier3D.addGenericConstraint(scene, 1, 2,
                    1024.5, .5, .5, 0, 0, 0, 1, 2048.5, .5, .5, 0, 0, 0, 1, 0);
            step(scene, hz, 2);
            double resting = y(scene, 1), control = y(scene, 2);
            require(Math.abs(resting - .5) < .01 && Math.abs(control - .5) < .01,
                    "positive control: both structures collide with actual world terrain");

            // This is the same public chunk removal used by the production collision override.
            Rapier3D.removeChunk(scene, 64, 0, 0, false);
            mass(scene, 1);
            Rapier3D.wakeUpObject(scene, 1);
            step(scene, hz, 1);
            double disabled = y(scene, 1);
            require(disabled < -3, "selected pitch structure passes through the world floor");
            require(Math.abs(y(scene, 2) - control) < .01, "carrier/unrelated collision remains enabled");
            require(Rapier3D.isConstraintValid(scene, joint), "collision removal retains physical joints");

            double[] v = new double[3], w = new double[3];
            Rapier3D.getLinearVelocity(scene, 1, v);
            Rapier3D.getAngularVelocity(scene, 1, w);
            Rapier3D.addLinearAngularVelocities(scene, 1, -v[0], -v[1], -v[2], -w[0], -w[1], -w[2], false);
            Rapier3D.teleportObject(scene, 1, 0, 2, 0, 0, 0, 0, 1);
            Rapier3D.addChunk(scene, 64, 0, 0, cube, false, 1);
            mass(scene, 1);
            Rapier3D.wakeUpObject(scene, 1);
            step(scene, hz, 2);
            double restored = y(scene, 1);
            require(Math.abs(restored - .5) < .01, "restoring the same structure restores world collision");
            require(Rapier3D.isConstraintValid(scene, joint), "restoration retains original body and joint identities");
            return String.format(Locale.ROOT,
                    "%d Hz: restingY=%.6f disabledY=%.6f carrierY=%.6f restoredY=%.6f; joint retained%n",
                    hz, resting, disabled, y(scene, 2), restored);
        } finally { Rapier3D.dispose(scene); }
    }

    private static void mass(long scene, int body) {
        Rapier3D.setMassProperties(scene, body, 10, new double[]{0, 0, 0},
                new double[]{10.0 / 6, 0, 0, 0, 10.0 / 6, 0, 0, 0, 10.0 / 6});
    }

    private static void step(long scene, int hz, int seconds) {
        for (int step = 0; step < hz * seconds; step++) {
            if (step % (hz / 20) == 0) Rapier3D.tick(scene, .05);
            Rapier3D.step(scene, 1.0 / hz);
        }
    }

    private static double y(long scene, int body) {
        double[] pose = new double[7];
        Rapier3D.getPose(scene, body, pose);
        return pose[1];
    }

    private static double[] pose(double x, double y, double z) { return new double[]{x, y, z, 0, 0, 0, 1}; }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
