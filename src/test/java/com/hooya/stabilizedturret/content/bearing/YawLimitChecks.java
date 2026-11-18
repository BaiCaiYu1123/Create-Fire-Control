package com.hooya.stabilizedturret.content.bearing;

import net.minecraft.nbt.CompoundTag;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Azimuth corridor, carrier-frame and storage regressions. */
public final class YawLimitChecks {
    public static void main(String[] args) {
        require(!YawLimits.UNLIMITED.enabled(), "180/180 must be a free hinge");
        for(int center=0;center<4;center++)require(!new YawLimits(180,180,center).enabled(),"both sides at 180 disable limits for every center");
        require(new YawLimits(179.99,180,0).enabled()&&new YawLimits(180,179.99,0).enabled(),"either side below 180 enables its stop");
        for (Quaterniond parent : new Quaterniond[]{new Quaterniond(), new Quaterniond().rotationXYZ(.3, 1.8, -.4)}) {
            for (int center = 0; center < 4; center++) {
                YawLimits limits = new YawLimits(45.5, 70.25, center);
                for (double angle : new double[]{-170, -100, -70.25, 0, 45.5, 90, 179}) {
                    Vector3d ray = ray(limits, angle, parent);
                    Vector3d bounded = limits.limitDirection(ray, parent);
                    double result = limits.angleDegrees(bounded, parent);
                    require(result >= -70.25 - 1e-8 && result <= 45.5 + 1e-8, "all cardinal centers respect asymmetric limits on tilted carriers");
                    require(Math.abs(parent.transformInverse(new Vector3d(bounded)).y - .25) < 1e-10, "azimuth limit preserves elevation");
                    require(YawLimits.UNLIMITED.limitDirection(ray, parent).distance(ray) < 1e-12, "unlimited leaves every ray untouched");
                }
                for (double axisSign : new double[]{-1, 1}) {
                    Vector3d right = ray(limits, -70.25, parent);
                    Vector3d left = ray(limits, 45.5, parent);
                    require(limits.advance(left, parent, Math.toRadians(2) * axisSign, axisSign).distance(left) < 1e-10, "mechanical input stops at left boundary even on inverted bearings");
                    require(limits.advance(right, parent, -Math.toRadians(2) * axisSign, axisSign).distance(right) < 1e-10, "mechanical input stops at right boundary even on inverted bearings");
                    require(limits.advance(left, parent, -Math.toRadians(2) * axisSign, axisSign).distance(left) > .02, "reverse input moves away from a stop");
                }
            }
        }
        YawLimits wide = new YawLimits(150, 120, YawLimits.SOUTH);
        Quaterniond parent = new Quaterniond().rotationXYZ(.2, 2, -.3);
        Vector3d from = ray(wide, 140, parent), to = ray(wide, -110, parent);
        double error = wide.errorRadians(from, to, parent, 1);
        require(Math.abs(Math.toDegrees(error) + 250) < 1e-9, "rear target must travel through the legal corridor, not shortcut through the forbidden sector");
        var motion = new BearingServoMath.OperatorMotion();
        for (int i = 0; i < 1000; i++) {
            double step = motion.advance(wide.errorRadians(from, to, parent, 1), Math.toRadians(40), .01);
            from = wide.advance(from, parent, step, 1);
            require(wide.angleDegrees(from, parent) >= -120 - 1e-8 && wide.angleDegrees(from, parent) <= 150 + 1e-8, "acquisition remains inside limits on every step");
        }
        require(from.distance(to) < 1e-8, "long acquisition reaches its requested heading");
        var legacyMotion = new BearingServoMath.StabilizedAimMotion();
        double travel = legacyMotion.advance(Math.toRadians(-250), 0, Math.toRadians(2), false);
        require(Math.abs(Math.toDegrees(travel) + 2) < 1e-9, "legacy acquisition follows the long legal turn");
        legacyMotion.record(Math.toRadians(-250), travel, false);
        double compensation = legacyMotion.compensation(Math.toRadians(-253), false);
        require(Math.abs(Math.toDegrees(compensation) + 5) < 1e-9, "legacy carrier compensation preserves remaining long traverse");
        require(Math.abs(Math.toDegrees(legacyMotion.advance(Math.toRadians(-253), compensation,
                Math.toRadians(2), false)) + 7) < 1e-9, "legacy retains automatic compensation outside the head rate budget");
        for (YawLimits open : new YawLimits[]{new YawLimits(180, 60, 1), new YawLimits(60, 180, 1)}) {
            double angle = open.left() == 180 ? 220 : -220;
            Vector3d ray = ray(open, angle, parent);
            require(open.limitDirection(ray, parent).distance(ray) < 1e-10, "a side at 180 allows travel beyond 180 degrees");
            Vector3d beforeRear = ray(open, Math.copySign(179, angle), parent);
            Vector3d afterRear = open.advance(beforeRear, parent, Math.toRadians(Math.copySign(2, angle)), 1);
            require(Math.abs(open.angleDegrees(afterRear, parent) - Math.copySign(181, angle)) < 1e-8, "the rear seam does not become an artificial stop");
        }
        YawLimits locked = new YawLimits(0, 0, 0);
        require(locked.limitDirection(ray(locked, 90, parent), parent).distance(ray(locked, 0, parent)) < 1e-10, "zero locks azimuth at its calibrated center");
        CompoundTag tag = new CompoundTag();
        wide.save(tag);
        require(YawLimits.load(tag).equals(wide), "limits and center survive storage");
        require(YawLimits.load(new CompoundTag()).equals(YawLimits.UNLIMITED), "old saves default to unrestricted yaw");
        require(new YawLimits(-10, 250, -5).equals(new YawLimits(0, 180, 1)), "invalid ranges and directions are sanitized");
        outsideReturnAcrossRearSeam();
        System.out.println("PASS: asymmetric yaw limits, four centers, tilted/inverted bearings, legal long turns, open-side rear seam and storage");
    }

    private static void outsideReturnAcrossRearSeam() {
        var stops = new BearingServoMath.YawStops();
        double half = Math.toRadians(45), dt = 1.0 / 40;
        stops.update(-half, half, Math.toRadians(179.9), dt);
        // An external impulse can carry the outside body through the atan2 seam.
        stops.update(-half, half, Math.toRadians(-179.9), dt);
        require(stops.reference() > Math.PI, "rear seam drift retains the original return side");
        double goal = stops.goal(half, dt);
        require(goal < stops.reference() && stops.reference() - goal < Math.toRadians(4),
                "return goal stays near the physical pose across the seam");
        require(stops.motorGoal(0) < 0 && stops.nativeMax() < 0,
                "both native stop and motor return inward after crossing the rear seam");
        stops.reset();
        stops.update(-half, half, Math.toRadians(-179.9), dt);
        require(stops.motorGoal(0) > 0 && stops.nativeMin() > 0,
                "a newly enabled rear-right pose returns from its own side");
    }

    private static Vector3d ray(YawLimits limits, double degrees, Quaterniond parent) {
        return parent.transform(limits.localCenter().rotateY(Math.toRadians(degrees)).mul(Math.sqrt(1 - .25 * .25)).add(0, .25, 0));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
