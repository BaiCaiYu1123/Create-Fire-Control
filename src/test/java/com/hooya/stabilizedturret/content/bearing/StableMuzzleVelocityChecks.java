package com.hooya.stabilizedturret.content.bearing;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class StableMuzzleVelocityChecks {
    private StableMuzzleVelocityChecks() {}

    public static void main(String[] args) {
        BearingBodyChainChecks.run();
        longBarrelCancelsCarrierYawAndPitch();
        tiltedOffsetPivotsPreserveTransportAndRoll();
        jointOrderDoesNotChangeTheVelocity();
        redundantAxesUseMinimumNorm();
        supportedPredictionUsesMeasuredMotion();
        unreachableAndInvalidFramesReject();
        System.out.println("PASS: stable muzzle transport, combined axes, offset pivots, roll and safe fallback");
    }

    private static void longBarrelCancelsCarrierYawAndPitch() {
        Vector3d muzzle = new Vector3d(0, 0, 8), ray = new Vector3d(0, 0, 1);
        Vector3d translation = new Vector3d(4, -2, 1);
        List<StableMuzzleVelocity.Joint> axes = List.of(joint(new Vector3d(0, 1, 0), new Vector3d()),
                joint(new Vector3d(1, 0, 0), new Vector3d()));
        for (Vector3d omega : List.of(new Vector3d(0, Math.toRadians(120), 0),
                new Vector3d(Math.toRadians(30), 0, 0), new Vector3d(0.4, -2.1, 0))) {
            Vector3d carrierPointVelocity = new Vector3d(translation).add(omega.cross(muzzle, new Vector3d()));
            Vector3d velocity = StableMuzzleVelocity.sample(carrierPointVelocity, omega, muzzle, ray, axes);
            requireNear(velocity, translation, 1.0e-11,
                    "stable barrel cancels long-lever carrier motion while retaining real translation");
        }
    }

    private static void tiltedOffsetPivotsPreserveTransportAndRoll() {
        Quaterniond frame = new Quaterniond().rotationXYZ(0.35, -0.8, 0.2);
        Vector3d a = frame.transform(new Vector3d(0, 1, 0));
        Vector3d b = frame.transform(new Vector3d(1, 0, 0));
        Vector3d ray = frame.transform(new Vector3d(0, 0, 1));
        Vector3d omega = new Vector3d(a).mul(1.2).add(new Vector3d(b).mul(-0.6))
                .add(new Vector3d(ray).mul(0.4));
        Vector3d translation = new Vector3d(4, -2, 3);
        Vector3d carrierCOM = new Vector3d(20000123, 73, -20000321);
        Vector3d p1 = new Vector3d(carrierCOM).add(2, 1, 0);
        Vector3d p2 = new Vector3d(carrierCOM).add(-1, 3, 1);
        Vector3d muzzle = new Vector3d(p2).add(new Vector3d(ray).mul(8)).add(new Vector3d(b).mul(0.3));
        Vector3d carrierPoint = new Vector3d(translation)
                .add(omega.cross(new Vector3d(muzzle).sub(carrierCOM), new Vector3d()));
        Vector3d expected = new Vector3d(carrierPoint)
                .add(new Vector3d(a).mul(-1.2).cross(new Vector3d(muzzle).sub(p1), new Vector3d()))
                .add(new Vector3d(b).mul(0.6).cross(new Vector3d(muzzle).sub(p2), new Vector3d()));
        Vector3d actual = StableMuzzleVelocity.sample(carrierPoint, omega, muzzle, ray,
                List.of(joint(a, p1), joint(b, p2)));
        requireNear(actual, expected, 1e-10, "tilted axes preserve exact offset-pivot transport and uncontrolled roll");
        require(actual.distance(translation) > 0.1, "offset pivot motion and roll are not deleted");
    }

    private static void jointOrderDoesNotChangeTheVelocity() {
        Vector3d ray = new Vector3d(0.3, 0.2, 1).normalize();
        Vector3d omega = new Vector3d(0.2, 0.5, -0.3), muzzle = new Vector3d(1, 2, 8);
        List<StableMuzzleVelocity.Joint> joints = new ArrayList<>(List.of(
                joint(new Vector3d(0, 1, 0), new Vector3d()),
                joint(new Vector3d(1, 0, 0), new Vector3d(0.3, 1, 0)),
                joint(new Vector3d(0.2, 0.8, 0.3), new Vector3d(1, 0, -1))));
        Vector3d velocity = new Vector3d(4, 2, 0).add(omega.cross(muzzle, new Vector3d()));
        Vector3d ordinary = StableMuzzleVelocity.sample(velocity, omega, muzzle, ray, joints);
        Collections.reverse(joints);
        requireNear(StableMuzzleVelocity.sample(velocity, omega, muzzle, ray, joints), ordinary, 1e-12,
                "joint enumeration order does not alter the minimum-norm transport");
    }

    private static void redundantAxesUseMinimumNorm() {
        Vector3d ray = new Vector3d(0, 0, 1), omega = new Vector3d(0, 2, 0);
        Vector3d muzzle = new Vector3d(0, 0, 8);
        Vector3d pivot1 = new Vector3d(), pivot2 = new Vector3d(0, 0, 2);
        Vector3d velocity = omega.cross(muzzle, new Vector3d());
        Vector3d expected = new Vector3d(velocity)
                .add(new Vector3d(0, -1, 0).cross(new Vector3d(muzzle).sub(pivot1), new Vector3d()))
                .add(new Vector3d(0, -1, 0).cross(new Vector3d(muzzle).sub(pivot2), new Vector3d()));
        requireNear(StableMuzzleVelocity.sample(velocity, omega, muzzle, ray,
                List.of(joint(new Vector3d(0, 1, 0), pivot1), joint(new Vector3d(0, 1, 0), pivot2))),
                expected, 1e-12, "redundant axes split counterrotation with minimum norm");
    }

    private static void unreachableAndInvalidFramesReject() {
        Vector3d ray = new Vector3d(0, 0, 1), muzzle = new Vector3d(0, 0, 8);
        require(StableMuzzleVelocity.sample(new Vector3d(), new Vector3d(1, 0, 0), muzzle, ray,
                List.of(joint(new Vector3d(0, 1, 0), new Vector3d()))) == null,
                "a lone yaw cannot cancel required pitch rotation");
        require(StableMuzzleVelocity.sample(new Vector3d(), new Vector3d(1, 0, 0), muzzle, ray,
                List.of(joint(ray, new Vector3d()))) == null, "an axis along the ray cannot steer it");
        require(StableMuzzleVelocity.sample(new Vector3d(), new Vector3d(), muzzle, new Vector3d(), List.of()) == null,
                "zero ray rejects");
        require(StableMuzzleVelocity.sample(new Vector3d(), new Vector3d(Double.NaN, 0, 0), muzzle, ray, List.of()) == null,
                "non-finite input rejects");
        requireNear(StableMuzzleVelocity.sample(new Vector3d(1, 2, 3), new Vector3d(0, 0, 2), muzzle, ray, List.of()),
                new Vector3d(1, 2, 3), 1e-12, "pure roll requires no steerable axis");
        List<StableMuzzleVelocity.Joint> tooMany = Collections.nCopies(9, joint(new Vector3d(0, 1, 0), new Vector3d()));
        require(StableMuzzleVelocity.sample(new Vector3d(), new Vector3d(), muzzle, ray, tooMany) == null,
                "joint count is bounded");
    }

    private static void supportedPredictionUsesMeasuredMotion() {
        var measured=new ArticulatedServoPredictor.BodyState(2000,new org.joml.Matrix3d().identity(),
                new Vector3d(),new Quaterniond(),new Vector3d(4,0,-2),new Vector3d(0,0,0.4));
        var predictedBase=new ArticulatedServoPredictor.BodyState(2000,new org.joml.Matrix3d().identity(),
                new Vector3d(0,-.007,0),new Quaterniond(),new Vector3d(4,-.275,-2),new Vector3d(0,1,0.4));
        var barrel=new ArticulatedServoPredictor.BodyState(3,new org.joml.Matrix3d().identity(),
                new Vector3d(0,-.007,0),new Quaterniond(),new Vector3d(4,-8,-2),new Vector3d(2,1,.4));
        var model=new StableMuzzleVelocity.Prediction(0,List.of(
                new StableMuzzleVelocity.JointFrame(0,1,new Vector3d(0,1,0),new Vector3d()),
                new StableMuzzleVelocity.JointFrame(0,1,new Vector3d(1,0,0),new Vector3d())));
        var bodies=List.of(predictedBase,barrel);
        Vector3d muzzle=new Vector3d(1,-.007,8),ray=new Vector3d(0,0,1);
        // Real translation and roll at an offset muzzle remain. Neither fictitious
        // falling nor trial yaw/pitch traverse belongs to the inherited velocity.
        Vector3d expected=new Vector3d(4,.4,-2);
        requireNear(model.velocity(bodies,measured,muzzle,ray),expected,1e-12,
                "supported forecast retains measured translation/roll and excludes unmodelled support acceleration");
        require(model.velocity(bodies,muzzle,ray).distance(expected)>.1,
                "the previous prediction introduces a nonzero stopped-carrier launch bias");
    }

    private static StableMuzzleVelocity.Joint joint(Vector3d axis, Vector3d pivot) {
        return new StableMuzzleVelocity.Joint(axis, pivot);
    }
    private static void requireNear(Vector3d actual, Vector3d expected, double tolerance, String message) {
        require(actual != null && expected != null && actual.distance(expected) < tolerance,
                message + ": actual=" + actual + " expected=" + expected);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
