package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/** Geometry shared by the physical heading sampler and the scope display. */
public final class ScopeOrientationMath {
    private ScopeOrientationMath() {}

    public static boolean valid(Vec3 direction) {
        return direction != null && Double.isFinite(direction.x) && Double.isFinite(direction.y)
                && Double.isFinite(direction.z) && direction.lengthSqr() > 1.0e-12;
    }

    public static double yaw(Vec3 direction) {
        return Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    public static double wrap(double degrees) {
        return degrees - 360.0 * Math.floor((degrees + 180.0) / 360.0);
    }

    public static Vec3 rotate(Quaterniondc rotation, Vec3 direction) {
        Vector3d result = rotation.transform(new Vector3d(direction.x, direction.y, direction.z));
        return new Vec3(result.x, result.y, result.z);
    }

    /** Remove the actual yaw joint rotation; the zero pose defines the vehicle's front. */
    public static Vec3 hullForward(Vec3 barrel, Quaterniondc turretPose, Quaterniondc bearingFrame,
                                   Quaterniondc plateFrame, double assemblyZero) {
        Vec3 localBarrel = rotate(new Quaterniond(turretPose).conjugate(), barrel);
        Quaterniond neutral = new Quaterniond(bearingFrame).rotateY(Math.toRadians(assemblyZero))
                .mul(new Quaterniond(plateFrame).conjugate());
        Vec3 forward = rotate(neutral, localBarrel);
        Vec3 horizontal = new Vec3(forward.x, 0, forward.z);
        return horizontal.lengthSqr() > 1.0e-8 ? horizontal.normalize() : null;
    }
}
