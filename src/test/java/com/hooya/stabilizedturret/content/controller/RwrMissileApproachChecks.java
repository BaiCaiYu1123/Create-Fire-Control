package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Regression scenarios for the managed-missile fallback, in world blocks/tick. */
public final class RwrMissileApproachChecks {
    public static void main(String[] args) {
        AABB aircraft = new AABB(-8, -3, -12, 8, 3, 12);
        Vec3 ahead = new Vec3(1000, 0, 0);
        require(RwrMissileApproach.isIncoming(ahead, new Vec3(-10, 0, 0), aircraft, Vec3.ZERO),
                "approaching managed missile must warn even without a radar target record");
        require(!RwrMissileApproach.isIncoming(ahead, new Vec3(10, 0, 0), aircraft, Vec3.ZERO),
                "outbound missile must not warn");
        require(!RwrMissileApproach.isIncoming(new Vec3(1000, 100, 0),
                new Vec3(-10, 0, 0), aircraft, Vec3.ZERO), "passing missile must not warn");
        require(!RwrMissileApproach.isIncoming(ahead, new Vec3(-1, 0, 0), aircraft, Vec3.ZERO),
                "a path beyond the ten-second prediction window must not warn yet");
        require(RwrMissileApproach.isIncoming(new Vec3(1000, 100, 0),
                new Vec3(-10, 0, 0), aircraft, new Vec3(0, 1, 0)),
                "carrier motion into a missile path must be included");
        require(!RwrMissileApproach.isIncoming(ahead, new Vec3(-10, 0, 0),
                aircraft, new Vec3(-20, 0, 0)), "a carrier escaping faster must not warn");
        require(!RwrMissileApproach.isIncoming(ahead, Vec3.ZERO, aircraft, Vec3.ZERO),
                "stationary missiles must not warn");
        require(!RwrMissileApproach.isIncoming(new Vec3(20, 0, 0),
                new Vec3(10, 0, 0), aircraft, Vec3.ZERO),
                "a missile leaving the near-miss envelope must stop warning");
        require(RwrMissileApproach.isIncoming(new Vec3(20, 0, 0),
                new Vec3(-10, 0, 0), aircraft, Vec3.ZERO),
                "an incoming missile already inside the near-miss envelope must warn");
        require(!RwrMissileApproach.isIncoming(ahead, new Vec3(Double.NaN, 0, 0),
                aircraft, Vec3.ZERO), "invalid telemetry must not warn");
        Vec3 offset = new Vec3(12000, 500, -9000);
        require(RwrMissileApproach.isIncoming(ahead.add(offset), new Vec3(-10, 0, 0),
                aircraft.move(offset), Vec3.ZERO), "world position must not change detection");
        System.out.println("RWR missile approach checks passed (11 scenarios)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
