package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** World-space fallback for managed missiles without a radar target identity. */
public final class RwrMissileApproach {
    public static boolean isIncoming(Vec3 position, Vec3 velocity, AABB receiver,
                                     Vec3 receiverVelocity) {
        if (!finite(position) || !finite(velocity) || !finite(receiverVelocity)) return false;
        Vec3 relativeVelocity = velocity.subtract(receiverVelocity);
        Vec3 toReceiver = receiver.getCenter().subtract(position);
        if (relativeVelocity.lengthSqr() < 1.0e-8
                || relativeVelocity.dot(toReceiver) <= 0.0) return false;
        // Ten seconds of relative motion, with room for a near miss/proximity fuze.
        AABB danger = receiver.inflate(16.0);
        return danger.contains(position)
                || danger.clip(position, position.add(relativeVelocity.scale(200.0))).isPresent();
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x)
                && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private RwrMissileApproach() {}
}
