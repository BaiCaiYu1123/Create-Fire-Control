package com.hooya.stabilizedturret.content.controller;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

final class ActiveRadarSeekerChecks {
    private static final UUID TARGET = UUID.fromString("120d0000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("120d0000-0000-0000-0000-000000000002");

    static void run() {
        var seeker = new ActiveRadarSeeker(TARGET, ActiveRadarSeeker.AimPoint.BOUNDS, new CompoundTag());
        Vec3 initial = new Vec3(1000, 0, 100);
        seeker.seed(initial, Vec3.ZERO, box(initial), Set.of(MEMBER), 0);
        Vec3 observed = initial.add(0, 0, 12);
        AtomicInteger reads = new AtomicInteger();
        require(seeker.sample(2, Vec3.ZERO, new Vec3(1, 0, 0), () -> {
            reads.incrementAndGet(); return observation(observed);
        }), "onboard seeker acquires independently of launch radar");
        near(seeker.memory().velocity(), new Vec3(0, 0, 6), "seeker measures its own target velocity");
        seeker.seed(new Vec3(1, 0, 0), new Vec3(100, 0, 0), box(initial), Set.of(), 999);
        near(seeker.memory().center(), observed, "later launcher data cannot overwrite onboard observations");
        seeker.sample(2, Vec3.ZERO, new Vec3(1, 0, 0), () -> { reads.incrementAndGet(); return null; });
        require(reads.get() == 1 && seeker.locked(), "multiple device queries cannot resample a tick");

        seeker.sample(3, Vec3.ZERO, new Vec3(-1, 0, 0), () -> observation(observed.add(0, 0, 6)));
        require(!seeker.locked() && seeker.memory().tick() == 2, "outside seeker field of view uses memory");
        near(AcceleratingMissileIntercept.targetPositionAt(seeker.memory().center(), seeker.memory().velocity(), 2, 5),
                observed.add(0, 0, 18), "lost contact coasts from last independent measurement");
        seeker.sample(5, Vec3.ZERO, new Vec3(1, 0, 0), () -> observation(observed.add(0, 0, 18)));
        require(seeker.locked(), "same assigned target can be reacquired without launch radar");
        near(seeker.memory().velocity(), new Vec3(0, 0, 6), "reacquisition respects elapsed sample time");
        seeker.sample(6, Vec3.ZERO, new Vec3(1, 0, 0), () -> null);
        require(!seeker.locked() && seeker.memory().tick() == 5, "unloaded target does not fabricate observations");

        CompoundTag saved = new CompoundTag(); seeker.save(saved);
        var restored = new ActiveRadarSeeker(TARGET, ActiveRadarSeeker.AimPoint.BOUNDS, saved);
        near(restored.memory().center(), seeker.memory().center(), "saved seeker position");
        near(restored.memory().velocity(), seeker.memory().velocity(), "saved seeker velocity");
        require(restored.memory().members().equals(Set.of(MEMBER)) && restored.memory().tick() == 5,
                "target identity, connected members and sample time survive restore");
        require(new ActiveRadarSeeker(MEMBER, ActiveRadarSeeker.AimPoint.BOUNDS, saved).memory() == null,
                "saved data cannot retarget a different missile assignment");
        require(!ActiveRadarSeeker.detectable(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(12001, 0, 0)), "finite seeker range");
        require(!ActiveRadarSeeker.detectable(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(-1, 0, 0)), "no omnidirectional rear tracking");
        require(!ActiveRadarSeeker.detectable(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(Double.NaN, 0, 0)), "reject invalid geometry");

        for (var type : new MianbaoAircraftCompat.WeaponType[]{MianbaoAircraftCompat.WeaponType.FAR,
                MianbaoAircraftCompat.WeaponType.MEDIUM,
                MianbaoAircraftCompat.WeaponType.MICA_EM, MianbaoAircraftCompat.WeaponType.AIM_120D, null}) {
            require(flight(type, true) < 1e-5, type + " active seeker must intercept a post-shutdown manoeuvre");
            require(flight(type, false) > 10, type + " scenario must distinguish active pursuit from old target extrapolation");
        }
        System.out.println("Active radar seeker checks passed: independent measurements, field of view/range, reacquisition, NBT and 10 radar-shutdown flights; infrared verified separately");
    }

    private static double flight(MianbaoAircraftCompat.WeaponType type, boolean active) {
        var seeker = new ActiveRadarSeeker(TARGET, ActiveRadarSeeker.AimPoint.BOUNDS, new CompoundTag());
        Vec3 missile = Vec3.ZERO, direction = new Vec3(1, 0, 0), target = new Vec3(1200, 0, 300), velocity = new Vec3(0, 0, 6);
        seeker.seed(target, velocity, box(target), Set.of(MEMBER), 0);
        var profile = type == null ? MianbaoAirDefenseCompat.speedProfile(4) : MianbaoAircraftCompat.speedProfile(type, 4);
        int lifetime = type == null ? 400 : MianbaoAircraftCompat.lifetimeTicks(type);
        int delay = type == null ? MianbaoAirDefenseCompat.guidanceDelayTicks() : MianbaoAircraftCompat.maneuverDelayTicks(type);
        double closest = Double.POSITIVE_INFINITY;
        for (int age = 1; age < lifetime; age++) {
            // Launch radar is off from age 2. The target changes course later.
            if (age >= 20 && age < 50) velocity = new Vec3(velocity.x * Math.cos(.04) - velocity.z * Math.sin(.04),
                    0, velocity.x * Math.sin(.04) + velocity.z * Math.cos(.04));
            Vec3 point = target;
            if (active || age < 2) seeker.sample(age, missile, direction, () -> observation(point));
            var sample = seeker.memory();
            Vec3 estimate = AcceleratingMissileIntercept.targetPositionAt(sample.center(), sample.velocity(), sample.tick(), age);
            double speed = profile.speedAt(age);
            double limit = type == null ? MianbaoAirDefenseCompat.maxTurnRadians(speed)
                    : MianbaoAircraftCompat.interceptMaxTurnRadians(type, speed);
            Vec3 next = age <= delay ? direction : AcceleratingMissileIntercept.guide(missile, direction, estimate,
                    sample.velocity(), profile, age, lifetime - age, limit);
            Vec3 relative = target.subtract(missile), nextMissile = missile.add(next.scale(speed)), nextTarget = target.add(velocity);
            Vec3 step = nextTarget.subtract(nextMissile).subtract(relative);
            double f = Math.max(0, Math.min(1, -relative.dot(step) / Math.max(1e-12, step.lengthSqr())));
            closest = Math.min(closest, relative.add(step.scale(f)).length());
            if (closest < 1e-5) break;
            missile = nextMissile; target = nextTarget; direction = next;
        }
        return closest;
    }

    private static ActiveRadarSeeker.Observation observation(Vec3 center) {
        return new ActiveRadarSeeker.Observation(center, null, box(center), Set.of(MEMBER));
    }
    private static AABB box(Vec3 center) { return new AABB(center, center).inflate(2); }
    private static void near(Vec3 a, Vec3 b, String message) { require(a.distanceTo(b) < 1e-9, message); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
