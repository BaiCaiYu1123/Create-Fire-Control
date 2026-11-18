package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Strict read-only gyro forecast checks against Sable's unchanged native solver. */
public final class NativeGyroForecastChecks {
    private record Tensor(String name, Matrix3d inertia) {}

    public static void main(String[] args) throws Exception {
        List<Tensor> tensors = tensors();
        Quaterniond[] poses = {
                new Quaterniond(), new Quaterniond().rotateXYZ(.62, -.32, .48),
                new Quaterniond().rotateAxis(2.1, new Vector3d(1, 2, 3).normalize())
        };
        Vector3d[] velocities = {
                new Vector3d(.5, .8, 1.1), new Vector3d(-2.3, 4.7, -3.1), new Vector3d(13, -11, 17)
        };
        int cases = 0;
        double peak = 0.0;
        String worst = "";
        StringBuilder csv = new StringBuilder("tensor,dt,pose,omega,errorRadPerSecond\n");
        for (Tensor tensor : tensors) for (double dt : new double[]{.025, 1.0 / 120.0}) {
            for (int poseIndex = 0; poseIndex < poses.length; poseIndex++) {
                for (int velocityIndex = 0; velocityIndex < velocities.length; velocityIndex++) {
                    Quaterniond pose = poses[poseIndex];
                    Vector3d omega = velocities[velocityIndex];
                    Matrix3d originalInertia = new Matrix3d(tensor.inertia);
                    Quaterniond originalPose = new Quaterniond(pose);
                    Vector3d originalOmega = new Vector3d(omega);
                    Vector3d forecast = NativeGyroForecast.predict(tensor.inertia, pose, omega, dt);
                    require(tensor.inertia.equals(originalInertia), "inertia input changed");
                    require(pose.equals(originalPose), "pose input changed");
                    require(omega.equals(originalOmega), "velocity input changed");
                    Vector3d nativeOmega = nativeStep(tensor.inertia, pose, omega, dt);
                    double error = forecast.distance(nativeOmega);
                    cases++;
                    csv.append(tensor.name).append(',').append(dt).append(',').append(poseIndex)
                            .append(',').append(velocityIndex).append(',').append(error).append('\n');
                    if (error > peak) {
                        peak = error;
                        worst = tensor.name + "/" + dt + "/" + poseIndex + "/" + velocityIndex;
                    }
                }
            }
        }
        rejectInvalidInputs();
        Files.writeString(Path.of("bearing-native-gyro.csv"), csv);
        String result = "cases=" + cases + " peakOmegaError=" + peak + " worst=" + worst;
        Files.writeString(Path.of("bearing-native-gyro-result.txt"),
                (peak <= 0.00002 ? "PASS " : "FAIL ") + result + System.lineSeparator());
        require(peak <= 0.00002, "Native gyro forecast mismatch: " + result);
        System.out.println("PASS " + result);
    }

    private static List<Tensor> tensors() {
        List<Tensor> tensors = new ArrayList<>();
        for (int[] order : new int[][]{{0, 1, 2}, {0, 2, 1}, {1, 0, 2},
                {1, 2, 0}, {2, 0, 1}, {2, 1, 0}}) {
            double[] values = {3, 5, 7};
            tensors.add(new Tensor("diagonal-" + Arrays.toString(order).replace(',', ';'),
                    new Matrix3d().zero().m00(values[order[0]]).m11(values[order[1]]).m22(values[order[2]])));
        }
        for (int axis = 0; axis < 3; axis++) {
            Quaterniond rotation = new Quaterniond().rotateAxis(.77,
                    axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
            tensors.add(new Tensor(new String[]{"YZ", "XZ", "XY"}[axis],
                    rotated(new double[]{3, 5, 7}, rotation)));
        }
        tensors.add(new Tensor("full", rotated(new double[]{3, 5, 7},
                new Quaterniond().rotateXYZ(.31, -.73, .91))));
        tensors.add(new Tensor("full-repeat", rotated(new double[]{3, 3, 5},
                new Quaterniond().rotateXYZ(-.41, .37, 1.12))));
        tensors.add(new Tensor("spherical", new Matrix3d().scaling(5)));
        tensors.add(new Tensor("carrier", new Matrix3d().set(new double[]{
                4.3263658854164335, 3.6308333379111337, 0,
                3.6308333379111337, 29.116666663794504, 0, 0, 0, 32.19303254921094
        })));
        Random random = new Random(773491);
        for (int sample = 0; sample < 128; sample++) {
            double x = .1 + 100 * random.nextDouble();
            double y = .1 + 100 * random.nextDouble();
            double z = .1 + 100 * random.nextDouble();
            Quaterniond rotation = new Quaterniond().rotateXYZ(6 * random.nextDouble(),
                    6 * random.nextDouble(), 6 * random.nextDouble());
            tensors.add(new Tensor("random-" + sample, rotated(new double[]{y + z, x + z, x + y}, rotation)));
        }
        return tensors;
    }

    private static Matrix3d rotated(double[] principal, Quaterniond orientation) {
        Matrix3d rotation = new Matrix3d().rotation(orientation);
        Matrix3d inertia = new Matrix3d(rotation);
        inertia.m00(inertia.m00() * principal[0]).m01(inertia.m01() * principal[0])
                .m02(inertia.m02() * principal[0]);
        inertia.m10(inertia.m10() * principal[1]).m11(inertia.m11() * principal[1])
                .m12(inertia.m12() * principal[1]);
        inertia.m20(inertia.m20() * principal[2]).m21(inertia.m21() * principal[2])
                .m22(inertia.m22() * principal[2]);
        return inertia.mul(new Matrix3d(rotation).transpose());
    }

    private static Vector3d nativeStep(Matrix3d inertia, Quaterniond pose, Vector3d omega, double dt) {
        long scene = Rapier3D.initialize(0, 0, 0, 0);
        try {
            Rapier3D.configSolverIterations(18, 2, 2);
            Rapier3D.createSubLevel(scene, 1, new double[]{0, 0, 0, pose.x, pose.y, pose.z, pose.w});
            Rapier3D.setCenterOfMass(scene, 1, 0, 0, 0);
            Rapier3D.setLocalBounds(scene, 1, 0, 0, 0, 0, 0, 0);
            Rapier3D.setMassProperties(scene, 1, 7.5, new double[3], inertia.get(new double[9]));
            Rapier3D.addLinearAngularVelocities(scene, 1, 0, 0, 0, omega.x, omega.y, omega.z, false);
            Rapier3D.tick(scene, dt);
            Rapier3D.step(scene, dt);
            double[] velocity = new double[3];
            Rapier3D.getAngularVelocity(scene, 1, velocity);
            return new Vector3d(velocity);
        } finally {
            Rapier3D.dispose(scene);
        }
    }

    private static void rejectInvalidInputs() {
        expectInvalid(() -> NativeGyroForecast.predict(new Matrix3d().m00(Double.NaN),
                new Quaterniond(), new Vector3d(), .025));
        expectInvalid(() -> NativeGyroForecast.predict(new Matrix3d().m10(.1),
                new Quaterniond(), new Vector3d(), .025));
        expectInvalid(() -> NativeGyroForecast.predict(new Matrix3d(),
                new Quaterniond(0, 0, 0, 0), new Vector3d(), .025));
        expectInvalid(() -> NativeGyroForecast.predict(new Matrix3d(),
                new Quaterniond(), new Vector3d(Double.POSITIVE_INFINITY, 0, 0), .025));
        expectInvalid(() -> NativeGyroForecast.predict(new Matrix3d(),
                new Quaterniond(), new Vector3d(), 0));
    }

    private static void expectInvalid(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid gyro input was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
