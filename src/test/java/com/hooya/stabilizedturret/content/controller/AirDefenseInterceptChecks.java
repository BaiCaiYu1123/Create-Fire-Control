package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

import java.util.Random;

/** Trajectory checks using the same intercept solver and steering as bound AA launchers. */
public final class AirDefenseInterceptChecks {
    private static final double MAX_STEER = Math.toRadians(13.0);
    private static final int LIFETIME = 400;
    private static final AcceleratingMissileIntercept.SpeedProfile PROFILE =
            MianbaoAirDefenseCompat.speedProfile(2.0);
    private static int scenarios;

    public static void main(String[] args) {
        com.hooya.stabilizedturret.content.display.MovingMissileFuzeChecks.main(args);
        verifySpeedAndTiming();
        verifySolverEdges();
        verifySteering();
        verifyAim120LaunchTransition();
        verifyAllLaunchLimits();
        verifyStraightCourseInvariant();

        trajectory("crossing, already aligned", new Vec3(1000, 200, 0),
                new Vec3(0, 10, 0), null, 11, -1, -1, Vec3.ZERO);
        trajectory("crossing, launcher clearance and upward acquisition", new Vec3(1000, 200, 0),
                new Vec3(0, 10, 0), new Vec3(0, 1, 0), 0, -1, -1, Vec3.ZERO);
        trajectory("three dimensional crossing", new Vec3(1200, 800, -350),
                new Vec3(3, -0.7, 6), new Vec3(0, 1, 0), 11, -1, -1, Vec3.ZERO);
        trajectory("head on", new Vec3(1800, 100, 300),
                new Vec3(-12, 0, -1), new Vec3(0, 0, 1), 11, -1, -1, Vec3.ZERO);
        trajectory("tail chase through speed cap", new Vec3(400, 100, 0),
                new Vec3(20, 0, 0), new Vec3(0, 1, 0), 11, -1, -1, Vec3.ZERO);
        trajectory("stationary side target", new Vec3(500, 0, 0),
                Vec3.ZERO, new Vec3(0, 1, 0), 11, -1, -1, Vec3.ZERO);
        trajectory("stationary target behind launcher", new Vec3(-500, 0, 0),
                Vec3.ZERO, new Vec3(1, 0, 0), 11, -1, -1, Vec3.ZERO);
        trajectory("acquisition near acceleration cap", new Vec3(1500, 200, -400),
                new Vec3(-2, 5, 1), new Vec3(1, 0, 0), 197, -1, -1, Vec3.ZERO);
        trajectory("lost radar contact coasts instead of freezing", new Vec3(1000, 200, 0),
                new Vec3(0, 10, 0), new Vec3(0, 1, 0), 11, 12, -1, Vec3.ZERO);
        trajectory("maneuver between two radar scans", new Vec3(1600, 200, 0),
                new Vec3(0, 8, 0), new Vec3(0, 1, 0), 11, -1, 49, new Vec3(0, -6, 4));
        verifyAircraftProfiles();
        MissileDemandGuidanceChecks.run();
        ActiveRadarSeekerChecks.run();
        System.out.println("Air-defense intercept checks passed (" + scenarios
                + " scenarios; PN demand, interception and hardware limits checked)");
    }

    private static void verifySpeedAndTiming() {
        near(PROFILE.speedAt(0), 2.0, 1.0e-12, "launch speed");
        near(PROFILE.speedAt(11), 3.76165, 1.0e-12, "post-tick speed at age 11");
        near(PROFILE.speedAt(200), 34.03, 1.0e-12, "speed at cap");
        near(PROFILE.speedAt(399), 34.03, 1.0e-12, "speed remains capped");
        // Known travel distances independently specify which commanded speed
        // is consumed on each tick, including the fractional terminal tick.
        knownIntercept(11, 3.25, 3.76165 + 3.9218 + 4.08195 + 0.25 * 4.2421);
        knownIntercept(198, 5.6, 33.7097 + 33.86985 + 3.6 * 34.03);
        knownIntercept(11, 300.25,
                189.0 * (3.76165 + 33.86985) / 2.0 + 111.25 * 34.03);
        var restored = new AcceleratingMissileIntercept.SpeedProfile(PROFILE.initialSpeed(), 34.03, 200);
        near(restored.speedAt(120), PROFILE.speedAt(120), 1.0e-12,
                "restoring the launch speed must preserve the acceleration schedule");
        scenarios++;
    }

    private static void knownIntercept(int age, double time, double distance) {
        Vec3 missile = new Vec3(10000, 250, -6000);
        Vec3 direction = new Vec3(2, 1, -3).normalize();
        Vec3 velocity = new Vec3(0.1, -0.2, 0.3);
        Vec3 target = missile.add(direction.scale(distance)).subtract(velocity.scale(time));
        var result = AcceleratingMissileIntercept.solve(missile, target, velocity,
                PROFILE, age, LIFETIME - age);
        require(result != null, "known interception must exist");
        near(result.flightTicks(), time, 1.0e-8, "discrete acceleration and fractional flight time");
        vectorNear(result.direction(), direction, 1.0e-9, "known interception heading");
        scenarios++;
    }

    private static void verifySolverEdges() {
        var constant = new AcceleratingMissileIntercept.SpeedProfile(2.0, 2.0, 200);
        var fastIncoming = AcceleratingMissileIntercept.solve(Vec3.ZERO,
                new Vec3(0.5, 0, 0), new Vec3(-10, 0, 0), constant, 0, 1);
        require(fastIncoming != null, "both roots inside a tick must not be missed");
        near(fastIncoming.flightTicks(), 0.5 / 12.0, 1.0e-10, "choose earliest root");
        require(AcceleratingMissileIntercept.solve(Vec3.ZERO, new Vec3(100, 0, 0),
                new Vec3(40, 0, 0), PROFILE, 11, 389) == null, "faster receding target is unreachable");
        require(AcceleratingMissileIntercept.solve(Vec3.ZERO, new Vec3(120, 0, 0),
                Vec3.ZERO, PROFILE, 200, 3) == null, "respect remaining lifetime");
        var late = AcceleratingMissileIntercept.solve(Vec3.ZERO, new Vec3(120, 0, 0),
                Vec3.ZERO, PROFILE, 200, 4);
        require(late != null, "reachable during fourth tick");
        near(late.flightTicks(), 120.0 / 34.03, 1.0e-10, "constant speed intercept");
        var equalSpeed = AcceleratingMissileIntercept.solve(Vec3.ZERO, new Vec3(100, 0, 0),
                new Vec3(-2, 0, 0), constant, 0, 30);
        require(equalSpeed != null, "linear quadratic degeneracy");
        near(equalSpeed.flightTicks(), 25.0, 1.0e-10, "equal speed incoming intercept");
        require(AcceleratingMissileIntercept.solve(Vec3.ZERO, new Vec3(100, 0, 0),
                new Vec3(2, 0, 0), constant, 0, 100) == null, "equal speed tail chase cannot close");
        require(AcceleratingMissileIntercept.solve(Vec3.ZERO, Vec3.ZERO,
                Vec3.ZERO, PROFILE, 11, 389) == null, "coincident target must not create a zero heading");
        require(AcceleratingMissileIntercept.solve(Vec3.ZERO, new Vec3(Double.NaN, 0, 0),
                Vec3.ZERO, PROFILE, 11, 389) == null, "reject invalid position");
        require(AcceleratingMissileIntercept.solve(Vec3.ZERO, new Vec3(100, 0, 0),
                new Vec3(0, Double.POSITIVE_INFINITY, 0), PROFILE, 11, 389) == null,
                "reject invalid velocity");
        require(AcceleratingMissileIntercept.solve(Vec3.ZERO, new Vec3(100, 0, 0),
                Vec3.ZERO, PROFILE, 11, 0) == null, "expired lifetime");
        Vec3 fallback = AcceleratingMissileIntercept.guide(Vec3.ZERO, new Vec3(0, 1, 0),
                new Vec3(100, 0, 0), new Vec3(40, 0, 0), PROFILE, 11, 389, MAX_STEER);
        near(fallback.length(), 1.0, 1.0e-12, "unreachable fallback stays finite and normalized");
        require(angle(new Vec3(0, 1, 0), fallback) > 0
                        && angle(new Vec3(0, 1, 0), fallback) < MAX_STEER,
                "unreachable fallback acquires without automatically using the turn limit");
        Vec3 sample = new Vec3(500, 100, -50);
        Vec3 velocity = new Vec3(0, 10, 2);
        vectorNear(AcceleratingMissileIntercept.targetPositionAt(sample, velocity, 100, 101),
                new Vec3(500, 110, -48), 1.0e-12, "old scan extrapolates to current tick");
        vectorNear(AcceleratingMissileIntercept.targetPositionAt(sample, velocity, 100, 140),
                new Vec3(500, 500, 30), 1.0e-12, "lost contact continues at last measured velocity");
        scenarios++;
    }

    private static void verifySteering() {
        Vec3 forward = new Vec3(1, 0, 0);
        Vec3 opposite = AcceleratingMissileIntercept.rotateTowards(forward.scale(10),
                forward.scale(-300), MAX_STEER);
        near(angle(forward, opposite), MAX_STEER, 1.0e-10, "antiparallel heading turns by the allowed angle");
        near(opposite.length(), 1.0, 1.0e-12, "rotation accepts unnormalized inputs");
        Vec3 nearHeading = new Vec3(Math.cos(0.1), Math.sin(0.1), 0);
        vectorNear(AcceleratingMissileIntercept.rotateTowards(forward, nearHeading, MAX_STEER),
                nearHeading, 1.0e-12, "acquire heading inside turn limit exactly");
        vectorNear(AcceleratingMissileIntercept.rotateTowards(forward.scale(4), Vec3.ZERO, MAX_STEER),
                forward, 1.0e-12, "zero displacement preserves heading");
        scenarios++;
    }

    private static void verifyStraightCourseInvariant() {
        Random random = new Random(0xAA2026);
        for (int scene = 0; scene < 240; scene++) {
            int age = 11 + scene % 210;
            Vec3 missile = new Vec3(random.nextDouble() * 40000 - 20000,
                    random.nextDouble() * 300, random.nextDouble() * 40000 - 20000);
            Vec3 offset = new Vec3(random.nextDouble() - 0.5, random.nextDouble() - 0.5,
                    random.nextDouble() - 0.5).normalize().scale(250 + random.nextDouble() * 3250);
            Vec3 velocity = new Vec3(random.nextDouble() * 16 - 8,
                    random.nextDouble() * 16 - 8, random.nextDouble() * 16 - 8);
            Vec3 target = missile.add(offset);
            var original = AcceleratingMissileIntercept.solve(missile, target, velocity,
                    PROFILE, age, LIFETIME - age);
            require(original != null, "random reachable scene " + scene);
            vectorNear(missile.add(original.direction().scale(travel(age, original.flightTicks()))),
                    original.point(), 1.0e-6, "predicted positions must meet, scene " + scene);
            Vec3 heading = original.direction();
            for (int elapsed = 0; elapsed + 1 < original.flightTicks(); elapsed++) {
                vectorNear(AcceleratingMissileIntercept.guide(missile, heading, target, velocity,
                        PROFILE, age + elapsed, LIFETIME - age - elapsed, MAX_STEER), heading, 1e-8,
                        "PN must command no correction on an accelerating collision course, scene " + scene);
                missile = missile.add(heading.scale(PROFILE.speedAt(age + elapsed)));
                target = target.add(velocity);
                var next = AcceleratingMissileIntercept.solve(missile, target, velocity,
                        PROFILE, age + elapsed + 1, LIFETIME - age - elapsed - 1);
                require(next != null, "course remains reachable, scene " + scene);
                vectorNear(next.point(), original.point(), 1.0e-6,
                        "interception point must stay fixed through acceleration, scene " + scene);
                vectorNear(next.direction(), heading, 1.0e-8,
                        "no terminal turn on unchanged target motion, scene " + scene);
            }
            scenarios++;
        }
    }

    private static void trajectory(
            String name, Vec3 targetStart, Vec3 initialTargetVelocity,
            Vec3 launchDirection, int initialAge, int dropContactAt,
            int maneuverAt, Vec3 maneuverVelocity
    ) {
        trajectory(name, targetStart, initialTargetVelocity, launchDirection, initialAge,
                dropContactAt, maneuverAt, maneuverVelocity, PROFILE, LIFETIME,
                MianbaoAirDefenseCompat.guidanceDelayTicks(), null);
    }

    private static void verifyAim120LaunchTransition() {
        var type = MianbaoAircraftCompat.WeaponType.AIM_120D;
        near(MianbaoAircraftCompat.maxTurnRadians(type), Math.toRadians(56), 1e-12,
                "AIM-120D original angular ceiling");
        near(MianbaoAircraftCompat.maxLateralAcceleration(type), 450 / .25 * 9.80665 / 400, 1e-12,
                "AIM-120D original overload ceiling");
        var profile = MianbaoAircraftCompat.speedProfile(type, 2.5);
        Vec3 position = Vec3.ZERO, direction = new Vec3(1, 0, 0);
        Vec3 target = new Vec3(5000, 0, 8660);
        for (int age = 1; age <= 20; age++) {
            double limit = MianbaoAircraftCompat.interceptMaxTurnRadians(type, profile.speedAt(age));
            Vec3 next = age <= MianbaoAircraftCompat.maneuverDelayTicks(type) ? direction
                    : AcceleratingMissileIntercept.guide(position, direction, target, Vec3.ZERO,
                    profile, age, 300 - age, limit);
            if (age < 10) require(next.equals(new Vec3(1, 0, 0)), "AIM-120D must first travel along rail axis");
            if (age == 10) require(angle(direction, next) > 0 && angle(direction, next) < Math.toRadians(10),
                    "AIM-120D must begin with a bounded transition, not a 56-degree snap");
            require(angle(direction, next) <= limit + 1e-7, "AIM-120D launch turn limit");
            direction = next;
            position = position.add(direction.scale(profile.speedAt(age)));
        }
        for (double speed : new double[]{.1, 2.5, 5, 17.015, 34.03, 45}) {
            double limit = MianbaoAircraftCompat.interceptMaxTurnRadians(type, speed);
            require(speed * Math.tan(limit) <= MianbaoAircraftCompat.maxLateralAcceleration(type) + 1e-9,
                    "AIM-120D transition must preserve original overload cap");
        }
        double cruise = Math.min(Math.toRadians(56), Math.atan2(450 / .25 * 9.80665 / 400, 34.03));
        near(MianbaoAircraftCompat.interceptMaxTurnRadians(type, 34.03), cruise, 1e-12,
                "AIM-120D full-speed envelope must remain unchanged");
        scenarios++;
    }

    private static void verifyAllLaunchLimits() {
        var types = new MianbaoAircraftCompat.WeaponType[]{
                MianbaoAircraftCompat.WeaponType.FAR, MianbaoAircraftCompat.WeaponType.MEDIUM,
                MianbaoAircraftCompat.WeaponType.CLOSE, MianbaoAircraftCompat.WeaponType.MICA_EM,
                MianbaoAircraftCompat.WeaponType.AIM_120D, MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT,
                MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_TOP_ATTACK, MianbaoAircraftCompat.WeaponType.LASER_GUIDED};
        double[] degrees = {10, 28, 44, 56, 56, 8, 7, 8};
        double[] overloads = {400, 900, 1000, 1800, 1800, 45, 40, 45};
        int[] delays = {9, 9, 9, 9, 9, 10, 9, 0};
        for (int index = 0; index < types.length; index++) {
            var type = types[index];
            near(MianbaoAircraftCompat.maxTurnRadians(type), Math.toRadians(degrees[index]), 1e-12, type + " original angular ceiling");
            near(MianbaoAircraftCompat.maxLateralAcceleration(type), overloads[index] * 9.80665 / 400, 1e-12, type + " original overload");
            require(MianbaoAircraftCompat.maneuverDelayTicks(type) == delays[index], type + " native launch stage");
            double maximum = MianbaoAircraftCompat.maximumSpeedBlocksPerTick(type);
            for (double speed : new double[]{.1, 2.5, 5, 17.015, 34.03, 45}) {
                double limit = MianbaoAircraftCompat.interceptMaxTurnRadians(type, speed);
                require(limit <= Math.toRadians(degrees[index]) + 1e-12, type + " angular cap");
                require(speed * Math.tan(limit) <= overloads[index] * 9.80665 / 400 + 1e-9, type + " overload cap");
                Vec3 previous = new Vec3(speed, 0, 0), requested = new Vec3(-speed, speed, 0);
                Vec3 bounded = MissileSteeringLimits.limitVelocity(previous, requested, limit);
                require(angle(previous, bounded) <= limit + 1e-7, type + " all guidance branches share limit");
                near(bounded.length(), requested.length(), 1e-10, type + " limiting a turn must not change speed");
            }
            double full = Math.min(Math.toRadians(degrees[index]), Math.atan2(overloads[index] * 9.80665 / 400, maximum));
            near(MianbaoAircraftCompat.interceptMaxTurnRadians(type, maximum), full, 1e-12, type + " full-speed envelope");
            scenarios++;
        }
        for (double speed : new double[]{.1, 2, 17, 34.03, 45}) {
            require(MianbaoAirDefenseCompat.maxTurnRadians(speed) <= Math.toRadians(13) + 1e-12, "AA original angular cap");
        }
        near(MianbaoAirDefenseCompat.maxTurnRadians(34.03), Math.toRadians(13), 1e-12, "AA full-speed envelope");
        Vec3 backwards = AcceleratingMissileIntercept.rotateTowards(new Vec3(1, 0, 0), new Vec3(-1, 0, 0), Math.toRadians(4));
        near(angle(new Vec3(1, 0, 0), backwards), Math.toRadians(4), 1e-10, "opposite heading must turn instead of remaining stuck");
    }

    private static void verifyAircraftProfiles() {
        Vec3 carrierVelocity = new Vec3(6, 0.2, -0.7);
        Vec3 railMotorVelocity = new Vec3(0, 0, 2.5);
        Vec3 launchVelocity = carrierVelocity.add(railMotorVelocity);
        for (var type : new MianbaoAircraftCompat.WeaponType[]{
                MianbaoAircraftCompat.WeaponType.FAR,
                MianbaoAircraftCompat.WeaponType.MEDIUM,
                MianbaoAircraftCompat.WeaponType.CLOSE,
                MianbaoAircraftCompat.WeaponType.MICA_EM,
                MianbaoAircraftCompat.WeaponType.AIM_120D}) {
            var profile = MianbaoAircraftCompat.speedProfile(type, launchVelocity.length());
            near(profile.speedAt(0), launchVelocity.length(), 1.0e-10,
                    type + ": include carrier velocity only in initial world speed");
            near(profile.speedAt(100), 34.03, 1.0e-10, type + ": reach Mach 2 after five seconds");
            near(profile.speedAt(199), 34.03, 1.0e-10, type + ": carrier speed must not be added again");
            int lifetime = MianbaoAircraftCompat.lifetimeTicks(type);
            int delay = MianbaoAircraftCompat.maneuverDelayTicks(type);
            trajectory(type + " straight crossing", new Vec3(1100, 160, -50),
                    new Vec3(0.4, 0.1, 8), null, 1, -1, -1, Vec3.ZERO,
                    profile, lifetime, delay, type);
            trajectory(type + " acquisition with inherited carrier motion and lost contact",
                    new Vec3(900, 120, 100), new Vec3(0.4, 0.1, 8), launchVelocity.normalize(),
                    1, 12, -1, Vec3.ZERO, profile, lifetime, delay, type);
            trajectory(type + " maneuver between radar scans", new Vec3(1600, 160, -300),
                    new Vec3(-1, 0, 6), new Vec3(0, 0, 1), 1, -1, 49, new Vec3(-4, 0, -2),
                    profile, lifetime, delay, type);
            double speed = profile.speedAt(100);
            double turn = MianbaoAircraftCompat.interceptMaxTurnRadians(type, speed);
            require(turn <= MianbaoAircraftCompat.maxTurnRadians(type), type + ": retain angular cap");
            require(speed * Math.tan(turn) <= MianbaoAircraftCompat.maxLateralAcceleration(type) + 1.0e-9,
                    type + ": retain lateral acceleration cap");
        }
        // An unusually fast carrier must not make the shared profile reject
        // an inherited speed above the missile's eventual maximum.
        var fastCarrierProfile = MianbaoAircraftCompat.speedProfile(MianbaoAircraftCompat.WeaponType.FAR, 45.0);
        near(fastCarrierProfile.speedAt(50), (45.0 + 34.03) * 0.5, 1.0e-10,
                "above-envelope inherited speed follows the same gradual schedule");
        trajectory("above-envelope carrier speed", new Vec3(1800, 120, -300), new Vec3(0, 0, 5),
                null, 1, -1, -1, Vec3.ZERO, fastCarrierProfile, 400, 0, MianbaoAircraftCompat.WeaponType.FAR);
        scenarios++;
    }

    private static void trajectory(
            String name, Vec3 targetStart, Vec3 initialTargetVelocity,
            Vec3 launchDirection, int initialAge, int dropContactAt,
            int maneuverAt, Vec3 maneuverVelocity,
            AcceleratingMissileIntercept.SpeedProfile profile, int lifetime, int delay,
            MianbaoAircraftCompat.WeaponType aircraftType
    ) {
        Vec3 missile = Vec3.ZERO;
        Vec3 direction = launchDirection;
        if (direction == null) {
            var intercept = AcceleratingMissileIntercept.solve(missile, targetStart,
                    initialTargetVelocity, profile, initialAge, lifetime - initialAge);
            require(intercept != null, name + ": initial intercept");
            direction = intercept.direction();
        }
        Vec3 scanPosition = targetStart;
        Vec3 scanVelocity = initialTargetVelocity;
        long scanTick = 1000;
        Vec3 acquiredHeading = null;
        double closest = Double.POSITIVE_INFINITY;
        int straightTicks = 0;
        int maxStraightTicks = 0;
        for (int elapsed = 0; initialAge + elapsed < lifetime; elapsed++) {
            int age = initialAge + elapsed;
            Vec3 target = targetAt(targetStart, initialTargetVelocity, maneuverAt, maneuverVelocity, elapsed);
            if (elapsed == maneuverAt) {
                acquiredHeading = null;
                straightTicks = 0;
            }
            // Radar position is only sampled every two ticks. Velocity is the
            // difference between scans, as in the controller, including a
            // mixed sample when a maneuver happens between scans.
            if (elapsed % 2 == 0 && (dropContactAt < 0 || elapsed < dropContactAt)) {
                Vec3 newVelocity = elapsed == 0 ? initialTargetVelocity
                        : target.subtract(scanPosition).scale(0.5);
                if (newVelocity.distanceToSqr(scanVelocity) > 1.0e-12) {
                    acquiredHeading = null;
                    straightTicks = 0;
                }
                scanPosition = target;
                scanVelocity = newVelocity;
                scanTick = 1000 + elapsed;
            }
            Vec3 estimatedTarget = AcceleratingMissileIntercept.targetPositionAt(
                    scanPosition, scanVelocity, scanTick, 1000 + elapsed);
            double turnLimit = aircraftType == null ? MianbaoAirDefenseCompat.maxTurnRadians(profile.speedAt(age))
                    : MianbaoAircraftCompat.interceptMaxTurnRadians(aircraftType, profile.speedAt(age));
            Vec3 nextDirection = age <= delay ? direction : AcceleratingMissileIntercept.guide(
                    missile, direction, estimatedTarget, scanVelocity,
                    profile, age, lifetime - age, turnLimit);
            require(angle(direction, nextDirection) <= turnLimit + 1.0e-7, name + ": limited turning");
            var intercept = AcceleratingMissileIntercept.solve(missile, estimatedTarget,
                    scanVelocity, profile, age, lifetime - age);
            if (age > delay && intercept != null
                    && nextDirection.distanceToSqr(intercept.direction()) < 1.0e-18) {
                if (acquiredHeading == null) acquiredHeading = nextDirection;
                vectorNear(nextDirection, acquiredHeading, 1.0e-7,
                        name + ": heading must stay straight after acquisition");
                maxStraightTicks = Math.max(maxStraightTicks, ++straightTicks);
            }
            Vec3 nextMissile = missile.add(nextDirection.scale(profile.speedAt(age)));
            Vec3 nextTarget = targetAt(targetStart, initialTargetVelocity, maneuverAt,
                    maneuverVelocity, elapsed + 1);
            Vec3 relative = target.subtract(missile);
            Vec3 relativeStep = nextTarget.subtract(nextMissile).subtract(relative);
            double fraction = relativeStep.lengthSqr() < 1.0e-12 ? 0.0
                    : Math.max(0.0, Math.min(1.0, -relative.dot(relativeStep) / relativeStep.lengthSqr()));
            closest = Math.min(closest, relative.add(relativeStep.scale(fraction)).length());
            if (closest < 1.0e-5) break;
            missile = nextMissile;
            direction = nextDirection;
        }
        require(closest < 1.0e-5, name + ": miss distance " + closest);
        System.out.printf("%s: closest %.8f blocks, straight course %d ticks%n",
                name, closest, maxStraightTicks);
        scenarios++;
    }

    private static Vec3 targetAt(Vec3 start, Vec3 velocity, int maneuverAt, Vec3 nextVelocity, int tick) {
        if (maneuverAt < 0 || tick <= maneuverAt) return start.add(velocity.scale(tick));
        return start.add(velocity.scale(maneuverAt)).add(nextVelocity.scale(tick - maneuverAt));
    }

    private static double travel(int age, double time) {
        int whole = (int) Math.floor(time);
        double result = 0.0;
        for (int tick = 0; tick < whole; tick++) result += PROFILE.speedAt(age + tick);
        return result + (time - whole) * PROFILE.speedAt(age + whole);
    }

    private static double angle(Vec3 first, Vec3 second) {
        return Math.acos(Math.max(-1.0, Math.min(1.0, first.normalize().dot(second.normalize()))));
    }

    private static void vectorNear(Vec3 actual, Vec3 expected, double tolerance, String message) {
        require(actual.distanceTo(expected) <= tolerance,
                message + ": error " + actual.distanceTo(expected));
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        require(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
