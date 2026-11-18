package com.hooya.stabilizedturret.client;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Single owner of the electro-optical pod's client-side attitude and gyro
 * target.
 *
 * <p>Data flows in one direction: manual input updates continuous gimbal
 * angles, a resolver supplies one legal world point, and aircraft motion is
 * compensated by solving the direction from the current optical origin back
 * to that immutable point. Camera/model/weapon consumers may read the result
 * but never feed an angle back into this controller.</p>
 */
public final class PodAimController {
    public static final float MIN_PITCH = -80.0F;
    public static final float MAX_PITCH = 260.0F;
    private static final double DEFAULT_RANGE = 4096.0;

    private float yaw;
    private float pitch;
    private boolean attitudeInitialized;
    private Vec3 worldTarget = Vec3.ZERO;
    private boolean targetValid;
    private double targetRange = DEFAULT_RANGE;
    private Vec3 renderedOrigin = Vec3.ZERO;
    private boolean renderedOriginValid;
    private long inputRevision;

    public void begin(Vec3 savedDirection, Vec3 origin) {
        if (!attitudeInitialized) {
            Angles initial = principalAngles(savedDirection);
            yaw = initial.yaw();
            pitch = clampPitch(initial.pitch());
            attitudeInitialized = true;
        } else {
            Angles restored = nearestAngles(savedDirection, yaw, pitch);
            yaw = restored.yaw();
            pitch = clampPitch(restored.pitch());
        }
        targetValid = false;
        renderedOrigin = origin;
        renderedOriginValid = finite(origin);
        targetRange = DEFAULT_RANGE;
        inputRevision++;
    }

    /** Ends ownership without resetting the saved continuous gimbal attitude. */
    public void end() {
        targetValid = false;
        renderedOrigin = Vec3.ZERO;
        renderedOriginValid = false;
        inputRevision++;
    }

    public void recordRenderedOrigin(Vec3 origin) {
        if (!finite(origin)) return;
        renderedOrigin = origin;
        renderedOriginValid = true;
    }

    public Vec3 inputOrigin(Vec3 fallback) {
        return renderedOriginValid && finite(renderedOrigin)
                ? renderedOrigin : fallback;
    }

    /**
     * Applies a mouse delta to the current gyro-compensated attitude. The
     * returned direction is ready for a synchronous legal-target raycast.
     */
    public Vec3 turn(
            Vec3 origin,
            float yawDelta,
            float pitchDelta
    ) {
        stabilize(origin);
        float requestedPitch = clampPitch(pitch + pitchDelta);
        // Below the nadir the video image is upside down. Reverse only the
        // mouse mapping so screen-left remains screen-left; yaw itself stays
        // continuous and never changes Euler branches.
        float yawSign = Math.cos(Math.toRadians(pitch)) < 0.0
                ? -1.0F : 1.0F;
        yaw += yawDelta * yawSign;
        pitch = requestedPitch;
        Vec3 direction = direction();
        double range = targetValid
                ? Math.max(1.0, origin.distanceTo(worldTarget))
                : Math.max(1.0, targetRange);
        worldTarget = origin.add(direction.scale(range));
        targetRange = range;
        targetValid = true;
        inputRevision++;
        return direction;
    }

    /**
     * Compensates all carrier translation and rotation against the retained
     * world point, then enforces the physical pitch stop. An unreachable point
     * is moved onto the stop at equal range so it can never force an inverted
     * yaw branch behind the controller's back.
     */
    public Vec3 stabilize(Vec3 origin) {
        if (!targetValid || !finite(origin)) return direction();
        Vec3 offset = worldTarget.subtract(origin);
        if (!finite(offset) || offset.lengthSqr() < 1.0e-8) {
            targetValid = false;
            return direction();
        }
        double range = offset.length();
        Angles solved = nearestAngles(offset, yaw, pitch);
        float limitedPitch = clampPitch(solved.pitch());
        yaw = solved.yaw();
        pitch = limitedPitch;
        Vec3 limitedDirection = direction();
        if (Math.abs(limitedPitch - solved.pitch()) > 1.0e-5F) {
            worldTarget = origin.add(limitedDirection.scale(range));
        }
        targetRange = range;
        return limitedDirection;
    }

    public void acceptTarget(Vec3 origin, Vec3 target) {
        if (!finite(origin) || !finite(target)) return;
        Vec3 offset = target.subtract(origin);
        if (offset.lengthSqr() < 1.0e-8) return;
        Angles solved = nearestAngles(offset, yaw, pitch);
        float limitedPitch = clampPitch(solved.pitch());
        yaw = solved.yaw();
        pitch = limitedPitch;
        targetRange = offset.length();
        worldTarget = origin.add(direction().scale(targetRange));
        targetValid = true;
    }

    public void invalidateTarget() {
        targetValid = false;
        inputRevision++;
    }

    public boolean hasTarget() {
        return targetValid && finite(worldTarget);
    }

    public Vec3 target() {
        return worldTarget;
    }

    public Vec3 direction() {
        return directionFromAngles(yaw, pitch);
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public long inputRevision() {
        return inputRevision;
    }

    public Quaternionf cameraRotation(Vec3 origin) {
        Vec3 stabilized = stabilize(origin);
        Angles angles = nearestAngles(stabilized, yaw, pitch);
        yaw = angles.yaw();
        pitch = clampPitch(angles.pitch());
        return new Quaternionf().rotationYXZ(
                (float) Math.PI - yaw * Mth.DEG_TO_RAD,
                -pitch * Mth.DEG_TO_RAD,
                0.0F
        ).normalize();
    }

    private static float clampPitch(float value) {
        return Mth.clamp(value, MIN_PITCH, MAX_PITCH);
    }

    private static Vec3 directionFromAngles(float yaw, float pitch) {
        // This direction is fed back through atan2 by acceptTarget/stabilize.
        // Mth's truncated sine-table indices bias that round trip and erase
        // small mouse deltas, causing direction-dependent drift or stalling.
        double pitchRadians = Math.toRadians(-pitch);
        double yawRadians = Math.toRadians(-yaw);
        double horizontal = Math.cos(pitchRadians);
        return new Vec3(
                Math.sin(yawRadians) * horizontal,
                Math.sin(pitchRadians),
                Math.cos(yawRadians) * horizontal
        ).normalize();
    }

    private static Angles principalAngles(Vec3 value) {
        Vec3 direction = value == null || value.lengthSqr() < 1.0e-8
                ? new Vec3(0.0, 0.0, 1.0) : value.normalize();
        double horizontal = Math.hypot(direction.x, direction.z);
        float yaw = horizontal < 1.0e-7
                ? 0.0F
                : Mth.wrapDegrees((float) Math.toDegrees(
                        Math.atan2(-direction.x, direction.z)));
        float pitch = (float) Math.toDegrees(
                Math.atan2(-direction.y, horizontal));
        return new Angles(yaw, pitch);
    }

    private static Angles nearestAngles(
            Vec3 value,
            float referenceYaw,
            float referencePitch
    ) {
        Vec3 direction = value == null || value.lengthSqr() < 1.0e-8
                ? directionFromAngles(referenceYaw, referencePitch)
                : value.normalize();
        double horizontal = Math.hypot(direction.x, direction.z);
        float principalPitch = (float) Math.toDegrees(
                Math.atan2(-direction.y, horizontal));
        if (horizontal < 1.0e-6) {
            // Yaw is undefined at the exact nadir/zenith. atan2(0, 0)
            // selecting zero caused an instantaneous quarter-turn whenever
            // the retained ground point passed directly under the pod. Keep
            // the existing gimbal azimuth and only choose the continuous
            // pitch branch; once the sight leaves the pole, the ordinary
            // direct/inverted solutions resume without a roll jump.
            float directPitch = nearestEquivalent(
                    principalPitch, referencePitch);
            float invertedPitch = nearestEquivalent(
                    180.0F - principalPitch, referencePitch);
            float pitch = Math.abs(invertedPitch - referencePitch)
                    < Math.abs(directPitch - referencePitch)
                    ? invertedPitch : directPitch;
            return new Angles(referenceYaw, pitch);
        }
        float principalYaw = Mth.wrapDegrees((float) Math.toDegrees(
                Math.atan2(-direction.x, direction.z)));
        Angles direct = new Angles(
                nearestEquivalent(principalYaw, referenceYaw),
                nearestEquivalent(principalPitch, referencePitch)
        );
        Angles inverted = new Angles(
                nearestEquivalent(principalYaw + 180.0F, referenceYaw),
                nearestEquivalent(180.0F - principalPitch, referencePitch)
        );
        return distanceSqr(inverted, referenceYaw, referencePitch)
                < distanceSqr(direct, referenceYaw, referencePitch)
                ? inverted : direct;
    }

    private static float nearestEquivalent(float value, float reference) {
        return reference + Mth.wrapDegrees(value - reference);
    }

    private static double distanceSqr(
            Angles value,
            float referenceYaw,
            float referencePitch
    ) {
        double yawDelta = value.yaw() - referenceYaw;
        double pitchDelta = value.pitch() - referencePitch;
        return yawDelta * yawDelta + pitchDelta * pitchDelta;
    }

    private static boolean finite(Vec3 value) {
        return value != null
                && Double.isFinite(value.x)
                && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }

    private record Angles(float yaw, float pitch) {
    }
}
