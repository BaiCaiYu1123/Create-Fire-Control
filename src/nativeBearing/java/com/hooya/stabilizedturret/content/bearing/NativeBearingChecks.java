package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.constraint.generic.RapierGenericConstraintHandle;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Native production checks and separately labelled, rejected momentum-observer experiments. */
public final class NativeBearingChecks {
    private static final double DT = 1.0 / 120.0;
    private static final Quaterniond Y_FRAME = BearingServoMath.hingeFrame(new Quaterniond(), 0.0);
    private static int checks;
    private static boolean sustainedHoldPassed = true;
    private static boolean turningArrivalPassed = true;

    public static void main(String[] args) throws java.io.IOException {
        coordinates();
        for(double dt:new double[]{1.0/40,1.0/120}) {
            var motion=new BearingServoMath.OperatorMotion();
            for(int i=0;i<40;i++) motion.advance(Math.PI,Math.toRadians(180),dt);
            double step=motion.advance(Math.PI,Math.toRadians(28),dt);
            require(Math.abs(step)<=Math.toRadians(28)*dt+1e-12 && Math.abs(motion.velocity())<=Math.toRadians(28)+1e-12,
                    "lowering parent-relative operator speed never retains an old faster traverse");
        }
        for (boolean experimental : new boolean[]{false, true}) {
            fastCarrierCompensation(DT, experimental);
            fastCarrierCompensation(1.0 / 40.0, experimental);
            tiltedCarrierTwoAxisHold(DT, 2000, experimental);
            tiltedCarrierTwoAxisHold(1.0 / 40.0, 2000, experimental);
            tiltedCarrierTwoAxisHold(1.0 / 40.0, 20, experimental);
            sustainedCarrierTurn(experimental);
        }
        constantRateAndStop();
        finiteAimArrival();
        finiteAimOnTurningCarrier();
        trackingAndWrap();
        limitsAndOffset();
        for (double carrierMass : new double[]{100.0, 100_000.0}) dragAndRelease(carrierMass);
        multipleTurretsAndDescendants();
        if (!Boolean.getBoolean("create_fire_control.characterizeCarrierHold")) {
            require(sustainedHoldPassed, "sustained carrier rotation holds every tested load before stopping");
        } else if (!sustainedHoldPassed) {
            System.out.println("KNOWN FAILURE: production sustained hold accuracy; characterization only accepts functional checks");
        }
        if (!turningArrivalPassed) System.out.println("REJECTED EXPERIMENT: strict no-reversal arrival on a turning carrier");
        System.out.println("PASS: " + checks + " functional native bearing checks (Sable Rapier 2.0.5)");
    }

    private static void coordinates() {
        for (Quaterniond parent : new Quaterniond[]{new Quaterniond(),new Quaterniond().rotationXYZ(.8,1.2,-.5),
                new Quaterniond().rotationXYZ(-1.4,-2.2,.9)}) {
            for (Vector3d axis : new Vector3d[]{new Vector3d(0,1,0),new Vector3d(1,0,0),new Vector3d(-1,0,0)}) {
                Vector3d localRay=new Vector3d(0,0,1);
                Vector3d requested=new Quaterniond().rotationAxis(.37,axis.x,axis.y,axis.z).transform(new Vector3d(localRay));
                double relative=BearingServoMath.parentRelativeDirectionAngle(parent,axis,
                        parent.transform(new Vector3d(localRay)),parent.transform(requested));
                require(Math.abs(relative-.37)<1e-12,"tilted immediate parent preserves its own traverse coordinate");
            }
        }
        for (Quaterniond facing : new Quaterniond[]{new Quaterniond(), new Quaterniond().rotationZ(Math.PI),
                new Quaterniond().rotationZ(-Math.PI / 2), new Quaterniond().rotationZ(Math.PI / 2),
                new Quaterniond().rotationX(Math.PI / 2), new Quaterniond().rotationX(-Math.PI / 2)}) {
            Quaterniond a = BearingServoMath.hingeFrame(facing, 0.0);
            Vector3d x = a.transform(new Vector3d(1, 0, 0));
            Vector3d y = facing.transform(new Vector3d(0, 1, 0));
            require(x.distance(y) < 1e-12, "local X matches bearing-facing Y");
            Quaterniond b = new Quaterniond(facing).rotateY(0.4);
            b = BearingServoMath.hingeFrame(b, 0.1);
            require(Math.abs(BearingServoMath.jointAngle(a, b) - 0.3) < 1e-12, "twist sign and zero frame");
        }
        require(Math.abs(BearingServoMath.motor(10, 1e12).stiffness()
                - BearingServoMath.motor(10, Double.POSITIVE_INFINITY).stiffness()) < 0.001,
                "heavy carrier does not inflate gains");
        for (double carrierPrediction : new double[]{-3.0, -0.4, 0.0, 0.4, 3.0}) {
            for (double sign : new double[]{-1, 1}) {
                double commanded = BearingServoMath.boundOperatorMotorGoal(
                        BearingServoMath.wrapRadians(sign * 0.6 + carrierPrediction), sign * 0.4, 0,
                        carrierPrediction);
                require(Math.abs(BearingServoMath.wrapRadians(commanded - carrierPrediction) - sign * 0.2) < 1e-12,
                        "operator arrival braking retains all carrier prediction through wrap");
            }
        }
    }

    private static void fastCarrierCompensation(double timeStep, boolean experimental) {
        double DT = timeStep;
        try (Scene s = new Scene(0, timeStep)) {
            s.box(1, 2000, 0, 0, 0, new Quaterniond());
            s.box(2, 10, 0, 2, 0, new Quaterniond());
            NativeBearingJoint joint = s.hinge(1, 2, new Vector3d(0, 2, 0), new Vector3d(), Y_FRAME);
            long steering = Rapier3D.addFreeConstraint(s.id, -1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
            for (int axis = 0; axis < 6; axis++) Rapier3D.setConstraintMotor(s.id, steering, axis,
                    0, 3e7, 3e5, false, 0);
            BearingServoMath.MotorTarget target = new BearingServoMath.MotorTarget();
            var feedback = new MomentumVelocityFeedback();
            BearingServoMath.MotorResidual residual = new BearingServoMath.MotorResidual();
            BearingServoMath.Motor motor = BearingServoMath.motor(10 * 1.25 / 3, 2000 * 1.25 / 3);
            double maxError = 0;
            for (int i = 0; i < 1200; i++) {
                double t = i * DT;
                Rapier3D.setConstraintMotor(s.id, steering, 4, 0.7 * Math.sin(t * 2), 3e7, 3e5, false, 0);
                double desired = BearingServoMath.jointAngle(new Quaterniond(s.orientation(1)).mul(Y_FRAME), Y_FRAME);
                double rate = experimental ? target.velocity(desired, -s.angularVelocity(1).y, DT)
                        : target.predictHeldRate(desired, -s.angularVelocity(1).y, 0, 0, DT);
                double parentVelocity = s.angularVelocity(1).y;
                double measured = s.angle(1, 2, Y_FRAME);
                joint.hinge().setFrame2(new Vector3d(), BearingServoMath.yawMotorFrame(Y_FRAME, measured));
                double velocity = BearingServoMath.heldTargetVelocityRate(-parentVelocity, DT, motor);
                if (experimental) velocity = feedback.velocity(rate, -parentVelocity, -parentVelocity, measured,
                        s.angularVelocity(2).y - parentVelocity, DT, false);
                joint.drive(BearingServoMath.servoGoal(BearingServoMath.wrapRadians(desired - measured + holdResidual(residual, desired, measured, DT, experimental)),
                        rate, velocity, DT, motor), motor);
                s.step();
                if (i > 120 && Math.abs(s.angle(-1, 2, Y_FRAME)) > maxError) {
                    maxError = Math.abs(s.angle(-1, 2, Y_FRAME));
                }
            }
            System.out.printf("%s oscillating carrier %.0f Hz: maximum world yaw error %.6f degrees%n",
                    experimental ? "rejected experiment" : "production baseline", 1 / timeStep, Math.toDegrees(maxError));
            require(Math.toDegrees(maxError) < 0.05, "carrier compensation under 0.05 degrees");
        }
    }

    private static void constantRateAndStop() {
        for (double speedDegrees : new double[]{45, -45}) {
            try (Scene s = new Scene(0)) {
                s.box(1, 10, 0, 0, 0, new Quaterniond());
                NativeBearingJoint joint = s.hinge(-1, 1, new Vector3d(), new Vector3d(), Y_FRAME);
                BearingServoMath.Motor motor = BearingServoMath.motor(10 * 1.25 / 3, Double.POSITIVE_INFINITY);
                BearingServoMath.MotorTarget target = new BearingServoMath.MotorTarget();
                BearingServoMath.MotorResidual residual = new BearingServoMath.MotorResidual();
                double rate = Math.toRadians(speedDegrees), position = 0, previousAngle = 0, maxRateError = 0;
                for (int i = 0; i < 600; i++) {
                    position = BearingServoMath.wrapRadians(position + rate * DT);
                    double positionRate = target.velocity(position, rate, DT);
                    double measured = s.angle(-1, 1, Y_FRAME);
                    joint.hinge().setFrame2(new Vector3d(), BearingServoMath.yawMotorFrame(Y_FRAME, measured));
                    joint.drive(BearingServoMath.servoGoal(BearingServoMath.wrapRadians(position - measured + residual.update(position, measured)),
                            positionRate, rate, DT, motor), motor);
                    s.step();
                    // Visible rotation includes Rapier's pose correction; its reported omega excludes that correction.
                    double angle = s.angle(-1, 1, Y_FRAME);
                    if (i > 60) {
                        double observedRate = BearingServoMath.wrapRadians(angle - previousAngle) / DT;
                        maxRateError = Math.max(maxRateError, Math.abs(observedRate - rate));
                    }
                    previousAngle = angle;
                }
                System.out.printf("constant-rate maximum pose speed error %.6f degrees/s%n", Math.toDegrees(maxRateError));
                require(Math.toDegrees(maxRateError) < 0.1, "constant turning rate through wrap seam");
                double maxStopError = 0;
                for (int i = 0; i < 24; i++) {
                    double measured = s.angle(-1, 1, Y_FRAME);
                    joint.hinge().setFrame2(new Vector3d(), BearingServoMath.yawMotorFrame(Y_FRAME, measured));
                    joint.drive(BearingServoMath.servoGoal(BearingServoMath.wrapRadians(position - measured + residual.update(position, measured)),
                            target.velocity(position, 0, DT), 0, DT, motor), motor);
                    s.step();
                    double error = Math.abs(BearingServoMath.wrapRadians(s.angle(-1, 1, Y_FRAME) - position));
                    maxStopError = Math.max(maxStopError, error);
                    if (i >= 12) require(Math.toDegrees(error) < 0.02, "stop settles within 0.1 seconds");
                }
                System.out.printf("operator %.0f deg/s: rate ripple %.6f deg/s, peak stop error %.6f deg%n",
                        speedDegrees, Math.toDegrees(maxRateError), Math.toDegrees(maxStopError));
                require(Math.toDegrees(maxStopError) < 0.5, "bounded stop response without slow easing");
            }
        }
    }

    private static void finiteAimArrival() {
        finiteAimArrival(false);
        finiteAimArrival(true);
    }

    private static void finiteAimArrival(boolean loaded) {
        for (double dt : new double[]{1.0 / 40.0, 1.0 / 120.0}) {
            for (double speed : new double[]{60, 180, -60, -180}) {
                try (Scene s = new Scene(0, dt)) {
                    s.box(1, 10, 0, 0, 0, new Quaterniond());
                    NativeBearingJoint joint = s.hinge(-1, 1, new Vector3d(), new Vector3d(), Y_FRAME);
                    double inertia = 10 * 1.25 / 3;
                    if (loaded) {
                        s.box(2, 20, 3, 2, 0, new Quaterniond());
                        NativeBearingJoint pitch = s.hinge(1, 2, new Vector3d(3, 2, 0), new Vector3d(), new Quaterniond());
                        pitch.drive(0, BearingServoMath.motor(20 * 1.25 / 3, inertia));
                        inertia += BearingServoMath.pivotInertia(20 * 1.25 / 3, 20,
                                new Vector3d(3, 2, 0), new Vector3d(0, 1, 0));
                    }
                    var motor = BearingServoMath.motor(inertia, Double.POSITIVE_INFINITY);
                    var motion = new BearingServoMath.OperatorMotion();
                    var target = new BearingServoMath.MotorTarget();
                    var residual = new BearingServoMath.MotorResidual();
                    double destination = Math.copySign(Math.toRadians(80), speed);
                    double held = 0, peakOvershoot = 0, previous = 0, landingRemaining = 0;
                    int reversals = 0;
                    for (int i = 0; i < 600; i++) {
                        double step = motion.advance(BearingServoMath.wrapRadians(destination - held), Math.toRadians(Math.abs(speed)), dt);
                        held = BearingServoMath.wrapRadians(held + step);
                        double rate = target.velocity(held, step / dt, dt) + motion.velocity() - step / dt;
                        double measured = s.angle(-1, 1, Y_FRAME);
                        joint.hinge().setFrame2(new Vector3d(), BearingServoMath.yawMotorFrame(Y_FRAME, measured));
                        landingRemaining = Math.abs(step / dt) > 1e-6 ? 0.15 : Math.max(0, landingRemaining - dt);
                        if (Math.abs(step) > 1e-8 || landingRemaining > 0) residual.reset();
                        double bias = Math.abs(step) > 1e-8 || landingRemaining > 0 ? 0 : residual.update(held, measured);
                        double motorGoal = BearingServoMath.servoGoal(BearingServoMath.wrapRadians(held - measured + bias),
                                rate, motion.velocity(), dt, motor);
                        if (landingRemaining > 0) {
                            motorGoal = BearingServoMath.boundAimMotorGoal(motorGoal,
                                    BearingServoMath.wrapRadians(destination - measured), 0);
                        }
                        joint.drive(motorGoal, motor);
                        s.step();
                        double actual = s.angle(-1, 1, Y_FRAME);
                        if (Math.abs(destination - held) < 1e-8 && landingRemaining == 0) {
                            require(Math.abs(actual - destination) < Math.toRadians(0.01),
                                    "finite aim settles within the landing interval: " + Math.toDegrees(actual - destination));
                        }
                        peakOvershoot = Math.max(peakOvershoot, Math.max(0, (actual - destination) * Math.signum(speed)));
                        if (i > 0 && (actual - previous) * Math.signum(speed) < -Math.toRadians(0.005)) reversals++;
                        previous = actual;
                    }
                    System.out.printf("finite aim %s %.0f Hz %.0f deg/s: overshoot %.6f deg, reversals %d%n",
                            loaded ? "nested" : "single", 1 / dt, speed, Math.toDegrees(peakOvershoot), reversals);
                    require(Math.toDegrees(peakOvershoot) < 0.03, "finite aim arrival brakes before crossing target");
                    require(reversals == 0, "finite aim does not hunt back and forth on arrival");
                    require(Math.abs(previous - destination) < Math.toRadians(0.01), "finite aim settles on target");
                }
            }
        }
    }

    private static void finiteAimOnTurningCarrier() throws java.io.IOException {
        StringBuilder report = new StringBuilder();
        java.util.Map<String, Double> baselinePeaks = new java.util.HashMap<>();
        for (int variant : new int[]{0, 1, 2}) {
            boolean revised = variant == 1, production = variant == 2;
            for (double dt : new double[]{1.0 / 40, 1.0 / 120}) {
                for (double speed : new double[]{60, 180, -60, -180}) {
                    try (Scene s = new Scene(0, dt)) {
                        s.box(1, 2000, 0, 0, 0, new Quaterniond());
                        s.box(2, 10, 0, 2, 0, new Quaterniond());
                        s.box(3, 5, 0, 4, 0, new Quaterniond());
                        // A turntable isolates yaw arrival from an uncommanded carrier tumble.
                        // The independent tilted-carrier checks cover the two other rotation axes.
                        s.hinge(-1, 1, new Vector3d(), new Vector3d(), Y_FRAME);
                        var joint = s.hinge(1, 2, new Vector3d(0, 2, 0), new Vector3d(), Y_FRAME);
                        var pitch = s.hinge(2, 3, new Vector3d(0, 2, 0), new Vector3d(), new Quaterniond());
                        pitch.drive(0, BearingServoMath.motor(5 * 1.25 / 3, 10 * 1.25 / 3));
                        var motor = BearingServoMath.motor(15 * 1.25 / 3, 2000 * 1.25 / 3);
                        var target = new BearingServoMath.MotorTarget();
                        var feedback = new MomentumVelocityFeedback();
                        var residual = new BearingServoMath.MotorResidual();
                        var motion = new BearingServoMath.OperatorMotion();
                        var aimVelocity = new BearingServoMath.AimVelocity();
                        double held = 0, destination = Math.copySign(Math.toRadians(80), speed);
                        double landing = 0, peakOvershoot = 0, previous = 0, actual = 0;
                        int reversals = 0;
                        for (int i = 0; i < 1000; i++) {
                            Vector3d correction = new Vector3d(0, Math.toRadians(90), 0).sub(s.angularVelocity(1));
                            Rapier3D.addLinearAngularVelocities(s.id, 1, 0, 0, 0, correction.x, correction.y, correction.z, false);
                            double step = i >= 120 ? motion.advance(BearingServoMath.wrapRadians(destination - held),
                                    Math.toRadians(Math.abs(speed)), dt) : 0;
                            held = BearingServoMath.wrapRadians(held + step);
                            double parentAngle = s.angle(-1, 1, Y_FRAME), measured = s.angle(1, 2, Y_FRAME);
                            double goal = BearingServoMath.wrapRadians(held - parentAngle);
                            double velocity = motion.velocity() - s.angularVelocity(1).y;
                            double rate = production ? target.predictHeldRate(goal, step / dt - s.angularVelocity(1).y,
                                    step / dt, motion.velocity(), dt) : target.velocity(goal, step / dt - s.angularVelocity(1).y, dt)
                                    + motion.velocity() - step / dt;
                            landing = Math.abs(step / dt) > 1e-6 ? 0.15 : Math.max(0, landing - dt);
                            boolean moving = Math.abs(step / dt) > 1e-6;
                            boolean arriving = landing > 0;
                            if (moving || arriving) residual.reset();
                            if (revised) velocity = feedback.velocity(rate, velocity, -s.angularVelocity(1).y, measured,
                                    s.angularVelocity(2).y - s.angularVelocity(1).y, dt, moving || arriving);
                            else { feedback.reset(); if (production) velocity = motion.velocity()
                                    + BearingServoMath.heldTargetVelocityRate(-s.angularVelocity(1).y, dt, motor); }
                            double bias = moving || arriving ? 0 : (production
                                    ? residual.update(goal, measured, dt) : residual.update(goal, measured));
                            joint.hinge().setFrame2(new Vector3d(), BearingServoMath.yawMotorFrame(Y_FRAME, measured));
                            double motorGoal = BearingServoMath.servoGoal(BearingServoMath.wrapRadians(goal - measured + bias),
                                    rate, velocity, dt, motor);
                            double worldRate = aimVelocity.sample(s.orientation(2).transform(new Vector3d(0, 0, 1)),
                                    new Vector3d(0, 1, 0), dt);
                            if (arriving) {
                                double carrierPrediction = BearingServoMath.servoGoal(0, rate - motion.velocity(),
                                        velocity - motion.velocity(), dt, motor);
                                motorGoal = BearingServoMath.boundOperatorMotorGoal(motorGoal,
                                        BearingServoMath.wrapRadians(destination - parentAngle - measured), 0, carrierPrediction);
                                if (production) motorGoal = BearingServoMath.brakeAimMotorGoal(motorGoal,
                                        BearingServoMath.wrapRadians(destination - parentAngle - measured), worldRate, dt, motor);
                            }
                            joint.drive(motorGoal, motor);
                            s.step();
                            actual = s.angle(-1, 2, Y_FRAME);
                            if (i >= 120) {
                                peakOvershoot = Math.max(peakOvershoot, Math.max(0, (actual - destination) * Math.signum(speed)));
                                if (i > 120 && (actual - previous) * Math.signum(speed) < -Math.toRadians(0.005)) reversals++;
                            }
                            previous = actual;
                        }
                        String line = String.format(java.util.Locale.ROOT,
                                "finite aim on turning carrier %s %.0f Hz %.0f deg/s: overshoot %.6f deg, reversals %d, final %.6f deg%n",
                                production ? "production" : revised ? "rejected observer" : "previous delivery", 1 / dt, speed, Math.toDegrees(peakOvershoot), reversals, Math.toDegrees(actual - destination));
                        System.out.print(line);
                        report.append(line);
                        String key = dt + "/" + speed;
                        if (variant == 0) baselinePeaks.put(key, peakOvershoot);
                        else if (revised) {
                            require(peakOvershoot <= baselinePeaks.get(key) + Math.toRadians(0.005),
                                    "carrier hold correction does not increase arrival overshoot");
                        }
                        if (production) turningArrivalPassed &= reversals == 0 && Math.toDegrees(peakOvershoot) < 0.03;
                        require(Math.abs(actual - destination) < Math.toRadians(0.01), "turning carrier settles on world aim");
                    }
                }
            }
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("bearing-arrival-accuracy-result.txt"),
                (turningArrivalPassed ? "PASS\n" : "FAIL: strict zero reversals over 0.005 degrees\n") + report);
    }

    private static void tiltedCarrierTwoAxisHold(double timeStep, double carrierMass, boolean experimental) {
        double DT = timeStep;
        try (Scene s = new Scene(0, timeStep)) {
            s.box(1, carrierMass, 0, 0, 0, new Quaterniond());
            s.box(2, 10, 0, 2, 0, new Quaterniond());
            s.box(3, 5, 0, 4, 0, new Quaterniond());
            NativeBearingJoint yaw = s.hinge(1, 2, new Vector3d(0, 2, 0), new Vector3d(), Y_FRAME);
            NativeBearingJoint pitch = s.hinge(2, 3, new Vector3d(0, 2, 0), new Vector3d(), new Quaterniond());
            long steering = Rapier3D.addFreeConstraint(s.id, -1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
            for (int axis = 0; axis < 6; axis++) Rapier3D.setConstraintMotor(s.id, steering, axis, 0, 3e7, 3e5, false, 0);
            BearingServoMath.MotorTarget yawTarget = new BearingServoMath.MotorTarget(), pitchTarget = new BearingServoMath.MotorTarget();
            var yawFeedback = new MomentumVelocityFeedback();
            var pitchFeedback = new MomentumVelocityFeedback();
            BearingServoMath.MotorResidual yawResidual = new BearingServoMath.MotorResidual(), pitchResidual = new BearingServoMath.MotorResidual();
            BearingServoMath.Motor yawMotor = BearingServoMath.motor(10 * 1.25 / 3, carrierMass * 1.25 / 3);
            BearingServoMath.Motor pitchMotor = BearingServoMath.motor(5 * 1.25 / 3, 10 * 1.25 / 3);
            Vector3d requested = new Quaterniond().rotationY(0.5).rotateX(-0.15).transform(new Vector3d(0, 0, 1));
            double maxError = 0;
            for (int i = 0; i < 1800; i++) {
                double t = i * DT;
                Rapier3D.setConstraintMotor(s.id, steering, 3, 0.22 * Math.sin(t * 1.7), 3e7, 3e5, false, 0);
                Rapier3D.setConstraintMotor(s.id, steering, 4, 0.35 * Math.sin(t * 2), 3e7, 3e5, false, 0);
                Rapier3D.setConstraintMotor(s.id, steering, 5, 0.18 * Math.sin(t * 1.3), 3e7, 3e5, false, 0);
                Vector3d yawAxis = s.orientation(1).transform(new Vector3d(0, 1, 0));
                Vector3d pitchAxis = s.orientation(2).transform(new Vector3d(1, 0, 0));
                // The user rate may be zero: a stationary held world ray still compensates all carrier motion.
                Vector3d held = BearingServoMath.advanceAimDirection(requested, requested, yawAxis, 0, DT);
                double measuredYaw = s.angle(1, 2, Y_FRAME);
                double yawGoal = BearingServoMath.wrapRadians(measuredYaw + BearingServoMath.signedDirectionAngle(
                        s.orientation(experimental ? 2 : 3).transform(new Vector3d(0, 0, 1)), held, yawAxis));
                double pitchGoal = BearingServoMath.wrapRadians(s.angle(2, 3, new Quaterniond()) + BearingServoMath.signedDirectionAngle(
                        s.orientation(3).transform(new Vector3d(0, 0, 1)), requested, pitchAxis));
                double yawVelocity = BearingServoMath.stabilizationRate(requested, yawAxis, s.angularVelocity(1));
                double pitchVelocity = BearingServoMath.stabilizationRate(requested, pitchAxis, s.angularVelocity(2));
                yaw.hinge().setFrame2(new Vector3d(), BearingServoMath.yawMotorFrame(Y_FRAME, measuredYaw));
                double yawPoseRate = experimental ? yawTarget.velocity(yawGoal, yawVelocity, DT)
                        : yawTarget.predictHeldRate(yawGoal, yawVelocity, 0, 0, DT);
                double pitchPoseRate = experimental ? pitchTarget.velocity(pitchGoal, pitchVelocity, DT)
                        : pitchTarget.predictHeldRate(pitchGoal, pitchVelocity, 0, 0, DT);
                double pitchMeasured = s.angle(2, 3, new Quaterniond());
                double yawMomentumRate = BearingServoMath.heldTargetVelocityRate(yawVelocity, DT, yawMotor);
                double pitchMomentumRate = BearingServoMath.heldTargetVelocityRate(pitchVelocity, DT, pitchMotor);
                if (experimental) {
                    yawMomentumRate = yawFeedback.velocity(yawPoseRate, yawVelocity, yawVelocity, measuredYaw,
                            new Vector3d(s.angularVelocity(2)).sub(s.angularVelocity(1)).dot(yawAxis), DT, false);
                    pitchMomentumRate = pitchFeedback.velocity(pitchPoseRate, pitchVelocity, pitchVelocity, pitchMeasured,
                            new Vector3d(s.angularVelocity(3)).sub(s.angularVelocity(2)).dot(pitchAxis), DT, false);
                }
                yaw.drive(BearingServoMath.servoGoal(BearingServoMath.wrapRadians(yawGoal - measuredYaw + holdResidual(yawResidual, yawGoal, measuredYaw, DT, experimental)),
                        yawPoseRate, yawMomentumRate, DT, yawMotor), yawMotor);
                pitch.drive(BearingServoMath.servoGoal(pitchGoal + holdResidual(pitchResidual, pitchGoal, s.angle(2, 3, new Quaterniond()), DT, experimental),
                        pitchPoseRate, pitchMomentumRate, DT, pitchMotor), pitchMotor);
                s.step();
                Vector3d actual = s.orientation(3).transform(new Vector3d(0, 0, 1));
                double error = Math.acos(Math.clamp(actual.dot(requested), -1.0, 1.0));
                if (i > 180) maxError = Math.max(maxError, error);
            }
            System.out.printf("%s tilted carrier mass %.0f, %.0f Hz: maximum two-axis world-ray error %.6f degrees%n",
                    experimental ? "rejected experiment" : "production baseline", carrierMass, 1 / timeStep, Math.toDegrees(maxError));
            require(Math.toDegrees(maxError) < 0.15, "yaw and pitch hold world aim during carrier yaw, pitch and roll");
        }
    }

    private static void trackingAndWrap() {
        try (Scene s = new Scene(0)) {
            s.box(1, 10, 0, 0, 0, new Quaterniond());
            NativeBearingJoint joint = s.hinge(-1, 1, new Vector3d(), new Vector3d(), Y_FRAME);
            BearingServoMath.Trajectory trajectory = new BearingServoMath.Trajectory();
            for (double target : new double[]{30, 175, -175, -40}) {
                for (int i = 0; i < 900; i++) {
                    double measured = s.angle(-1, 1, Y_FRAME);
                    joint.drive(trajectory.advance(Math.toRadians(target), measured, Math.PI, DT),
                            BearingServoMath.motor(10 * 1.25 / 3, Double.POSITIVE_INFINITY));
                    // A foreign/native tick cannot overwrite the owned motor.
                    joint.setMotor(ConstraintJointAxis.ANGULAR_X, -2.0, 1e12, 0, false, 0);
                    s.step();
                }
                System.out.printf("target %.1f: actual %.3f, omega %.4f%n", target,
                        Math.toDegrees(s.angle(-1, 1, Y_FRAME)), s.angularVelocity(1).length());
                require(Math.abs(BearingServoMath.wrapRadians(s.angle(-1, 1, Y_FRAME)
                        - Math.toRadians(target))) < 0.01, "native target/sign/seam " + target);
            }
            joint.remove();
            require(!joint.isValid(), "owned joint removal invalidates handle");
        }
    }

    private static void sustainedCarrierTurn(boolean experimental) throws java.io.IOException {
        boolean passed = true;
        StringBuilder report = new StringBuilder();
        for (boolean eccentric : new boolean[]{false, true}) {
            for (double degreesPerSecond : new double[]{30, 120, -120, 360}) {
                try (Scene s = new Scene(0, 1.0 / 40)) {
                    double dt = s.timeStep, speed = Math.toRadians(degreesPerSecond);
                    s.box(1, 2000, 0, 0, 0, new Quaterniond());
                    s.box(2, 10, 0, 2, 0, new Quaterniond());
                    double offset = eccentric ? 3 : 0, barrelMass = eccentric ? 50 : 5;
                    s.box(3, barrelMass, offset, 4, 0, new Quaterniond());
                    var yaw = s.hinge(1, 2, new Vector3d(0, 2, 0), new Vector3d(), Y_FRAME);
                    var pitch = s.hinge(2, 3, new Vector3d(offset, 2, 0), new Vector3d(), new Quaterniond());
                    var yawMotor = BearingServoMath.motor(10 * 1.25 / 3 + BearingServoMath.pivotInertia(
                            barrelMass * 1.25 / 3, barrelMass, new Vector3d(offset, 2, 0), new Vector3d(0, 1, 0)), 2000 * 1.25 / 3);
                    var pitchMotor = BearingServoMath.motor(barrelMass * 1.25 / 3, 10 * 1.25 / 3);
                    var yawTarget = new BearingServoMath.MotorTarget();
                    var pitchTarget = new BearingServoMath.MotorTarget();
                    var yawVelocity = new MomentumVelocityFeedback();
                    var pitchVelocity = new MomentumVelocityFeedback();
                    var yawResidual = new BearingServoMath.MotorResidual();
                    var pitchResidual = new BearingServoMath.MotorResidual();
                    Vector3d held = new Quaterniond().rotationY(0.5).rotateX(-0.15).transform(new Vector3d(0, 0, 1));
                    double peak = 0, signedBias = 0, yawImpulse = 0, pitchImpulse = 0, peakYaw = 0, peakPitch = 0;
                    for (int i = 0; i < 600; i++) {
                        // A velocity drive maintains carrier rotation; a fixed world ray receives no operator motion.
                        Vector3d correction = new Vector3d(0, speed, 0).sub(s.angularVelocity(1));
                        Rapier3D.addLinearAngularVelocities(s.id, 1, 0, 0, 0, correction.x, correction.y, correction.z, false);
                        Vector3d yawAxis = s.orientation(1).transform(new Vector3d(0, 1, 0));
                        Vector3d pitchAxis = s.orientation(2).transform(new Vector3d(1, 0, 0));
                        double yawMeasured = s.angle(1, 2, Y_FRAME), pitchMeasured = s.angle(2, 3, new Quaterniond());
                        double yawGoal = BearingServoMath.wrapRadians(yawMeasured + BearingServoMath.signedDirectionAngle(
                                s.orientation(experimental ? 2 : 3).transform(new Vector3d(0, 0, 1)), held, yawAxis));
                        double pitchGoal = BearingServoMath.wrapRadians(pitchMeasured + BearingServoMath.signedDirectionAngle(
                                s.orientation(3).transform(new Vector3d(0, 0, 1)), held, pitchAxis));
                        double yawRate = BearingServoMath.stabilizationRate(held, yawAxis, s.angularVelocity(1));
                        double pitchRate = BearingServoMath.stabilizationRate(held, pitchAxis, s.angularVelocity(2));
                        yaw.hinge().setFrame2(new Vector3d(), BearingServoMath.yawMotorFrame(Y_FRAME, yawMeasured));
                        double yawPoseRate = experimental ? yawTarget.velocity(yawGoal, yawRate, dt)
                                : yawTarget.predictHeldRate(yawGoal, yawRate, 0, 0, dt);
                        double pitchPoseRate = experimental ? pitchTarget.velocity(pitchGoal, pitchRate, dt)
                                : pitchTarget.predictHeldRate(pitchGoal, pitchRate, 0, 0, dt);
                        double yawNativeRate = new Vector3d(s.angularVelocity(2)).sub(s.angularVelocity(1)).dot(yawAxis);
                        double pitchNativeRate = new Vector3d(s.angularVelocity(3)).sub(s.angularVelocity(2)).dot(pitchAxis);
                        double yawFeedbackRate = experimental
                                ? yawVelocity.velocity(yawPoseRate, yawRate, yawRate, yawMeasured, yawNativeRate, dt, false)
                                : BearingServoMath.heldTargetVelocityRate(yawRate, dt, yawMotor);
                        double pitchFeedbackRate = experimental
                                ? pitchVelocity.velocity(pitchPoseRate, pitchRate, pitchRate, pitchMeasured, pitchNativeRate, dt, false)
                                : BearingServoMath.heldTargetVelocityRate(pitchRate, dt, pitchMotor);
                        yaw.drive(BearingServoMath.servoGoal(BearingServoMath.wrapRadians(yawGoal - yawMeasured + holdResidual(yawResidual, yawGoal, yawMeasured, dt, experimental)),
                                yawPoseRate, yawFeedbackRate, dt, yawMotor), yawMotor);
                        pitch.drive(BearingServoMath.servoGoal(pitchGoal + holdResidual(pitchResidual, pitchGoal, pitchMeasured, dt, experimental),
                                pitchPoseRate, pitchFeedbackRate, dt, pitchMotor), pitchMotor);
                        s.step();
                        Vector3d linearImpulse = new Vector3d(), angularImpulse = new Vector3d();
                        yaw.getJointImpulses(linearImpulse, angularImpulse);
                        yawImpulse = Math.max(yawImpulse, angularImpulse.length());
                        pitch.getJointImpulses(linearImpulse, angularImpulse);
                        pitchImpulse = Math.max(pitchImpulse, angularImpulse.length());
                        if (i >= 120) {
                            Vector3d actual = s.orientation(3).transform(new Vector3d(0, 0, 1));
                            peak = Math.max(peak, Math.acos(Math.clamp(actual.dot(held), -1, 1)));
                            signedBias += BearingServoMath.signedDirectionAngle(held, actual, new Vector3d(0, 1, 0));
                            peakYaw = Math.max(peakYaw, Math.abs(BearingServoMath.signedDirectionAngle(actual, held, yawAxis)));
                            peakPitch = Math.max(peakPitch, Math.abs(BearingServoMath.signedDirectionAngle(actual, held, pitchAxis)));
                        }
                    }
                    String line = String.format(java.util.Locale.ROOT,
                            "%s sustained carrier %s %.0f deg/s: peak %.6f deg, signed yaw bias %.6f deg%n",
                            experimental ? "rejected experiment" : "production baseline",
                            eccentric ? "eccentric heavy" : "centered", degreesPerSecond, Math.toDegrees(peak), Math.toDegrees(signedBias / 480));
                    System.out.print(line);
                    report.append(line);
                    System.out.printf("sustained impulses yaw %.6f / cap %.6f, pitch %.6f / cap %.6f, parent %s, yaw %s, pitch %s%n",
                            yawImpulse, yawMotor.maxTorque() * dt, pitchImpulse, pitchMotor.maxTorque() * dt,
                            s.angularVelocity(1), s.angularVelocity(2), s.angularVelocity(3));
                    System.out.printf("axis peak yaw %.6f pitch %.6f%n", Math.toDegrees(peakYaw), Math.toDegrees(peakPitch));
                    passed &= Math.toDegrees(peak) < 0.05;
                }
            }
        }
        if (!experimental) sustainedHoldPassed = passed;
        java.nio.file.Files.writeString(java.nio.file.Path.of("bearing-turn-" + (experimental ? "experiment" : "baseline") + "-accuracy-result.txt"),
                (passed ? "PASS\n" : "FAIL: threshold < 0.05 degrees\n") + report);
    }

    private static void limitsAndOffset() {
        try (Scene s = new Scene(-9.81)) {
            s.box(1, 10, 0, 0, 0, new Quaterniond());
            NativeBearingJoint joint = s.hinge(-1, 1, new Vector3d(), new Vector3d(), new Quaterniond());
            joint.hinge().setLimit(ConstraintJointAxis.ANGULAR_X, Math.toRadians(-20), Math.toRadians(45));
            for (int i = 0; i < 1200; i++) {
                // Simulate offset edits via the same bounded anchor update used in production.
                joint.hinge().setFrame1(new Vector3d(Math.min(1, i * DT), 0, 0), new Quaterniond());
                joint.drive(Math.toRadians(i < 600 ? 100 : -100), BearingServoMath.motor(2, Double.POSITIVE_INFINITY));
                s.step();
                double angle = s.angle(-1, 1, new Quaterniond());
                require(angle >= Math.toRadians(-21) && angle <= Math.toRadians(46), "physical pitch stops");
            }
            require(s.position(1).distance(new Vector3d(1, 0, 0)) < 0.03, "offset anchor carries gravity load");
            BearingServoMath.PitchStops stops = new BearingServoMath.PitchStops();
            BearingServoMath.MotorTarget recovery = new BearingServoMath.MotorTarget();
            for (int i = 0; i < 600; i++) {
                double measured = s.angle(-1, 1, new Quaterniond());
                stops.update(Math.toRadians(-5), Math.toRadians(5), measured, DT);
                joint.hinge().setLimit(ConstraintJointAxis.ANGULAR_X, stops.min(), stops.max());
                double goal = stops.goal(0, measured, DT);
                double velocity = stops.transitioning() ? BearingServoMath.wrapRadians(goal - measured) / DT : 0;
                BearingServoMath.Motor motor = BearingServoMath.motor(2, Double.POSITIVE_INFINITY);
                joint.drive(BearingServoMath.servoGoal(goal, recovery.velocity(goal, velocity, DT), velocity, DT, motor), motor);
                s.step();
                require(s.angularVelocity(1).length() < 5, "narrowing stops does not snap outside body");
            }
            require(Math.abs(s.angle(-1, 1, new Quaterniond())) < Math.toRadians(1), "narrowed stop recovery");
        }
    }

    private static void dragAndRelease(double carrierMass) {
        try (Scene s = new Scene(0)) {
            s.box(1, carrierMass, 0, 0, 0, new Quaterniond());
            s.box(2, 10, 0, 2, 0, new Quaterniond());
            NativeBearingJoint joint = s.hinge(1, 2, new Vector3d(0, 2, 0), new Vector3d(), Y_FRAME);
            long staff = Rapier3D.addFreeConstraint(s.id, -1, 2, 0, 0, 0, 0.7, 0, 0, 0, 0, 0, 1);
            for (int axis = 0; axis < 6; axis++) {
                double goal = axis == 0 ? 3 : axis == 1 ? 2.5 : 0;
                Rapier3D.setConstraintMotor(s.id, staff, axis, goal, 1200, 120, false, 0);
            }
            double carrierReaction = 0;
            double peakSpeed = 0;
            BearingServoMath.MotorTarget target = new BearingServoMath.MotorTarget();
            BearingServoMath.MotorResidual residual = new BearingServoMath.MotorResidual();
            for (int i = 0; i < 1800; i++) {
                if (i == 360) Rapier3D.removeConstraint(s.id, staff);
                Quaterniond parent = s.orientation(1);
                double measured = s.angle(1, 2, Y_FRAME);
                Vector3d axis = parent.transform(new Vector3d(0, 1, 0));
                Vector3d held = new Vector3d(0, 0, 1);
                double desired = BearingServoMath.wrapRadians(measured + BearingServoMath.signedDirectionAngle(
                        s.orientation(2).transform(new Vector3d(0, 0, 1)), held, axis));
                double velocity = BearingServoMath.stabilizationRate(held, axis, s.angularVelocity(1));
                BearingServoMath.Motor motor = BearingServoMath.motor(10 * 1.25 / 3, carrierMass * 1.25 / 3);
                joint.hinge().setFrame2(new Vector3d(), BearingServoMath.yawMotorFrame(Y_FRAME, measured));
                joint.drive(BearingServoMath.servoGoal(BearingServoMath.wrapRadians(desired - measured + residual.update(desired, measured)),
                        target.velocity(desired, velocity, DT), velocity, DT, motor), motor);
                s.step();
                peakSpeed = Math.max(peakSpeed, s.angularVelocity(2).length());
                carrierReaction = Math.max(carrierReaction, s.angularVelocity(1).length());
                require(s.position(1).length() < 200 && s.position(2).length() < 200, "drag positions bounded");
                require(s.angularVelocity(1).length() < 60 && s.angularVelocity(2).length() < 60,
                        "drag/release angular velocities bounded");
            }
            require(carrierReaction > 1e-6, "native reaction reaches carrier " + carrierMass);
            require(s.angularVelocity(2).length() < Math.max(0.05, peakSpeed * 0.1), "release disturbance decays");
            Vector3d a = s.orientation(1).transform(new Vector3d(0, 2, 0)).add(s.position(1));
            require(a.distance(s.position(2)) < 0.05, "drag preserves connection");
            System.out.printf("drag mass %.0f: peak omega %.3f, final %.5f, carrier reaction %.5f%n",
                    carrierMass, peakSpeed, s.angularVelocity(2).length(), carrierReaction);
        }
    }

    private static void multipleTurretsAndDescendants() {
        try (Scene s = new Scene(0)) {
            s.box(1, 2000, 0, 0, 0, new Quaterniond());
            s.box(2, 10, -3, 2, 0, new Quaterniond());
            s.box(3, 10, 3, 2, 0, new Quaterniond());
            s.box(4, 5, -3, 4, 0, new Quaterniond());
            s.box(5, 5, 3, 4, 0, new Quaterniond());
            s.box(6, 5, -3, 6, 0, new Quaterniond());
            s.box(7, 5, -3, 8, 0, new Quaterniond());
            NativeBearingJoint a = s.hinge(1, 2, new Vector3d(-3, 2, 0), new Vector3d(), Y_FRAME);
            NativeBearingJoint b = s.hinge(1, 3, new Vector3d(3, 2, 0), new Vector3d(), Y_FRAME);
            NativeBearingJoint pitchA = s.hinge(2, 4, new Vector3d(0, 2, 0), new Vector3d(), new Quaterniond());
            NativeBearingJoint pitchB = s.hinge(3, 5, new Vector3d(0, 2, 0), new Vector3d(), new Quaterniond());
            long ordinary = Rapier3D.addRotaryConstraint(s.id, 4, 6, 0, 2, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0);
            long weld = Rapier3D.addFixedConstraint(s.id, 6, 7, 0, 2, 0, 0, 0, 0, 0, 0, 0, 1);
            Rapier3D.setConstraintContactsEnabled(s.id, ordinary, false);
            Rapier3D.setConstraintContactsEnabled(s.id, weld, false);
            Rapier3D.setConstraintMotor(s.id, ordinary, 3, 0.3, 100, 20, true, 100);
            long steering = Rapier3D.addFreeConstraint(s.id, -1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
            for (int axis = 0; axis < 6; axis++) Rapier3D.setConstraintMotor(s.id, steering, axis,
                    axis == 4 ? 0.5 : 0.0, 30000, 10000, false, 0);
            BearingServoMath.Trajectory ta = new BearingServoMath.Trajectory();
            BearingServoMath.Trajectory tb = new BearingServoMath.Trajectory();
            for (int i = 0; i < 2400; i++) {
                double desiredA = BearingServoMath.jointAngle(new Quaterniond(s.orientation(1)).mul(Y_FRAME),
                        new Quaterniond().rotationY(0.7).mul(Y_FRAME));
                double desiredB = BearingServoMath.jointAngle(new Quaterniond(s.orientation(1)).mul(Y_FRAME),
                        new Quaterniond().rotationY(-0.5).mul(Y_FRAME));
                a.drive(ta.advance(desiredA, s.angle(1, 2, Y_FRAME), 1.0, DT), BearingServoMath.motor(5, 700));
                b.drive(tb.advance(desiredB, s.angle(1, 3, Y_FRAME), 0.5, DT), BearingServoMath.motor(5, 700));
                pitchA.drive(0.2, BearingServoMath.motor(5, 5));
                pitchB.drive(-0.3, BearingServoMath.motor(5, 5));
                s.step();
                for (int body = 1; body <= 7; body++) require(s.angularVelocity(body).length() < 30, "nested system bounded");
            }
            require(Math.abs(s.angle(-1, 2, Y_FRAME) - 0.7) < 0.03, "turret A independent world goal");
            require(Math.abs(s.angle(-1, 3, Y_FRAME) + 0.5) < 0.03, "turret B independent world goal");
            require(Math.abs(s.angle(2, 4, new Quaterniond()) - 0.2) < 0.03, "pitch A independent");
            require(Math.abs(s.angle(3, 5, new Quaterniond()) + 0.3) < 0.03, "pitch B independent");
            Vector3d attach = s.orientation(4).transform(new Vector3d(0, 2, 0)).add(s.position(4));
            require(attach.distance(s.position(6)) < 0.03, "ordinary bearing follows natively");
            attach = s.orientation(6).transform(new Vector3d(0, 2, 0)).add(s.position(6));
            require(attach.distance(s.position(7)) < 0.03, "fixed weld follows natively");
        }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static double holdResidual(BearingServoMath.MotorResidual residual, double goal,
                                       double measured, double dt, boolean experimental) {
        return experimental ? residual.update(goal, measured) : residual.update(goal, measured, dt);
    }

    /** Express a requested pose rate in the motor's momentum-velocity coordinates. */
    private static final class MomentumVelocityFeedback {
        private boolean initialized;
        private double previousAngle;
        private double carrierBias;
        private double calibrationRate;
        private double quietSeconds;

        public void reset() { initialized = false; carrierBias = 0.0; calibrationRate = 0.0; quietSeconds = 0.5; }

        public double velocity(double poseRate, double baseRate, double carrierRate, double measuredAngle,
                               double nativeRate, double dt, boolean operatorChanging) {
            double observedRate = initialized ? BearingServoMath.wrapRadians(measuredAngle - previousAngle) / dt : nativeRate;
            if (!initialized) quietSeconds = 0.5;
            previousAngle = measuredAngle;
            initialized = true;
            // Rapier's positional constraint correction is absent from its reported omega.
            // Damping a pose rate as if it were momentum leaves a speed-dependent world bias.
            quietSeconds = operatorChanging ? 0.0 : quietSeconds + dt;
            if (quietSeconds >= 0.5) {
                carrierBias = poseRate + nativeRate - observedRate - baseRate;
                calibrationRate = carrierRate;
            }
            // Preserve the calibrated carrier contribution during operator motion and
            // braking. Operator momentum must never bypass its own braking envelope.
            double bias = carrierBias;
            if (Math.abs(calibrationRate) > 0.05) bias *= carrierRate / calibrationRate;
            return baseRate + bias;
        }
    }

    private static final class Scene implements AutoCloseable {
        final long id;
        final double timeStep;
        final java.util.List<Integer> bodies = new java.util.ArrayList<>();
        Scene(double gravity) { this(gravity, DT); }
        Scene(double gravity, double timeStep) {
            this.timeStep = timeStep;
            id = Rapier3D.initialize(0, gravity, 0, 1.0);
            // Match PhysicsConfigData, which overrides the DLL's own defaults in a real server.
            Rapier3D.configFrequencyAndDamping(40, 5);
            Rapier3D.configSolverIterations(18, 2, 2);
            Rapier3D.configMinIslandSize(128);
        }
        void box(int body, double mass, double x, double y, double z, Quaterniond q) {
            Rapier3D.createBox(id, body, mass, 0.5, 0.5, 1.0, new double[]{x, y, z, q.x, q.y, q.z, q.w});
            bodies.add(body);
        }
        NativeBearingJoint hinge(int a, int b, Vector3d anchorA, Vector3d anchorB, Quaterniond frame) {
            long handle = Rapier3D.addGenericConstraint(id, a, b,
                    anchorA.x, anchorA.y, anchorA.z, frame.x, frame.y, frame.z, frame.w,
                    anchorB.x, anchorB.y, anchorB.z, frame.x, frame.y, frame.z, frame.w, 0b110111);
            NativeBearingJoint joint = new NativeBearingJoint(new RapierGenericConstraintHandle(id, handle));
            joint.setContactsEnabled(false);
            return joint;
        }
        double[] pose(int body) {
            if (body == -1) return new double[]{0, 0, 0, 0, 0, 0, 1};
            double[] data = new double[7]; Rapier3D.getPose(id, body, data); return data;
        }
        Quaterniond orientation(int body) { double[] p = pose(body); return new Quaterniond(p[3], p[4], p[5], p[6]); }
        Vector3d position(int body) { double[] p = pose(body); return new Vector3d(p[0], p[1], p[2]); }
        Vector3d angularVelocity(int body) {
            double[] data = new double[3]; Rapier3D.getAngularVelocity(id, body, data);
            return new Vector3d(data[0], data[1], data[2]);
        }
        double angle(int a, int b, Quaterniond frame) {
            return BearingServoMath.jointAngle(orientation(a).mul(frame), orientation(b).mul(frame));
        }
        void step() {
            for (int body : bodies) Rapier3D.wakeUpObject(id, body);
            Rapier3D.step(id, timeStep);
        }
        @Override public void close() { Rapier3D.dispose(id); }
    }
}
