package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.constraint.generic.RapierGenericConstraintHandle;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Strict supported-carrier hold checks against Sable's unchanged native solver.
 * A world hinge locks carrier translation and roll/pitch while leaving yaw free.
 * This fixture complements (and does not replace) the free-carrier eccentric cases.
 * Its carrier velocity drive is external test input; both turret axes use the
 * production position motor, with finite authority and physical reactions.
 */
public final class NativeSupportedTurnChecks {
    private static final Quaterniond Y_FRAME = BearingServoMath.hingeFrame(new Quaterniond(), 0.0);
    private static final Vector3d FORWARD = new Vector3d(0, 0, 1);
    private static final double PITCH_MIN = -Math.PI / 2.0;
    private static final double PITCH_MAX = Math.PI / 2.0;
    private static final double STRICT_PEAK_DEGREES = 0.05;
    private static final double STARTUP_SECONDS = 3.0;
    private static final double DURATION_SECONDS = 15.0;

    private NativeSupportedTurnChecks() {}

    public static void main(String[] args) throws IOException {
        boolean characterized = Boolean.getBoolean("create_fire_control.characterizeCarrierHold");
        StringBuilder report = new StringBuilder();
        List<String> failures = new ArrayList<>();
        StringBuilder csv = new StringBuilder("load,hz,speed_deg_s,steps,duration_seconds,"
                + "startup_peak_deg,steady_peak_deg,steady_mean_yaw_bias_deg,steady_mean_pitch_bias_deg,"
                + "peak_carrier_swing_deg,peak_plate_swing_deg,peak_barrel_swing_deg,"
                + "max_carrier_omega_rad_s,max_plate_omega_rad_s,max_barrel_omega_rad_s,"
                + "min_requested_pitch_deg,max_requested_pitch_deg,pitch_outside_reachable_steps,"
                + "max_pitch_joint_magnitude_deg,yaw_motor_max_torque,pitch_motor_max_torque,"
                + "peak_yaw_joint_linear_impulse,peak_yaw_joint_angular_impulse,"
                + "peak_pitch_joint_linear_impulse,peak_pitch_joint_angular_impulse,"
                + "peak_carrier_reaction_delta_omega_rad_s,functional_pass,strict_005_pass\n");
        boolean precisionPass = true;
        for (boolean eccentric : new boolean[]{false, true}) {
            for (int hz : new int[]{40, 120}) {
                for (double speed : new double[]{30, -30, 120, -120, 360, -360}) {
                    Result result = run(eccentric, hz, speed);
                    precisionPass &= result.steadyPeakDegrees() < STRICT_PEAK_DEGREES;
                    failures.addAll(result.functionalFailures());
                    csv.append(result.csv());
                    report.append(result.summary()).append('\n');
                    System.out.println(result.summary());
                }
            }
        }
        String status = precisionPass && failures.isEmpty() ? "PASS" : "FAIL";
        report.insert(0, status + ": production supported-carrier continuous-turn accuracy\n"
                + "Strict steady peak threshold: <" + STRICT_PEAK_DEGREES + " degrees.\n"
                + "Every independent case begins at exact yaw=0.5 rad/pitch=-0.15 rad, zero linear/angular velocity.\n"
                + "Startup [0,3] seconds and steady (3,15] seconds are reported separately.\n"
                + "Carrier mass=2000; plate mass=10; barrel centered mass=5 or eccentric mass=50 at local x=3.\n"
                + "Production held prediction, held-target damping feed, time-step residual, yaw rebasing,"
                + " pitch stops, assembly inertia, finite motor cap and physical joint reactions are retained.\n"
                + "No operator motion or operator speed limit is applied to carrier compensation.\n"
                + "Native joint impulse API returns joint constraint impulses; it does not expose the motor's"
                + " accumulated impulse. Configured torque caps are reported without inferring motor saturation.\n"
                + "This supported fixture supplements the existing free-carrier checks.\n\n");
        if (!failures.isEmpty()) report.append("\nFunctional failures:\n").append(String.join("\n", failures)).append('\n');
        Files.writeString(Path.of("bearing-supported-turn-accuracy-result.txt"), report);
        Files.writeString(Path.of("bearing-supported-turn-accuracy.csv"), csv);
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
        if (!precisionPass && !characterized) {
            throw new AssertionError("Production supported-carrier continuous-turn steady peak must stay <"
                    + STRICT_PEAK_DEGREES + " degrees in every load/rate/timestep case; see accuracy report");
        }
        if (!precisionPass) System.out.println("ACCURACY FAIL: characterization only; all supported-turn functional checks completed");
        else System.out.println("PASS: strict supported-turn accuracy and functional checks");
    }

    private static Result run(boolean eccentric, int hz, double speedDegrees) {
        double dt = 1.0 / hz;
        double speed = Math.toRadians(speedDegrees);
        double offset = eccentric ? 3.0 : 0.0;
        double barrelMass = eccentric ? 50.0 : 5.0;
        String label = (eccentric ? "eccentric" : "centered") + " " + hz + "Hz " + speedDegrees + "deg/s";
        List<String> failures = new ArrayList<>();
        int steps = (int) Math.round(DURATION_SECONDS * hz);
        int startupSteps = (int) Math.round(STARTUP_SECONDS * hz);
        try (Scene s = new Scene(dt)) {
            Quaterniond yawInitial = new Quaterniond().rotationY(0.5);
            Quaterniond barrelInitial = new Quaterniond(yawInitial).rotateX(-0.15);
            Vector3d barrelPosition = yawInitial.transform(new Vector3d(offset, 2, 0)).add(0, 2, 0);
            Vector3d held = barrelInitial.transform(new Vector3d(FORWARD));
            s.box(1, 2000, new Vector3d(), new Quaterniond());
            s.box(2, 10, new Vector3d(0, 2, 0), yawInitial);
            s.box(3, barrelMass, barrelPosition, barrelInitial);
            NativeBearingJoint support = s.hinge(-1, 1, new Vector3d(), new Vector3d(), Y_FRAME);
            NativeBearingJoint yaw = s.hinge(1, 2, new Vector3d(0, 2, 0), new Vector3d(), Y_FRAME);
            NativeBearingJoint pitch = s.hinge(2, 3, new Vector3d(offset, 2, 0), new Vector3d(), new Quaterniond());
            BearingServoMath.MotorTarget yawTarget = new BearingServoMath.MotorTarget();
            BearingServoMath.MotorTarget pitchTarget = new BearingServoMath.MotorTarget();
            BearingServoMath.MotorResidual yawResidual = new BearingServoMath.MotorResidual();
            BearingServoMath.MotorResidual pitchResidual = new BearingServoMath.MotorResidual();
            BearingServoMath.PitchStops stops = new BearingServoMath.PitchStops();
            // The unchanged DLL stores the exact supplied transforms in native float precision.
            // This initialization tolerance is independent of the strict world-aim accuracy limit.
            check(Math.abs(s.angle(1, 2, Y_FRAME) - 0.5) < 1e-6, label, "exact supplied initial yaw", failures);
            check(Math.abs(s.angle(2, 3, new Quaterniond()) + 0.15) < 1e-6, label, "exact supplied initial pitch", failures);
            for (int body = 1; body <= 3; body++) {
                check(s.omega(body).length() < 1e-10 && s.velocity(body).length() < 1e-10,
                        label, "zero initial velocity body " + body, failures);
            }
            double startupPeak = 0, steadyPeak = 0, yawBias = 0, pitchBias = 0;
            double carrierSwing = 0, plateSwing = 0, barrelSwing = 0;
            double carrierOmega = 0, plateOmega = 0, barrelOmega = 0;
            double minPitchGoal = Double.POSITIVE_INFINITY, maxPitchGoal = Double.NEGATIVE_INFINITY, maxMeasuredPitch = 0;
            double yawLinearImpulse = 0, yawAngularImpulse = 0, pitchLinearImpulse = 0, pitchAngularImpulse = 0;
            double carrierReaction = 0, yawTorqueCap = 0, pitchTorqueCap = 0;
            int outsideReachable = 0, steadyCount = 0;
            Vector3d linearImpulse = new Vector3d(), angularImpulse = new Vector3d();
            for (int i = 0; i < steps; i++) {
                // External smooth carrier yaw drive, with roll/pitch constrained only by the real support hinge.
                Vector3d correction = new Vector3d(0, speed, 0).sub(s.omega(1));
                Rapier3D.addLinearAngularVelocities(s.id, 1, 0, 0, 0,
                        correction.x, correction.y, correction.z, false);
                Quaterniond carrierPose = s.orientation(1), platePose = s.orientation(2), barrelPose = s.orientation(3);
                Vector3d yawAxis = carrierPose.transform(new Vector3d(0, 1, 0));
                Vector3d pitchAxis = platePose.transform(new Vector3d(1, 0, 0));
                // Production's refreshed source cache uses the actual nested barrel forward for yaw error.
                // Both axes read this same pre-solve source pose; neither edits a rigid body pose or velocity.
                Vector3d actualBefore = barrelPose.transform(new Vector3d(FORWARD));
                double yawMeasured = s.angle(1, 2, Y_FRAME);
                double pitchMeasured = s.angle(2, 3, new Quaterniond());
                double yawGoal = BearingServoMath.wrapRadians(yawMeasured
                        + BearingServoMath.signedDirectionAngle(actualBefore, held, yawAxis));
                double pitchRequested = BearingServoMath.wrapRadians(pitchMeasured
                        + BearingServoMath.signedDirectionAngle(actualBefore, held, pitchAxis));
                minPitchGoal = Math.min(minPitchGoal, pitchRequested);
                maxPitchGoal = Math.max(maxPitchGoal, pitchRequested);
                if (pitchRequested < PITCH_MIN || pitchRequested > PITCH_MAX) outsideReachable++;
                stops.update(PITCH_MIN, PITCH_MAX, pitchMeasured, dt);
                pitch.hinge().setLimit(ConstraintJointAxis.ANGULAR_X, stops.min(), stops.max());
                double pitchGoal = stops.goal(pitchRequested, pitchMeasured, dt);
                double yawBaseRate = BearingServoMath.stabilizationRate(held, yawAxis, s.omega(1));
                double pitchBaseRate = BearingServoMath.stabilizationRate(held, pitchAxis, s.omega(2));
                double yawRate = yawTarget.predictHeldRate(yawGoal, yawBaseRate, 0, 0, dt);
                double pitchRate = pitchTarget.predictHeldRate(pitchGoal, pitchBaseRate, 0, 0, dt);
                // Match production's per-step assemblyInertiaAtPivot and inertiaAtPivot.
                // Descendant pivots use the child's actual world anchor; parent pivots
                // use the persistent parent-local anchor, including pitch's y=2 offset.
                Vector3d yawChildPivot = s.position(2);
                Vector3d pitchChildPivot = s.position(3);
                Vector3d yawParentPivot = carrierPose.transform(new Vector3d(0, 2, 0)).add(s.position(1));
                Vector3d pitchParentPivot = platePose.transform(new Vector3d(offset, 2, 0)).add(s.position(2));
                BearingServoMath.Motor yawMotor = BearingServoMath.motor(
                        inertiaAtWorldPivot(s, 2, 10, yawChildPivot, yawAxis)
                                + inertiaAtWorldPivot(s, 3, barrelMass, yawChildPivot, yawAxis),
                        inertiaAtWorldPivot(s, 1, 2000, yawParentPivot, yawAxis));
                BearingServoMath.Motor pitchMotor = BearingServoMath.motor(
                        inertiaAtWorldPivot(s, 3, barrelMass, pitchChildPivot, pitchAxis),
                        inertiaAtWorldPivot(s, 2, 10, pitchParentPivot, pitchAxis));
                yawTorqueCap = Math.max(yawTorqueCap, yawMotor.maxTorque());
                pitchTorqueCap = Math.max(pitchTorqueCap, pitchMotor.maxTorque());
                double yawVelocityRate = BearingServoMath.heldTargetVelocityRate(yawBaseRate, dt, yawMotor);
                double pitchVelocityRate = BearingServoMath.heldTargetVelocityRate(pitchBaseRate, dt, pitchMotor);
                yaw.hinge().setFrame2(new Vector3d(), BearingServoMath.yawMotorFrame(Y_FRAME, yawMeasured));
                double yawCommand = BearingServoMath.servoGoal(BearingServoMath.wrapRadians(yawGoal - yawMeasured
                        + yawResidual.update(yawGoal, yawMeasured, dt)), yawRate, yawVelocityRate, dt, yawMotor);
                double pitchCommand = BearingServoMath.servoGoal(BearingServoMath.wrapRadians(pitchGoal
                        + pitchResidual.update(pitchGoal, pitchMeasured, dt)), pitchRate, pitchVelocityRate, dt, pitchMotor);
                check(Double.isFinite(yawCommand) && Double.isFinite(pitchCommand), label,
                        "finite motor command", failures);
                yaw.drive(yawCommand, yawMotor);
                pitch.drive(pitchCommand, pitchMotor);
                s.step();
                Vector3d actual = s.orientation(3).transform(new Vector3d(FORWARD));
                double error = Math.acos(Math.clamp(actual.dot(held), -1.0, 1.0));
                if (i < startupSteps) startupPeak = Math.max(startupPeak, error);
                else {
                    steadyCount++;
                    steadyPeak = Math.max(steadyPeak, error);
                    yawBias += BearingServoMath.signedDirectionAngle(held, actual, new Vector3d(0, 1, 0));
                    pitchBias += BearingServoMath.signedDirectionAngle(held, actual, pitchAxis);
                }
                for (int body = 1; body <= 3; body++) {
                    double[] pose = s.pose(body);
                    boolean finite = true;
                    for (double component : pose) finite &= Double.isFinite(component);
                    Vector3d angular = s.omega(body), velocity = s.velocity(body);
                    finite &= finite(angular) && finite(velocity);
                    check(finite, label, "finite native pose and velocity body " + body, failures);
                    double unitError = Math.abs(s.orientation(body).lengthSquared() - 1.0);
                    check(unitError < 1e-6, label, "normalized native orientation body " + body, failures);
                }
                carrierSwing = Math.max(carrierSwing, swing(s.orientation(1)));
                plateSwing = Math.max(plateSwing, swing(s.orientation(2)));
                barrelSwing = Math.max(barrelSwing, swing(s.orientation(3)));
                carrierOmega = Math.max(carrierOmega, s.omega(1).length());
                plateOmega = Math.max(plateOmega, s.omega(2).length());
                barrelOmega = Math.max(barrelOmega, s.omega(3).length());
                maxMeasuredPitch = Math.max(maxMeasuredPitch, Math.abs(s.angle(2, 3, new Quaterniond())));
                yaw.getJointImpulses(linearImpulse, angularImpulse);
                check(finite(linearImpulse) && finite(angularImpulse), label, "finite yaw reaction impulses", failures);
                yawLinearImpulse = Math.max(yawLinearImpulse, linearImpulse.length());
                yawAngularImpulse = Math.max(yawAngularImpulse, angularImpulse.length());
                pitch.getJointImpulses(linearImpulse, angularImpulse);
                check(finite(linearImpulse) && finite(angularImpulse), label, "finite pitch reaction impulses", failures);
                pitchLinearImpulse = Math.max(pitchLinearImpulse, linearImpulse.length());
                pitchAngularImpulse = Math.max(pitchAngularImpulse, angularImpulse.length());
                carrierReaction = Math.max(carrierReaction, new Vector3d(0, speed, 0).sub(s.omega(1)).length());
                check(support.isValid() && yaw.isValid() && pitch.isValid(), label, "all native joints remain valid", failures);
            }
            check(outsideReachable == 0, label, "held goal remains within pitch +/-90 degrees", failures);
            check(maxMeasuredPitch < PITCH_MAX + 0.02, label, "pitch physical stop respected", failures);
            check(carrierSwing < Math.toRadians(1.0), label, "world support prevents carrier tipping", failures);
            check(carrierReaction > 1e-9, label, "servo transmits reaction to dynamic carrier", failures);
            String row = String.format(Locale.ROOT,
                    "%s,%d,%.0f,%d,%.1f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,"
                            + "%.9f,%.9f,%.9f,%.9f,%.9f,%d,%.9f,%.9f,%.9f,"
                            + "%.9f,%.9f,%.9f,%.9f,%.9f,%s,%s%n",
                    eccentric ? "eccentric" : "centered", hz, speedDegrees, steps, steps * dt,
                    Math.toDegrees(startupPeak), Math.toDegrees(steadyPeak), Math.toDegrees(yawBias / steadyCount),
                    Math.toDegrees(pitchBias / steadyCount), Math.toDegrees(carrierSwing), Math.toDegrees(plateSwing),
                    Math.toDegrees(barrelSwing), carrierOmega, plateOmega, barrelOmega,
                    Math.toDegrees(minPitchGoal), Math.toDegrees(maxPitchGoal), outsideReachable,
                    Math.toDegrees(maxMeasuredPitch), yawTorqueCap, pitchTorqueCap,
                    yawLinearImpulse, yawAngularImpulse, pitchLinearImpulse, pitchAngularImpulse,
                    carrierReaction, failures.isEmpty(), Math.toDegrees(steadyPeak) < STRICT_PEAK_DEGREES);
            String summary = String.format(Locale.ROOT,
                    "%s: startup=%.9f deg steady=%.9f deg %s; plateSwing=%.9f deg carrierSwing=%.9f deg; "
                            + "requestedPitch=[%.6f,%.6f] deg reachable=%s; maxOmega=[%.6f,%.6f,%.6f] rad/s; "
                            + "motorCaps=[%.6f,%.6f]; carrierReaction=%.9f rad/s; functional=%s",
                    label, Math.toDegrees(startupPeak), Math.toDegrees(steadyPeak),
                    Math.toDegrees(steadyPeak) < STRICT_PEAK_DEGREES ? "PASS" : "FAIL",
                    Math.toDegrees(plateSwing), Math.toDegrees(carrierSwing), Math.toDegrees(minPitchGoal),
                    Math.toDegrees(maxPitchGoal), outsideReachable == 0, carrierOmega, plateOmega, barrelOmega,
                    yawTorqueCap, pitchTorqueCap, carrierReaction, failures.isEmpty() ? "PASS" : "FAIL");
            return new Result(Math.toDegrees(steadyPeak), row, summary, failures);
        }
    }

    private static double swing(Quaterniond orientation) {
        return Math.acos(Math.clamp(orientation.transform(new Vector3d(0, 1, 0)).y, -1.0, 1.0));
    }

    private static double inertiaAtWorldPivot(Scene scene, int body, double mass, Vector3d worldPivot,
                                              Vector3d worldAxis) {
        // Boxes have half extents (.5,.5,1): local inertia diag(m*1.25/3,m*1.25/3,m*.5/3).
        Vector3d localAxis = scene.orientation(body).transformInverse(new Vector3d(worldAxis));
        double centerInertia = mass / 3.0 * (1.25 * (localAxis.x * localAxis.x + localAxis.y * localAxis.y)
                + 0.5 * localAxis.z * localAxis.z);
        Vector3d radius = new Vector3d(worldPivot).sub(scene.position(body));
        return BearingServoMath.pivotInertia(centerInertia, mass, radius, worldAxis);
    }

    private static boolean finite(Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static void check(boolean passed, String label, String message, List<String> failures) {
        String failure = label + ": " + message;
        if (!passed && !failures.contains(failure)) failures.add(failure);
    }

    private record Result(double steadyPeakDegrees, String csv, String summary, List<String> functionalFailures) {}

    private static final class Scene implements AutoCloseable {
        final long id;
        final double dt;
        Scene(double dt) {
            this.dt = dt;
            id = Rapier3D.initialize(0, 0, 0, 1);
            // Real server overrides DLL defaults with these PhysicsConfigData values.
            Rapier3D.configFrequencyAndDamping(40, 5);
            Rapier3D.configSolverIterations(18, 2, 2);
            Rapier3D.configMinIslandSize(128);
        }
        void box(int body, double mass, Vector3d position, Quaterniond q) {
            Rapier3D.createBox(id, body, mass, 0.5, 0.5, 1,
                    new double[]{position.x, position.y, position.z, q.x, q.y, q.z, q.w});
        }
        NativeBearingJoint hinge(int a, int b, Vector3d anchorA, Vector3d anchorB, Quaterniond frame) {
            long handle = Rapier3D.addGenericConstraint(id, a, b,
                    anchorA.x, anchorA.y, anchorA.z, frame.x, frame.y, frame.z, frame.w,
                    anchorB.x, anchorB.y, anchorB.z, frame.x, frame.y, frame.z, frame.w, 0b110111);
            NativeBearingJoint joint = new NativeBearingJoint(new RapierGenericConstraintHandle(id, handle));
            joint.setContactsEnabled(false);
            return joint;
        }
        double[] pose(int body) {
            double[] pose = new double[7];
            Rapier3D.getPose(id, body, pose);
            return pose;
        }
        Quaterniond orientation(int body) {
            if (body == -1) return new Quaterniond();
            double[] pose = pose(body);
            return new Quaterniond(pose[3], pose[4], pose[5], pose[6]);
        }
        Vector3d position(int body) {
            double[] pose = pose(body);
            return new Vector3d(pose[0], pose[1], pose[2]);
        }
        Vector3d omega(int body) {
            double[] rate = new double[3];
            Rapier3D.getAngularVelocity(id, body, rate);
            return new Vector3d(rate[0], rate[1], rate[2]);
        }
        Vector3d velocity(int body) {
            double[] rate = new double[3];
            Rapier3D.getLinearVelocity(id, body, rate);
            return new Vector3d(rate[0], rate[1], rate[2]);
        }
        double angle(int parent, int child, Quaterniond frame) {
            return BearingServoMath.jointAngle(orientation(parent).mul(frame), orientation(child).mul(frame));
        }
        void step() {
            for (int body = 1; body <= 3; body++) Rapier3D.wakeUpObject(id, body);
            Rapier3D.step(id, dt);
        }
        @Override public void close() { Rapier3D.dispose(id); }
    }
}
