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
import java.util.Locale;

/** A held turret keeps its world ray while a sibling's original aiming motor stays unchanged. */
public final class NativeArticulatedServoSubsetChecks {
    private static final Path REPORT = Path.of("bearing-articulated-subset-result.txt");
    private static final Quaterniond FRAME = BearingServoMath.hingeFrame(new Quaterniond(), 0);
    private static final Vector3d FORWARD = new Vector3d(0, 0, 1);
    private static int cases;
    private static double peakHeldError, peakPredictionError;

    public static void main(String[] args) throws Exception {
        Files.writeString(REPORT, "RUNNING\n");
        try {
            for (int hz : new int[]{40, 120}) {
                for (int selected : new int[]{0, 1}) {
                    for (double aimingStep : new double[]{-0.045, 0.045}) {
                        for (boolean supportFirst : new boolean[]{false, true}) {
                            run(hz, selected, aimingStep, supportFirst);
                            run(hz, selected, aimingStep, supportFirst,true);
                        }
                    }
                }
            }
            String result = String.format(Locale.ROOT,
                    "PASS: %d native subset cases; held ray peak %.9f degrees; prediction peak %.9f degrees%n",
                    cases, peakHeldError, peakPredictionError);
            Files.writeString(REPORT, result);
            System.out.print(result);
        } catch (Throwable failure) {
            Files.writeString(REPORT, "FAIL\n" + failure + "\n");
            throw failure;
        }
    }

    private static void run(int hz, int selected, double aimingStep, boolean supportFirst) {
        run(hz,selected,aimingStep,supportFirst,false);
    }

    private static void run(int hz,int selected,double aimingStep,boolean supportFirst,boolean childOnly) {
        double dt = 1.0 / hz;
        long scene = Rapier3D.initialize(0, 0, 0, 0);
        try {
            Rapier3D.configFrequencyAndDamping(40, 5);
            Rapier3D.configSolverIterations(18, 2, 2);
            Rapier3D.configMinIslandSize(128);
            List<ArticulatedServoPredictor.BodyState> bodies = new ArrayList<>();
            bodies.add(new ArticulatedServoPredictor.BodyState(7.5,
                    new Matrix3d().scaling(3.125, 12.5, 10.625), new Vector3d(),
                    new Quaterniond(), new Vector3d(), new Vector3d()));
            Rapier3D.createBox(scene, 1, 7.5, 2, 0.5, 1, pose(new Vector3d(), new Quaterniond()));
            double[] initialYaw = {0.5, -0.4};
            double[] original = {initialYaw[0], initialYaw[1]};
            original[1 - selected] += aimingStep;
            Vector3d childAnchor = new Vector3d(0, 0.2, 0);
            // Collinear anchors isolate the controlled yaw response from locked-axis sag.
            Vector3d[] parentAnchors = {new Vector3d(0, 1.2, 0), new Vector3d(0, -1.2, 0)};
            for (int i = 0; i < 2; i++) {
                Quaterniond rotation = new Quaterniond().rotationY(initialYaw[i]);
                Vector3d position = new Vector3d(parentAnchors[i])
                        .sub(rotation.transform(new Vector3d(childAnchor)));
                bodies.add(new ArticulatedServoPredictor.BodyState(3,
                        new Matrix3d().scaling(0.5, 0.5, 0.5), position, rotation,
                        new Vector3d(), new Vector3d()));
                Rapier3D.createBox(scene, i + 2, 3, 0.5, 0.5, 0.5, pose(position, rotation));
            }
            Quaterniond supportFrame = new Quaterniond().rotationY(0.03);
            List<ArticulatedServoPredictor.HingeMotor> hinges = new ArrayList<>();
            long[] handles = new long[2];
            long rowOrder = 0;
            if (supportFirst) createSupport(scene, supportFrame);
            for (int i = 0; i < 2; i++) {
                Vector3d parentAnchor = parentAnchors[i];
                handles[i] = Rapier3D.addGenericConstraint(scene, 1, i + 2,
                        parentAnchor.x, parentAnchor.y, parentAnchor.z, FRAME.x, FRAME.y, FRAME.z, FRAME.w,
                        childAnchor.x, childAnchor.y, childAnchor.z, FRAME.x, FRAME.y, FRAME.z, FRAME.w, 0b110111);
                Rapier3D.setConstraintContactsEnabled(scene, handles[i], false);
                long hingeOrder = rowOrder++ + (supportFirst ? 1 : 0);
                long driveOrder = Long.MAX_VALUE;
                if (childOnly) {
                    Rapier3D.setConstraintMotor(scene,handles[i],3,0,0,0,true,0);
                    handles[i]=Rapier3D.addGenericConstraint(scene,-1,i+2,0,0,0,FRAME.x,FRAME.y,FRAME.z,FRAME.w,
                            childAnchor.x,childAnchor.y,childAnchor.z,FRAME.x,FRAME.y,FRAME.z,FRAME.w,0);
                    driveOrder=rowOrder+++(supportFirst?1:0);
                }
                hinges.add(new ArticulatedServoPredictor.HingeMotor(0, i + 1, parentAnchor, childAnchor,
                        FRAME, FRAME, original[i], 120000, 1000, 10000, hingeOrder,childOnly,driveOrder));
            }
            if (!supportFirst) createSupport(scene, supportFrame);
            var support = new ArticulatedServoPredictor.WorldMotor(0, supportFrame, new Vector3d(),
                    new Vector3d(), new double[3], new double[3],
                    new double[]{2650, 2650, 2650, 10000, 10000, 10000},
                    new double[]{125, 125, 125, 850, 850, 850},
                    new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
                    63, supportFirst, supportFirst ? 0 : rowOrder);
            Vector3d held = bodies.get(selected + 1).orientation().transform(new Vector3d(FORWARD));
            var goal = new ArticulatedServoSolver.RayGoal(0, selected + 1, new Vector3d(0, 1, 0),
                    FORWARD, held);
            var solved = childOnly
                    ? ArticulatedServoSolver.solveIndependent(bodies,hinges,null,List.of(support),new Vector3d(),dt,
                    List.of(goal),new int[]{selected},18,2,2,550,4,false)
                    : ArticulatedServoSolver.solve(bodies, hinges, null, List.of(support),
                    new Vector3d(), dt, List.of(goal), new int[]{selected});
            require(solved.accepted(), "subset solve rejected: " + solved.failure());
            double[] targets = solved.targets();
            require(targets.length == 2, "subset result omitted the fixed motor");
            require(Double.doubleToLongBits(targets[1 - selected]) == Double.doubleToLongBits(original[1 - selected]),
                    "subset solver modified the sibling aiming target");
            // A world actuator can hold yaw without changing its target when the
            // parent turns about the same world axis. Two-body motors must compensate.
            if (!childOnly) require(Math.abs(BearingServoMath.wrapRadians(targets[selected] - original[selected])) > 1e-5,
                    "two-body held motor did not compensate the moving carrier");
            checkRejectedInputs(bodies, hinges, support, goal, dt, original);
            checkFullSelectionCompatibility(bodies, hinges, support, dt);
            double[] defensive = solved.targets();
            defensive[1 - selected] = 123;
            require(Arrays.equals(targets, solved.targets()), "result exposes a mutable target array");

            List<ArticulatedServoPredictor.HingeMotor> driven = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Rapier3D.setConstraintMotor(scene, handles[i], 3, targets[i], 120000, 1000, true, 10000);
                driven.add(hinges.get(i).withGoal(targets[i]));
            }
            var expected = ArticulatedServoPredictor.predict(bodies, driven, null, List.of(support),
                    new Vector3d(), dt, 18, 2, 2, 550, 4, false);
            Rapier3D.step(scene, dt);
            double[] actualPose = new double[7];
            for (int i = 0; i < 3; i++) {
                Rapier3D.getPose(scene, i + 1, actualPose);
                Quaterniond actual = new Quaterniond(actualPose[3], actualPose[4], actualPose[5], actualPose[6]);
                double predictionError = rotationError(actual, expected.bodies().get(i).orientation());
                peakPredictionError = Math.max(peakPredictionError, predictionError);
                require(predictionError < 0.001, "subset native prediction error " + predictionError);
                if (i == selected + 1) {
                    Vector3d ray = actual.normalize().transform(new Vector3d(FORWARD));
                    double heldError = Math.toDegrees(Math.acos(Math.clamp(ray.dot(held), -1, 1)));
                    peakHeldError = Math.max(peakHeldError, heldError);
                    require(heldError < 0.001, "held sibling lost its world ray: " + heldError);
                }
                if (i == 2 - selected) {
                    Vector3d ray = actual.normalize().transform(new Vector3d(FORWARD));
                    Vector3d before = bodies.get(i).orientation().transform(new Vector3d(FORWARD));
                    require(Math.toDegrees(Math.acos(Math.clamp(ray.dot(before), -1, 1))) > 0.05,
                            "sibling aiming motor was prevented from turning");
                }
            }
            cases++;
        } finally {
            Rapier3D.dispose(scene);
        }
    }

    private static void checkRejectedInputs(List<ArticulatedServoPredictor.BodyState> bodies,
                                             List<ArticulatedServoPredictor.HingeMotor> hinges,
                                             ArticulatedServoPredictor.WorldMotor support,
                                             ArticulatedServoSolver.RayGoal goal, double dt, double[] original) {
        for (int[] invalid : new int[][]{null, {}, {-1}, {2}, {0, 0}}) {
            List<ArticulatedServoSolver.RayGoal> goals = invalid != null && invalid.length == 2
                    ? List.of(goal, goal) : List.of(goal);
            var result = ArticulatedServoSolver.solve(bodies, hinges, null, List.of(support),
                    new Vector3d(), dt, goals, invalid);
            require(!result.accepted() && result.failure() == ArticulatedServoSolver.Failure.INVALID_SNAPSHOT
                    && Arrays.equals(original, result.targets()), "invalid subset changed original targets");
            var independent = ArticulatedServoSolver.solveIndependent(bodies,hinges,null,List.of(support),
                    new Vector3d(),dt,goals,invalid,18,2,2,550,4,false);
            require(!independent.accepted() && independent.failure()==ArticulatedServoSolver.Failure.INVALID_SNAPSHOT
                    && Arrays.equals(original,independent.targets()),"invalid independent subset changed original targets");
        }
        var mismatch = ArticulatedServoSolver.solve(bodies, hinges, null, List.of(support),
                new Vector3d(), dt, List.of(goal), new int[]{0, 1});
        require(!mismatch.accepted() && Arrays.equals(original, mismatch.targets()),
                "mismatched ray count changed original targets");
    }

    private static void checkFullSelectionCompatibility(List<ArticulatedServoPredictor.BodyState> bodies,
                                                        List<ArticulatedServoPredictor.HingeMotor> hinges,
                                                        ArticulatedServoPredictor.WorldMotor support, double dt) {
        List<ArticulatedServoSolver.RayGoal> goals = new ArrayList<>();
        for (int child = 1; child <= 2; child++) {
            goals.add(new ArticulatedServoSolver.RayGoal(0, child, new Vector3d(0, 1, 0), FORWARD,
                    bodies.get(child).orientation().transform(new Vector3d(FORWARD))));
        }
        var previousApi = ArticulatedServoSolver.solve(bodies, hinges, null, List.of(support),
                new Vector3d(), dt, goals);
        var identity = ArticulatedServoSolver.solve(bodies, hinges, null, List.of(support),
                new Vector3d(), dt, goals, new int[]{0, 1});
        require(previousApi.accepted() && identity.accepted()
                && Arrays.equals(previousApi.targets(), identity.targets()),
                "full-selection overload changed the previous API");
        var reversed = ArticulatedServoSolver.solve(bodies, hinges, null, List.of(support),
                new Vector3d(), dt, List.of(goals.get(1), goals.get(0)), new int[]{1, 0});
        require(reversed.accepted(), "reordered selection was rejected: " + reversed.failure());
        double[] expected = identity.targets(), actual = reversed.targets();
        for (int i = 0; i < expected.length; i++) {
            require(Math.abs(BearingServoMath.wrapRadians(expected[i] - actual[i])) < 1e-9,
                    "selection order changed full target indexing");
        }
    }

    private static void createSupport(long scene, Quaterniond frame) {
        long handle = Rapier3D.addFreeConstraint(scene, -1, 1, 0, 0, 0, 0, 0, 0,
                frame.x, frame.y, frame.z, frame.w);
        for (int axis = 0; axis < 6; axis++) {
            Rapier3D.setConstraintMotor(scene, handle, axis, 0, axis < 3 ? 2650 : 10000,
                    axis < 3 ? 125 : 850, false, 0);
        }
    }

    private static double rotationError(Quaterniond actual, Quaterniond expected) {
        Quaterniond delta = new Quaterniond(actual).normalize().mul(new Quaterniond(expected).conjugate()).normalize();
        return Math.toDegrees(2 * Math.atan2(Math.sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z),
                Math.abs(delta.w)));
    }

    private static double[] pose(Vector3d position, Quaterniond orientation) {
        return new double[]{position.x, position.y, position.z,
                orientation.x, orientation.y, orientation.z, orientation.w};
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
