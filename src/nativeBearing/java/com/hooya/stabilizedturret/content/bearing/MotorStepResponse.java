package com.hooya.stabilizedturret.content.bearing;

/**
 * Diagnostic scalar inverse of Rapier's acceleration-based position motor.
 * This assumes no torque saturation and no impulses from other joint axes.
 * The native constraint still owns and enforces its finite torque limit.
 */
final class MotorStepResponse {
    private MotorStepResponse() {}

    /**
     * Returns the fixed native target, relative to the measured joint angle,
     * that places the scalar model at positionError + positionRate * dt.
     * initialRelativeRate is the native relative momentum velocity at entry,
     * not the previous step's pose difference. All angles are radians.
     */
    static double targetOffset(BearingServoMath.Motor motor, double dt, int solverIterations,
                               double initialRelativeRate, double positionError,
                               double positionRate) {
        Response response = response(motor, dt, solverIterations);
        double authority = 1.0 - response.positionFromPosition();
        if (!(authority > 1e-12)) {
            throw new IllegalArgumentException("Motor has no invertible position authority");
        }
        return (positionError + positionRate * dt
                - response.positionFromVelocity() * initialRelativeRate) / authority;
    }

    /**
     * One native internal step has h = dt / solverIterations. From motor ERP
     * and CFM, v' = (v - h*K*(q-target))/(1+h*D+h*h*K), then q' = q+h*v'.
     * PGS iterations refine the same velocity solution; stabilization runs
     * after integration and does not create another time step.
     */
    static Response response(BearingServoMath.Motor motor, double dt, int solverIterations) {
        if (!(dt > 0.0) || !Double.isFinite(dt) || solverIterations < 1
                || !(motor.stiffness() > 0.0) || !Double.isFinite(motor.stiffness())
                || !(motor.damping() >= 0.0) || !Double.isFinite(motor.damping())) {
            throw new IllegalArgumentException("Invalid motor step response parameters");
        }
        double h = dt / solverIterations;
        double denominator = 1.0 + h * motor.damping() + h * h * motor.stiffness();
        double m00 = (1.0 + h * motor.damping()) / denominator;
        double m01 = h / denominator;
        double m10 = -h * motor.stiffness() / denominator;
        double m11 = 1.0 / denominator;
        double a00 = 1.0, a01 = 0.0, a10 = 0.0, a11 = 1.0;
        for (int i = 0; i < solverIterations; i++) {
            double next00 = m00 * a00 + m01 * a10;
            double next01 = m00 * a01 + m01 * a11;
            double next10 = m10 * a00 + m11 * a10;
            double next11 = m10 * a01 + m11 * a11;
            a00 = next00; a01 = next01; a10 = next10; a11 = next11;
        }
        return new Response(a00, a01, a10, a11);
    }

    record Response(double positionFromPosition, double positionFromVelocity,
                    double velocityFromPosition, double velocityFromVelocity) {}
}
