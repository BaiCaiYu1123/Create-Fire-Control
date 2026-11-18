package com.hooya.stabilizedturret.content.display;

import net.minecraft.world.phys.Vec3;

public final class MovingMissileFuzeChecks {
    public static void main(String[] args) {
        double halfMach1 = 17.015 / 2;
        Vec3 from = new Vec3(-halfMach1, 200, 0), to = new Vec3(halfMach1, 200, 0);
        Vec3 threatFrom = new Vec3(0, 200, -halfMach1), threatTo = new Vec3(0, 200, halfMach1);
        require(threatTo.distanceToSqr(new Vec3(0, 200, 0)) > 25,
                "Fixture must reproduce an endpoint-only miss");
        var crossing = MovingMissileFuze.firstContact(from, to, threatFrom, threatTo, 5);
        require(crossing != null && crossing.fraction() > 0 && crossing.fraction() < 0.5,
                "Mach-1 crossing did not trigger within the tick");
        require(Math.abs(crossing.interceptor().distanceTo(crossing.target()) - 5) < 1e-10,
                "Fuze must trigger on first entry, at the configured range");
        require(MovingMissileFuze.firstContact(from.scale(2), to.scale(2),
                threatFrom.scale(2), threatTo.scale(2), 5) != null, "Mach-2 crossing skipped the fuze");
        require(MovingMissileFuze.firstContact(from, to,
                threatFrom.add(0, 5.01, 0), threatTo.add(0, 5.01, 0), 5) == null,
                "A near miss outside the configured radius was intercepted");
        require(MovingMissileFuze.firstContact(from, to,
                new Vec3(0, 200, 0), new Vec3(0, 200, 17.015), 5) == null,
                "Geometrically crossing paths at different times must not trigger");
        require(MovingMissileFuze.firstContact(from, to, threatFrom, threatTo, 0) == null,
                "Zero range must disable proximity, leaving contact collision alone");
        require(MovingMissileFuze.firstContact(from, to, threatFrom, threatTo, Double.NaN) == null,
                "Invalid range must not detonate");
        require(MovingMissileFuze.firstContact(from, from, from, from, 1).fraction() == 0,
                "Co-located stationary missiles must trigger");
        require(MovingMissileFuze.firstContact(from, to, from.add(0, 6, 0), to.add(0, 6, 0), 5) == null,
                "Equal velocity must not manufacture a relative approach");
        require(MovingMissileFuze.firstContact(from, to, new Vec3(0, 200, 0), new Vec3(0, 200, 0), 1) != null,
                "Stationary-target segment sweep regressed");
        Vec3 translation = new Vec3(20481032.5, 984.5, -20532231.5);
        var translated = MovingMissileFuze.firstContact(from.add(translation), to.add(translation),
                threatFrom.add(translation), threatTo.add(translation), 5);
        require(translated != null && Math.abs(translated.fraction() - crossing.fraction()) < 1e-9,
                "World-coordinate translation changed the crossing fraction");
        System.out.println("PASS: simultaneous moving-missile fuze, Mach-1/2 crossings, exact radius, staggered crossings and disabled proximity");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
