package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

/** Independent closed-form oracle for Mianbao's first-tick velocity cache. */
public final class MianbaoRocketBallisticsChecks {
    public static void main(String[] args) {
        for (double drop : new double[]{0.025, 0.03}) {
            for (double drag : new double[]{(double) 0.99F, (double) 0.6F}) {
                for (Vec3 velocity : new Vec3[]{new Vec3(0, 0, 10), new Vec3(9, 1, -2),
                        new Vec3(-1.4, -0.3, 10.8), new Vec3(2.1, 2.5, 8.4)}) {
                    checkEverySegment(velocity, drag, drop);
                    checkImpact(velocity, drag, drop);
                }
            }
        }
        checkArmingAndTimeout();
        checkInvalidInput();
        System.out.println("PASS: native rocket flight, carrier motion, ordinary/heavy drop, water launch, arming and timeout");
    }

    private static void checkEverySegment(Vec3 initial, double drag, double drop) {
        Vec3 origin = new Vec3(17, 100, -29);
        int[] tick = {0};
        MianbaoRocketFlight.trace(origin, initial, 300, 0, drag, drop, -10000, (from, to) -> {
            near(from, position(origin, initial, tick[0], drag, drop), "segment start");
            near(to, position(origin, initial, ++tick[0], drag, drop), "segment end");
            return null;
        });
        require(tick[0] == 300, "native 300-tick lifetime");
    }

    private static void checkImpact(Vec3 initial, double drag, double drop) {
        Vec3 origin = new Vec3(17, 100, -29);
        // Locate the ground crossing with a closed-form position, then interpolate
        // exactly as the game's block sweep does within that single tick.
        Vec3 expected = null;
        for (int tick = 1; tick <= 300; tick++) {
            Vec3 from = position(origin, initial, tick - 1, drag, drop);
            Vec3 to = position(origin, initial, tick, drag, drop);
            if (from.y >= 0 && to.y <= 0) {
                expected = from.lerp(to, from.y / (from.y - to.y));
                break;
            }
        }
        require(expected != null, "oracle reaches ground");
        Vec3 actual = MianbaoRocketFlight.trace(origin, initial, 300, 10, drag, drop,
                -10000, (from, to) -> from.y >= 0 && to.y <= 0
                        ? from.lerp(to, from.y / (from.y - to.y)) : null);
        near(actual, expected, "swept ground impact");
    }

    private static Vec3 position(Vec3 origin, Vec3 initial, int ticks, double drag, double drop) {
        if (ticks == 0) return origin;
        double cachedTicks = ticks - 1;
        double forwardTime = 1 + drag * cachedTicks;
        return origin.add(initial.scale(forwardTime)).add(0,
                -0.05 * cachedTicks - drop * cachedTicks * (cachedTicks - 1) / 2, 0);
    }

    private static void checkArmingAndTimeout() {
        Vec3 hit = MianbaoRocketFlight.trace(Vec3.ZERO, new Vec3(10, 0, 0),
                300, 10, (double) 0.99F, 0.025, -10000, (from, to) -> from);
        near(hit, position(Vec3.ZERO, new Vec3(10, 0, 0), 9, (double) 0.99F, 0.025),
                "first contact is armed on tick 10");
        int[] queries = {0};
        MianbaoRocketFlight.trace(Vec3.ZERO, new Vec3(10, 0, 0),
                Integer.MAX_VALUE, 10, (double) 0.99F, 0.025, -10000,
                (from, to) -> { queries[0]++; return null; });
        require(queries[0] == 291, "no predicted contacts beyond native timeout");
    }

    private static void checkInvalidInput() {
        Vec3 hit = MianbaoRocketFlight.trace(Vec3.ZERO, new Vec3(Double.NaN, 0, 0),
                300, 10, 0.99, 0.025, -10000,
                (from, to) -> { throw new AssertionError("invalid trajectory queried terrain"); });
        require(hit == null, "invalid velocity rejected");
    }

    private static void near(Vec3 actual, Vec3 expected, String message) {
        require(actual != null && actual.distanceTo(expected) < 1e-8,
                message + ": actual=" + actual + ", expected=" + expected);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private MianbaoRocketBallisticsChecks() {}
}
