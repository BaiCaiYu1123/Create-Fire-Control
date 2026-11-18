package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

import java.util.Random;

/** Compare whole-flight PN demand with the previous full-rate lead-point controller. */
final class MissileDemandGuidanceChecks {
    private static final MianbaoAircraftCompat.WeaponType[] TYPES = {
            MianbaoAircraftCompat.WeaponType.FAR, MianbaoAircraftCompat.WeaponType.MEDIUM,
            MianbaoAircraftCompat.WeaponType.CLOSE, MianbaoAircraftCompat.WeaponType.MICA_EM,
            MianbaoAircraftCompat.WeaponType.AIM_120D, null};
    private static int checked;

    static void run() {
        demandIsIndependentOfCeiling();
        int oldSaturated = 0, newSaturated = 0;
        double oldPeak = 0, newPeak = 0, oldEffort = 0, newEffort = 0;
        for (var type : TYPES) for (int motion = 0; motion < 3; motion++) {
            var scene = new Scene(type, 1, 7, new Vec3(2000, 300, 1500),
                    motion == 0 ? Vec3.ZERO : new Vec3(0.2, 0, 4), motion == 2, false, false);
            Result previous = fly(scene, false), pn = fly(scene, true);
            require(previous.miss < 1e-5 && pn.miss < 1e-5, type + " comparison must intercept: " + pn);
            oldSaturated += previous.saturated; newSaturated += pn.saturated;
            oldPeak += previous.peakTurn; newPeak += pn.peakTurn;
            oldEffort += previous.effort; newEffort += pn.effort;
            checked += 2;
        }
        require(newSaturated < oldSaturated / 3, "PN should substantially reduce saturation");
        require(newPeak < oldPeak * .7, "PN should reduce peak heading changes");
        require(newEffort < oldEffort, "comparison scenes should use less squared lateral acceleration");
        System.out.printf("PN comparison (18 paired flights): saturated ticks %d -> %d; mean peak turn %.3f -> %.3f degrees; squared-acceleration effort %.3f -> %.3f%n",
                oldSaturated, newSaturated, Math.toDegrees(oldPeak / 18), Math.toDegrees(newPeak / 18), oldEffort, newEffort);

        Random random = new Random(0x120D2026);
        for (var type : TYPES) for (int i = 0; i < 40; i++) {
            double bearing = Math.toRadians(5 + random.nextDouble() * 75);
            double range = 1100 + random.nextDouble() * 900;
            Vec3 target = new Vec3(Math.cos(bearing) * range, random.nextDouble() * 300 - 150,
                    Math.sin(bearing) * range);
            Vec3 velocity = new Vec3(random.nextDouble() * 4 - 2, random.nextDouble() - .5,
                    random.nextDouble() * 6 - 3);
            var scene = new Scene(type, i < 20 ? 1 : 100, i < 20 ? 7 : 34.03,
                    target, velocity, i % 3 == 0, i % 4 == 0,
                    i % 7 == 0 && i % 3 != 0 && i % 4 != 0);
            Result result = fly(scene, true);
            require(result.miss < hitTolerance(scene),
                    type + " seeded flight " + i + " must intercept: " + result);
            checked++;
        }
        for (var type : new MianbaoAircraftCompat.WeaponType[]{
                MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT,
                MianbaoAircraftCompat.WeaponType.LASER_GUIDED}) {
            for (int i = 0; i < 2; i++) {
                var scene = new Scene(type, 1, 4, new Vec3(1400, -150, 600),
                        type == MianbaoAircraftCompat.WeaponType.LASER_GUIDED ? Vec3.ZERO : new Vec3(0, 0, i * 2),
                        false, false, false);
                require(fly(scene, true).miss < 1e-5, type + " must intercept using its speed and overload envelope");
                checked++;
            }
        }
        System.out.println("Demand guidance checks passed (" + checked + " flights/checks, including noisy scans and continuous manoeuvres)");
    }

    private static void demandIsIndependentOfCeiling() {
        Vec3 heading = new Vec3(1, 0, 0);
        var profile = new AcceleratingMissileIntercept.SpeedProfile(34.03, 34.03, 100);
        Vec3 offset = new Vec3(Math.cos(.2), 0, Math.sin(.2));
        Vec3 far = AcceleratingMissileIntercept.guide(Vec3.ZERO, heading, offset.scale(5000),
                Vec3.ZERO, profile, 0, 400, Math.toRadians(10));
        Vec3 moreAuthority = AcceleratingMissileIntercept.guide(Vec3.ZERO, heading, offset.scale(5000),
                Vec3.ZERO, profile, 0, 400, Math.toRadians(56));
        require(far.distanceTo(moreAuthority) < 1e-12, "raising the limit must not increase an unsaturated demand");
        double farTurn = angle(heading, far);
        double nearTurn = angle(heading, AcceleratingMissileIntercept.guide(Vec3.ZERO, heading, offset.scale(1000),
                Vec3.ZERO, profile, 0, 400, Math.toRadians(56)));
        require(farTurn > 0 && farTurn < Math.toRadians(1), "small far-field error should use a light correction");
        require(nearTurn > farTurn * 4.9 && nearTurn < farTurn * 5.1, "PN demand must reflect time to impact");
        double smaller = angle(heading, AcceleratingMissileIntercept.guide(Vec3.ZERO, heading,
                new Vec3(Math.cos(.1), 0, Math.sin(.1)).scale(5000), Vec3.ZERO, profile, 0, 400, Math.toRadians(56)));
        require(smaller < farTurn * .55, "smaller error should request less correction");
        Vec3 urgent = AcceleratingMissileIntercept.guide(Vec3.ZERO, heading, new Vec3(80, 0, 80),
                Vec3.ZERO, profile, 0, 400, .1);
        require(Math.abs(angle(heading, urgent) - .1) < 1e-9, "urgent interception can still use full authority");
        checked++;
    }

    private record Scene(MianbaoAircraftCompat.WeaponType type, int age, double launchSpeed,
                         Vec3 target, Vec3 velocity, boolean manoeuvre, boolean noisy, boolean lostContact) {}
    private record Result(double miss, int saturated, double peakTurn, double effort) {}

    private static Result fly(Scene scene, boolean proportional) {
        var type = scene.type;
        int lifetime = type == null ? 400 : MianbaoAircraftCompat.lifetimeTicks(type);
        int delay = type == null ? MianbaoAirDefenseCompat.guidanceDelayTicks() : MianbaoAircraftCompat.maneuverDelayTicks(type);
        var profile = type == null ? MianbaoAirDefenseCompat.speedProfile(scene.launchSpeed)
                : MianbaoAircraftCompat.speedProfile(type, scene.launchSpeed);
        double navigation = type == MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT ? 4 : 3;
        Vec3 missile = Vec3.ZERO, heading = new Vec3(1, 0, 0);
        Vec3 target = scene.target, velocity = scene.velocity, sample = target, sampleVelocity = velocity;
        Vec3 previousTrueSample = target;
        long sampleTick = 0;
        double miss = Double.POSITIVE_INFINITY, peak = 0, effort = 0;
        int saturated = 0;
        for (int tick = 0; scene.age + tick < lifetime; tick++) {
            int age = scene.age + tick;
            if (scene.manoeuvre && tick >= 40 && tick < 110) {
                double yaw = tick < 75 ? .015 : -.015;
                velocity = new Vec3(velocity.x * Math.cos(yaw) - velocity.z * Math.sin(yaw),
                        velocity.y, velocity.x * Math.sin(yaw) + velocity.z * Math.cos(yaw));
            }
            if (tick % 2 == 0 && (!scene.lostContact || tick < 30)) {
                // Bounded scan jitter also perturbs the measured velocity.
                Vec3 noise = scene.noisy ? new Vec3(Math.sin(tick * 1.7), Math.cos(tick * .9), Math.sin(tick * 2.3)).scale(.08) : Vec3.ZERO;
                Vec3 nextSample = target.add(noise);
                sampleVelocity = tick == 0 ? velocity : nextSample.subtract(previousTrueSample).scale(.5);
                sample = nextSample; previousTrueSample = nextSample; sampleTick = tick;
            }
            Vec3 estimate = AcceleratingMissileIntercept.targetPositionAt(sample, sampleVelocity, sampleTick, tick);
            double speed = profile.speedAt(age);
            double limit = type == null ? MianbaoAirDefenseCompat.maxTurnRadians(speed)
                    : MianbaoAircraftCompat.interceptMaxTurnRadians(type, speed);
            Vec3 next = heading;
            if (age > delay) {
                if (proportional) next = AcceleratingMissileIntercept.guide(missile, heading, estimate, sampleVelocity,
                        profile, age, lifetime - age, limit, navigation);
                else {
                    var solution = AcceleratingMissileIntercept.solve(missile, estimate, sampleVelocity, profile, age, lifetime - age);
                    next = AcceleratingMissileIntercept.rotateTowards(heading,
                            solution == null ? estimate.subtract(missile) : solution.direction(), limit);
                }
            }
            double turn = angle(heading, next);
            require(turn <= limit + 1e-7, "whole-flight angular ceiling");
            if (type != null) require(speed * Math.tan(turn) <= MianbaoAircraftCompat.maxLateralAcceleration(type) + 1e-6,
                    "whole-flight overload ceiling");
            if (age <= delay) require(turn < 1e-7, "launch clearance");
            if (limit > 0 && turn >= limit * .999) saturated++;
            peak = Math.max(peak, turn);
            double acceleration = speed * Math.tan(turn);
            effort += acceleration * acceleration;
            Vec3 nextMissile = missile.add(next.scale(speed)), nextTarget = target.add(velocity);
            Vec3 relative = target.subtract(missile), step = nextTarget.subtract(nextMissile).subtract(relative);
            double f = step.lengthSqr() < 1e-12 ? 0 : Math.max(0, Math.min(1, -relative.dot(step) / step.lengthSqr()));
            miss = Math.min(miss, relative.add(step.scale(f)).length());
            if (miss < hitTolerance(scene)) break;
            missile = nextMissile; heading = next; target = nextTarget;
        }
        return new Result(miss, saturated, peak, effort);
    }

    private static double angle(Vec3 a, Vec3 b) {
        return Math.acos(Math.max(-1, Math.min(1, a.normalize().dot(b.normalize()))));
    }
    private static double hitTolerance(Scene scene) {
        // A target still accelerating between two radar samples cannot have
        // an exact future centre predicted. Require a sub-block closest pass
        // (smaller than the target body/fuze), while constant-motion cases
        // retain the exact swept-interception requirement.
        return scene.noisy ? .5 : scene.manoeuvre ? .25 : 1e-5;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
