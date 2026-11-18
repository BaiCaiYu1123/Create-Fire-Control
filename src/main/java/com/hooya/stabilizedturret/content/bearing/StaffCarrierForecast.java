package com.hooya.stabilizedturret.content.bearing;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Read-only prediction of a directly grabbed carrier's upcoming staff motor response. */
public final class StaffCarrierForecast {
    private StaffCarrierForecast() {}

    /**
     * World rotation increment expected while the staff target is held for this physics step.
     * The position-only motor's overdamped response uses its configured stiffness/damping ratio.
     * Native momentum velocity is kept out of this pose prediction.
     */
    public static Quaterniond forecastDelta(Quaterniondc actual, Quaterniondc queued, double dt,
                                             double stiffness, double damping) {
        if (!(dt > 0.0) || !Double.isFinite(dt) || !(stiffness > 0.0)
                || !Double.isFinite(stiffness) || !(damping > 0.0) || !Double.isFinite(damping)
                || !valid(actual) || !valid(queued)) return new Quaterniond();
        Quaterniond error = new Quaterniond(queued).normalize()
                .mul(new Quaterniond(actual).normalize().conjugate()).normalize();
        if (error.w < 0.0) error.mul(-1.0);
        double response = -Math.expm1(-dt * stiffness / damping);
        return new Quaterniond().slerp(error, response).normalize();
    }

    /** Carrier-only change in the yaw target; existing barrel error cancels in the difference. */
    public static double carrierJointDisplacement(Vector3dc actualRay, Vector3dc held,
                                                  Vector3dc axis, Quaterniondc carrierDelta) {
        Vector3d futureRay = carrierDelta.transform(new Vector3d(actualRay));
        Vector3d futureAxis = carrierDelta.transform(new Vector3d(axis));
        return BearingServoMath.wrapRadians(BearingServoMath.signedDirectionAngle(futureRay,
                new Vector3d(held), futureAxis) - BearingServoMath.signedDirectionAngle(
                new Vector3d(actualRay), new Vector3d(held), new Vector3d(axis)));
    }

    /** Predict pitch after this turret's yaw motor cancels its share of carrier rotation. */
    public static double pitchDisplacement(Vector3dc actualRay, Vector3dc held, Vector3dc pitchAxis,
                                            Quaterniondc carrierDelta, Vector3dc yawAxis,
                                            double yawDisplacement) {
        Vector3d futureRay = carrierDelta.transform(new Vector3d(actualRay));
        Vector3d futurePitchAxis = carrierDelta.transform(new Vector3d(pitchAxis));
        if (yawAxis != null && Double.isFinite(yawDisplacement) && yawDisplacement != 0.0) {
            Vector3d futureYawAxis = carrierDelta.transform(new Vector3d(yawAxis));
            if (futureYawAxis.lengthSquared() > 1e-12) {
                futureYawAxis.normalize();
                Quaterniond yaw = new Quaterniond().rotationAxis(yawDisplacement,
                        futureYawAxis.x, futureYawAxis.y, futureYawAxis.z);
                yaw.transform(futureRay);
                yaw.transform(futurePitchAxis);
            }
        }
        return BearingServoMath.wrapRadians(BearingServoMath.signedDirectionAngle(futureRay,
                new Vector3d(held), futurePitchAxis) - BearingServoMath.signedDirectionAngle(
                new Vector3d(actualRay), new Vector3d(held), new Vector3d(pitchAxis)));
    }

    private static boolean valid(Quaterniondc value) {
        return value != null && Double.isFinite(value.x()) && Double.isFinite(value.y())
                && Double.isFinite(value.z()) && Double.isFinite(value.w())
                && value.lengthSquared() > 1e-12;
    }
}
