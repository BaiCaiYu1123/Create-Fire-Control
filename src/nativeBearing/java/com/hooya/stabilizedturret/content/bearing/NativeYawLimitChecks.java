package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.constraint.generic.RapierGenericConstraintHandle;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Actual Rapier hinge stops with the production parent/plate coordinate conversion. */
public final class NativeYawLimitChecks {
    private static final double DT = 1.0 / 120.0;

    public static void main(String[] args) {
        rotatingParentCorridor();
        enablingOutsideCorridor();
        for (Quaterniond parent : new Quaterniond[]{new Quaterniond(), new Quaterniond().rotationXYZ(.3, 1.4, -.25)}) {
            for (double sign : new double[]{1, -1}) {
                for (int center = 0; center < 4; center++) {
                    for (boolean positive : new boolean[]{true, false}) {
                        YawLimits limits = new YawLimits(45.5, 70.25, center);
                        try (Scene s = new Scene(parent, sign, limits)) {
                            var stops = new BearingServoMath.YawStops();
                            double half = limits.halfRangeRadians();
                            for (int tick = 0; tick < 300; tick++) {
                                s.rebase(stops, half, DT);
                                // Deliberately demand an illegal pose: the native hinge must stop it.
                                s.joint.drive((positive ? half + .3 : -half - .3) - stops.reference(),
                                        BearingServoMath.motor(10, Double.POSITIVE_INFINITY));
                                s.step();
                            }
                            double expected = positive == (sign > 0) ? limits.maxDegrees() : limits.minDegrees();
                            double actual = limits.angleDegrees(s.orientation().transform(new Vector3d(0, 0, 1)), parent);
                            require(Math.abs(actual - expected) < .2, "native physical stop: center=" + center + " sign=" + sign + " expected=" + expected + " actual=" + actual);
                        }
                    }
                }
                for (YawLimits corridor : new YawLimits[]{new YawLimits(180, 60, 1), new YawLimits(60, 180, 1),
                        new YawLimits(150, 120, 1), new YawLimits(0, 0, 1)}) {
                    try (Scene s = new Scene(parent, sign, corridor)) {
                        var stops = new BearingServoMath.YawStops();
                        double half = Math.min(Math.PI - 1e-7, corridor.halfRangeRadians());
                        double requested = corridor.left() == 180 ? 220 : corridor.right() == 180 ? -220 : corridor.left();
                        for (int tick = 0; tick < 1000; tick++) {
                            double heading = Math.copySign(Math.min(Math.abs(requested), tick * .5), requested);
                            double goal = Math.toRadians((heading - corridor.midpointDegrees()) * sign);
                            s.rebase(stops, half, DT);
                            s.joint.drive(stops.motorGoal(BearingServoMath.wrapRadians(goal - stops.reference())),
                                    BearingServoMath.motor(10, Double.POSITIVE_INFINITY));
                            s.step();
                        }
                        double actual = corridor.angleDegrees(s.orientation().transform(new Vector3d(0, 0, 1)), parent);
                        require(Math.abs(actual - requested) < .2, "native open side/zero stop: expected=" + requested + " actual=" + actual);
                    }
                }
                try (Scene s = new Scene(parent, sign, YawLimits.UNLIMITED)) {
                    double total = 0, previous = 0;
                    for (int tick = 0; tick < 1800; tick++) {
                        double measured = BearingServoMath.jointAngle(s.frameA, s.orientation().mul(s.baseFrameB));
                        Quaterniond rebased = BearingServoMath.yawMotorFrame(s.baseFrameB, measured);
                        s.joint.hinge().setFrame2(new Vector3d(), rebased);
                        s.joint.driveFrame(s.frameA, rebased, new Vector3d());
                        s.joint.drive(BearingServoMath.servoGoal(Math.toRadians(1), Math.toRadians(120),
                                Math.toRadians(120), DT, BearingServoMath.motor(10, Double.POSITIVE_INFINITY)),
                                BearingServoMath.motor(10, Double.POSITIVE_INFINITY));
                        s.step();
                        double now = BearingServoMath.jointAngle(s.frameA, s.orientation().mul(s.baseFrameB));
                        total += BearingServoMath.wrapRadians(now - previous);
                        previous = now;
                    }
                    require(Math.toDegrees(total) > 1000, "180/180 native hinge must pass the rear seam repeatedly; actual=" + Math.toDegrees(total));
                }
            }
        }
        System.out.println("PASS: Rapier moving-frame yaw stops, smooth outside activation at 40/120 Hz, four centers, tilted/inverted bearings, open sides and unrestricted revolutions");
    }

    private static void rotatingParentCorridor() {
        YawLimits limits=new YawLimits(25,40,YawLimits.SOUTH);
        for(double sign:new double[]{1,-1})try(Scene s=new Scene(new Quaterniond(),sign,limits)) {
            var stops=new BearingServoMath.YawStops();
            Quaterniond facing=sign>0?new Quaterniond():new Quaterniond().rotationZ(Math.PI);
            Quaterniond parent=new Quaterniond();
            for(int tick=0;tick<2400;tick++) {
                parent.rotationY(tick*DT*.5);
                s.frameA.set(parent).mul(BearingServoMath.hingeFrame(facing,0));
                s.joint.hinge().setFrame1(new Vector3d(),s.frameA);
                s.rebase(stops,limits.halfRangeRadians(),DT);
                double requested=Math.toRadians((25-limits.midpointDegrees())*sign);
                s.joint.drive(stops.motorGoal(BearingServoMath.wrapRadians(requested-stops.reference())),
                        BearingServoMath.motor(10,Double.POSITIVE_INFINITY));
                s.step();
                if(tick>300) {
                    double local=limits.angleDegrees(s.orientation().transform(new Vector3d(0,0,1)),parent);
                    require(local>=-40.2&&local<=25.2,"physical yaw corridor rotates with its parent: "+local);
                }
            }
        }
        System.out.println("PASS: yaw stops follow a parent rotating more than 360 degrees, including inverted bearings");
    }

    private static void enablingOutsideCorridor() {
        double peakRate = 0;
        int scenes = 0;
        for (double dt : new double[]{DT, 1.0 / 40.0}) {
            for (Quaterniond parent : new Quaterniond[]{new Quaterniond(), new Quaterniond().rotationXYZ(.3, 1.4, -.25)}) {
                for (double sign : new double[]{1, -1}) {
                    for (int center = 0; center < 4; center++) {
                        YawLimits limits = new YawLimits(45, 45, center);
                        for (double initial : new double[]{180, -180, 179.999, -179.999, 170, -170, 100, -100}) {
                            try (Scene s = new Scene(parent, sign, limits, initial)) {
                                var stops = new BearingServoMath.YawStops();
                                double previous = s.measured();
                                for (int tick = 0; tick < Math.ceil(5 / dt); tick++) {
                                    s.rebase(stops, limits.halfRangeRadians(), dt);
                                    double goal = stops.goal(Math.copySign(limits.halfRangeRadians(), stops.reference()), dt);
                                    s.joint.drive(stops.motorGoal(BearingServoMath.wrapRadians(goal - stops.reference())),
                                            BearingServoMath.motor(10, Double.POSITIVE_INFINITY));
                                    s.step(dt);
                                    double now = s.measured();
                                    double rate = Math.abs(BearingServoMath.wrapRadians(now - previous)) / dt;
                                    peakRate = Math.max(peakRate, rate);
                                    require(rate < Math.toRadians(65), "enabling limits must not kick the body: initial=" + initial
                                            + " center=" + center + " sign=" + sign + " rate=" + Math.toDegrees(rate));
                                    previous = now;
                                }
                                require(Math.abs(Math.toDegrees(previous)) <= 45.2, "outside activation returns inside: " + Math.toDegrees(previous));
                                scenes++;
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("enable yaw limits: scenes=%d peakReturnDegS=%.6f%n", scenes, Math.toDegrees(peakRate));
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    private static final class Scene implements AutoCloseable {
        final long id;
        final NativeBearingJoint joint;
        final Quaterniond frameA, baseFrameB;

        Scene(Quaterniond parent, double sign, YawLimits limits) {
            this(parent, sign, limits, 0);
        }

        Scene(Quaterniond parent, double sign, YawLimits limits, double initialDegrees) {
            id = Rapier3D.initialize(0, 0, 0, 1);
            Rapier3D.configFrequencyAndDamping(40, 5);
            Rapier3D.configSolverIterations(18, 2, 2);
            Quaterniond facing = sign > 0 ? new Quaterniond() : new Quaterniond().rotationZ(Math.PI);
            double center = Math.atan2(limits.localCenter().x, limits.localCenter().z);
            Quaterniond orientation = new Quaterniond(parent).rotateY((limits.enabled() ? center : 0) + Math.toRadians(initialDegrees));
            Rapier3D.createBox(id, 1, 10, .5, .5, 1, new double[]{0, 0, 0,
                    orientation.x, orientation.y, orientation.z, orientation.w});
            frameA = new Quaterniond(parent).mul(BearingServoMath.hingeFrame(facing, 0));
            double zero = limits.enabled() ? limits.nativeCenterDegrees(parent.transform(new Vector3d(0, 0, 1)), parent, sign) : 0;
            baseFrameB = BearingServoMath.hingeFrame(facing, Math.toRadians(zero));
            long hinge = Rapier3D.addGenericConstraint(id, -1, 1,
                    0, 0, 0, frameA.x, frameA.y, frameA.z, frameA.w,
                    0, 0, 0, baseFrameB.x, baseFrameB.y, baseFrameB.z, baseFrameB.w, 0b110111);
            long motor = Rapier3D.addGenericConstraint(id, -1, 1,
                    0, 0, 0, frameA.x, frameA.y, frameA.z, frameA.w,
                    0, 0, 0, baseFrameB.x, baseFrameB.y, baseFrameB.z, baseFrameB.w, 0);
            joint = new NativeBearingJoint(new RapierGenericConstraintHandle(id, hinge), new RapierGenericConstraintHandle(id, motor));
        }

        Quaterniond orientation() {
            double[] pose = new double[7];
            Rapier3D.getPose(id, 1, pose);
            return new Quaterniond(pose[3], pose[4], pose[5], pose[6]);
        }
        double measured() { return BearingServoMath.jointAngle(frameA, orientation().mul(baseFrameB)); }
        void rebase(BearingServoMath.YawStops stops, double half, double dt) {
            stops.update(-half, half, measured(), dt);
            Quaterniond frame = BearingServoMath.yawMotorFrame(baseFrameB, stops.reference());
            joint.hinge().setFrame2(new Vector3d(), frame);
            joint.hinge().setLimit(ConstraintJointAxis.ANGULAR_X, stops.nativeMin(), stops.nativeMax());
            joint.driveFrame(frameA, frame, new Vector3d());
        }
        void step() { step(DT); }
        void step(double dt) { Rapier3D.wakeUpObject(id, 1); Rapier3D.step(id, dt); }
        @Override public void close() { Rapier3D.dispose(id); }
    }
}
