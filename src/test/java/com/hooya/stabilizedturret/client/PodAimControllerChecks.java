package com.hooya.stabilizedturret.client;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Regression checks for sub-table-step mouse input through the complete pod feedback loop. */
public final class PodAimControllerChecks {
    private static final Vec3 ORIGIN = new Vec3(123.0, 250.0, -456.0);

    public static void main(String[] args) {
        slowMouseInputIsSymmetric();
        opposingInputReturnsToStart();
        retainedPointSurvivesCameraReadsAndCarrierMotion();
        pitchStopsAndNadirRemainContinuous();
        System.out.println("PASS: pod slow-input symmetry, reversal, gyro target, camera basis and pitch travel");
    }

    private static void slowMouseInputIsSymmetric() {
        List<String> failures = new ArrayList<>();
        for (float yaw : new float[]{-135.0F, -45.0F, 0.0F, 45.0F, 135.0F}) {
            for (float pitch : new float[]{-25.0F, 25.0F, 145.0F}) {
                for (float step : new float[]{0.0005F, 0.01F}) {
                    double left = drag(yaw, pitch, -step, 1000);
                    double right = drag(yaw, pitch, step, 1000);
                    double expected = step * 1000.0;
                    if (yaw == -45.0F && pitch == 25.0F && step == 0.0005F) {
                        System.out.printf("Slow drag (1000 x 0.0005 deg): left=%.6f right=%.6f%n", left, right);
                    }
                    if (Math.abs(left + expected) > 0.015
                            || Math.abs(right - expected) > 0.015
                            || Math.abs(left + right) > 0.015) {
                        failures.add(String.format(
                                "yaw=%.0f pitch=%.0f step=%.4f: left=%.6f right=%.6f expected=+/-%.6f",
                                yaw, pitch, step, left, right, expected));
                    }
                }
            }
        }
        require(failures.isEmpty(), "Equal slow mouse input must have equal travel in both directions:\n"
                + String.join("\n", failures));
    }

    private static double drag(float yaw, float pitch, float step, int frames) {
        PodAimController pod = pod(yaw, pitch);
        double initialYaw = pod.yaw();
        for (int frame = 0; frame < frames; frame++) {
            inputFrame(pod, step, 0.0F);
        }
        // Past the nadir, horizontal input reverses gimbal yaw to retain screen direction.
        return (pod.yaw() - initialYaw) * (pitch > 90.0F ? -1.0 : 1.0);
    }

    private static void opposingInputReturnsToStart() {
        for (float pitch : new float[]{25.0F, 145.0F}) {
            PodAimController pod = pod(45.0F, pitch);
            Vec3 initialDirection = pod.direction();
            for (int frame = 0; frame < 1000; frame++) inputFrame(pod, -0.0005F, 0.0F);
            for (int frame = 0; frame < 1000; frame++) inputFrame(pod, 0.0005F, 0.0F);
            require(pod.direction().distanceTo(initialDirection) < 1.0e-5,
                    "Equal opposing drags return to the initial sight direction, pitch=" + pitch);
        }
    }

    private static void retainedPointSurvivesCameraReadsAndCarrierMotion() {
        PodAimController pod = pod(37.25F, 28.5F);
        Vec3 requestedTarget = ORIGIN.add(direction(37.25, 28.5).scale(2500.0));
        pod.acceptTarget(ORIGIN, requestedTarget);
        require(pod.target().distanceTo(requestedTarget) < 0.001,
                "Accepting an on-ray target preserves its position without a lookup-table offset");
        Vec3 retainedTarget = pod.target();
        for (int frame = 0; frame < 2000; frame++) {
            Vec3 origin = ORIGIN.add(Math.sin(frame * 0.01) * 20.0,
                    Math.cos(frame * 0.02) * 10.0, frame * 0.01);
            Vec3 expectedDirection = retainedTarget.subtract(origin).normalize();
            Vec3 actualDirection = pod.stabilize(origin);
            require(actualDirection.distanceTo(expectedDirection) < 1.0e-6,
                    "Carrier translation retains the world point at frame " + frame);
            for (int read = 0; read < 4; read++) {
                Vector3f cameraForward = new Vector3f(0.0F, 0.0F, -1.0F)
                        .rotate(pod.cameraRotation(origin));
                require(new Vec3(cameraForward).distanceTo(expectedDirection) < 1.0e-6,
                        "Camera forward agrees with the stabilized sight ray");
                require(pod.target().equals(retainedTarget), "Camera reads preserve the gyro world point");
            }
        }
    }

    private static void pitchStopsAndNadirRemainContinuous() {
        PodAimController pod = pod(45.0F, 25.0F);
        inputFrame(pod, 0.0F, 1000.0F);
        require(Math.abs(pod.pitch() - PodAimController.MAX_PITCH) < 0.001F,
                "Upper pitch stop remains enforced");
        inputFrame(pod, 0.0F, -1000.0F);
        require(Math.abs(pod.pitch() - PodAimController.MIN_PITCH) < 0.001F,
                "Lower pitch stop remains enforced");

        pod = pod(45.0F, 89.0F);
        for (int frame = 0; frame < 20; frame++) {
            inputFrame(pod, 0.0F, 0.1F);
            require(Math.abs(pod.yaw() - 45.0F) < 0.001F,
                    "Crossing the nadir preserves gimbal azimuth");
            require(Math.abs(pod.pitch() - (89.0F + (frame + 1) * 0.1F)) < 0.001F,
                    "Pitch advances continuously through the nadir");
        }
    }

    private static PodAimController pod(float yaw, float pitch) {
        PodAimController pod = new PodAimController();
        // Initialize inverted views by traversing the same continuous pitch branch as mouse input.
        float initialPitch = Math.min(pitch, 45.0F);
        pod.begin(direction(yaw, initialPitch), ORIGIN);
        pod.acceptTarget(ORIGIN, ORIGIN.add(pod.direction().scale(2500.0)));
        if (pitch != initialPitch) inputFrame(pod, 0.0F, pitch - initialPitch);
        return pod;
    }

    private static void inputFrame(PodAimController pod, float yaw, float pitch) {
        Vec3 direction = pod.turn(ORIGIN, yaw, pitch);
        // ClientAimState synchronously raycasts, accepts the on-ray hit, then renders/ticks.
        pod.acceptTarget(ORIGIN, ORIGIN.add(direction.scale(2500.0)));
        pod.stabilize(ORIGIN);
        pod.cameraRotation(ORIGIN);
    }

    private static Vec3 direction(double yaw, double pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
