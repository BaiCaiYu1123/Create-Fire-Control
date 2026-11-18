package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/** Finite-range regressions using the production inverse ballistics and CBC flight equations. */
public final class FiniteMuzzleVelocityChecks {
    private static final Vec3 FORWARD = new Vec3(0.0, 0.0, 1.0);
    private static final Vec3 MUZZLE_RADIUS = new Vec3(0.0, 0.0, 8.0);
    private static final double MUZZLE_SPEED = 8.0; // Blocks/tick, or 160 blocks/second.
    private static final double STRICT_ANGLE_DEGREES = 0.05;
    private static int checks;
    private static double maximumCorrectedAngle;
    private static double maximumCorrectedMiss;

    public static void main(String[] args) {
        for (double range : new double[]{100.0, 500.0}) {
            for (double rate : new double[]{120.0, 360.0}) {
                for (double sign : new double[]{-1.0, 1.0}) {
                    for (boolean yaw : new boolean[]{true, false}) {
                        demonstratesFictitiousCarrierRotation(range, rate * sign, yaw);
                        preservesRealTranslation(range, rate * sign, yaw);
                    }
                }
            }
        }
        for (double range : new double[]{100.0, 500.0}) {
            for (boolean quadratic : new boolean[]{false, true}) {
                preservesGravityDragAndTranslation(range, quadratic);
            }
        }
        System.out.printf(Locale.ROOT,
                "PASS: %d finite-target cases; max corrected plane miss %.9f blocks, angle %.9f degrees; "
                        + "signed carrier rotation reproduced without discarding true inherited velocity%n",
                checks, maximumCorrectedMiss, maximumCorrectedAngle);
    }

    private static void demonstratesFictitiousCarrierRotation(double range, double rate, boolean yaw) {
        Vec3 target = FORWARD.scale(range);
        Vec3 fictitious = carrierRotationAtMuzzle(rate, yaw);
        var actual = profile(Vec3.ZERO, 0.0, 0.0, false);
        var oldFeedForward = profile(fictitious, 0.0, 0.0, false);
        Vec3 oldDirection = solve(oldFeedForward, target);
        Vec3 oldImpact = impactAtTargetPlane(actual, oldDirection, target);
        Vec3 correctedDirection = solve(actual, target);
        Vec3 correctedImpact = impactAtTargetPlane(actual, correctedDirection, target);
        double expectedAngle = Math.toDegrees(Math.asin(fictitious.length() / MUZZLE_SPEED));
        double oldAngle = targetAngle(oldImpact, target);

        require(Math.abs(oldAngle - expectedAngle) < 0.001,
                description(range, rate, yaw) + " must reproduce the production solver's false lead: "
                        + oldAngle + " instead of " + expectedAngle);
        require(oldImpact.subtract(target).dot(fictitious) < 0.0,
                description(range, rate, yaw) + " finite impact must drift opposite carrier rotation");
        require(oldDirection.dot(fictitious) < 0.0,
                description(range, rate, yaw) + " barrel must acquire the fictitious opposite lead");
        double approximateAngle = Math.abs(rate) == 120.0 ? 6.0 : 18.0;
        require(Math.abs(oldAngle - approximateAngle) < 0.4,
                description(range, rate, yaw) + " must reproduce approximately 6/18 degree bias");
        verifyCorrected(correctedImpact, target, "stationary true muzzle " + description(range, rate, yaw));
        require(angle(correctedDirection, FORWARD) < STRICT_ANGLE_DEGREES,
                "The corrected stationary muzzle must retain its world direction");
        checks++;
    }

    private static void preservesRealTranslation(double range, double rate, boolean yaw) {
        Vec3 target = FORWARD.scale(range);
        // This velocity belongs to the actual stable barrel. It must survive the correction.
        Vec3 trueVelocity = new Vec3(0.25, -0.10, 0.20);
        Vec3 fictitious = carrierRotationAtMuzzle(rate, yaw);
        var actual = profile(trueVelocity, 0.0, 0.0, false);
        var oldFeedForward = profile(trueVelocity.add(fictitious), 0.0, 0.0, false);
        Vec3 correctedDirection = solve(actual, target);
        Vec3 correctedImpact = impactAtTargetPlane(actual, correctedDirection, target);
        Vec3 oldImpact = impactAtTargetPlane(actual, solve(oldFeedForward, target), target);
        Vec3 zeroedImpact = impactAtTargetPlane(actual,
                solve(profile(Vec3.ZERO, 0.0, 0.0, false), target), target);

        verifyCorrected(correctedImpact, target, "translated true muzzle " + description(range, rate, yaw));
        require(targetAngle(oldImpact, target) > 5.0,
                "A translated barrel still exposes fictitious carrier angular compensation");
        require(oldImpact.subtract(target).dot(fictitious) < 0.0,
                "False angular compensation must retain its opposite drift under real translation");
        require(targetAngle(zeroedImpact, target) > 1.0,
                "Replacing inherited velocity with zero must fail for a moving barrel");
        require(correctedDirection.x < 0.0 && correctedDirection.y > 0.0,
                "Corrected aim must compensate the actual rightward/downward translation");
        checks++;
    }

    private static void preservesGravityDragAndTranslation(double range, boolean quadratic) {
        Vec3 target = FORWARD.scale(range);
        Vec3 trueVelocity = new Vec3(0.25, -0.10, 0.20);
        double drag = quadratic ? 0.00015 : 0.001;
        var actual = profile(trueVelocity, -0.025, drag, quadratic);
        Vec3 correctedDirection = solve(actual, target);
        Vec3 correctedImpact = impactAtTargetPlane(actual, correctedDirection, target);
        verifyCorrected(correctedImpact, target,
                "gravity/" + (quadratic ? "quadratic" : "linear") + " drag at " + range);

        var zeroed = profile(Vec3.ZERO, actual.gravity(), actual.drag(), quadratic);
        Vec3 zeroedImpact = impactAtTargetPlane(actual, solve(zeroed, target), target);
        require(targetAngle(zeroedImpact, target) > 1.0,
                "Real inherited translation must remain active for gravity and drag projectiles");
        require(correctedDirection.x < 0.0 && correctedDirection.y > 0.0,
                "Both gravity and true carrier translation must affect the inverse ballistic solution");
        checks++;
    }

    private static Vec3 carrierRotationAtMuzzle(double degreesPerSecond, boolean yaw) {
        double radiansPerSecond = Math.toRadians(degreesPerSecond);
        Vec3 angularVelocity = yaw
                ? new Vec3(0.0, radiansPerSecond, 0.0)
                : new Vec3(radiansPerSecond, 0.0, 0.0);
        // The obsolete compensation assumes the stabilized eight-block barrel rotates with the carrier.
        return angularVelocity.cross(MUZZLE_RADIUS).scale(1.0 / 20.0);
    }

    private static CannonBallisticsCompat.ShotProfile profile(
            Vec3 inheritedVelocity, double gravity, double drag, boolean quadratic) {
        return new CannonBallisticsCompat.ShotProfile(
                Vec3.ZERO, FORWARD, inheritedVelocity, MUZZLE_SPEED, gravity, drag, quadratic,
                "finite-muzzle-regression", "stable-physical-barrel", 8, 1, 1200, 1.0, 1.0, 0.0);
    }

    private static Vec3 solve(CannonBallisticsCompat.ShotProfile profile, Vec3 target) {
        Vec3 direction = CannonBallisticsCompat.solveLaunchDirection(profile, target);
        require(direction != null && Double.isFinite(direction.lengthSqr()) && direction.lengthSqr() > 0.99,
                "The reachable finite target must have a valid production launch solution");
        return direction;
    }

    private static Vec3 impactAtTargetPlane(
            CannonBallisticsCompat.ShotProfile actual, Vec3 launchDirection, Vec3 target) {
        Vec3 normal = target.subtract(actual.muzzle()).normalize();
        Vec3 impact = CannonProjectileFlight.trace(actual.muzzle(),
                launchDirection.scale(actual.muzzleSpeed()).add(actual.carrierVelocity()),
                actual.gravity(), actual.drag(), actual.quadraticDrag(), actual.maxFlightTicks(),
                -Double.MAX_VALUE, (from, to) -> {
                    double before = from.subtract(target).dot(normal);
                    double after = to.subtract(target).dot(normal);
                    if (before > 0.0 || after < 0.0 || after <= before) return null;
                    return from.lerp(to, -before / (after - before));
                });
        require(impact != null, "The actual CBC trajectory must cross the selected finite target plane");
        return impact;
    }

    private static void verifyCorrected(Vec3 impact, Vec3 target, String description) {
        double miss = impact.distanceTo(target);
        double angle = targetAngle(impact, target);
        maximumCorrectedMiss = Math.max(maximumCorrectedMiss, miss);
        maximumCorrectedAngle = Math.max(maximumCorrectedAngle, angle);
        require(miss < 0.02,
                description + " must hit its finite plane within 0.02 blocks, got " + miss);
        require(angle < STRICT_ANGLE_DEGREES,
                description + " must remain below strict 0.05 degree aim error, got " + angle);
    }

    private static double targetAngle(Vec3 impact, Vec3 target) {
        return angle(impact, target);
    }

    private static double angle(Vec3 first, Vec3 second) {
        double dot = first.normalize().dot(second.normalize());
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    private static String description(double range, double rate, boolean yaw) {
        return (yaw ? "yaw" : "pitch") + " " + rate + " degrees/second at " + range + " blocks";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private FiniteMuzzleVelocityChecks() {}
}
