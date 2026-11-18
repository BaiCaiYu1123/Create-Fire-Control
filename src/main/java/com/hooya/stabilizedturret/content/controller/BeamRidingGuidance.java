package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;

/** Shared sight-line guidance for the computer's ATGM and ordinary AA rounds. */
public final class BeamRidingGuidance {
    private static final double CROSS_TRACK_GAIN = 0.08;
    private static final double MAX_LATERAL_SPEED_RATIO = 0.22;
    static final double MAX_TURN_RADIANS = Math.toRadians(4.0);

    public static Vec3 guide(Vec3 position, Vec3 velocity, Vec3 origin, Vec3 direction, double speed) {
        return guide(position, velocity, origin, direction, speed, 1.0);
    }

    /** Scales steering authority while retaining the same beam-riding law. */
    static Vec3 guide(Vec3 position, Vec3 velocity, Vec3 origin, Vec3 direction,
                      double speed, double maneuverability) {
        Vec3 beam = direction.normalize();
        double along = Math.max(0.0, position.subtract(origin).dot(beam));
        Vec3 correction = origin.add(beam.scale(along)).subtract(position);
        double distance = correction.length();
        double lateral = Math.min(speed * MAX_LATERAL_SPEED_RATIO * maneuverability,
                distance * CROSS_TRACK_GAIN * maneuverability);
        Vec3 desired = beam.scale(speed);
        if (distance > 1.0e-6) desired = desired.add(correction.scale(lateral / distance));
        Vec3 current = velocity.lengthSqr() < 1.0e-8 ? beam : velocity.normalize();
        return AcceleratingMissileIntercept.rotateTowards(current, desired.normalize(),
                MAX_TURN_RADIANS * maneuverability).scale(speed);
    }

    private BeamRidingGuidance() {}
}
