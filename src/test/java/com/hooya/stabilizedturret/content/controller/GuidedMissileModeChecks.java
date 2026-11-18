package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.config.FireControlSettings;
import net.minecraft.nbt.CompoundTag;

public final class GuidedMissileModeChecks {
    public static void main(String[] args) {
        require(FireControlSettings.MISSILE_TRAJECTORY_MODE.getDefault() == FireControlSettings.TrajectoryMode.MATHEMATICAL,
                "Instance-wide trajectory must default to mathematical flight");
        require(FireControlSettings.mathematicalMissileFlight(), "Unloaded config must use its mathematical default");

        for (String oldRoute : new String[]{"", "entity", "math", "unknown"}) {
            CompoundTag shot = new CompoundTag(); shot.putString(GuidedMissileMode.ROUTE, oldRoute);
            require(GuidedMissileMode.captureRoute(shot, () -> true), "Restored old shots must use the math default");
            require(shot.getInt(GuidedMissileMode.ROUTE_POLICY_TAG) == 1, "Shot route was not versioned");
            require(GuidedMissileMode.captureRoute(shot, () -> { throw new AssertionError("In-flight math route consulted new default"); }),
                    "Changing the config changed an already selected math shot");
        }
        CompoundTag entityShot = new CompoundTag();
        require(!GuidedMissileMode.captureRoute(entityShot, () -> false), "Explicit entity default was ignored");
        require(!GuidedMissileMode.captureRoute(entityShot, () -> { throw new AssertionError("In-flight entity route consulted new default"); }),
                "Changing the config changed an already selected entity shot");
        System.out.println("PASS: mathematical config default, old-shot route selection and fixed in-flight routes");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
