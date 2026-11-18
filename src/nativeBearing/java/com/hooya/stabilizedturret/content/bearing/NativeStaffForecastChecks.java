package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Strict, separately reported direct-carrier staff prediction checks against Sable's unchanged DLL. */
public final class NativeStaffForecastChecks {
    private static final Quaterniond Y_FRAME = BearingServoMath.hingeFrame(new Quaterniond(), 0.0);
    private static final Vector3d FORWARD = new Vector3d(0.0, 0.0, 1.0);
    private static final Path REPORT = Path.of("bearing-staff-forecast-accuracy-result.txt");
    private static final double MAX_ERROR_DEGREES = 0.05;
    private static final double MAX_ANGULAR_SPEED = 8.0;

    public static void main(String[] args) throws IOException {
        StringBuilder report = new StringBuilder("Direct carrier staff forecast; free dual-axis, dual-turret fixture\n");
        Files.writeString(REPORT, "RUNNING\n" + report);
        try {
            for (boolean forecast : new boolean[]{false, true}) {
                for (double dt : new double[]{1.0 / 40.0, 1.0 / 120.0}) {
                    Result result = run(dt, forecast);
                    String line = result.describe(forecast, dt);
                    report.append(line);
                    System.out.print(line);
                    if (forecast) result.requireStrictHold();
                }
            }
        } catch (Throwable failure) {
            Files.writeString(REPORT, "FAIL\n" + report + failure + "\n");
            throw failure;
        }
        Files.writeString(REPORT, "PASS\n" + report);
        System.out.println("PASS: direct carrier staff forecast; strict world-ray error < 0.05 degrees and angular speed < 8 rad/s");
    }

    private static Result run(double dt, boolean forecast) {
        try (Scene scene = new Scene(dt)) {
            scene.box(1, 2000.0, 0.0, 0.0);
            Turret first = new Turret(scene, 2, 3, 3.0, 30.0, 10.0);
            Turret second = new Turret(scene, 4, 5, -3.0, -35.0, -5.0);
            long support = Rapier3D.addFreeConstraint(scene.id, -1, 1,
                    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0);
            for (int axis = 0; axis < 6; axis++) {
                Rapier3D.setConstraintMotor(scene.id, support, axis, 0.0, 3e7, 3e5, false, 0.0);
            }
            // Both rates run the same 3-second settling, 7-second grab and 4-second release profile.
            int start = (int) Math.round(3.0 / dt);
            int release = start + (int) Math.round(7.0 / dt);
            int end = release + (int) Math.round(4.0 / dt);
            long staff = 0L;
            double[][] peaks = new double[3][2];
            double peakAngularSpeed = 0.0;
            for (int step = 0; step < end; step++) {
                boolean grabbed = step >= start && step < release;
                if (step == start) {
                    Rapier3D.removeConstraint(scene.id, support);
                    first.resetMotion();
                    second.resetMotion();
                }
                Quaterniond queued = new Quaterniond();
                if (grabbed) {
                    // User input is held for a 20 Hz game tick, including every physics substep.
                    double time = Math.floor((step - start) * dt / 0.05 + 1e-6) * 0.05;
                    queued.rotationY(0.35 * Math.sin(time * 2.4))
                            .rotateX(0.14 * Math.sin(time * 1.7)).rotateZ(0.11 * Math.sin(time * 1.3));
                    Vector3d goal = new Vector3d(0.8 + 1.5 * Math.sin(time), 0.4 * Math.sin(time * 0.7), 0.0);
                    Vector3d localGoal = queued.transformInverse(new Vector3d(goal));
                    // Match Simulated: rebuild the free constraint every substep, keeping the queued goal.
                    if (staff != 0L) Rapier3D.removeConstraint(scene.id, staff);
                    staff = Rapier3D.addFreeConstraint(scene.id, -1, 1,
                            0.0, 0.0, 0.0, 0.8, 0.0, 0.0, queued.x, queued.y, queued.z, queued.w);
                    for (int axis = 3; axis < 6; axis++) {
                        Rapier3D.setConstraintMotor(scene.id, staff, axis, 0.0, 10000.0, 850.0, false, 0.0);
                    }
                    for (int axis = 0; axis < 3; axis++) {
                        double position = axis == 0 ? localGoal.x : axis == 1 ? localGoal.y : localGoal.z;
                        Rapier3D.setConstraintMotor(scene.id, staff, axis, position, 2650.0, 125.0, false, 0.0);
                    }
                } else if (step == release) {
                    Rapier3D.removeConstraint(scene.id, staff);
                    staff = 0L;
                    first.resetMotion();
                    second.resetMotion();
                }
                Quaterniond carrier = scene.orientation(1);
                Quaterniond rotation = grabbed
                        ? StaffCarrierForecast.forecastDelta(carrier, queued, dt, 10000.0, 850.0)
                        : new Quaterniond();
                // All axes read this same pre-solve snapshot; setting a motor never changes physical pose.
                first.drive(carrier, rotation, grabbed, forecast, dt);
                second.drive(carrier, rotation, grabbed, forecast, dt);
                scene.step();
                int phase = grabbed ? 1 : step >= release ? 2 : 0;
                if (step > start - (int) Math.round(1.0 / dt)) {
                    peaks[phase][0] = Math.max(peaks[phase][0], first.worldRayError());
                    peaks[phase][1] = Math.max(peaks[phase][1], second.worldRayError());
                }
                if (step >= start) {
                    for (int body = 1; body <= 5; body++) {
                        peakAngularSpeed = Math.max(peakAngularSpeed, scene.angularVelocity(body).length());
                    }
                }
            }
            return new Result(peaks, peakAngularSpeed);
        }
    }

    private record Result(double[][] peaks, double angularSpeed) {
        private String describe(boolean forecast, double dt) {
            return String.format(Locale.ROOT,
                    "%s %.0f Hz: before %.9f/%.9f deg, during %.9f/%.9f deg, after %.9f/%.9f deg; peak omega %.9f rad/s%n",
                    forecast ? "forecast helper" : "previous position/velocity model", 1.0 / dt,
                    Math.toDegrees(peaks[0][0]), Math.toDegrees(peaks[0][1]),
                    Math.toDegrees(peaks[1][0]), Math.toDegrees(peaks[1][1]),
                    Math.toDegrees(peaks[2][0]), Math.toDegrees(peaks[2][1]), angularSpeed);
        }

        private void requireStrictHold() {
            for (double[] phase : peaks) {
                for (double peak : phase) {
                    if (!Double.isFinite(peak) || Math.toDegrees(peak) >= MAX_ERROR_DEGREES) {
                        throw new AssertionError("direct carrier staff forecast world-ray error must remain < 0.05 degrees");
                    }
                }
            }
            if (!Double.isFinite(angularSpeed) || angularSpeed >= MAX_ANGULAR_SPEED) {
                throw new AssertionError("direct carrier staff forecast angular speed must remain < 8 rad/s");
            }
        }
    }

    private static final class Turret {
        private final Scene scene;
        private final int yawBody, pitchBody;
        private final long yawJoint, pitchJoint;
        private final Vector3d heldRay;
        private final BearingServoMath.Motor yawMotor, pitchMotor;
        private final BearingServoMath.MotorTarget yawTarget = new BearingServoMath.MotorTarget();
        private final BearingServoMath.MotorTarget pitchTarget = new BearingServoMath.MotorTarget();
        private final BearingServoMath.MotorResidual yawResidual = new BearingServoMath.MotorResidual();
        private final BearingServoMath.MotorResidual pitchResidual = new BearingServoMath.MotorResidual();
        private double previousIdealYaw, previousIdealPitch;
        private boolean initial = true;

        private Turret(Scene scene, int yawBody, int pitchBody, double x, double yawDegrees, double elevationDegrees) {
            this.scene = scene;
            this.yawBody = yawBody;
            this.pitchBody = pitchBody;
            scene.box(yawBody, 10.0, x, 2.0);
            scene.box(pitchBody, 5.0, x, 4.0);
            yawJoint = scene.hinge(1, yawBody, new Vector3d(x, 2.0, 0.0), Y_FRAME);
            pitchJoint = scene.hinge(yawBody, pitchBody, new Vector3d(0.0, 2.0, 0.0), new Quaterniond());
            Rapier3D.setConstraintLimit(scene.id, pitchJoint, 3, -Math.PI / 2.0, Math.PI / 2.0);
            heldRay = new Quaterniond().rotationY(Math.toRadians(yawDegrees))
                    .rotateX(-Math.toRadians(elevationDegrees)).transform(new Vector3d(FORWARD));
            yawMotor = BearingServoMath.motor(15.0 * 1.25 / 3.0, 2000.0 * (1.25 / 3.0 + x * x));
            pitchMotor = BearingServoMath.motor(5.0 * 1.25 / 3.0, 10.0 * (1.25 / 3.0 + 4.0));
        }

        private void resetMotion() {
            yawTarget.reset();
            pitchTarget.reset();
            yawResidual.reset();
            pitchResidual.reset();
            initial = true;
        }

        private void drive(Quaterniond carrier, Quaterniond rotation, boolean grabbed, boolean forecast, double dt) {
            Vector3d local = carrier.transformInverse(new Vector3d(heldRay));
            double idealYaw = Math.atan2(local.x, local.z);
            double idealPitch = -Math.asin(Math.clamp(local.y, -1.0, 1.0));
            Vector3d actualRay = scene.orientation(pitchBody).transform(new Vector3d(FORWARD));
            Vector3d yawAxis = carrier.transform(new Vector3d(0.0, 1.0, 0.0));
            Vector3d pitchAxis = scene.orientation(yawBody).transform(new Vector3d(1.0, 0.0, 0.0));
            double measuredYaw = scene.angle(1, yawBody, Y_FRAME);
            double measuredPitch = scene.angle(yawBody, pitchBody, new Quaterniond());
            double yawVelocity = BearingServoMath.stabilizationRate(heldRay, yawAxis, scene.angularVelocity(1));
            double pitchVelocity = BearingServoMath.stabilizationRate(heldRay, pitchAxis, scene.angularVelocity(yawBody));
            double goalYaw = measuredYaw + BearingServoMath.signedDirectionAngle(actualRay, heldRay, yawAxis);
            double goalPitch = measuredPitch + BearingServoMath.signedDirectionAngle(actualRay, heldRay, pitchAxis);
            double yawRate = initial ? yawVelocity : BearingServoMath.wrapRadians(idealYaw - previousIdealYaw) / dt;
            double pitchRate = initial ? pitchVelocity : BearingServoMath.wrapRadians(idealPitch - previousIdealPitch) / dt;
            if (grabbed && forecast) {
                double yawDisplacement = StaffCarrierForecast.carrierJointDisplacement(actualRay, heldRay, yawAxis, rotation);
                double pitchDisplacement = StaffCarrierForecast.pitchDisplacement(actualRay, heldRay,
                        pitchAxis, rotation, yawAxis, yawDisplacement);
                yawRate = yawDisplacement / dt;
                pitchRate = pitchDisplacement / dt;
            } else if (forecast) {
                yawRate = yawTarget.predictHeldRate(idealYaw, yawVelocity, 0.0, 0.0, dt);
                pitchRate = pitchTarget.predictHeldRate(idealPitch, pitchVelocity, 0.0, 0.0, dt);
            }
            previousIdealYaw = idealYaw;
            previousIdealPitch = idealPitch;
            initial = false;
            double yawBias = forecast ? yawResidual.update(goalYaw, measuredYaw, dt) : yawResidual.update(goalYaw, measuredYaw);
            double pitchBias = forecast ? pitchResidual.update(goalPitch, measuredPitch, dt) : pitchResidual.update(goalPitch, measuredPitch);
            Quaterniond frame = BearingServoMath.yawMotorFrame(Y_FRAME, measuredYaw);
            Rapier3D.setConstraintFrame(scene.id, yawJoint, 1, 0.0, 0.0, 0.0, frame.x, frame.y, frame.z, frame.w);
            double yawDampingRate = forecast ? BearingServoMath.heldTargetVelocityRate(yawVelocity, dt, yawMotor) : yawVelocity;
            double pitchDampingRate = forecast ? BearingServoMath.heldTargetVelocityRate(pitchVelocity, dt, pitchMotor) : pitchVelocity;
            scene.drive(yawJoint, BearingServoMath.servoGoal(BearingServoMath.wrapRadians(goalYaw - measuredYaw + yawBias),
                    yawRate, yawDampingRate, dt, yawMotor), yawMotor);
            scene.drive(pitchJoint, BearingServoMath.servoGoal(BearingServoMath.wrapRadians(goalPitch + pitchBias),
                    pitchRate, pitchDampingRate, dt, pitchMotor), pitchMotor);
        }

        private double worldRayError() {
            Vector3d actual = scene.orientation(pitchBody).transform(new Vector3d(FORWARD)).normalize();
            return Math.acos(Math.clamp(actual.dot(heldRay), -1.0, 1.0));
        }
    }

    private static final class Scene implements AutoCloseable {
        private final long id;
        private final double dt;

        private Scene(double dt) {
            this.dt = dt;
            id = Rapier3D.initialize(0.0, 0.0, 0.0, 1.0);
            Rapier3D.configFrequencyAndDamping(40.0, 5.0);
            Rapier3D.configSolverIterations(18, 2, 2);
            Rapier3D.configMinIslandSize(128);
        }

        private void box(int body, double mass, double x, double y) {
            Rapier3D.createBox(id, body, mass, 0.5, 0.5, 1.0, new double[]{x, y, 0.0, 0.0, 0.0, 0.0, 1.0});
        }

        private long hinge(int a, int b, Vector3d anchor, Quaterniond frame) {
            long joint = Rapier3D.addGenericConstraint(id, a, b,
                    anchor.x, anchor.y, anchor.z, frame.x, frame.y, frame.z, frame.w,
                    0.0, 0.0, 0.0, frame.x, frame.y, frame.z, frame.w, 0b110111);
            Rapier3D.setConstraintContactsEnabled(id, joint, false);
            return joint;
        }

        private Quaterniond orientation(int body) {
            double[] pose = new double[7];
            Rapier3D.getPose(id, body, pose);
            return new Quaterniond(pose[3], pose[4], pose[5], pose[6]);
        }

        private Vector3d angularVelocity(int body) {
            double[] velocity = new double[3];
            Rapier3D.getAngularVelocity(id, body, velocity);
            return new Vector3d(velocity[0], velocity[1], velocity[2]);
        }

        private double angle(int a, int b, Quaterniond frame) {
            return BearingServoMath.jointAngle(orientation(a).mul(frame), orientation(b).mul(frame));
        }

        private void drive(long joint, double goal, BearingServoMath.Motor motor) {
            Rapier3D.setConstraintMotor(id, joint, 3, goal, motor.stiffness(), motor.damping(), true, motor.maxTorque());
        }

        private void step() {
            for (int body = 1; body <= 5; body++) Rapier3D.wakeUpObject(id, body);
            Rapier3D.step(id, dt);
        }

        @Override public void close() { Rapier3D.dispose(id); }
    }
}
