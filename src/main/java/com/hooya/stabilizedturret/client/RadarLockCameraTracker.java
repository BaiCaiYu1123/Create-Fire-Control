package com.hooya.stabilizedturret.client;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Smooth acquisition followed by bearing deltas, retaining the gunner's manual angular lead. */
public final class RadarLockCameraTracker {
    private UUID targetId;
    private int view;
    private Vec3 previousBearing;
    private double remainingYaw, remainingPitch;
    private final LockCameraAcquisitionMemory acquisitions = new LockCameraAcquisitionMemory();

    public record Correction(float yaw, float pitch) {}

    public void suspend() { targetId = null; previousBearing = null; remainingYaw = remainingPitch = 0; }
    public void reset() { suspend(); acquisitions.clear(); }

    public Correction update(UUID id, int viewMode, Vec3 target, Vec3 origin, Vec3 sight, double seconds) {
        Vec3 bearing = target.subtract(origin);
        if (bearing.lengthSqr() < 1e-8 || sight.lengthSqr() < 1e-8) return new Correction(0, 0);
        float motionYaw = 0, motionPitch = 0;
        if (acquisitions.begin(id)) {
            targetId = id;
            view = viewMode;
            remainingYaw = Mth.wrapDegrees(yaw(bearing) - yaw(sight));
            remainingPitch = pitch(bearing) - pitch(sight);
        } else if (!id.equals(targetId) || viewMode != view || previousBearing == null) {
            // A scope/camera switch or remount establishes a new bearing baseline,
            // but must not centre the player's deliberately chosen sight again.
            targetId = id;
            view = viewMode;
            remainingYaw = remainingPitch = 0;
        } else {
            motionYaw = Mth.wrapDegrees(yaw(bearing) - yaw(previousBearing));
            motionPitch = pitch(bearing) - pitch(previousBearing);
        }
        previousBearing = bearing;
        double alpha = 1 - Math.exp(-6 * Math.clamp(seconds, 0, .1));
        float yaw = (float) (remainingYaw * alpha), pitch = (float) (remainingPitch * alpha);
        remainingYaw -= yaw;
        remainingPitch -= pitch;
        return new Correction(motionYaw + yaw, motionPitch + pitch);
    }

    private static float yaw(Vec3 v) { return (float) Math.toDegrees(Math.atan2(-v.x, v.z)); }
    private static float pitch(Vec3 v) { return (float) -Math.toDegrees(Math.atan2(v.y, Math.hypot(v.x, v.z))); }
}
