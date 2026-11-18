package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.constraint.generic.RapierGenericConstraintHandle;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Diagnostic only: isolate position lookahead, damping, and momentum feed-forward.
 * Every case starts at the same exact world aim with zero velocity. A world hinge
 * supports the carrier's translation and roll/pitch while allowing free yaw. The
 * carrier velocity drive belongs to this fixture, never to production control.
 */
public final class NativeMotorTimingExperiment {
    private static final double DT = 1.0 / 40.0;
    private static final Quaterniond Y_FRAME = BearingServoMath.hingeFrame(new Quaterniond(), 0);

    public static void main(String[] args) throws Exception {
        boolean quick = args.length > 0 && args[0].equals("quick");
        boolean trace = args.length > 0 && args[0].equals("trace");
        boolean residualAudit = args.length > 0 && args[0].equals("residual");
        StringBuilder report = new StringBuilder("load,speed_deg_s,velocity_mode,residual_mode,damping_scale,lookahead_scale,"
                + "settled_peak_deg,startup_peak_deg,mean_yaw_bias_deg,mean_pitch_bias_deg,peak_yaw_error_deg,peak_pitch_error_deg,"
                + "mean_carrier_omega_y,mean_yaw_omega_y,mean_barrel_omega_y,mean_yaw_pose_rate,"
                + "mean_yaw_relative_native_rate,peak_support_tilt_deg,peak_yaw_joint_impulse,"
                + "peak_pitch_joint_impulse,peak_carrier_reaction_delta_omega,strict_005_pass\n");
        for (boolean eccentric : trace ? new boolean[]{true} : new boolean[]{false, true}) {
            for (double degrees : trace ? new double[]{120} : new double[]{120, 360}) {
                for (double damping : quick || trace || residualAudit ? new double[]{1} : new double[]{1, 0.25, 0.01, 0}) {
                    for (double lookahead : quick || trace || residualAudit ? new double[]{1} : new double[]{1, 0.5, 0}) {
                        for (VelocityMode mode : VelocityMode.values()) {
                            for (ResidualMode residual : trace || residualAudit ? ResidualMode.values() : new ResidualMode[]{ResidualMode.PRODUCTION}) {
                                String row = run(eccentric, degrees, damping, lookahead, mode, residual, trace);
                                System.out.print(row);
                                report.append(row);
                            }
                        }
                    }
                }
            }
        }
        Path output = Path.of(trace ? "bearing-motor-timing-trace-summary.csv" : "bearing-motor-timing-experiment.csv");
        Files.writeString(output, report);
        System.out.println("Diagnostic results: " + output.toAbsolutePath());
    }

    private enum VelocityMode { BASE, ZERO_VELOCITY, OBSERVER, ZOH, DISCRETE }
    private enum ResidualMode { PRODUCTION, DISABLED, NO_LARGE_ERROR_RESET, GAIN_010, GAIN_005, GAIN_002 }

    private static String run(boolean eccentric, double speedDegrees, double dampingScale,
                              double lookaheadScale, VelocityMode mode, ResidualMode residualMode,
                              boolean writeTrace) throws java.io.IOException {
        StringBuilder trace = new StringBuilder("step,yaw_goal,yaw_measured,yaw_residual,yaw_position_rate,yaw_native_rate,"
                + "yaw_base_rate,yaw_velocity_feed,yaw_motor_goal,pitch_goal,pitch_measured,pitch_residual,pitch_position_rate,"
                + "pitch_native_rate,pitch_motor_goal,world_yaw_error_deg,world_pitch_error_deg,total_error_deg,"
                + "carrier_omega_x,carrier_omega_y,carrier_omega_z,yaw_omega_x,yaw_omega_y,yaw_omega_z,"
                + "carrier_tilt_deg,turret_tilt_deg,yaw_joint_impulse,pitch_joint_impulse\n");
        try (Scene s = new Scene()) {
            double speed = Math.toRadians(speedDegrees);
            double offset = eccentric ? 3 : 0, barrelMass = eccentric ? 50 : 5;
            Quaterniond yawOrientation = new Quaterniond().rotationY(0.5);
            Quaterniond barrelOrientation = new Quaterniond(yawOrientation).rotateX(-0.15);
            Vector3d barrelPosition = yawOrientation.transform(new Vector3d(offset, 2, 0)).add(0, 2, 0);
            Vector3d held = barrelOrientation.transform(new Vector3d(0, 0, 1));
            s.box(1, 2000, new Vector3d(), new Quaterniond());
            s.box(2, 10, new Vector3d(0, 2, 0), yawOrientation);
            s.box(3, barrelMass, barrelPosition, barrelOrientation);
            // Hinge X follows world Y: all translations and world X/Z rotations lock.
            s.hinge(-1, 1, new Vector3d(), new Vector3d(), Y_FRAME);
            NativeBearingJoint yaw = s.hinge(1, 2, new Vector3d(0, 2, 0), new Vector3d(), Y_FRAME);
            NativeBearingJoint pitch = s.hinge(2, 3, new Vector3d(offset, 2, 0), new Vector3d(), new Quaterniond());
            BearingServoMath.Motor yawMotor = scaledDamping(BearingServoMath.motor(10 * 1.25 / 3
                    + BearingServoMath.pivotInertia(barrelMass * 1.25 / 3, barrelMass,
                    new Vector3d(offset, 2, 0), new Vector3d(0, 1, 0)), 2000 * 1.25 / 3), dampingScale);
            BearingServoMath.Motor pitchMotor = scaledDamping(
                    BearingServoMath.motor(barrelMass * 1.25 / 3, 10 * 1.25 / 3), dampingScale);
            var yawTarget = new BearingServoMath.MotorTarget();
            var pitchTarget = new BearingServoMath.MotorTarget();
            var yawResidual = new Residual(residualMode);
            var pitchResidual = new Residual(residualMode);
            var yawObserver = new Observer();
            var pitchObserver = new Observer();
            double peak = 0, startupPeak = 0, signedYaw = 0, signedPitch = 0, peakYawError = 0, peakPitchError = 0;
            double omegaCarrier = 0, omegaYaw = 0, omegaBarrel = 0, poseRate = 0, nativeRate = 0;
            double peakTilt = 0, yawImpulse = 0, pitchImpulse = 0, carrierReaction = 0;
            double previousYaw = s.angle(1, 2, Y_FRAME);
            int measuredCount = 0;
            for (int i = 0; i < 600; i++) {
                Vector3d correction = new Vector3d(0, speed, 0).sub(s.omega(1));
                Rapier3D.addLinearAngularVelocities(s.id, 1, 0, 0, 0,
                        correction.x, correction.y, correction.z, false);
                Vector3d yawAxis = s.orientation(1).transform(new Vector3d(0, 1, 0));
                Vector3d pitchAxis = s.orientation(2).transform(new Vector3d(1, 0, 0));
                double yawMeasured = s.angle(1, 2, Y_FRAME);
                double pitchMeasured = s.angle(2, 3, new Quaterniond());
                double yawGoal = BearingServoMath.wrapRadians(yawMeasured + BearingServoMath.signedDirectionAngle(
                        s.orientation(2).transform(new Vector3d(0, 0, 1)), held, yawAxis));
                double pitchGoal = BearingServoMath.wrapRadians(pitchMeasured + BearingServoMath.signedDirectionAngle(
                        s.orientation(3).transform(new Vector3d(0, 0, 1)), held, pitchAxis));
                double yawBaseRate = BearingServoMath.stabilizationRate(held, yawAxis, s.omega(1));
                double pitchBaseRate = BearingServoMath.stabilizationRate(held, pitchAxis, s.omega(2));
                double yawPositionRate = yawTarget.velocity(yawGoal, yawBaseRate, DT);
                double pitchPositionRate = pitchTarget.velocity(pitchGoal, pitchBaseRate, DT);
                double yawNative = s.omega(2).sub(s.omega(1)).dot(yawAxis);
                double pitchNative = s.omega(3).sub(s.omega(2)).dot(pitchAxis);
                double yawVelocity = velocity(mode, yawBaseRate, yawMotor,
                        yawObserver.sample(yawPositionRate, yawMeasured, yawNative));
                double pitchVelocity = velocity(mode, pitchBaseRate, pitchMotor,
                        pitchObserver.sample(pitchPositionRate, pitchMeasured, pitchNative));
                yaw.hinge().setFrame2(new Vector3d(), BearingServoMath.yawMotorFrame(Y_FRAME, yawMeasured));
                double yawResidualValue = yawResidual.update(yawGoal, yawMeasured);
                double pitchResidualValue = pitchResidual.update(pitchGoal, pitchMeasured);
                double yawError = BearingServoMath.wrapRadians(yawGoal - yawMeasured + yawResidualValue);
                double pitchError = BearingServoMath.wrapRadians(pitchGoal - pitchMeasured + pitchResidualValue);
                double yawCommand = mode == VelocityMode.DISCRETE
                        ? MotorStepResponse.targetOffset(yawMotor, DT, 18, yawNative, yawError,
                            yawPositionRate * lookaheadScale)
                        : BearingServoMath.servoGoal(yawError, yawPositionRate * lookaheadScale,
                            yawVelocity, DT, yawMotor);
                double pitchCommand = mode == VelocityMode.DISCRETE
                        ? pitchMeasured + MotorStepResponse.targetOffset(pitchMotor, DT, 18, pitchNative,
                            pitchError, pitchPositionRate * lookaheadScale)
                        : BearingServoMath.servoGoal(pitchMeasured + pitchError,
                            pitchPositionRate * lookaheadScale, pitchVelocity, DT, pitchMotor);
                yaw.drive(BearingServoMath.wrapRadians(yawCommand), yawMotor);
                pitch.drive(BearingServoMath.wrapRadians(pitchCommand), pitchMotor);
                s.step();
                Vector3d actual = s.orientation(3).transform(new Vector3d(0, 0, 1));
                double error = Math.acos(Math.clamp(actual.dot(held), -1, 1));
                startupPeak = Math.max(startupPeak, error);
                Vector3d linear = new Vector3d(), angular = new Vector3d();
                yaw.getJointImpulses(linear, angular);
                double yawStepImpulse = angular.length();
                yawImpulse = Math.max(yawImpulse, yawStepImpulse);
                pitch.getJointImpulses(linear, angular);
                double pitchStepImpulse = angular.length();
                pitchImpulse = Math.max(pitchImpulse, pitchStepImpulse);
                carrierReaction = Math.max(carrierReaction, new Vector3d(0, speed, 0).sub(s.omega(1)).length());
                peakTilt = Math.max(peakTilt, Math.acos(Math.clamp(
                        s.orientation(1).transform(new Vector3d(0, 1, 0)).y, -1, 1)));
                double afterYaw = s.angle(1, 2, Y_FRAME);
                double yawErrorAfter = BearingServoMath.signedDirectionAngle(held, actual, new Vector3d(0, 1, 0));
                double pitchErrorAfter = BearingServoMath.signedDirectionAngle(held, actual, pitchAxis);
                if (writeTrace) {
                    Vector3d carrierOmega = s.omega(1), yawOmega = s.omega(2);
                    appendRow(trace, i, yawGoal, yawMeasured, yawResidualValue, yawPositionRate, yawNative,
                            yawBaseRate, yawVelocity, yawCommand, pitchGoal, pitchMeasured, pitchResidualValue,
                            pitchPositionRate, pitchNative, pitchCommand, Math.toDegrees(yawErrorAfter),
                            Math.toDegrees(pitchErrorAfter), Math.toDegrees(error), carrierOmega.x,
                            carrierOmega.y, carrierOmega.z, yawOmega.x, yawOmega.y, yawOmega.z,
                            Math.toDegrees(Math.acos(Math.clamp(s.orientation(1).transform(new Vector3d(0, 1, 0)).y, -1, 1))),
                            Math.toDegrees(Math.acos(Math.clamp(s.orientation(2).transform(new Vector3d(0, 1, 0)).y, -1, 1))),
                            yawStepImpulse, pitchStepImpulse);
                }
                if (i >= 120) {
                    measuredCount++;
                    peak = Math.max(peak, error);
                    signedYaw += yawErrorAfter;
                    signedPitch += pitchErrorAfter;
                    peakYawError = Math.max(peakYawError, Math.abs(yawErrorAfter));
                    peakPitchError = Math.max(peakPitchError, Math.abs(pitchErrorAfter));
                    omegaCarrier += s.omega(1).y;
                    omegaYaw += s.omega(2).y;
                    omegaBarrel += s.omega(3).y;
                    poseRate += BearingServoMath.wrapRadians(afterYaw - previousYaw) / DT;
                    nativeRate += yawNative;
                }
                previousYaw = afterYaw;
            }
            if (writeTrace) Files.writeString(Path.of("bearing-motor-trace-" + mode + "-" + residualMode + ".csv"), trace);
            return String.format(Locale.ROOT,
                    "%s,%.0f,%s,%s,%.3f,%.1f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%s%n",
                    eccentric ? "eccentric" : "centered", speedDegrees, mode, residualMode, dampingScale, lookaheadScale,
                    Math.toDegrees(peak), Math.toDegrees(startupPeak), Math.toDegrees(signedYaw / measuredCount),
                    Math.toDegrees(signedPitch / measuredCount), Math.toDegrees(peakYawError),
                    Math.toDegrees(peakPitchError), omegaCarrier / measuredCount,
                    omegaYaw / measuredCount, omegaBarrel / measuredCount, poseRate / measuredCount,
                    nativeRate / measuredCount, Math.toDegrees(peakTilt), yawImpulse, pitchImpulse,
                    carrierReaction, Math.toDegrees(peak) < 0.05);
        }
    }

    private static void appendRow(StringBuilder builder, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(values[i]);
        }
        builder.append('\n');
    }

    private static final class Residual {
        final ResidualMode mode;
        final BearingServoMath.MotorResidual production = new BearingServoMath.MotorResidual();
        double correction;
        Residual(ResidualMode mode) { this.mode = mode; }
        double update(double goal, double measured) {
            return switch (mode) {
                case PRODUCTION -> production.update(goal, measured);
                case DISABLED -> 0;
                case NO_LARGE_ERROR_RESET -> correction = Math.clamp(correction
                        + BearingServoMath.wrapRadians(goal - measured) * 0.4,
                        -Math.toRadians(1), Math.toRadians(1));
                case GAIN_010, GAIN_005, GAIN_002 -> {
                    double error = BearingServoMath.wrapRadians(goal - measured);
                    double gain = mode == ResidualMode.GAIN_010 ? 0.1 : mode == ResidualMode.GAIN_005 ? 0.05 : 0.02;
                    correction = Math.abs(error) > Math.toRadians(2) ? 0 : Math.clamp(correction + error * gain,
                            -Math.toRadians(1), Math.toRadians(1));
                    yield correction;
                }
            };
        }
    }

    private static double velocity(VelocityMode mode, double base, BearingServoMath.Motor motor, double observed) {
        double ratio = motor.damping() > 0 ? DT * motor.stiffness() / motor.damping() : Double.POSITIVE_INFINITY;
        double beta = ratio < 700 ? ratio / Math.expm1(ratio) : 0;
        return switch (mode) {
            case BASE -> base;
            case ZERO_VELOCITY -> 0;
            case OBSERVER -> observed;
            case ZOH -> base * beta;
            case DISCRETE -> 0;
        };
    }

    private static BearingServoMath.Motor scaledDamping(BearingServoMath.Motor motor, double scale) {
        return new BearingServoMath.Motor(motor.stiffness(), motor.damping() * scale, motor.maxTorque());
    }

    /** Exact hold-only form of the previous observer; no operator state in these scenes. */
    private static final class Observer {
        boolean initialized;
        double previous;
        double sample(double desiredPoseRate, double measuredAngle, double nativeRelativeRate) {
            double actualPoseRate = initialized ? BearingServoMath.wrapRadians(measuredAngle - previous) / DT
                    : nativeRelativeRate;
            initialized = true;
            previous = measuredAngle;
            return desiredPoseRate + nativeRelativeRate - actualPoseRate;
        }
    }

    private static final class Scene implements AutoCloseable {
        final long id = Rapier3D.initialize(0, 0, 0, 1);
        Scene() {
            Rapier3D.configFrequencyAndDamping(40, 5);
            Rapier3D.configSolverIterations(18, 2, 2);
            Rapier3D.configMinIslandSize(128);
        }
        void box(int body, double mass, Vector3d position, Quaterniond q) {
            Rapier3D.createBox(id, body, mass, 0.5, 0.5, 1,
                    new double[]{position.x, position.y, position.z, q.x, q.y, q.z, q.w});
        }
        NativeBearingJoint hinge(int a, int b, Vector3d aa, Vector3d ab, Quaterniond frame) {
            long handle = Rapier3D.addGenericConstraint(id, a, b,
                    aa.x, aa.y, aa.z, frame.x, frame.y, frame.z, frame.w,
                    ab.x, ab.y, ab.z, frame.x, frame.y, frame.z, frame.w, 0b110111);
            NativeBearingJoint joint = new NativeBearingJoint(new RapierGenericConstraintHandle(id, handle));
            joint.setContactsEnabled(false);
            return joint;
        }
        Quaterniond orientation(int body) {
            if (body == -1) return new Quaterniond();
            double[] pose = new double[7];
            Rapier3D.getPose(id, body, pose);
            return new Quaterniond(pose[3], pose[4], pose[5], pose[6]);
        }
        Vector3d omega(int body) {
            double[] rate = new double[3];
            Rapier3D.getAngularVelocity(id, body, rate);
            return new Vector3d(rate[0], rate[1], rate[2]);
        }
        double angle(int a, int b, Quaterniond frame) {
            return BearingServoMath.jointAngle(orientation(a).mul(frame), orientation(b).mul(frame));
        }
        void step() {
            for (int body = 1; body <= 3; body++) Rapier3D.wakeUpObject(id, body);
            Rapier3D.step(id, DT);
        }
        @Override public void close() { Rapier3D.dispose(id); }
    }
}
