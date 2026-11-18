package com.hooya.stabilizedturret.content.bearing;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;

/** Checks trial-dependent targets without a game world or a native physics mutation. */
public final class ArticulatedDynamicRayChecks {
    private ArticulatedDynamicRayChecks() {}

    public static void main(String[] args) {
        AimCompensationChecks.run();
        carrierPredictionSettlesAfterStopping();
        constantCallbackPreservesStaticTargets();
        responseProbesRecomputeTheirDesiredRay();
        nonlinearTargetNeedsAdditionalRefinements();
        additionalRefinementsKeepTheTotalCorrectionBound();
        invalidCallbackRetainsOriginalTargets();
        System.out.println("PASS: articulated dynamic targets, per-trial response and safe fallback");
    }

    private static void carrierPredictionSettlesAfterStopping() {
        var prediction = new BearingServoMath.MotorTarget();
        require(!prediction.hasCarrierPrediction(1e-3), "a fresh motor has no carrier recovery");
        prediction.predictHeldRate(0, 2.1, 0, 0, .025);
        require(prediction.hasCarrierPrediction(1e-3), "a moving carrier needs joint prediction");
        prediction.predictHeldRate(0, 0, 0, 0, .025);
        require(prediction.hasCarrierPrediction(1e-3), "zero real velocity does not erase the stopping tail");
        for (int step = 0; step < 240; step++) prediction.predictHeldRate(0, 0, 0, 0, .025);
        require(!prediction.hasCarrierPrediction(1e-3), "a settled carrier can return to native sleep");
        prediction.reset();
        double operatorRate = prediction.predictHeldRate(0, 2.1, 2.1, 2.1, .025);
        require(Math.abs(operatorRate - 2.1) < 1e-12 && !prediction.hasCarrierPrediction(1e-3),
                "pure operator traverse retains its speed without creating carrier recovery");
        prediction.reset();
        require(!prediction.hasCarrierPrediction(1e-3), "a reset discards obsolete carrier history");
    }

    private static void constantCallbackPreservesStaticTargets() {
        Vector3d desired = new Quaterniond().rotationY(0.02).transform(new Vector3d(0, 0, 1));
        var ordinary = solve(goal(desired, null));
        var dynamic = solve(goal(desired, output -> new Vector3d(desired)));
        require(ordinary.accepted() && dynamic.accepted(), "constant targets converge");
        require(Double.doubleToLongBits(ordinary.targets()[0]) == Double.doubleToLongBits(dynamic.targets()[0]),
                "constant callback keeps the existing solver bitwise target");
        require(Double.doubleToLongBits(ordinary.maxResidualRadians())
                        == Double.doubleToLongBits(dynamic.maxResidualRadians()),
                "constant callback keeps the existing solver residual");
    }

    private static void responseProbesRecomputeTheirDesiredRay() {
        int[] calls = {0};
        double[] firstAndChanged = {Double.NaN, Double.NaN};
        var result = solve(goal(new Vector3d(0, 0, 1), output -> {
            calls[0]++;
            double angularVelocity = output.getFirst().angularVelocity().y;
            if (Double.isNaN(firstAndChanged[0])) firstAndChanged[0] = angularVelocity;
            else if (Math.abs(angularVelocity - firstAndChanged[0]) > 1.0e-8) {
                firstAndChanged[1] = angularVelocity;
            }
            return new Quaterniond().rotationY(0.02 + angularVelocity * 0.005)
                    .transform(new Vector3d(0, 0, 1));
        }));
        require(result.accepted(), "trial-dependent target converges: " + result.failure());
        require(calls[0] > 2 && Double.isFinite(firstAndChanged[1]),
                "each probe observes its own predicted velocity");
        var output = ArticulatedServoPredictor.predict(bodies(), List.of(hinge().withGoal(result.targets()[0])),
                null, new Vector3d(), 0.025, 18, 2, 2, 550, 4, false).bodies();
        Vector3d ray = output.getFirst().orientation().transform(new Vector3d(0, 0, 1));
        Vector3d desired = new Quaterniond().rotationY(0.02 + output.getFirst().angularVelocity().y * 0.005)
                .transform(new Vector3d(0, 0, 1));
        require(Math.abs(BearingServoMath.signedDirectionAngle(ray, desired, new Vector3d(0, 1, 0))) < 1.0e-5,
                "final target satisfies the final predicted physical state");
    }

    private static void invalidCallbackRetainsOriginalTargets() {
        for (var invalid : List.<ArticulatedServoSolver.DynamicRayTarget>of(
                output -> null, output -> new Vector3d(), output -> new Vector3d(Double.NaN, 0, 1))) {
            var result = solve(goal(new Vector3d(0, 0, 1), invalid));
            require(!result.accepted() && result.failure() == ArticulatedServoSolver.Failure.DEGENERATE_RAY,
                    "invalid target rejects the optional prediction");
            require(Double.doubleToLongBits(result.targets()[0]) == Double.doubleToLongBits(hinge().goal()),
                    "rejected target leaves the original motor command intact");
        }
    }

    private static void nonlinearTargetNeedsAdditionalRefinements() {
        final double root = 0.13;
        var result = solve(goal(new Vector3d(0, 0, 1), output -> {
            Vector3d outgoing = output.getFirst().orientation().transform(new Vector3d(0, 0, 1));
            double angle = Math.atan2(outgoing.x, outgoing.z);
            // A flat response near the target models the curvature that appears when a
            // velocity-dependent finite solution changes along with the motor's response.
            double miss = angle - root;
            return new Quaterniond().rotationY(angle - miss * miss * miss)
                    .transform(new Vector3d(0, 0, 1));
        }));
        require(result.accepted(), "nonlinear dynamic target converges: " + result.failure());
        require(result.iterations() > 3 && result.iterations() <= 6,
                "nonlinear response exercises the additional dynamic refinements: " + result.iterations());
        require(result.maxResidualRadians() <= 1.0e-5,
                "additional iterations retain the same strict residual tolerance");
        require(Math.abs(BearingServoMath.wrapRadians(result.targets()[0] - hinge().goal())) <= 0.15 + 1e-10,
                "accepted nonlinear target remains inside the original total correction bound");
    }

    private static void additionalRefinementsKeepTheTotalCorrectionBound() {
        double[] largestPredictedAngle = {0};
        var result = solve(goal(new Vector3d(0, 0, 1), output -> {
            Vector3d outgoing = output.getFirst().orientation().transform(new Vector3d(0, 0, 1));
            largestPredictedAngle[0] = Math.max(largestPredictedAngle[0], Math.abs(Math.atan2(outgoing.x, outgoing.z)));
            return new Quaterniond().rotationY(0.3).transform(new Vector3d(0, 0, 1));
        }));
        require(!result.accepted() && result.failure() == ArticulatedServoSolver.Failure.CORRECTION_LIMIT,
                "a dynamic target outside the old total bound still rejects: " + result.failure());
        require(largestPredictedAngle[0] < 0.16,
                "additional iterations cannot double the previous total allowed motor correction");
        require(Double.doubleToLongBits(result.targets()[0]) == Double.doubleToLongBits(hinge().goal()),
                "bounded rejection preserves the original motor command bitwise");
    }

    private static ArticulatedServoSolver.RayGoal goal(Vector3d ray,
                                                      ArticulatedServoSolver.DynamicRayTarget callback) {
        return new ArticulatedServoSolver.RayGoal(-1,
                List.of(new ArticulatedServoSolver.RaySource(0, new Vector3d(0, 0, 1))),
                new Vector3d(0, 1, 0), ray, callback);
    }

    private static ArticulatedServoSolver.Result solve(ArticulatedServoSolver.RayGoal goal) {
        return ArticulatedServoSolver.solve(bodies(), List.of(hinge()), null, new Vector3d(),
                0.025, List.of(goal));
    }

    private static List<ArticulatedServoPredictor.BodyState> bodies() {
        return List.of(new ArticulatedServoPredictor.BodyState(100, new Matrix3d().scaling(1000),
                new Vector3d(), new Quaterniond(), new Vector3d(), new Vector3d(0, 2.1, 0)));
    }

    private static ArticulatedServoPredictor.HingeMotor hinge() {
        Quaterniond frame = new Quaterniond().rotationZ(Math.PI / 2);
        return new ArticulatedServoPredictor.HingeMotor(-1, 0, new Vector3d(), new Vector3d(),
                frame, frame, 0, 1.0e9, 1.0e7, Double.POSITIVE_INFINITY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
