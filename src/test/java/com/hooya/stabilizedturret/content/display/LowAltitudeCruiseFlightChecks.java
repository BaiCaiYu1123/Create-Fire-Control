package com.hooya.stabilizedturret.content.display;

import net.minecraft.world.phys.Vec3;

/** Long-flight checks: bounds, convergence, and independent steering channels. */
public final class LowAltitudeCruiseFlightChecks {
    private static final double SPEED = 17.015;

    public static void main(String[] args) {
        radiusAndIndependence();
        stableFlatFlight(false);
        stableFlatFlight(true);
        rollingTerrain();
        missingTerrain();
        verticalRelease();
    }

    private static void radiusAndIndependence() {
        Vec3 pos = new Vec3(0, 90, 0);
        Vec3 initial = new Vec3(SPEED, 0, 0);
        Vec3 target = new Vec3(-10000, 90, 10000);
        LowAltitudeCruiseFlight flat = new LowAltitudeCruiseFlight(pos, initial, target);
        LowAltitudeCruiseFlight hill = new LowAltitudeCruiseFlight(pos, initial, target);
        Vec3 a = flat.step(pos, initial, target, SPEED, 100, (x, z) -> 60);
        Vec3 b = hill.step(pos, initial, target, SPEED, 100, (x, z) -> 100);
        require(Math.abs(Math.atan2(a.z, a.x) - Math.atan2(b.z, b.x)) < 1.0e-12,
                "terrain correction must not consume the horizontal steering budget");
        require(Math.atan2(a.z, a.x) <= SPEED / LowAltitudeCruiseFlight.GUIDANCE_RADIUS + 1.0e-10,
                "horizontal turn must obey its minimum radius");
        require(Math.atan2(b.y, b.horizontalDistance()) <= SPEED / LowAltitudeCruiseFlight.TERRAIN_RADIUS + 1.0e-10,
                "terrain pitch must obey its independent minimum radius");
        require(b.y > a.y, "terrain obstacle needs its own climb command");
    }

    private static void stableFlatFlight(boolean fromBelow) {
        Vec3 pos = new Vec3(0, fromBelow ? 65 : 200, 0);
        Vec3 velocity = new Vec3(SPEED, fromBelow ? -2 : 5, 0).normalize().scale(SPEED);
        Vec3 target = new Vec3(100000, 60, 0);
        LowAltitudeCruiseFlight flight = new LowAltitudeCruiseFlight(pos, velocity, target);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int age = 1; age <= 1100; age++) {
            velocity = flight.step(pos, velocity, target, SPEED, age, (x, z) -> 60);
            pos = pos.add(velocity);
            require(pos.y > 60, "flat-ground correction must not collide with terrain");
            require(Math.abs(velocity.length() - SPEED) < 1.0e-9, "speed envelope must be retained");
            if (age > 200) { min = Math.min(min, pos.y); max = Math.max(max, pos.y); }
        }
        require(min >= 89.9 && max <= 90.1, "long-range flight must settle at 30 blocks without oscillation: " + min + ".." + max);
    }

    private static void rollingTerrain() {
        LowAltitudeCruiseFlight.Ground ground = (x, z) -> 60 + 20 * Math.sin(x / 160.0);
        Vec3 pos = new Vec3(0, 90, 0);
        Vec3 velocity = new Vec3(SPEED, 0, 0);
        Vec3 target = new Vec3(100000, 60, 0);
        LowAltitudeCruiseFlight flight = new LowAltitudeCruiseFlight(pos, velocity, target);
        for (int age = 1; age <= 1100; age++) {
            Vec3 next = flight.step(pos, velocity, target, SPEED, age, ground);
            double pitchChange = Math.abs(Math.atan2(next.y, next.horizontalDistance())
                    - Math.atan2(velocity.y, velocity.horizontalDistance()));
            require(pitchChange <= SPEED / LowAltitudeCruiseFlight.TERRAIN_RADIUS + 1.0e-9, "terrain radius exceeded");
            velocity = next;
            pos = pos.add(velocity);
            double clearance = pos.y - ground.height(pos.x, pos.z);
            require(clearance >= 10 && clearance < 65, "rolling terrain must stay bounded: " + clearance);
        }
    }

    private static void missingTerrain() {
        Vec3 pos = new Vec3(0, 90, 0);
        Vec3 target = new Vec3(100000, 60, 0);
        LowAltitudeCruiseFlight flight = new LowAltitudeCruiseFlight(pos, new Vec3(1, 0, 0), target);
        Vec3 velocity = new Vec3(SPEED, 0, 0);
        for (int age = 0; age < 100; age++) {
            velocity = flight.step(pos, velocity, target, SPEED, age, (x, z) -> Double.NaN);
            pos = pos.add(velocity);
        }
        require(Math.abs(pos.y - 90) < 1.0e-9, "unloaded terrain must not be interpreted as a deep valley");
    }

    private static void verticalRelease() {
        Vec3 pos = new Vec3(0, 61, 0);
        Vec3 velocity = new Vec3(0, 2, 0);
        Vec3 target = new Vec3(100000, 60, 10000);
        LowAltitudeCruiseFlight flight = new LowAltitudeCruiseFlight(pos, velocity.normalize(), target);
        for (int age = 1; age <= 400; age++) {
            double speed = Math.max(2, SPEED * Math.min(1, Math.max(0, age - 10) / 200.0));
            velocity = flight.step(pos, velocity, target, speed, age, (x, z) -> 60);
            pos = pos.add(velocity);
            require(pos.y > 60 && pos.y < 150, "VLS transition must clear ground without a high-altitude loop: " + pos);
        }
        require(pos.x > 2000 && Math.abs(pos.y - 90) < 0.1, "VLS must transition to stable terrain-following flight");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
