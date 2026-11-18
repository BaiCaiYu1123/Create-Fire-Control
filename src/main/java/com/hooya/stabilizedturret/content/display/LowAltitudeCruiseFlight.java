package com.hooya.stabilizedturret.content.display;

import net.minecraft.world.phys.Vec3;

/** Independent horizontal pursuit and damped, predictive terrain following. */
public final class LowAltitudeCruiseFlight {
    // Ordinary cruise's 2.25 degrees/tick at Mach 1, expressed as a radius.
    public static final double GUIDANCE_RADIUS = 17.015 / Math.toRadians(2.25);
    public static final double TERRAIN_RADIUS = 120.0;
    private static final double RELEASE_RADIUS = 24.0;
    private static final double CLEARANCE = 30.0; // Native 20--40 block band.
    public static final double PREFETCH_DISTANCE = 256.0;
    private static final double MAX_PITCH = Math.toRadians(35.0);
    private final double launchY;
    private final boolean verticalLaunch;
    private boolean released;
    private double heading;
    private double plannedCurvature;
    private double plannedTurn;

    @FunctionalInterface
    public interface Ground {
        /** Surface height, or NaN if the chunk is not loaded. Must never load chunks. */
        double height(double x, double z);
    }

    public LowAltitudeCruiseFlight(Vec3 launch, Vec3 direction, Vec3 target) {
        launchY = launch.y;
        verticalLaunch = direction.normalize().y >= 0.75;
        Vec3 horizontal = direction.horizontalDistanceSqr() > 1.0e-8
                ? direction : target.subtract(launch);
        heading = Math.atan2(horizontal.z, horizontal.x);
    }

    public Vec3 step(Vec3 position, Vec3 previous, Vec3 target, double speed,
                     int age, Ground ground) {
        speed = Math.max(0.0, speed);
        double pitch = Math.atan2(previous.y, previous.horizontalDistance());
        if (previous.horizontalDistanceSqr() > 1.0e-8) {
            heading = Math.atan2(previous.z, previous.x);
        }
        if (verticalLaunch && !released) {
            released = age >= 10 && position.y - launchY >= 16.0;
            if (!released) return previous.normalize().scale(speed);
        }
        Vec3 delta = target.subtract(position);
        double distance = delta.horizontalDistance();
        double horizontalSpeed = speed * Math.cos(pitch);
        double headingError = wrap(Math.atan2(delta.z, delta.x) - heading);
        double yawStep = Math.min(Math.toRadians(2.25), horizontalSpeed / GUIDANCE_RADIUS);
        double yawChange = distance > 1.0e-6 ? clamp(headingError, -yawStep, yawStep) : 0.0;
        heading += yawChange;
        headingError -= yawChange;

        boolean terminal = distance < Math.max(160.0, speed * 20.0);
        double desiredVertical = terminal
                ? delta.y / Math.max(4.0, distance / Math.max(0.5, speed)) : -speed * 0.35;
        boolean knownTerrain = false;
        boolean terrainAvoidance = false;
        double lookAhead = Math.max(96.0, speed * 12.0);
        // Sample the same bounded turn arc that the horizontal controller can
        // fly, instead of querying only along the previous straight velocity.
        double turnPerBlock = horizontalSpeed < 1.0e-8 ? 0.0
                : clamp(headingError / horizontalSpeed, -1.0 / GUIDANCE_RADIUS, 1.0 / GUIDANCE_RADIUS);
        plannedCurvature = turnPerBlock;
        plannedTurn = headingError;
        for (double ahead = 0.0; ahead <= lookAhead; ahead += 8.0) {
            if (terminal && ahead >= Math.max(0.0, distance - 16.0)) break;
            Vec3 offset = prefetchOffset(ahead);
            double floor = ground.height(position.x + offset.x, position.z + offset.z);
            if (!Double.isFinite(floor)) continue;
            knownTerrain = true;
            double clearance = terminal ? 2.0 : CLEARANCE;
            double timeToSample = ahead / Math.max(0.5, speed);
            // A height-error velocity command supplies braking as the missile
            // approaches its clearance. It never accumulates native +/- pushes.
            double demand = (floor + clearance - position.y) / (timeToSample + 8.0);
            if (demand > desiredVertical) {
                desiredVertical = demand;
                terrainAvoidance = true;
            }
        }
        if (!terminal && !knownTerrain) desiredVertical = 0.0;
        double desiredPitch = Math.asin(clamp(desiredVertical / Math.max(0.5, speed),
                -Math.sin(MAX_PITCH), Math.sin(MAX_PITCH)));
        double radius = terminal && !terrainAvoidance ? GUIDANCE_RADIUS : TERRAIN_RADIUS;
        // The cold-launch transition has its own short, smooth clearing arc;
        // it must not spend hundreds of blocks turning down from vertical.
        if (verticalLaunch && pitch > MAX_PITCH) radius = RELEASE_RADIUS;
        double pitchStep = Math.min(Math.toRadians(6.0), speed / radius);
        pitch += clamp(desiredPitch - pitch, -pitchStep, pitchStep);
        double planar = speed * Math.cos(pitch);
        return new Vec3(planar * Math.cos(heading), speed * Math.sin(pitch), planar * Math.sin(heading));
    }

    static Vec3 arcOffset(double heading, double curvature, double distance) {
        if (Math.abs(curvature) < 1.0e-8) {
            return new Vec3(Math.cos(heading) * distance, 0, Math.sin(heading) * distance);
        }
        double end = heading + curvature * distance;
        return new Vec3((Math.sin(end) - Math.sin(heading)) / curvature,
                0, (Math.cos(heading) - Math.cos(end)) / curvature);
    }

    public Vec3 prefetchOffset(double distance) {
        double turningDistance = Math.abs(plannedCurvature) < 1.0e-8
                ? 0.0 : Math.min(distance, Math.abs(plannedTurn / plannedCurvature));
        Vec3 bend = arcOffset(heading, plannedCurvature, turningDistance);
        double endHeading = heading + plannedCurvature * turningDistance;
        return bend.add(Math.cos(endHeading) * (distance - turningDistance),
                0, Math.sin(endHeading) * (distance - turningDistance));
    }

    private static double wrap(double angle) { return Math.atan2(Math.sin(angle), Math.cos(angle)); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
