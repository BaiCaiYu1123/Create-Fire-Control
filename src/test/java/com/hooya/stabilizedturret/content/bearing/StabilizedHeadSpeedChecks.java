package com.hooya.stabilizedturret.content.bearing;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;

/** Cross-step carrier/aim separation, including finite geometry and landing; no native runtime. */
public final class StabilizedHeadSpeedChecks {
    public static void main(String[] args) {
        relativeVelocityRemovesOnlyCompensation();
        landingPreservesCompensation();
        finiteAcquisitionKeepsItsOwnBudget();
        independentAxesKeepIndependentBudgets();
        headAcquisitionUsesParentSpeed();
        legacyInheritedMotionIsNotCancelled();
        System.out.println("PASS: parent-relative head acquisition gives 30 + 20 = 50 deg/s world, then resumes world hold; both axes/backends covered");
    }

    private static void headAcquisitionUsesParentSpeed() {
        for (Vector3d localAxis : new Vector3d[]{new Vector3d(0, 1, 0), new Vector3d(1, 0, 0)}) {
            for (double dt : new double[]{.05, 1.0 / 120}) {
                for (double carrierDegrees : new double[]{-20, 20}) {
                    Quaterniond base = new Quaterniond().rotationXYZ(.3, -.4, .15);
                    Vector3d axis = base.transform(new Vector3d(localAxis));
                    Vector3d held = base.transform(new Vector3d(0, 0, 1));
                    Vector3d requested = rotate(held, axis, Math.toRadians(75));
                    var traverse = new BearingServoMath.ParentTraverse();
                    var motion = new BearingServoMath.OperatorMotion();
                    double headSpeed = Math.toRadians(30), carrierSpeed = Math.toRadians(carrierDegrees);
                    Quaterniond parent = new Quaterniond(base);
                    traverse.observe(parent);
                    int cruiseSamples = 0;
                    boolean arrived = false;
                    for (int tick = 1; tick < 1200; tick++) {
                        Quaterniond previousParent = new Quaterniond(parent);
                        Vector3d previousHeld = new Vector3d(held);
                        parent.set(base).rotateAxis(carrierSpeed * dt * tick, localAxis.x, localAxis.y, localAxis.z);
                        double error = BearingServoMath.signedDirectionAngle(held, requested, axis);
                        if (Math.abs(error) > 1e-6 || Math.abs(motion.velocity()) > 1e-6) {
                            boolean follow = BearingServoMath.followParentDuringTraverse(error, headSpeed, dt);
                            if (follow) {
                                double parentTravel = BearingServoMath.signedDirectionAngle(held,
                                        traverse.carriedDirection(held, parent), axis);
                                held = rotate(held, axis, BearingServoMath.boundParentTraverse(error, parentTravel));
                            }
                            error = BearingServoMath.signedDirectionAngle(held, requested, axis);
                            double step = motion.advance(error, headSpeed, dt);
                            held = rotate(held, axis, step);
                            if (follow && Math.abs(step / dt - headSpeed) < 1e-8) {
                                double relativeRate = BearingServoMath.signedDirectionAngle(
                                        previousParent.transformInverse(new Vector3d(previousHeld)),
                                        parent.transformInverse(new Vector3d(held)), localAxis) / dt;
                                double worldRate = BearingServoMath.signedDirectionAngle(previousHeld, held, axis) / dt;
                                require(Math.abs(relativeRate - headSpeed) < 1e-9,
                                        "30 deg/s acquisition means 30 deg/s on the immediate parent");
                                require(Math.abs(worldRate - headSpeed - carrierSpeed) < 1e-9,
                                        "20 deg/s same-direction carrier gives 50 deg/s world; opposite gives 10");
                                cruiseSamples++;
                            }
                        }
                        traverse.observe(parent);
                        if (Math.abs(BearingServoMath.signedDirectionAngle(held, requested, axis)) < 1e-8) {
                            arrived = true;
                            break;
                        }
                    }
                    require(arrived && cruiseSamples > 5, "parent-relative acquisition reaches the finite heading");
                    Vector3d acquired = new Vector3d(held);
                    for (int tick = 0; tick < 30; tick++) {
                        parent.rotateAxis(carrierSpeed * dt, localAxis.x, localAxis.y, localAxis.z);
                        double error = BearingServoMath.signedDirectionAngle(held, requested, axis);
                        require(Math.abs(error) < 1e-8 && motion.advance(error, headSpeed, dt) == 0,
                                "acquired sight creates no operator motion while its carrier keeps rotating");
                        traverse.observe(parent);
                        require(held.distance(acquired) < 1e-12, "idle stabilization keeps the acquired world ray fixed");
                    }
                }
            }
        }
        require(BearingServoMath.boundParentTraverse(.001, .02) == .001
                        && BearingServoMath.boundParentTraverse(-.001, -.02) == -.001,
                "carrier inheritance stops exactly at acquisition in either direction");
    }

    private static void legacyInheritedMotionIsNotCancelled() {
        for (boolean yaw : new boolean[]{true, false}) {
            for (double carrierTravel : new double[]{-.04, .04}) {
                var motion = new BearingServoMath.StabilizedAimMotion();
                Quaterniond tilt = new Quaterniond().rotationXYZ(.2, .1, -.15);
                Vector3d axis = tilt.transform(yaw ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0));
                Vec3 up = vec(tilt.transform(new Vector3d(0, 1, 0)));
                Vec3 pivot = Vec3.ZERO, offset = vec(tilt.transform(new Vector3d(yaw ? .8 : 0, .2, 2)));
                Vec3 forward = vec(tilt.transform(new Vector3d(0, 0, 1)));
                Vec3 goal = vec(rotate(vector(forward), axis, .6).mul(40));
                double maxStep = .015;
                double error = correction(pivot, offset, forward, goal, axis, up, yaw);
                double step = motion.advance(error, 0, maxStep);
                offset = vec(rotate(vector(offset), axis, step));
                forward = vec(rotate(vector(forward), axis, step));
                motion.record(error, step);
                // The legacy post-step lock carries the acquiring turret along
                // with its parent between head commands, instead of world-locking it.
                offset = vec(rotate(vector(offset), axis, carrierTravel));
                forward = vec(rotate(vector(forward), axis, carrierTravel));
                Vec3 nextUp = vec(rotate(vector(up), axis, carrierTravel));
                double nextError = correction(pivot, offset, forward, goal, axis, nextUp, yaw);
                double oldWorldCompensation = motion.compensation(nextError);
                require(Math.abs(oldWorldCompensation + carrierTravel) < 1e-6,
                        "preceding world compensation would cancel the carrier during head acquisition: yaw="
                                + yaw + " compensation=" + oldWorldCompensation + " carrier=" + carrierTravel);
                double automatic = BearingServoMath.wrapRadians(oldWorldCompensation + carrierTravel);
                double nextStep = motion.advance(nextError, automatic, maxStep);
                require(Math.abs(nextStep - maxStep) < 1e-6,
                        "legacy head step is relative to parent; inherited carrier motion stays intact");
                require(Math.abs((carrierTravel + nextStep) - (carrierTravel + maxStep)) < 1e-6,
                        "legacy world traverse contains both parent motion and capped relative head motion");
            }
        }
    }

    private static void relativeVelocityRemovesOnlyCompensation() {
        for (Vector3d localAxis : new Vector3d[]{new Vector3d(0, 1, 0), new Vector3d(1, 0, 0)}) {
            for (double dt : new double[]{.05, 1.0 / 120}) {
                for (double parentRate : new double[]{-3.0, 0, 3.0}) {
                    for (double automaticRate : new double[]{-1.2, 0, 1.2}) {
                        double headRate = .4;
                        var velocity = new BearingServoMath.AimVelocity();
                        Quaterniond base = new Quaterniond().rotationXYZ(.31, -.42, .18);
                        Vector3d axis = base.transform(new Vector3d(localAxis));
                        Vector3d actual = base.transform(new Vector3d(0, 0, 1));
                        Vector3d commanded = rotate(actual, axis, headRate * dt);
                        velocity.sampleCompensated(actual, actual, commanded, base, localAxis, dt);
                        double maxRelativeRate = 0;
                        for (int frame = 1; frame <= 16; frame++) {
                            Quaterniond parent = new Quaterniond(base).rotateAxis(parentRate * dt * frame,
                                    localAxis.x, localAxis.y, localAxis.z);
                            Vector3d compensated = rotate(commanded, axis, automaticRate * dt);
                            Vector3d nextActual = rotate(actual, axis, (headRate + automaticRate) * dt);
                            Vector3d nextCommand = rotate(compensated, axis, headRate * dt);
                            double rate = velocity.sampleCompensated(nextActual, compensated,
                                    nextCommand, parent, localAxis, dt);
                            require(Math.abs(rate - headRate) < 1e-10,
                                    "head rate survives parent rotation and automatic aim movement in both directions");
                            maxRelativeRate = Math.max(maxRelativeRate, Math.abs(headRate + automaticRate - parentRate));
                            actual = nextActual;
                            commanded = nextCommand;
                        }
                        if (Math.abs(parentRate) > 1) require(maxRelativeRate > headRate * 2,
                                "fixture requires joint speed greater than head speed, rather than a world-speed identity");
                    }
                }
            }
        }
    }

    private static void landingPreservesCompensation() {
        var motor = new BearingServoMath.Motor(100, 10, 20);
        for (double compensation : new double[]{-.3, .3}) {
            double headRemaining = .012, measured = .2;
            double motorGoal = measured + compensation + .02;
            double bounded = BearingServoMath.boundOperatorMotorGoal(motorGoal,
                    measured + headRemaining, measured, compensation);
            require(Math.abs(BearingServoMath.wrapRadians(bounded - measured) - compensation - headRemaining * .5) < 1e-12,
                    "landing retains the full stabilizer displacement even when it opposes head motion");
            double braked = BearingServoMath.brakeAimMotorGoal(bounded, headRemaining, .4, .05, motor);
            double expected = compensation + headRemaining * .5 - (.4 - headRemaining / .05) * .1;
            require(Math.abs(BearingServoMath.wrapRadians(braked - measured) - expected) < 1e-12,
                    "braking affects only the extracted head rate");
            double old = BearingServoMath.boundAimMotorGoal(motorGoal, measured + compensation + headRemaining, measured);
            require(Math.abs(BearingServoMath.wrapRadians(old - bounded)) > .1,
                    "fixture reproduces the old landing envelope consuming stabilization");
        }
    }

    private static void finiteAcquisitionKeepsItsOwnBudget() {
        for (boolean yaw : new boolean[]{true, false}) {
            for (double movement : new double[]{-1, 1}) {
                var motion = new BearingServoMath.StabilizedAimMotion();
                Quaterniond tilt = new Quaterniond().rotationXYZ(.2, .1, -.15);
                Vector3d axis = tilt.transform(yaw ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0));
                Vec3 up = vec(tilt.transform(new Vector3d(0, 1, 0)));
                Vec3 pivot = new Vec3(0, 0, 0), offset = vec(tilt.transform(new Vector3d(.8, .2, 2)));
                Vec3 forward = vec(tilt.transform(new Vector3d(0, 0, 1)));
                Vec3 selected = yaw ? new Vec3(18, 2, 35) : new Vec3(1, 14, 35);
                double maxStep = Math.toRadians(yaw ? 1 : .5);
                double firstError = correction(pivot, offset, forward, selected, axis, up, yaw);
                double firstStep = motion.advance(firstError, 0, maxStep);
                require(Math.abs(firstStep) <= maxStep + 1e-12 && Math.abs(firstError) > maxStep * 4,
                        "a new finite target begins with bounded acquisition");
                offset = vec(rotate(vector(offset), axis, firstStep));
                forward = vec(rotate(vector(forward), axis, firstStep));
                motion.record(firstError, firstStep);
                Vec3 movedPivot = yaw ? new Vec3(8 * movement, 0, 0) : new Vec3(0, 8 * movement, 0);
                double precedingGoalNow = correction(movedPivot, offset, forward, selected, axis, up, yaw);
                double compensation = motion.compensation(precedingGoalNow);
                require(Math.abs(compensation) > maxStep * 2,
                        "carrier translation requires compensation faster than head speed");
                // Simultaneously select another goal: the preceding goal isolates
                // carrier movement without erasing its unfinished head traverse.
                Vec3 newGoal = selected.add(yaw ? 3 : 0, yaw ? 0 : 3, 0);
                double totalError = correction(movedPivot, offset, forward, newGoal, axis, up, yaw);
                double travel = motion.advance(totalError, compensation, maxStep);
                double headTravel = BearingServoMath.operatorCorrection(travel, compensation);
                require(Math.abs(headTravel) <= maxStep + 1e-12,
                        "finite head motion is capped after automatic movement is removed");
                require(Math.abs(travel - Math.clamp(totalError, -maxStep, maxStep)) > maxStep,
                        "old total-error clamp cannot reproduce separated compensation");
                Vec3 afterOffset = vec(rotate(vector(offset), axis, travel));
                Vec3 afterForward = vec(rotate(vector(forward), axis, travel));
                motion.record(totalError, travel);
                double settledGeometry = correction(movedPivot, afterOffset, afterForward, newGoal, axis, up, yaw);
                require(Math.abs(motion.compensation(settledGeometry)) < 1e-6,
                        "uncompleted acquisition never becomes unlimited compensation on the next tick");
                motion.reset();
                require(motion.compensation(totalError) == 0, "mode changes clear automatic motion history");
            }
        }
    }

    private static void independentAxesKeepIndependentBudgets() {
        var yaw = new BearingServoMath.StabilizedAimMotion();
        var pitch = new BearingServoMath.StabilizedAimMotion();
        yaw.record(.8, .02);
        pitch.record(-.4, -.01);
        double yawCompensation = yaw.compensation(.98), pitchCompensation = pitch.compensation(-.69);
        double yawTravel = yaw.advance(1.2, yawCompensation, .02);
        double pitchTravel = pitch.advance(-.9, pitchCompensation, .01);
        require(Math.abs(yawTravel - yawCompensation - .02) < 1e-12
                        && Math.abs(pitchTravel - pitchCompensation + .01) < 1e-12,
                "direction and elevation retain different signed speed budgets");
        yaw.reset();
        require(Math.abs(pitch.compensation(-.69) - pitchCompensation) < 1e-12,
                "resetting one turret/axis cannot change another axis's compensation");
    }

    private static double correction(Vec3 pivot, Vec3 offset, Vec3 forward, Vec3 target,
                                     Vector3d axis, Vec3 up, boolean yaw) {
        return LegacyFiniteAimSolver.correction(List.of(new LegacyFiniteAimSolver.Ray(
                pivot.add(offset), forward, target, null)), pivot, vec(axis), up, yaw);
    }

    private static Vector3d rotate(Vector3d value, Vector3d axis, double angle) {
        return new Quaterniond().rotationAxis(angle, axis.x, axis.y, axis.z).transform(new Vector3d(value));
    }
    private static Vector3d vector(Vec3 value) { return new Vector3d(value.x, value.y, value.z); }
    private static Vec3 vec(Vector3d value) { return new Vec3(value.x, value.y, value.z); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
