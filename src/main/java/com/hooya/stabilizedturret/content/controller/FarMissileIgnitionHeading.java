package com.hooya.stabilizedturret.content.controller;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** World attitude saved by Mianbao's delayed-ignition rack, independent of falling velocity. */
final class FarMissileIgnitionHeading {
    static final String RELEASE_TAG = "MianbaoFarRackRelease";
    static final String ALIGNED_TAG = "CreateFireControlIgnitionHeadingAligned";

    static @Nullable Vec3 direction(CompoundTag release) {
        if (!release.getBoolean("SoundPlayed")) return null;
        for (String key : new String[]{"QX", "QY", "QZ", "QW"}) {
            if (!release.contains(key, Tag.TAG_ANY_NUMERIC)) return null;
        }
        Quaterniond rotation = new Quaterniond(release.getDouble("QX"), release.getDouble("QY"),
                release.getDouble("QZ"), release.getDouble("QW"));
        double norm = rotation.lengthSquared();
        if (!Double.isFinite(norm) || norm < 1.0e-12) return null;
        Vector3d angular = new Vector3d(release.getDouble("WX"), release.getDouble("WY"), release.getDouble("WZ"));
        if (!angular.isFinite()) return null;
        int age = Math.max(0, release.getInt(release.contains("IgnitionDropAge", Tag.TAG_ANY_NUMERIC)
                ? "IgnitionDropAge" : "Age"));
        double speed = angular.length();
        if (!Double.isFinite(speed)) return null;
        // Native angular velocity is in world space, so its rotation precedes the release attitude.
        if (speed > 1.0e-9) rotation.premul(new Quaterniond().rotationAxis(
                speed * age, angular.x / speed, angular.y / speed, angular.z / speed));
        Vector3d forward = rotation.normalize().transform(new Vector3d(0, 0, -1));
        return forward.isFinite() ? new Vec3(forward.x, forward.y, forward.z).normalize() : null;
    }

    private FarMissileIgnitionHeading() { }
}
