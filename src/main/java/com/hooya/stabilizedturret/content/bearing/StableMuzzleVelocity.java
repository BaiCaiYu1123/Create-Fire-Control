package com.hooya.stabilizedturret.content.bearing;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;
import java.util.Objects;

/** Nominal muzzle transport when controlled joints keep the barrel's world ray fixed. */
public final class StableMuzzleVelocity {
    private static final int MAX_JOINTS = 8;
    private static final double MIN_LENGTH_SQUARED = 1.0e-12;
    private static final double RANK_TOLERANCE = 1.0e-12;
    private static final double REACHABLE_TOLERANCE = 1.0e-8;

    private StableMuzzleVelocity() {}

    /** Local hinge frames with pivots measured from the predicted child's COM. */
    public record JointFrame(int parent, int child, Vector3d localAxis, Vector3d localPivot) {
        public JointFrame {
            localAxis = new Vector3d(localAxis);
            localPivot = new Vector3d(localPivot);
        }
    }

    /** Uses the same holding kinematics in every trial, never that trial's operator speed. */
    public record Prediction(int carrier, List<JointFrame> frames) {
        public Prediction { frames = List.copyOf(frames); }
        public @Nullable Vector3d velocity(List<ArticulatedServoPredictor.BodyState> bodies,
                                           Vector3d muzzle, Vector3d ray) {
            return velocity(bodies, bodies.get(carrier), muzzle, ray);
        }

        /**
         * Forecast hinge geometry, but inherit the carrier's measured motion. The joint-only
         * predictor has no terrain contacts, wheels or support forces; its trial acceleration
         * must not become a fictitious launch velocity on a supported, stopped vehicle.
         */
        public @Nullable Vector3d velocity(List<ArticulatedServoPredictor.BodyState> bodies,
                                           ArticulatedServoPredictor.BodyState measuredCarrier,
                                           Vector3d muzzle, Vector3d ray) {
            var base = bodies.get(carrier);
            Vector3d omega = measuredCarrier.angularVelocity();
            Vector3d transported = omega.cross(new Vector3d(muzzle).sub(base.position()), new Vector3d())
                    .add(measuredCarrier.linearVelocity());
            List<Joint> joints = new java.util.ArrayList<>();
            for (var frame : frames) {
                var parent = bodies.get(frame.parent());
                var child = bodies.get(frame.child());
                joints.add(new Joint(parent.orientation().transform(new Vector3d(frame.localAxis())),
                        child.orientation().transform(new Vector3d(frame.localPivot())).add(child.position())));
            }
            return sample(transported,omega,muzzle,ray,joints);
        }
    }

    /** World coordinates; the axis is normalized, and the pivot is a physical world point. */
    public record Joint(Vector3d worldAxis, Vector3d worldPivot) {
        public Joint {
            worldAxis = new Vector3d(Objects.requireNonNull(worldAxis));
            worldPivot = new Vector3d(Objects.requireNonNull(worldPivot));
            if (!worldAxis.isFinite() || worldAxis.lengthSquared() < MIN_LENGTH_SQUARED
                    || !worldPivot.isFinite()) throw new IllegalArgumentException("Invalid stable muzzle joint");
            worldAxis.normalize();
        }
        @Override public Vector3d worldAxis() { return new Vector3d(worldAxis); }
        @Override public Vector3d worldPivot() { return new Vector3d(worldPivot); }
    }

    /**
     * Velocities use blocks/second and radians/second. The carrier velocity is evaluated at
     * the muzzle as if it were rigidly attached to that carrier. Joint counterrotation then
     * removes only motion needed to hold the ray; translation, offset pivots and roll remain.
     * No actual or requested operator traverse rate enters this calculation.
     */
    public static @Nullable Vector3d sample(Vector3d carrierVelocityAtMuzzle, Vector3d carrierOmega,
                                            Vector3d worldMuzzle, Vector3d heldWorldRay,
                                            List<Joint> joints) {
        if (!valid(carrierVelocityAtMuzzle) || !valid(carrierOmega) || !valid(worldMuzzle)
                || !valid(heldWorldRay) || heldWorldRay.lengthSquared() < MIN_LENGTH_SQUARED
                || joints == null || joints.size() > MAX_JOINTS) return null;
        Vector3d ray = new Vector3d(heldWorldRay).normalize();
        Vector3d tangentU = ray.cross(Math.abs(ray.y) < 0.9
                ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0), new Vector3d()).normalize();
        Vector3d tangentV = ray.cross(tangentU, new Vector3d()).normalize();
        Vector3d correction = carrierOmega.cross(ray, new Vector3d()).negate();
        double b0 = correction.dot(tangentU), b1 = correction.dot(tangentV);
        double[][] columns = new double[joints.size()][2];
        double g00 = 0, g01 = 0, g11 = 0;
        for (int i = 0; i < joints.size(); i++) {
            Joint joint = joints.get(i);
            if (joint == null) return null;
            Vector3d tangent = joint.worldAxis.cross(ray, new Vector3d());
            double a0 = tangent.dot(tangentU), a1 = tangent.dot(tangentV);
            columns[i][0] = a0; columns[i][1] = a1;
            g00 += a0 * a0; g01 += a0 * a1; g11 += a1 * a1;
        }
        // Solve (A A^T) x = b in the ray's two-dimensional tangent plane; q = A^T x
        // is the minimum-norm joint counterrotation, independent of joint enumeration order.
        double largest = 0.5 * (g00 + g11 + Math.hypot(g00 - g11, 2 * g01));
        double determinant = g00 * g11 - g01 * g01;
        double x0, x1;
        if (largest < MIN_LENGTH_SQUARED) {
            if (Math.hypot(b0, b1) > REACHABLE_TOLERANCE) return null;
            x0 = 0; x1 = 0;
        } else if (determinant > largest * largest * RANK_TOLERANCE) {
            x0 = (g11 * b0 - g01 * b1) / determinant;
            x1 = (g00 * b1 - g01 * b0) / determinant;
        } else {
            // A single effective axis is sufficient only when the required ray correction
            // lies on that axis's tangent. An unreachable direction safely rejects the model.
            double e0, e1;
            if (Math.abs(g01) > MIN_LENGTH_SQUARED) {
                e0 = g01; e1 = largest - g00;
                double length = Math.hypot(e0, e1);
                e0 /= length; e1 /= length;
            } else {
                e0 = g00 >= g11 ? 1 : 0;
                e1 = g00 >= g11 ? 0 : 1;
            }
            double projected = e0 * b0 + e1 * b1;
            if (Math.hypot(b0 - projected * e0, b1 - projected * e1)
                    > REACHABLE_TOLERANCE * Math.max(1.0, Math.hypot(b0, b1))) return null;
            x0 = e0 * projected / largest;
            x1 = e1 * projected / largest;
        }
        Vector3d velocity = new Vector3d(carrierVelocityAtMuzzle);
        Vector3d remainingRayVelocity = new Vector3d(correction).negate();
        for (int i = 0; i < joints.size(); i++) {
            Joint joint = joints.get(i);
            double rate = columns[i][0] * x0 + columns[i][1] * x1;
            if (!Double.isFinite(rate)) return null;
            Vector3d angular = new Vector3d(joint.worldAxis).mul(rate);
            velocity.add(angular.cross(new Vector3d(worldMuzzle).sub(joint.worldPivot), new Vector3d()));
            remainingRayVelocity.add(angular.cross(ray, new Vector3d()));
        }
        if (!velocity.isFinite() || !remainingRayVelocity.isFinite()
                || remainingRayVelocity.length() > REACHABLE_TOLERANCE
                * Math.max(1.0, correction.length())) return null;
        return velocity;
    }

    private static boolean valid(Vector3d value) { return value != null && value.isFinite(); }
}
