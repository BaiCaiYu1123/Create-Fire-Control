package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

/** Ammunition, muzzle offset and carrier velocity must agree between aiming and swept impacts. */
public final class CannonImpactPredictionChecks {
    public static void main(String[] args) {
        checkDiscreteMotion();
        for (boolean quadratic : new boolean[]{false, true}) {
            for (Vec3 carrier : new Vec3[]{Vec3.ZERO, new Vec3(0.8, 0.05, 0.2)}) {
                solveAndTrace(new Vec3(8, 70, -12), new Vec3(26, 70, 220), carrier,
                        -0.05, quadratic ? 0.0002 : 0.002, quadratic, 9.0);
                solveAndTrace(new Vec3(-5, 92, 4), new Vec3(-50, 68, 320), carrier,
                        -0.025, quadratic ? 0.00005 : 0.001, quadratic, 12.0);
            }
        }
        solveAndTrace(new Vec3(40, 100, 25), new Vec3(45, 100, 40), new Vec3(1.0, 0, 0),
                -0.05, 0, false, 9.0);
        checkTraceBounds();
        checkUnreachableTarget();
        System.out.println("PASS: cannon swept motion, ammunition, carrier velocity and muzzle-offset impacts");
    }

    private static void checkUnreachableTarget() {
        var profile = new CannonBallisticsCompat.ShotProfile(new Vec3(0, 100, 0), new Vec3(0, 0, 1),
                Vec3.ZERO, 4, -0.05, 0.01, false, "short_range_shell", "autocannon",
                5, 1, 400, 1, 1, 1);
        require(CannonBallisticsCompat.solveLaunchDirection(profile, new Vec3(0, 100, 4096)) == null,
                "unreachable target must not return a steep nearest-trajectory direction");
    }

    private static void checkDiscreteMotion() {
        Vec3 position = new Vec3(2, 30, -1), velocity = new Vec3(3, 4, 0);
        for (boolean quadratic : new boolean[]{false, true}) {
            double force = 0.01 * (quadratic ? 25 : 5);
            Vec3 acceleration = new Vec3(-3 * force / 5, -4 * force / 5 - 0.05, 0);
            var step = CannonProjectileFlight.step(position, velocity, -0.05, 0.01, quadratic);
            near(step.position(), position.add(velocity).add(acceleration.scale(0.5)), 1e-10, "CBC half-acceleration position");
            near(step.velocity(), velocity.add(acceleration), 1e-10, "CBC full-acceleration velocity");
        }
    }

    private static void solveAndTrace(Vec3 muzzle, Vec3 target, Vec3 carrier,
                                     double gravity, double drag, boolean quadratic, double speed) {
        var profile = new CannonBallisticsCompat.ShotProfile(muzzle, target.subtract(muzzle).normalize(),
                carrier, speed, gravity, drag, quadratic, "test_ammunition", "autocannon",
                5, 1, 400, 1, 1, 1);
        Vec3 direction = CannonBallisticsCompat.solveLaunchDirection(profile, target);
        require(direction != null, "reachable ammunition solution");
        // Sweep against a plane through the target, including hits between integer tick positions.
        Vec3 hit = CannonProjectileFlight.trace(muzzle, direction.scale(speed).add(carrier),
                gravity, drag, quadratic, 400, -100, (from, to) -> {
                    if (from.z > target.z || to.z < target.z || to.z == from.z) return null;
                    return from.lerp(to, (target.z - from.z) / (to.z - from.z));
                });
        require(hit != null, "predicted trajectory crosses target plane");
        near(hit, target, 0.12, "solved direction meets actual swept impact for loaded ammunition");
        if (carrier.x > 0.5) require(direction.x < target.subtract(muzzle).normalize().x,
                "cannon compensates inherited lateral velocity");
    }

    private static void checkTraceBounds() {
        int[] calls = {0};
        Vec3 hit = CannonProjectileFlight.trace(Vec3.ZERO, new Vec3(0, 0, 1), 0, 0, false,
                Integer.MAX_VALUE, -100, (from, to) -> { calls[0]++; return null; });
        require(hit == null && calls[0] == 1200, "flight-time budget bounds misses");
        calls[0] = 0;
        CannonProjectileFlight.trace(Vec3.ZERO, new Vec3(0, 0, 100), 0, 0, false,
                1200, -100, (from, to) -> { calls[0]++; return null; });
        require(calls[0] <= 41, "range budget bounds fast ammunition");
        Vec3 contact = new Vec3(0, 0, 0.25);
        near(CannonProjectileFlight.trace(Vec3.ZERO, new Vec3(0, 0, 10), 0, 0, false,
                1200, -100, (from, to) -> contact), contact, 0, "first swept contact returned unchanged");
    }

    private static void near(Vec3 actual, Vec3 expected, double tolerance, String message) {
        require(actual.distanceTo(expected) <= tolerance, message + ": error=" + actual.distanceTo(expected));
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private CannonImpactPredictionChecks() {}
}
