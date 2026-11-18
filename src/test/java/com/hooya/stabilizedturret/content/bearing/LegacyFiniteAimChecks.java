package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;

/** Regression fixtures for the reported pose-lock block offset and close-range reversal. */
public final class LegacyFiniteAimChecks {
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final Vec3 FORWARD = new Vec3(0, 0, 1);

    public static void main(String[] args) {
        closeRangeConvergesWithoutReversing();
        eccentricMuzzleAndTiltedParent();
        gravityUsesThePoseLockShotVelocity();
        unreachableTargetDoesNotSpin();
        sharedYawKeepsBothMuzzles();
        pitchDoesNotFollowRearwardAzimuth();
        physicalServoKeepsInheritedShotVelocity();
        closeScopeAndMuzzleAreSolvedTogether();
        System.out.println("PASS: legacy finite aim converges at close range and compensates the actual shot");
    }

    private static void closeRangeConvergesWithoutReversing() {
        Vec3 target = new Vec3(1, 0, 10), localMuzzle = new Vec3(0, 0, 8);
        double oldAngle = .2, newAngle = .2, previousError = Double.POSITIVE_INFINITY;
        for (int tick = 0; tick < 30; tick++) {
            var old = yawRay(localMuzzle, target, oldAngle);
            double oldError = angle(old.forward(), target.subtract(old.muzzle()).normalize(), UP);
            oldAngle += Math.clamp(oldError, -Math.toRadians(5), Math.toRadians(5));
            var current = yawRay(localMuzzle, target, newAngle);
            double correction = LegacyFiniteAimSolver.correction(List.of(current), Vec3.ZERO, UP, UP, true);
            require(Math.abs(correction) <= previousError + 1e-9, "close-range inverse converges without reversing");
            previousError = Math.abs(correction);
            newAngle += Math.clamp(correction, -Math.toRadians(5), Math.toRadians(5));
        }
        require(Math.abs(newAngle - Math.atan2(1, 10)) < 1e-9, "muzzle parallax converges at a two-block clearance");
        require(Math.abs(oldAngle - Math.atan2(1, 10)) > .01, "fixture reproduces the old alternating correction");
    }

    private static void eccentricMuzzleAndTiltedParent() {
        Vec3 localMuzzle = new Vec3(2, 0, 8), target = new Vec3(0, 0, 12);
        double expected = -Math.asin(2.0 / 12);
        Quaterniond parent = new Quaterniond().rotationXYZ(.3, .8, -.2);
        Vec3 axis = rotate(parent, UP), forward = rotate(parent, FORWARD);
        var ray = new LegacyFiniteAimSolver.Ray(rotate(parent, localMuzzle), forward, rotate(parent, target), null);
        double actual = LegacyFiniteAimSolver.correction(List.of(ray), Vec3.ZERO, axis, axis, true);
        require(Math.abs(actual - expected) < 1e-9, "eccentric muzzle is solved around its parent-relative axis");
    }

    private static void gravityUsesThePoseLockShotVelocity() {
        Vec3 muzzle = new Vec3(0, 0, 8), target = new Vec3(0, 0, 120), axis = new Vec3(1, 0, 0);
        var profile = new CannonBallisticsCompat.ShotProfile(muzzle, FORWARD, new Vec3(1, 0, 0),
                8, -.05, .01, false, "test", "test", 8, 1, 200, 1, 1, 0);
        var ray = new LegacyFiniteAimSolver.Ray(muzzle, FORWARD, target, profile);
        double correction = LegacyFiniteAimSolver.correction(List.of(ray), Vec3.ZERO, axis, UP, false);
        require(correction < -.01, "finite aim raises the barrel for gravity instead of aiming straight at the block");
        Quaterniond rotation = new Quaterniond().rotationAxis(correction, axis.x, axis.y, axis.z);
        Vec3 actualMuzzle = rotate(rotation, muzzle), actualForward = rotate(rotation, FORWARD);
        Vec3 required = CannonBallisticsCompat.solveLaunchDirection(CannonBallisticsCompat.withFrame(
                profile, actualMuzzle, actualForward, Vec3.ZERO), target);
        require(required != null && Math.abs(angle(actualForward, required, axis)) < 1e-7,
                "barrel and compensated outgoing shot agree after rotating the muzzle: correction=" + correction
                        + " forward=" + actualForward + " required=" + required
                        + " residual=" + (required == null ? null : angle(actualForward, required, axis)));
        var stoppedProfile = CannonBallisticsCompat.withCarrierVelocity(profile, Vec3.ZERO);
        double stopped = LegacyFiniteAimSolver.correction(List.of(new LegacyFiniteAimSolver.Ray(
                muzzle, FORWARD, target, stoppedProfile)), Vec3.ZERO, axis, UP, false);
        require(Math.abs(stopped - correction) < 1e-12, "pose lock never feeds carrier/aiming velocity back into ballistics");
    }

    private static void unreachableTargetDoesNotSpin() {
        Vec3 localMuzzle = new Vec3(0, 0, 8), target = new Vec3(0, 0, 4);
        double orientation = .3;
        for (int tick = 0; tick < 20; tick++) {
            var ray = yawRay(localMuzzle, target, orientation);
            orientation += Math.clamp(LegacyFiniteAimSolver.correction(List.of(ray), Vec3.ZERO, UP, UP, true), -.1, .1);
        }
        require(Math.abs(orientation) < 1e-9, "a block behind the muzzle selects its nearest attainable pose without half-turns");
    }

    private static void sharedYawKeepsBothMuzzles() {
        var left = new LegacyFiniteAimSolver.Ray(new Vec3(-2, 0, 8), FORWARD, new Vec3(0, 0, 50), null);
        var right = new LegacyFiniteAimSolver.Ray(new Vec3(2, 0, 8), FORWARD, new Vec3(0, 0, 50), null);
        require(Math.abs(LegacyFiniteAimSolver.correction(List.of(left, right), Vec3.ZERO, UP, UP, true)) < 1e-10,
                "shared yaw retains equal contributions from both physical muzzles");
    }

    private static void pitchDoesNotFollowRearwardAzimuth() {
        var ray = new LegacyFiniteAimSolver.Ray(new Vec3(0, 0, 8), FORWARD, new Vec3(20, 3, -50), null);
        double correction = LegacyFiniteAimSolver.correction(List.of(ray), Vec3.ZERO, new Vec3(1, 0, 0), UP, false);
        require(correction < 0 && Math.abs(correction) < .1, "a rearward target requests only its elevation, never a pitch half-turn");
    }

    private static void physicalServoKeepsInheritedShotVelocity() {
        Vec3 muzzle = new Vec3(0, 0, 8), target = new Vec3(0, 0, 40), velocity = new Vec3(.3, 0, 0);
        var profile = new CannonBallisticsCompat.ShotProfile(muzzle, FORWARD, velocity,
                8, -.05, .01, false, "test", "test", 8, 1, 200, 1, 1, 0);
        var ray = new LegacyFiniteAimSolver.Ray(muzzle, FORWARD, target, profile, velocity, null);
        double correction = LegacyFiniteAimSolver.correction(List.of(ray), Vec3.ZERO, UP, UP, true);
        Quaterniond rotation = new Quaterniond().rotationY(correction);
        Vec3 rotatedMuzzle = rotate(rotation, muzzle), rotatedBore = rotate(rotation, FORWARD);
        Vec3 launch = CannonBallisticsCompat.solveLaunchDirection(CannonBallisticsCompat.withFrame(
                profile, rotatedMuzzle, rotatedBore, velocity), target);
        require(correction < -.01 && launch != null && Math.abs(angle(rotatedBore, launch, UP)) < 1e-7,
                "reaction servo inverse compensates real shot inheritance while rotating its muzzle");
    }

    private static void closeScopeAndMuzzleAreSolvedTogether() {
        Vec3 muzzle = new Vec3(0, 0, 8), optic = new Vec3(.4, 0, 2);
        Vec3 sight = new Vec3(.08, 0, 1).normalize();
        Vec3 normal = new Vec3(0, 0, -1), surface = new Vec3(0, 0, 10);
        var moving = new MovingAimRay(new java.util.UUID(0, 1), optic, sight, surface, normal);
        java.util.function.DoubleFunction<Vec3> targetAtAngle = angle -> moving.intersect(
                new Quaterniond().rotationY(angle).transform(new Vector3d(optic.x, optic.y, optic.z)), null);
        double orientation = -.15, previous = Double.POSITIVE_INFINITY;
        for (int tick = 0; tick < 40; tick++) {
            final double current = orientation;
            var ray = new LegacyFiniteAimSolver.Ray(rotate(new Quaterniond().rotationY(current), muzzle),
                    rotate(new Quaterniond().rotationY(current), FORWARD), targetAtAngle.apply(current), null,
                    Vec3.ZERO, delta -> targetAtAngle.apply(current + delta));
            double correction = LegacyFiniteAimSolver.correction(List.of(ray), Vec3.ZERO, UP, UP, true);
            require(Math.abs(correction) <= previous + 1e-7,
                    "scope-origin movement cannot rearm alternating close-range corrections");
            previous = Math.abs(correction);
            orientation += Math.clamp(correction, -.035, .035);
        }
        Vec3 actualMuzzle = rotate(new Quaterniond().rotationY(orientation), muzzle);
        Vec3 actualBore = rotate(new Quaterniond().rotationY(orientation), FORWARD);
        require(Math.abs(angle(actualBore, targetAtAngle.apply(orientation).subtract(actualMuzzle).normalize(), UP)) < 1e-7,
                "close physical face, offset scope and eight-block barrel converge together");
    }

    private static LegacyFiniteAimSolver.Ray yawRay(Vec3 muzzle, Vec3 target, double angle) {
        Quaterniond rotation = new Quaterniond().rotationY(angle);
        return new LegacyFiniteAimSolver.Ray(rotate(rotation, muzzle), rotate(rotation, FORWARD), target, null);
    }
    private static double angle(Vec3 from, Vec3 to, Vec3 axis) {
        from = from.subtract(axis.scale(from.dot(axis)));
        to = to.subtract(axis.scale(to.dot(axis)));
        return Math.atan2(axis.dot(from.cross(to)), from.dot(to));
    }
    private static Vec3 rotate(Quaterniond rotation, Vec3 value) {
        Vector3d transformed = rotation.transform(new Vector3d(value.x, value.y, value.z));
        return new Vec3(transformed.x, transformed.y, transformed.z);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
