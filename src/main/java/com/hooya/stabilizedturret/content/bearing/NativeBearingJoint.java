package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle;
import org.joml.Vector3d;
import org.joml.Quaterniond;

/**
 * Owns an attachment hinge and a separate world-to-child actuator in the bearing's
 * existing handle slot. Removal/disassembly releases both handles. The actuator
 * applies no direct motor reaction to the parent body.
 */
public final class NativeBearingJoint implements RotaryConstraintHandle {
    private final GenericConstraintHandle hinge;
    private final GenericConstraintHandle actuator;

    NativeBearingJoint(GenericConstraintHandle hinge, GenericConstraintHandle actuator) {
        this.hinge = hinge;
        this.actuator = actuator;
    }

    /** Retains the historical two-body motor for native comparison fixtures. */
    NativeBearingJoint(GenericConstraintHandle hinge) { this(hinge, hinge); }

    GenericConstraintHandle hinge() { return hinge; }
    GenericConstraintHandle actuator() { return actuator; }

    void driveFrame(Quaterniond worldFrame, Quaterniond childFrame, Vector3d childAnchor) {
        actuator.setFrame1(new Vector3d(), worldFrame);
        actuator.setFrame2(childAnchor, childFrame);
    }

    void relax() {
        actuator.setMotor(ConstraintJointAxis.ANGULAR_X, 0, 0, 0, true, 0);
    }

    void drive(double angle, BearingServoMath.Motor motor) {
        actuator.setMotor(ConstraintJointAxis.ANGULAR_X, angle,
                motor.stiffness(), motor.damping(), true, motor.maxTorque());
    }

    @Override public void getJointImpulses(Vector3d linear, Vector3d angular) {
        hinge.getJointImpulses(linear, angular);
    }
    @Override public void setContactsEnabled(boolean enabled) { hinge.setContactsEnabled(enabled); }
    @Override public void setMotor(ConstraintJointAxis axis, double target, double stiffness,
                                   double damping, boolean hasMaxForce, double maxForce) {
        // Simulated and optional integrations must not overwrite an owned fire-control servo.
    }
    @Override public void remove() {
        if (actuator.isValid()) actuator.remove();
        if (hinge.isValid()) hinge.remove();
    }
    @Override public boolean isValid() { return hinge.isValid() && actuator.isValid(); }
}
