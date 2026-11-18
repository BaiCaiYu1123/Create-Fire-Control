package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Manual motion and actuator selection checks; no game world or native runtime required. */
public final class StabilizerDisabledChecks {
    public static void main(String[] args) {
        manualTargetRidesItsParent();
        unchangedViewReacquiresAfterCarrierMotion();
        dampedMotionStartsCruisesAndStops();
        reactionManualReversalRestartsWithoutWaiting();
        manualMotorNeverDrivesTheParent();
        manualMotorFollowsCarrierMotion();
        originalShortRampIsPreserved();
        physicsTickBoundaryKeepsHeadAimRunning();
        System.out.println("PASS: live manual head aim reacquires, reaction-mode reversals restart without waiting, damping and parent motor isolation preserved");
    }

    private static void unchangedViewReacquiresAfterCarrierMotion() {
        var target = new BearingServoMath.ParentAimTarget();
        Vector3d view = new Vector3d(0, 0, 1);
        target.set(view, new Quaterniond());
        Quaterniond movedParent = new Quaterniond().rotationXYZ(.3, .8, -.2);
        target.set(view, movedParent);
        Vector3d transportedBarrel = movedParent.transform(new Vector3d(view));
        double correction = BearingServoMath.parentRelativeDirectionAngle(movedParent, new Vector3d(0, 1, 0),
                transportedBarrel, target.worldDirection(movedParent));
        require(target.worldDirection(movedParent).distance(view) < 1e-12 && Math.abs(correction) > .1,
                "unchanged view is still a live aim goal and creates correction after carrier rotation");
        var motion = new BearingServoMath.OperatorMotion();
        double maxSpeed = Math.toRadians(30), dt = .05;
        double step = motion.advanceDamped(correction, maxSpeed, dt);
        require(step * correction > 0 && Math.abs(step) < maxSpeed * dt,
                "carrier correction is ordinary damped head aim, never an unlimited stabilization turn");
        // Looking at the same block after sideways translation also changes parallax.
        Vector3d block = new Vector3d(0, 0, 40), movedMuzzle = new Vector3d(3, 0, 8);
        Vector3d launch = block.sub(movedMuzzle).normalize();
        target.set(launch, movedParent);
        require(target.worldDirection(movedParent).distance(launch) < 1e-12 && Math.abs(launch.x) > .09,
                "moving muzzle geometry updates even without changing the selected block or view");
    }

    private static void manualTargetRidesItsParent() {
        Vector3d axis = new Vector3d(0, 1, 0), localRay = new Vector3d(0, 0, 1);
        new Quaterniond().rotationAxis(.4, 0, 1, 0).transform(localRay);
        Quaterniond initialParent = new Quaterniond().rotationXYZ(.1, .2, -.3);
        Vector3d originalWorldRay = initialParent.transform(new Vector3d(localRay));
        var request = new BearingServoMath.ParentAimTarget();
        request.set(originalWorldRay, initialParent);
        for (Quaterniond parent : new Quaterniond[]{new Quaterniond(),
                new Quaterniond().rotationXYZ(.5, 1.2, -.4), new Quaterniond().rotationXYZ(-.4, -1.2, .3)}) {
            Vector3d expected = parent.transform(new Vector3d(localRay));
            Vector3d actual = request.worldDirection(parent);
            require(actual.distance(expected) < 1e-12, "manual target inherits all parent axes");
            require(Math.abs(BearingServoMath.parentRelativeDirectionAngle(parent, axis, expected, actual)) < 1e-12,
                    "parent rotation alone creates zero manual correction");
        }
        require(Math.abs(BearingServoMath.signedDirectionAngle(localRay, originalWorldRay, axis)) > .01,
                "fixture distinguishes parent-relative aiming from keeping the old world direction");
    }

    private static void physicsTickBoundaryKeepsHeadAimRunning() {
        var motion = new BearingServoMath.OperatorMotion();
        double speed = Math.toRadians(30), remaining = Math.toRadians(120);
        for (int tick = 100; tick < 120; tick++) {
            // One substep uses this tick's refreshed command; the next can
            // see the next game tick before the controller refreshes it.
            for (int age : new int[]{0, 1}) {
                require(BearingServoMath.liveAimCommand(tick, tick + age),
                        "a one-tick command phase difference must not stop head aim");
                remaining -= motion.advanceDamped(remaining, speed, .025);
            }
        }
        require(motion.velocity() >= speed * .99,
                "manual acceleration survives alternating controller/physics tick phases");
        require(!BearingServoMath.liveAimCommand(100, 102)
                        && !BearingServoMath.liveAimCommand(102, 100)
                        && !BearingServoMath.liveAimCommand(Long.MIN_VALUE, 100),
                "expired, future and absent commands are never revived");
    }

    private static void dampedMotionStartsCruisesAndStops() {
        for (double dt : new double[]{1.0 / 20, 1.0 / 120}) {
            var motion = new BearingServoMath.OperatorMotion();
            double maxSpeed = Math.toRadians(60), remaining = Math.toRadians(90);
            double firstStep = 0, finalStep = 0, peakVelocity = 0;
            int steps = 0;
            for (; steps < 600 && remaining > 1e-9; steps++) {
                double step = motion.advanceDamped(remaining, maxSpeed, dt);
                if (steps == 0) firstStep = step;
                finalStep = step;
                require(step >= 0 && step <= remaining + 1e-12, "manual motion never crosses a stationary target");
                require(step <= maxSpeed * dt + 1e-12 && Math.abs(motion.velocity()) <= maxSpeed + 1e-12,
                        "damping keeps the configured parent-relative speed limit");
                peakVelocity = Math.max(peakVelocity, motion.velocity());
                remaining -= step;
            }
            require(remaining < 1e-9 && motion.velocity() == 0, "manual aim arrives and stops");
            require(firstStep < maxSpeed * dt * .2, "manual aim starts slowly");
            double previousRampFirstStep = maxSpeed / .35 * dt * dt * .5;
            require(firstStep < previousRampFirstStep * .5, "new ramp is more than twice as slow at startup");
            require(peakVelocity > maxSpeed * .99, "long manual traverses reach the configured cruise speed");
            require(finalStep < maxSpeed * dt * .3, "manual aim slows down before arrival");
            require(steps * dt > 2.2, "stronger start and stop ramps are visible throughout a 90-degree traverse");
        }
    }

    private static void reactionManualReversalRestartsWithoutWaiting() {
        for (double dt : new double[]{.05, .025, 1.0 / 120}) {
            for (double direction : new double[]{-1, 1}) {
                var motion = new BearingServoMath.OperatorMotion();
                var oldMotion = new BearingServoMath.OperatorMotion();
                double speed = Math.toRadians(60);
                for (int frame = 0; frame < Math.ceil(1.0 / dt); frame++) {
                    require(!motion.restartIfReversing(direction * Math.PI),
                            "continuing in the same direction retains its damping trajectory");
                    motion.advanceDamped(direction * Math.PI, speed, dt);
                    oldMotion.advanceDamped(direction * Math.PI, speed, dt);
                }
                require(Math.abs(motion.velocity()) >= speed * .99, "reversal begins at cruise speed");
                double remaining = -direction * Math.toRadians(90);
                var motor = BearingServoMath.motor(1, Double.POSITIVE_INFINITY);
                double oldStep = oldMotion.advanceDamped(remaining, speed, dt);
                double oldGoal = BearingServoMath.manualWorldMotorGoal(oldStep, oldMotion.velocity(), 0, dt, motor);
                require(oldStep * remaining < 0 && Math.abs(BearingServoMath.boundOperatorMotorGoal(
                                oldGoal, remaining, 0, 0)) < 1e-12,
                        "old damping reproduces the reaction motor's zero-command pause after reversal");

                require(motion.restartIfReversing(remaining) && motion.velocity() == 0,
                        "only the native manual caller discards obsolete commanded velocity");
                double step = motion.advanceDamped(remaining, speed, dt);
                require(step * remaining > 0 && Math.abs(step) < speed * dt * .2,
                        "first reversed command immediately starts the new slow acceleration ramp");
                double newGoal = BearingServoMath.manualWorldMotorGoal(step, motion.velocity(), 0, dt, motor);
                newGoal = BearingServoMath.boundOperatorMotorGoal(newGoal, remaining, 0, 0);
                newGoal = BearingServoMath.brakeAimMotorGoal(newGoal, remaining, direction * speed, dt, motor);
                require(newGoal * remaining > 0,
                        "reanchored native motor commands the new direction while braking old physical momentum");
                remaining -= step;
                double finalStep = step;
                for (int frame = 0; frame < Math.ceil(5.0 / dt) && Math.abs(remaining) > 1e-9; frame++) {
                    require(!motion.restartIfReversing(remaining), "a reversed acquisition never repeatedly restarts");
                    step = motion.advanceDamped(remaining, speed, dt);
                    require(step * remaining > 0 && Math.abs(step) <= Math.abs(remaining) + 1e-12,
                            "reversed acquisition keeps moving toward its target without overshoot");
                    require(Math.abs(step) <= speed * dt + 1e-12 && Math.abs(motion.velocity()) <= speed + 1e-12,
                            "reversal retains the configured relative speed limit");
                    remaining -= step;
                    finalStep = step;
                }
                require(Math.abs(remaining) < 1e-9 && motion.velocity() == 0
                                && Math.abs(finalStep) < speed * dt * .3,
                        "reversed acquisition still slows down and stops at the target");
            }
        }
    }

    private static void manualMotorNeverDrivesTheParent() {
        var hinge = new RecordingHandle();
        var world = new RecordingHandle();
        var joint = new NativeBearingJoint(hinge, world);
        var motor = new BearingServoMath.Motor(100, 10, 20);
        joint.drive(.2, motor);
        require(world.stiffness == 100 && hinge.stiffness == 0, "stabilized mode retains the world actuator");
        joint.drive(BearingServoMath.manualWorldMotorGoal(.4, .2, .5, .05, motor), motor);
        require(world.stiffness == 100 && hinge.motorWrites == 0,
                "manual aiming changes only the child actuator and never enables a parent reaction motor");
        joint.relax();
        require(world.stiffness == 0 && hinge.stiffness == 0 && hinge.damping == 0,
                "staff yielding releases whichever actuator is active");
        joint.drive(.3, motor);
        require(hinge.motorWrites == 0 && world.stiffness == 100 && world.goal == .3,
                "mode changes keep the attachment hinge free of motor commands");
    }

    private static void manualMotorFollowsCarrierMotion() {
        var motor = new BearingServoMath.Motor(100, 10, 20);
        for (double carrierRate : new double[]{-1.2, 0, 1.2}) {
            double base = BearingServoMath.servoGoal(.3, .1, .1, .05, motor);
            double following = BearingServoMath.manualWorldMotorGoal(.3, .1, carrierRate, .05, motor);
            require(Math.abs(BearingServoMath.wrapRadians(following - base)
                    - carrierRate * (.05 + motor.damping() / motor.stiffness())) < 1e-12,
                    "child actuator follows parent motion without damping the carrier's angular velocity");
        }
    }

    private static void originalShortRampIsPreserved() {
        double speed = Math.toRadians(60), error = Math.toRadians(90), dt = .05;
        var original = new BearingServoMath.OperatorMotion();
        var manual = new BearingServoMath.OperatorMotion();
        double oldStep = original.advance(error, speed, dt);
        double manualStep = manual.advanceDamped(error, speed, dt);
        require(Math.abs(oldStep - speed * dt * .5) < 1e-12 && original.velocity() == speed,
                "enabled mode retains the previous short acceleration ramp");
        require(manualStep < oldStep / 5, "only manual mode receives the longer damping ramp");
    }

    private static final class RecordingHandle implements GenericConstraintHandle {
        double goal, stiffness, damping;
        int motorWrites;
        @Override public void getJointImpulses(Vector3d linear, Vector3d angular) {}
        @Override public void setContactsEnabled(boolean enabled) {}
        @Override public void setMotor(ConstraintJointAxis axis, double goal, double stiffness, double damping,
                                       boolean limited, double cap) {
            require(axis == ConstraintJointAxis.ANGULAR_X, "only the driven bearing axis is changed");
            motorWrites++;
            this.goal = goal; this.stiffness = stiffness; this.damping = damping;
        }
        @Override public void remove() {}
        @Override public boolean isValid() { return true; }
        @Override public void setFrame1(Vector3dc anchor, Quaterniondc rotation) {}
        @Override public void setFrame2(Vector3dc anchor, Quaterniondc rotation) {}
        @Override public void setLimit(ConstraintJointAxis axis, double minimum, double maximum) {}
        @Override public void lockAxes(ConstraintJointAxis... axes) {}
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
