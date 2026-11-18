package com.hooya.stabilizedturret.content.bearing;

import net.minecraft.nbt.CompoundTag;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/** Parent-local azimuth stops. 180 disables that side; two disabled sides are a free hinge. */
public record YawLimits(double left, double right, int centerDirection) {
    public static final int EAST = 0, SOUTH = 1, WEST = 2, NORTH = 3;
    public static final YawLimits UNLIMITED = new YawLimits(180.0, 180.0, SOUTH);

    public YawLimits {
        left = clamp(left);
        right = clamp(right);
        if (centerDirection < EAST || centerDirection > NORTH) centerDirection = SOUTH;
    }

    public static double clamp(double value) {
        return Double.isFinite(value) ? Math.clamp(value, 0.0, 180.0) : 180.0;
    }

    public boolean enabled() { return left < 180.0 || right < 180.0; }

    // An open side may pass the rear hemisphere. Its only remaining obstacle
    // is the other side's stop after going around, never an artificial stop at 180.
    public double minDegrees() { return right < 180.0 ? -right : left - 360.0; }
    public double maxDegrees() { return left < 180.0 ? left : 360.0 - right; }
    public double midpointDegrees() { return (minDegrees() + maxDegrees()) * 0.5; }
    public double halfRangeRadians() { return Math.toRadians((maxDegrees() - minDegrees()) * 0.5); }

    public double nativeCenterDegrees(Vector3d zeroForward, Quaterniondc parent, double axisSign) {
        return Math.toDegrees(BearingServoMath.wrapRadians(Math.toRadians(
                (midpointDegrees() - angleDegrees(zeroForward, parent)) * axisSign)));
    }

    public Vector3d localCenter() {
        return switch (centerDirection) {
            case EAST -> new Vector3d(1, 0, 0);
            case WEST -> new Vector3d(-1, 0, 0);
            case NORTH -> new Vector3d(0, 0, -1);
            default -> new Vector3d(0, 0, 1);
        };
    }

    public double angleDegrees(Vector3d worldDirection, Quaterniondc parent) {
        Vector3d local = parent.transformInverse(new Vector3d(worldDirection));
        Vector3d center = localCenter();
        if (local.x * local.x + local.z * local.z < 1e-12) return 0.0;
        double angle = Math.toDegrees(Math.atan2(center.z * local.x - center.x * local.z,
                center.x * local.x + center.z * local.z));
        if (enabled()) angle += 360.0 * Math.rint((midpointDegrees() - angle) / 360.0);
        return angle;
    }

    public Vector3d limitDirection(Vector3d direction, Quaterniondc parent) {
        if (!enabled()) return new Vector3d(direction);
        double angle = angleDegrees(direction, parent);
        double bounded = Math.clamp(angle, minDegrees(), maxDegrees());
        return rotate(direction, parent, Math.toRadians(bounded - angle));
    }

    public double errorRadians(Vector3d from, Vector3d to, Quaterniondc parent, double axisSign) {
        return Math.toRadians(angleDegrees(limitDirection(to, parent), parent)
                - angleDegrees(from, parent)) * axisSign;
    }

    public Vector3d advance(Vector3d from, Quaterniondc parent, double axisStep, double axisSign) {
        double current = angleDegrees(from, parent);
        double next = Math.clamp(current + Math.toDegrees(axisStep) * axisSign,
                minDegrees(), maxDegrees());
        return rotate(from, parent, Math.toRadians(next - current));
    }

    private static Vector3d rotate(Vector3d direction, Quaterniondc parent, double radians) {
        Vector3d local = parent.transformInverse(new Vector3d(direction));
        local.rotateY(radians);
        return parent.transform(local);
    }

    public void save(CompoundTag tag) {
        tag.putDouble("YawLeftLimit", left);
        tag.putDouble("YawRightLimit", right);
        tag.putInt("YawLimitCenter", centerDirection);
    }

    public static YawLimits load(CompoundTag tag) {
        return new YawLimits(tag.contains("YawLeftLimit") ? tag.getDouble("YawLeftLimit") : 180.0,
                tag.contains("YawRightLimit") ? tag.getDouble("YawRightLimit") : 180.0,
                tag.contains("YawLimitCenter") ? tag.getInt("YawLimitCenter") : SOUTH);
    }
}
