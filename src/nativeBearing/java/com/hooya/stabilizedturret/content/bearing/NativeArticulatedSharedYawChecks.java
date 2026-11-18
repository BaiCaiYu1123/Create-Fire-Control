package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.constraint.generic.RapierGenericConstraintHandle;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Strict JNI regression for one actual yaw hinge shared by two independently elevated barrels. */
public final class NativeArticulatedSharedYawChecks {
    private static final Vector3d FORWARD = new Vector3d(0, 0, 1);
    private static final Vector3d GRAVITY = new Vector3d(0, -11, 0);
    private static final Quaterniond YAW_FRAME = BearingServoMath.hingeFrame(new Quaterniond(), 0);
    private static final double[] SPEEDS = {120, -120, 360, 30};
    private static final double RAY_LIMIT_DEGREES = 0.05;
    private static final double ANCHOR_LIMIT_BLOCKS = 0.005;

    private NativeArticulatedSharedYawChecks() {}

    public static void main(String[] args) throws Exception {
        StringBuilder report = new StringBuilder("Shared yaw: carrier -> yaw -> two independent pitch/barrel bodies\n");
        boolean passed = true;
        for (int hz : new int[]{40, 120}) {
            for (boolean childOnly : new boolean[]{false,true}) {
                Result result = run(hz,childOnly);
                report.append(result.report);
                passed &= result.passed;
            }
        }
        report.insert(0, passed ? "PASS\n" : "FAIL: strict shared-yaw native regression\n");
        Files.writeString(Path.of("bearing-shared-yaw-result.txt"), report);
        if (!passed) throw new AssertionError("Shared-yaw world ray/anchor/reaction regression failed");
    }

    private static Result run(int hz,boolean childOnly) {
        double dt = 1.0 / hz;
        long scene = Rapier3D.initialize(GRAVITY.x, GRAVITY.y, GRAVITY.z, 0.09);
        Rapier3D.configFrequencyAndDamping(40, 5);
        Rapier3D.configSolverIterations(18, 2, 2);
        Rapier3D.configMinIslandSize(128);
        try {
            Quaterniond yawQ = new Quaterniond().rotationY(Math.toRadians(30));
            Quaterniond pitch1Q = new Quaterniond(yawQ).rotateX(Math.toRadians(-10));
            Quaterniond pitch2Q = new Quaterniond(yawQ).rotateX(Math.toRadians(7));
            Vector3d yawPosition = new Vector3d(0, 1.2, 0);
            Vector3d pitchAnchor1 = new Vector3d(-0.8, 0.3, 0);
            Vector3d pitchAnchor2 = new Vector3d(0.8, 0.3, 0);
            Vector3d pitchPivot = new Vector3d(0, 0, -0.8);
            List<Body> bodies = List.of(
                    body(scene, 1, 10, diagonal(20, 25, 30), new Vector3d(), new Quaterniond()),
                    body(scene, 2, 4, diagonal(2, 3, 4), yawPosition, yawQ),
                    body(scene, 3, 2, diagonal(0.4, 1.2, 1.2),
                            pivotPosition(yawPosition, yawQ, pitchAnchor1, pitch1Q, pitchPivot), pitch1Q),
                    body(scene, 4, 2, diagonal(0.4, 1.2, 1.2),
                            pivotPosition(yawPosition, yawQ, pitchAnchor2, pitch2Q, pitchPivot), pitch2Q));
            Vector3d held1 = pitch1Q.transform(new Vector3d(FORWARD));
            Vector3d held2 = pitch2Q.transform(new Vector3d(FORWARD));
            Vector3d heldAverage = new Vector3d(held1).add(held2).normalize();

            List<Axis> axes = List.of(
                    axis(scene, 0, 1, new Vector3d(0, 1, 0), new Vector3d(0, -0.2, 0), YAW_FRAME,
                            new Vector3d(0, 1, 0), true, BearingServoMath.motor(16, childOnly?Double.POSITIVE_INFINITY:25),childOnly),
                    axis(scene, 1, 2, pitchAnchor1, pitchPivot, new Quaterniond(),
                            new Vector3d(1, 0, 0), false, BearingServoMath.motor(1.68, childOnly?Double.POSITIVE_INFINITY:2),childOnly),
                    axis(scene, 1, 3, pitchAnchor2, pitchPivot, new Quaterniond(),
                            new Vector3d(1, 0, 0), false, BearingServoMath.motor(1.68, childOnly?Double.POSITIVE_INFINITY:2),childOnly));
            for (Axis axis : axes) {
                if (!(Double.isFinite(axis.motor.maxTorque()) && axis.motor.maxTorque() > 0)) {
                    throw new AssertionError("Every owned hinge requires a finite nonzero torque cap");
                }
            }
            // The service fixture adds its carrier support after assembling the controlled hinges.
            long support = Rapier3D.addFreeConstraint(scene, -1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
            for (int index : new int[]{0, 1, 2, 3, 5}) {
                Rapier3D.setConstraintMotor(scene, support, index, 0, 3e7, 3e5, false, 0);
            }
            List<ArticulatedServoPredictor.WorldMotor> worlds = worldSupport(childOnly?6:3);
            List<ArticulatedServoSolver.RayGoal> goals = List.of(
                    new ArticulatedServoSolver.RayGoal(0,
                            List.of(new ArticulatedServoSolver.RaySource(2, FORWARD),
                                    new ArticulatedServoSolver.RaySource(3, FORWARD)),
                            new Vector3d(0, 1, 0), heldAverage),
                    new ArticulatedServoSolver.RayGoal(1, 2, new Vector3d(1, 0, 0), FORWARD, held1),
                    new ArticulatedServoSolver.RayGoal(1, 3, new Vector3d(1, 0, 0), FORWARD, held2));

            StringBuilder report = new StringBuilder();
            boolean passed = true;
            long accepted = 0;
            Map<ArticulatedServoSolver.Failure, Long> rejections = new LinkedHashMap<>();
            double maxReaction = 0;
            double travel = 0;
            for (double speedDegrees : SPEEDS) {
                double peak1 = 0, peak2 = 0, anchorPeak = 0, omegaPeak = 0;
                for (int step = 0; step < 4 * hz; step++) {
                    Rapier3D.tick(scene, dt);
                    double commandedRate = Math.toRadians(speedDegrees);
                    Vector3d initialOmega = omega(scene, 1);
                    Rapier3D.addLinearAngularVelocities(scene, 1, 0, 0, 0,
                            0, commandedRate - initialOmega.y, 0, false);
                    List<ArticulatedServoPredictor.BodyState> state = capture(scene, bodies);
                    List<ArticulatedServoPredictor.HingeMotor> hinges = new ArrayList<>();
                    for (int index = 0; index < axes.size(); index++) {
                        Axis axis = axes.get(index);
                        Quaterniond parentQ = state.get(axis.parent).orientation();
                        Quaterniond childQ = state.get(axis.child).orientation();
                        double measured = BearingServoMath.jointAngle(new Quaterniond(parentQ).mul(axis.frame),
                                new Quaterniond(childQ).mul(axis.frame));
                        Vector3d actual = index == 0
                                ? state.get(2).orientation().transform(new Vector3d(FORWARD))
                                        .add(state.get(3).orientation().transform(new Vector3d(FORWARD))).normalize()
                                : state.get(axis.child).orientation().transform(new Vector3d(FORWARD));
                        Vector3d held = index == 0 ? heldAverage : index == 1 ? held1 : held2;
                        Vector3d worldAxis = parentQ.transform(new Vector3d(axis.localAxis));
                        double goal = BearingServoMath.wrapRadians(measured
                                + BearingServoMath.signedDirectionAngle(actual, held, worldAxis));
                        Quaterniond childFrame = axis.yaw
                                ? BearingServoMath.yawMotorFrame(axis.frame, measured) : new Quaterniond(axis.frame);
                        if (axis.yaw) axis.joint.hinge().setFrame2(axis.childAnchor, childFrame);
                        if (childOnly) axis.joint.driveFrame(new Quaterniond(parentQ).mul(axis.frame),childFrame,axis.childAnchor);
                        double reference = axis.yaw ? measured : 0;
                        double stabilizationRate = BearingServoMath.stabilizationRate(held, worldAxis,
                                state.get(axis.parent).angularVelocity());
                        // Match the production servo's hold feed-forward before asking the bounded
                        // assembly inverse to refine it. A bare zero-order position command needs
                        // over 0.15 rad correction at 360 deg/s and 40 Hz, outside that inverse's bounds.
                        double velocity = BearingServoMath.heldTargetVelocityRate(stabilizationRate, dt, axis.motor);
                        double command = BearingServoMath.servoGoal(BearingServoMath.wrapRadians(goal - reference),
                                stabilizationRate, velocity, dt, axis.motor);
                        if (childOnly) command=BearingServoMath.wrapRadians(goal-reference);
                        hinges.add(new ArticulatedServoPredictor.HingeMotor(axis.parent, axis.child,
                                axis.parentAnchor, axis.childAnchor, axis.frame, childFrame, command,
                                axis.motor.stiffness(), axis.motor.damping(), axis.motor.maxTorque(),
                                childOnly?index*2:index,childOnly,childOnly?index*2+1:Long.MAX_VALUE));
                    }
                    ArticulatedServoSolver.Result solved = childOnly
                            ? ArticulatedServoSolver.solveIndependent(state,hinges,null,worlds,GRAVITY,dt,goals,
                            new int[]{0,1,2},18,2,2,550,4,false)
                            : ArticulatedServoSolver.solve(state, hinges, null, worlds, GRAVITY, dt, goals);
                    if (solved.accepted()) accepted++;
                    else rejections.merge(solved.failure(), 1L, Long::sum);
                    double[] targets = solved.targets();
                    for (int index = 0; index < axes.size(); index++) {
                        axes.get(index).joint.drive(targets[index], axes.get(index).motor);
                    }
                    for (Body body : bodies) Rapier3D.wakeUpObject(scene, body.id);
                    Rapier3D.step(scene, dt);
                    peak1 = Math.max(peak1, rayError(orientation(scene, 3).transform(new Vector3d(FORWARD)), held1));
                    peak2 = Math.max(peak2, rayError(orientation(scene, 4).transform(new Vector3d(FORWARD)), held2));
                    for (Axis axis : axes) {
                        Vector3d a = orientation(scene, axis.parent + 1).transform(new Vector3d(axis.parentAnchor))
                                .add(position(scene, axis.parent + 1));
                        Vector3d b = orientation(scene, axis.child + 1).transform(new Vector3d(axis.childAnchor))
                                .add(position(scene, axis.child + 1));
                        anchorPeak = Math.max(anchorPeak, a.distance(b));
                        Vector3d linear = new Vector3d(), angular = new Vector3d();
                        axis.joint.getJointImpulses(linear, angular);
                        if (!linear.isFinite() || !angular.isFinite()) throw new AssertionError("Nonfinite hinge reaction");
                        maxReaction = Math.max(maxReaction, angular.length());
                    }
                    for (Body body : bodies) omegaPeak = Math.max(omegaPeak, omega(scene, body.id).length());
                    travel += omega(scene, 1).length() * dt;
                }
                boolean phasePassed = peak1 < RAY_LIMIT_DEGREES && peak2 < RAY_LIMIT_DEGREES
                        && anchorPeak < ANCHOR_LIMIT_BLOCKS && Double.isFinite(omegaPeak) && omegaPeak < 12;
                passed &= phasePassed;
                String line = String.format(Locale.ROOT,
                        "%s %dHz %.0f deg/s %s: rays %.9f/%.9f deg; anchor %.9f blocks; omega %.6f%n",
                        childOnly?"independent child drive":"legacy two-body",hz, speedDegrees, phasePassed ? "PASS" : "FAIL", peak1, peak2, anchorPeak, omegaPeak);
                report.append(line);
                System.out.print(line);
            }
            double barrelSeparation = rayError(orientation(scene, 3).transform(new Vector3d(FORWARD)),
                    orientation(scene, 4).transform(new Vector3d(FORWARD)));
            boolean reactionPassed = maxReaction > 1e-6 && travel > Math.PI * 4 && barrelSeparation > 15;
            passed &= reactionPassed;
            String line = String.format(Locale.ROOT,
                    "  %s: finite capped joint reaction max %.9f; travel %.6f rad; independent elevation %.6f deg; accepted=%d rejected=%s%n",
                    reactionPassed ? "PASS" : "FAIL", maxReaction, travel, barrelSeparation, accepted, rejections);
            report.append(line);
            System.out.print(line);
            return new Result(passed, report.toString());
        } finally {
            Rapier3D.dispose(scene);
        }
    }

    private static Matrix3d diagonal(double x, double y, double z) {
        return new Matrix3d(x, 0, 0, 0, y, 0, 0, 0, z);
    }

    private static Vector3d pivotPosition(Vector3d parentPosition, Quaterniond parentQ, Vector3d parentAnchor,
                                           Quaterniond childQ, Vector3d childAnchor) {
        return parentQ.transform(new Vector3d(parentAnchor)).add(parentPosition)
                .sub(childQ.transform(new Vector3d(childAnchor)));
    }

    private static Body body(long scene, int id, double mass, Matrix3d tensor,
                             Vector3d position, Quaterniond q) {
        Rapier3D.createSubLevel(scene, id, new double[]{position.x, position.y, position.z, q.x, q.y, q.z, q.w});
        Rapier3D.setCenterOfMass(scene, id, 0, 0, 0);
        Rapier3D.setLocalBounds(scene, id, -1, -1, -1, 1, 1, 1);
        Rapier3D.setMassProperties(scene, id, mass, new double[3], tensor.get(new double[9]));
        return new Body(id, mass, tensor);
    }

    private static Axis axis(long scene, int parent, int child, Vector3d parentAnchor,
                             Vector3d childAnchor, Quaterniond frame, Vector3d localAxis,
                             boolean yaw, BearingServoMath.Motor motor,boolean childOnly) {
        long handle = Rapier3D.addGenericConstraint(scene, parent + 1, child + 1,
                parentAnchor.x, parentAnchor.y, parentAnchor.z, frame.x, frame.y, frame.z, frame.w,
                childAnchor.x, childAnchor.y, childAnchor.z, frame.x, frame.y, frame.z, frame.w, 0b110111);
        RapierGenericConstraintHandle hinge=new RapierGenericConstraintHandle(scene,handle);
        NativeBearingJoint joint;
        if (childOnly) {
            hinge.setMotor(ConstraintJointAxis.ANGULAR_X,0,0,0,true,0);
            Quaterniond worldFrame=orientation(scene,parent+1).mul(frame);
            long actuator=Rapier3D.addGenericConstraint(scene,-1,child+1,0,0,0,worldFrame.x,worldFrame.y,worldFrame.z,worldFrame.w,
                    childAnchor.x,childAnchor.y,childAnchor.z,frame.x,frame.y,frame.z,frame.w,0);
            joint=new NativeBearingJoint(hinge,new RapierGenericConstraintHandle(scene,actuator));
        } else joint = new NativeBearingJoint(hinge);
        joint.setContactsEnabled(false);
        if (!yaw) joint.hinge().setLimit(ConstraintJointAxis.ANGULAR_X, -Math.PI / 2, Math.PI / 2);
        return new Axis(parent, child, parentAnchor, childAnchor, frame, localAxis, yaw, motor, joint);
    }

    private static List<ArticulatedServoPredictor.WorldMotor> worldSupport(int order) {
        double[] k = new double[6], d = new double[6], cap = new double[6];
        java.util.Arrays.fill(k, 3e7);
        java.util.Arrays.fill(d, 3e5);
        java.util.Arrays.fill(cap, Double.POSITIVE_INFINITY);
        return List.of(new ArticulatedServoPredictor.WorldMotor(0, new Quaterniond(), new Vector3d(), new Vector3d(),
                new double[3], new double[3], k, d, cap, 47, false, order));
    }

    private static List<ArticulatedServoPredictor.BodyState> capture(long scene, List<Body> bodies) {
        List<ArticulatedServoPredictor.BodyState> result = new ArrayList<>();
        for (Body body : bodies) {
            double[] linear = new double[3];
            Rapier3D.getLinearVelocity(scene, body.id, linear);
            result.add(new ArticulatedServoPredictor.BodyState(body.mass, body.tensor, position(scene, body.id),
                    orientation(scene, body.id), new Vector3d(linear), omega(scene, body.id)));
        }
        return result;
    }

    private static Vector3d position(long scene, int body) {
        double[] p = new double[7]; Rapier3D.getPose(scene, body, p); return new Vector3d(p[0], p[1], p[2]);
    }

    private static Quaterniond orientation(long scene, int body) {
        double[] p = new double[7]; Rapier3D.getPose(scene, body, p); return new Quaterniond(p[3], p[4], p[5], p[6]);
    }

    private static Vector3d omega(long scene, int body) {
        double[] w = new double[3]; Rapier3D.getAngularVelocity(scene, body, w); return new Vector3d(w);
    }

    private static double rayError(Vector3d actual, Vector3d held) {
        return Math.toDegrees(Math.acos(Math.clamp(actual.normalize().dot(new Vector3d(held).normalize()), -1, 1)));
    }

    private record Body(int id, double mass, Matrix3d tensor) {}
    private record Axis(int parent, int child, Vector3d parentAnchor, Vector3d childAnchor,
                        Quaterniond frame, Vector3d localAxis, boolean yaw,
                        BearingServoMath.Motor motor, NativeBearingJoint joint) {}
    private record Result(boolean passed, String report) {}
}
