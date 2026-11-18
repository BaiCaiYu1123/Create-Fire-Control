package com.hooya.stabilizedturret.content.bearing;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Predicts connected loads while allowing each feedback loop to drive only its own motor. */
public final class ArticulatedServoSolver {
    public static final int MAX_BODIES = ArticulatedServoPredictor.MAX_BODIES;
    private static final int MAX_AXES = 8;
    private static final int MAX_ITERATIONS = 3;
    private static final int MAX_DYNAMIC_ITERATIONS = 6;
    private static final double PROBE_RADIANS = 1.0e-4;
    private static final double RESIDUAL_TOLERANCE = 1.0e-5;
    private static final double STEP_CORRECTION_LIMIT = 0.05;
    private static final double TOTAL_CORRECTION_LIMIT = STEP_CORRECTION_LIMIT * MAX_ITERATIONS;

    private ArticulatedServoSolver() {}

    public record RaySource(int barrelIndex, Vector3d localRay) {
        public RaySource { localRay = new Vector3d(Objects.requireNonNull(localRay)); }
        @Override public Vector3d localRay() { return new Vector3d(localRay); }
    }

    /** Re-evaluates a finite aim solution using this trial's predicted physical bodies. */
    @FunctionalInterface
    public interface DynamicRayTarget {
        Vector3d direction(List<ArticulatedServoPredictor.BodyState> output);
    }

    /** A feedback observation; shared head-aim commands may average multiple firing rays. */
    public record RayGoal(int parentIndex, List<RaySource> sources, Vector3d localAxis,
                          Vector3d heldWorldRay, DynamicRayTarget dynamicTarget) {
        public RayGoal {
            sources = List.copyOf(Objects.requireNonNull(sources));
            localAxis = new Vector3d(Objects.requireNonNull(localAxis));
            heldWorldRay = new Vector3d(Objects.requireNonNull(heldWorldRay));
        }

        public RayGoal(int parentIndex, List<RaySource> sources, Vector3d localAxis,
                       Vector3d heldWorldRay) {
            this(parentIndex, sources, localAxis, heldWorldRay, null);
        }

        public RayGoal(int parentIndex, int barrelIndex, Vector3d localAxis, Vector3d localRay,
                       Vector3d heldWorldRay) {
            this(parentIndex, List.of(new RaySource(barrelIndex, localRay)), localAxis, heldWorldRay);
        }

        public RayGoal(int parentIndex, Vector3d localAxis, Vector3d heldWorldRay,
                       List<RaySource> sources) {
            this(parentIndex, sources, localAxis, heldWorldRay);
        }

        public RayGoal(int parentIndex, Vector3d localAxis, Vector3d heldWorldRay,
                       List<RaySource> sources, DynamicRayTarget dynamicTarget) {
            this(parentIndex, sources, localAxis, heldWorldRay, dynamicTarget);
        }

        @Override public Vector3d localAxis() { return new Vector3d(localAxis); }
        @Override public Vector3d heldWorldRay() { return new Vector3d(heldWorldRay); }
    }

    public enum Failure {
        NONE, INVALID_SNAPSHOT, DEGENERATE_RAY, INVALID_PREDICTION, SINGULAR_RESPONSE,
        SATURATED_RESPONSE, CORRECTION_LIMIT, DID_NOT_CONVERGE
    }

    /** A rejected result contains the caller's original targets for a safe fallback. */
    public record Result(boolean accepted, double[] targets, double maxResidualRadians,
                         int iterations, Failure failure) {
        public Result { targets = targets.clone(); }
        @Override public double[] targets() { return targets.clone(); }
    }

    public static Result solve(List<ArticulatedServoPredictor.BodyState> bodies,
                               List<ArticulatedServoPredictor.HingeMotor> hinges,
                               ArticulatedServoPredictor.StaffMotor staff, Vector3d gravity,
                               double dt, List<RayGoal> goals) {
        return solve(bodies, hinges, staff, List.of(), gravity, dt, goals, 18, 2, 2, 550, 4, false);
    }

    public static Result solve(List<ArticulatedServoPredictor.BodyState> bodies,
                               List<ArticulatedServoPredictor.HingeMotor> hinges,
                               ArticulatedServoPredictor.StaffMotor staff,
                               List<ArticulatedServoPredictor.WorldMotor> worlds, Vector3d gravity,
                               double dt, List<RayGoal> goals) {
        return solve(bodies, hinges, staff, worlds, gravity, dt, goals, 18, 2, 2, 550, 4, false);
    }

    /** Simulates every hinge while changing only the indices paired with the requested rays. */
    public static Result solve(List<ArticulatedServoPredictor.BodyState> bodies,
                               List<ArticulatedServoPredictor.HingeMotor> hinges,
                               ArticulatedServoPredictor.StaffMotor staff,
                               List<ArticulatedServoPredictor.WorldMotor> worlds, Vector3d gravity,
                               double dt, List<RayGoal> goals, int[] controlledHingeIndices) {
        return solve(bodies, hinges, staff, worlds, gravity, dt, goals, controlledHingeIndices,
                18, 2, 2, 550, 4, false);
    }

    public static Result solve(List<ArticulatedServoPredictor.BodyState> bodies,
                               List<ArticulatedServoPredictor.HingeMotor> hinges,
                               ArticulatedServoPredictor.StaffMotor staff, Vector3d gravity,
                               double dt, List<RayGoal> goals, int substeps, int pgs,
                               int stabilization, double jointFrequency, double jointDampingRatio,
                               boolean staffFirst) {
        return solve(bodies, hinges, staff, List.of(), gravity, dt, goals, substeps, pgs,
                stabilization, jointFrequency, jointDampingRatio, staffFirst);
    }

    public static Result solve(List<ArticulatedServoPredictor.BodyState> bodies,
                               List<ArticulatedServoPredictor.HingeMotor> hinges,
                               ArticulatedServoPredictor.StaffMotor staff,
                               List<ArticulatedServoPredictor.WorldMotor> worlds, Vector3d gravity,
                               double dt, List<RayGoal> goals, int substeps, int pgs,
                               int stabilization, double jointFrequency, double jointDampingRatio,
                               boolean staffFirst) {
        int[] allHinges = new int[hinges == null ? 0 : hinges.size()];
        for (int i = 0; i < allHinges.length; i++) allHinges[i] = i;
        return solve(bodies, hinges, staff, worlds, gravity, dt, goals, allHinges,
                substeps, pgs, stabilization, jointFrequency, jointDampingRatio, staffFirst);
    }

    /**
     * Unselected motors retain their original targets, including during each response probe.
     * The returned target array always has the full hinge list's size and order.
     */
    public static Result solve(List<ArticulatedServoPredictor.BodyState> bodies,
                               List<ArticulatedServoPredictor.HingeMotor> hinges,
                               ArticulatedServoPredictor.StaffMotor staff,
                               List<ArticulatedServoPredictor.WorldMotor> worlds, Vector3d gravity,
                               double dt, List<RayGoal> goals, int[] controlledHingeIndices,
                               int substeps, int pgs, int stabilization,
                               double jointFrequency, double jointDampingRatio, boolean staffFirst) {
        double[] original = originalTargets(hinges);
        if (bodies == null || hinges == null || worlds == null || goals == null || gravity == null
                || bodies.isEmpty() || bodies.size() > MAX_BODIES || hinges.isEmpty()
                || hinges.size() > ArticulatedServoPredictor.MAX_HINGES
                || controlledHingeIndices == null || controlledHingeIndices.length > MAX_AXES
                || !validIndices(controlledHingeIndices, hinges.size())
                || goals.size() != controlledHingeIndices.length
                || !gravity.isFinite() || !Double.isFinite(dt) || dt <= 0.0
                || !finite(original) || !validGoals(goals, bodies.size())) {
            return reject(original, Double.POSITIVE_INFINITY, 0, Failure.INVALID_SNAPSHOT);
        }
        int[] controlled = controlledHingeIndices.clone();
        // Finite ballistics depends on the trial's velocity as well as its pose.
        // Use more Newton refinements without increasing the bounded total correction.
        int maximumIterations = goals.stream().anyMatch(goal -> goal.dynamicTarget != null)
                ? MAX_DYNAMIC_ITERATIONS : MAX_ITERATIONS;

        double initialResidual = Double.POSITIVE_INFINITY;
        int iteration = 0;
        try {
            double[] targets = original.clone();
            Evaluation current = evaluate(bodies, hinges, staff, worlds, gravity, dt, goals, targets,
                    substeps, pgs, stabilization, jointFrequency, jointDampingRatio, staffFirst);
            initialResidual = current.maximum;
            if (current.maximum <= RESIDUAL_TOLERANCE) {
                return new Result(true, targets, current.maximum, 0, Failure.NONE);
            }

            for (; iteration < maximumIterations; iteration++) {
                int axes = controlled.length;
                double[][] response = new double[axes][axes];
                for (int motor = 0; motor < axes; motor++) {
                    double[] probe = targets.clone();
                    int hinge = controlled[motor];
                    probe[hinge] = BearingServoMath.wrapRadians(probe[hinge] + PROBE_RADIANS);
                    Evaluation changed = evaluate(bodies, hinges, staff, worlds, gravity, dt, goals, probe,
                            substeps, pgs, stabilization, jointFrequency, jointDampingRatio, staffFirst);
                    for (int ray = 0; ray < axes; ray++) {
                        response[ray][motor] = BearingServoMath.wrapRadians(
                                changed.residual[ray] - current.residual[ray]) / PROBE_RADIANS;
                    }
                }

                double[] rhs = new double[axes];
                for (int ray = 0; ray < axes; ray++) rhs[ray] = -current.residual[ray];
                double[] correction = solveResponse(response, rhs);
                if (correction == null) {
                    return reject(original, initialResidual, iteration, Failure.SINGULAR_RESPONSE);
                }

                double[] candidate = targets.clone();
                boolean atLimit = false;
                for (int motor = 0; motor < axes; motor++) {
                    int hinge = controlled[motor];
                    double increment = Math.clamp(correction[motor],
                            -STEP_CORRECTION_LIMIT, STEP_CORRECTION_LIMIT);
                    double total = BearingServoMath.wrapRadians(candidate[hinge] + increment - original[hinge]);
                    atLimit |= Math.abs(total) > TOTAL_CORRECTION_LIMIT + 1.0e-10;
                    candidate[hinge] = BearingServoMath.wrapRadians(original[hinge]
                            + Math.clamp(total, -TOTAL_CORRECTION_LIMIT, TOTAL_CORRECTION_LIMIT));
                }

                Evaluation next = evaluate(bodies, hinges, staff, worlds, gravity, dt, goals, candidate,
                        substeps, pgs, stabilization, jointFrequency, jointDampingRatio, staffFirst);
                if (next.maximum <= RESIDUAL_TOLERANCE) {
                    return new Result(true, candidate, next.maximum, iteration + 1, Failure.NONE);
                }
                if (!(next.maximum < current.maximum * 0.999)) {
                    return reject(original, initialResidual, iteration + 1,
                            atLimit ? Failure.CORRECTION_LIMIT : Failure.SATURATED_RESPONSE);
                }
                targets = candidate;
                current = next;
            }
            return reject(original, initialResidual, iteration, Failure.DID_NOT_CONVERGE);
        } catch (DegenerateRay ignored) {
            return reject(original, initialResidual, iteration, Failure.DEGENERATE_RAY);
        } catch (IllegalArgumentException | ArithmeticException ignored) {
            return reject(original, initialResidual, iteration, Failure.INVALID_PREDICTION);
        }
    }

    private record Evaluation(double[] residual, double maximum) {}

    /** Each feedback loop probes and corrects only its own actuator, never a sibling motor. */
    public static Result solveIndependent(List<ArticulatedServoPredictor.BodyState> bodies,
            List<ArticulatedServoPredictor.HingeMotor> hinges, ArticulatedServoPredictor.StaffMotor staff,
            List<ArticulatedServoPredictor.WorldMotor> worlds, Vector3d gravity, double dt,
            List<RayGoal> goals, int[] controlled, int substeps, int pgs, int stabilization,
            double frequency, double damping, boolean staffFirst) {
        double[] original = originalTargets(hinges), targets = original.clone();
        if (bodies == null || hinges == null || worlds == null || goals == null || gravity == null
                || bodies.isEmpty() || bodies.size() > MAX_BODIES || hinges.isEmpty()
                || hinges.size() > ArticulatedServoPredictor.MAX_HINGES || controlled == null || controlled.length > MAX_AXES
                || !validIndices(controlled, hinges.size()) || goals.size() != controlled.length
                || !gravity.isFinite() || !Double.isFinite(dt) || dt <= 0.0 || !finite(original)
                || !validGoals(goals,bodies.size())) {
            return reject(original, Double.POSITIVE_INFINITY, 0, Failure.INVALID_SNAPSHOT);
        }
        try {
            for (int pass = 0; pass < MAX_DYNAMIC_ITERATIONS; pass++) {
                Evaluation measured = evaluate(bodies, hinges, staff, worlds, gravity, dt, goals, targets,
                        substeps, pgs, stabilization, frequency, damping, staffFirst);
                if (measured.maximum <= RESIDUAL_TOLERANCE) return new Result(true, targets, measured.maximum, pass, Failure.NONE);
                for (int axis = 0; axis < controlled.length; axis++) {
                    List<ArticulatedServoPredictor.HingeMotor> trial = new ArrayList<>(hinges.size());
                    for (int i = 0; i < hinges.size(); i++) trial.add(hinges.get(i).withGoal(targets[i]));
                    Result own = solve(bodies, trial, staff, worlds, gravity, dt, List.of(goals.get(axis)),
                            new int[]{controlled[axis]}, substeps, pgs, stabilization, frequency, damping, staffFirst);
                    if (!own.accepted) return reject(original, measured.maximum, pass, own.failure);
                    int motor = controlled[axis];
                    double corrected = own.targets[motor];
                    if (Math.abs(BearingServoMath.wrapRadians(corrected - original[motor])) > TOTAL_CORRECTION_LIMIT + 1e-10) {
                        return reject(original, measured.maximum, pass, Failure.CORRECTION_LIMIT);
                    }
                    targets[motor] = corrected;
                }
            }
            Evaluation finalState = evaluate(bodies, hinges, staff, worlds, gravity, dt, goals, targets,
                    substeps, pgs, stabilization, frequency, damping, staffFirst);
            return finalState.maximum <= RESIDUAL_TOLERANCE
                    ? new Result(true, targets, finalState.maximum, MAX_DYNAMIC_ITERATIONS, Failure.NONE)
                    : reject(original, finalState.maximum, MAX_DYNAMIC_ITERATIONS, Failure.DID_NOT_CONVERGE);
        } catch (DegenerateRay ignored) {
            return reject(original, Double.POSITIVE_INFINITY, 0, Failure.DEGENERATE_RAY);
        } catch (IllegalArgumentException | ArithmeticException ignored) {
            return reject(original, Double.POSITIVE_INFINITY, 0, Failure.INVALID_PREDICTION);
        }
    }

    private static Evaluation evaluate(List<ArticulatedServoPredictor.BodyState> bodies,
                                       List<ArticulatedServoPredictor.HingeMotor> hinges,
                                       ArticulatedServoPredictor.StaffMotor staff,
                                       List<ArticulatedServoPredictor.WorldMotor> worlds, Vector3d gravity,
                                       double dt, List<RayGoal> goals, double[] targets,
                                       int substeps, int pgs, int stabilization,
                                       double jointFrequency, double jointDampingRatio, boolean staffFirst) {
        List<ArticulatedServoPredictor.HingeMotor> trial = new ArrayList<>(hinges.size());
        for (int i = 0; i < hinges.size(); i++) {
            var hinge = hinges.get(i);
            trial.add(hinge.withGoal(targets[i]));
        }
        var output = ArticulatedServoPredictor.predict(bodies, trial, staff, worlds, gravity, dt,
                substeps, pgs, stabilization, jointFrequency, jointDampingRatio, staffFirst).bodies();
        double[] residual = new double[goals.size()];
        double maximum = 0.0;
        for (int i = 0; i < goals.size(); i++) {
            RayGoal goal = goals.get(i);
            Quaterniond reference = goal.parentIndex < 0 ? new Quaterniond()
                    : output.get(goal.parentIndex).orientation();
            Vector3d axis = reference.transform(new Vector3d(goal.localAxis)).normalize();
            Vector3d ray = new Vector3d();
            for (RaySource source : goal.sources) {
                Vector3d outgoing = output.get(source.barrelIndex).orientation()
                        .transform(new Vector3d(source.localRay)).normalize();
                if (!outgoing.isFinite()) throw new DegenerateRay();
                ray.add(outgoing);
            }
            if (ray.lengthSquared() < 1.0e-12) throw new DegenerateRay();
            ray.normalize();
            Vector3d desired = goal.dynamicTarget == null ? goal.heldWorldRay
                    : goal.dynamicTarget.direction(output);
            if (desired == null || !desired.isFinite() || desired.lengthSquared() < 1.0e-12) {
                throw new DegenerateRay();
            }
            Vector3d held = new Vector3d(desired).normalize();
            if (!axis.isFinite() || !ray.isFinite() || !held.isFinite()
                    || 1.0 - Math.pow(ray.dot(axis), 2) < 1.0e-8
                    || 1.0 - Math.pow(held.dot(axis), 2) < 1.0e-8) {
                throw new DegenerateRay();
            }
            residual[i] = BearingServoMath.signedDirectionAngle(ray, held, axis);
            if (!Double.isFinite(residual[i])) throw new ArithmeticException("Non-finite ray residual");
            maximum = Math.max(maximum, Math.abs(residual[i]));
        }
        return new Evaluation(residual, maximum);
    }

    private static double[] solveResponse(double[][] matrix, double[] rhs) {
        int size = rhs.length;
        double largest = 0.0;
        for (double[] row : matrix) for (double value : row) {
            if (!Double.isFinite(value)) return null;
            largest = Math.max(largest, Math.abs(value));
        }
        if (largest < 1.0e-6 || !finite(rhs)) return null;
        double minimumPivot = largest * 1.0e-7;
        for (int column = 0; column < size; column++) {
            int pivot = column;
            for (int row = column + 1; row < size; row++) {
                if (Math.abs(matrix[row][column]) > Math.abs(matrix[pivot][column])) pivot = row;
            }
            double divisor = matrix[pivot][column];
            if (!Double.isFinite(divisor) || Math.abs(divisor) < minimumPivot) return null;
            double[] swap = matrix[column];
            matrix[column] = matrix[pivot];
            matrix[pivot] = swap;
            double value = rhs[column];
            rhs[column] = rhs[pivot];
            rhs[pivot] = value;
            for (int j = column; j < size; j++) matrix[column][j] /= divisor;
            rhs[column] /= divisor;
            for (int row = 0; row < size; row++) if (row != column) {
                double coefficient = matrix[row][column];
                for (int j = column; j < size; j++) matrix[row][j] -= coefficient * matrix[column][j];
                rhs[row] -= coefficient * rhs[column];
            }
        }
        return finite(rhs) ? rhs : null;
    }

    private static boolean validGoals(List<RayGoal> goals, int bodies) {
        for (RayGoal goal : goals) {
            if (goal == null || goal.parentIndex < -1 || goal.parentIndex >= bodies
                    || !goal.localAxis.isFinite() || goal.localAxis.lengthSquared() < 1.0e-12
                    || goal.sources.isEmpty()
                    || !goal.heldWorldRay.isFinite() || goal.heldWorldRay.lengthSquared() < 1.0e-12) return false;
            for (RaySource source : goal.sources) {
                if (source == null || source.barrelIndex < 0 || source.barrelIndex >= bodies
                        || !source.localRay.isFinite() || source.localRay.lengthSquared() < 1.0e-12) return false;
            }
        }
        return true;
    }

    private static boolean validIndices(int[] indices, int hinges) {
        if (indices == null || indices.length == 0 || indices.length > MAX_AXES) return false;
        boolean[] used = new boolean[hinges];
        for (int index : indices) {
            if (index < 0 || index >= hinges || used[index]) return false;
            used[index] = true;
        }
        return true;
    }

    private static double[] originalTargets(List<ArticulatedServoPredictor.HingeMotor> hinges) {
        if (hinges == null) return new double[0];
        double[] targets = new double[hinges.size()];
        for (int i = 0; i < targets.length; i++) {
            var hinge = hinges.get(i);
            targets[i] = hinge == null ? Double.NaN : hinge.goal();
        }
        return targets;
    }

    private static boolean finite(double[] values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static Result reject(double[] original, double residual, int iterations, Failure failure) {
        return new Result(false, original, residual, iterations, failure);
    }

    private static final class DegenerateRay extends RuntimeException {}
}
