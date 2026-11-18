package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.content.controller.AimRayLimits;
import net.minecraft.world.phys.Vec3;

/** A distant orbit camera is valid through its authorized physical eye, not its orbit radius. */
public final class AimCameraGeometry {
    public static final double MAX_OFFSET=AimRayLimits.MAX_TARGET_DISTANCE;
    public static final double MOVING_POSE_TOLERANCE=512.0;
    private static final double POSE_TOLERANCE=64.0;
    public static boolean validOffset(Vec3 offset) {
        return finite(offset)&&offset.lengthSqr()<=MAX_OFFSET*MAX_OFFSET;
    }
    public static boolean matches(Vec3 expectedEye,Vec3 projectedEye,Vec3 origin,Vec3 offset) {
        return finite(expectedEye)&&finite(projectedEye)&&finite(origin)&&validOffset(offset)
                &&expectedEye.distanceToSqr(projectedEye)<=POSE_TOLERANCE*POSE_TOLERANCE
                &&origin.distanceToSqr(projectedEye.add(offset))<=POSE_TOLERANCE*POSE_TOLERANCE;
    }
    /** A seat-local anchor survives render/server pose skew without granting an arbitrary eye. */
    public static boolean matchesSeat(Vec3 seatEye,Vec3 localEye,Vec3 projectedEye,Vec3 origin,Vec3 offset) {
        return finite(seatEye)&&finite(localEye)&&finite(projectedEye)&&finite(origin)&&validOffset(offset)
                &&seatEye.distanceToSqr(localEye)<=8.0*8.0
                &&origin.distanceToSqr(projectedEye.add(offset))<=MOVING_POSE_TOLERANCE*MOVING_POSE_TOLERANCE;
    }
    private static boolean finite(Vec3 v){return v!=null&&Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
    private AimCameraGeometry() {}
}
