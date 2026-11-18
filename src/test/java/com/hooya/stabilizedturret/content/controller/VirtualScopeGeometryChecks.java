package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import java.util.List;
import static com.hooya.stabilizedturret.content.controller.VirtualScopeGeometry.*;

public final class VirtualScopeGeometryChecks {
    private static final Vec3 X = new Vec3(1, 0, 0);

    public static void main(String[] args) {
        Axis left = new Axis(new Vec3(-3, 2, 4), X);
        Axis right = new Axis(new Vec3(3, 2, 4), X.scale(-1));
        near(midpoint(List.of(left, right)).pivot(), new Vec3(0, 2, 4));
        near(midpoint(List.of(right, left)).pivot(), new Vec3(0, 2, 4));
        near(midpoint(List.of(left, right, new Axis(new Vec3(-2, 2, 4), X))).pivot(), new Vec3(0, 2, 4));
        if (midpoint(List.of(left, new Axis(new Vec3(3, 2.25, 4), X))) != null)
            throw new AssertionError("Parallel offset pitch bearings were accepted");
        if (midpoint(List.of(left, new Axis(right.pivot(), new Vec3(0, 0, 1)))) != null)
            throw new AssertionError("Crossed pitch bearings were accepted");
        if (midpoint(List.of()) != null) throw new AssertionError("Missing pitch bearing was accepted");

        // Nearby pivot on a different line must lose to a farther pivot whose axis is nearer.
        Axis offset = new Axis(new Vec3(0, 1, 0), X);
        Axis closeAxis = new Axis(new Vec3(10, 0.25, 0), X);
        near(nearestCannonAxis(Vec3.ZERO, List.of(offset, closeAxis)).pivot(), closeAxis.pivot());
        near(nearestCannonAxis(Vec3.ZERO, List.of(left, right, offset)).pivot(), offset.pivot());
        near(nearestCannonAxis(new Vec3(0, 2, 4), List.of(offset, left, right)).pivot(), new Vec3(0, 2, 4));

        // A CBC breech/native mount pivot may be well behind or above the
        // bound pitch bearing. Neither offset belongs in the virtual camera.
        Axis bearing = new Axis(new Vec3(-3, 2, 8), X);
        Axis breech = new Axis(new Vec3(1, 6, -4), X);
        near(cameraAxis(bearing, breech).pivot(), new Vec3(1, 2, 8));
        near(cameraAxis(bearing, null).pivot(), bearing.pivot());
        if (!coaxial(bearing, cameraAxis(bearing, breech)))
            throw new AssertionError("CBC native pivot replaced the bound physical pitch axis");
        Axis twins = nearestCannonAxis(bearing.pivot(), List.of(
                new Axis(new Vec3(-1, 6, -4), X), new Axis(new Vec3(3, 6, -4), X)));
        near(cameraAxis(bearing, twins).pivot(), new Vec3(1, 2, 8));
        for (double angle : new double[]{-.5, 0, .7}) {
            var offsetFromBearing = breech.pivot().subtract(bearing.pivot());
            var moved = new org.joml.Quaterniond().rotationAxis(angle, 1, 0, 0).transform(
                    new org.joml.Vector3d(offsetFromBearing.x, offsetFromBearing.y, offsetFromBearing.z));
            Axis rotatedBreech = new Axis(bearing.pivot().add(moved.x, moved.y, moved.z), X);
            near(cameraAxis(bearing, rotatedBreech).pivot(), new Vec3(1, 2, 8));
        }

        Vec3 diagonal = new Vec3(1, 2, 3).normalize();
        Vec3 origin = new Vec3(100, 64, -200);
        Axis tiltedLeft = new Axis(origin.add(diagonal.scale(-5)), diagonal);
        Axis tiltedRight = new Axis(origin.add(diagonal.scale(5)), diagonal.scale(-1));
        near(midpoint(List.of(tiltedLeft, tiltedRight)).pivot(), origin);
        Axis tiltedGun = new Axis(origin.add(diagonal.scale(2)).add(2, -1, 0), new Vec3(0, 1, 0));
        near(cameraAxis(tiltedLeft, tiltedGun).pivot(), origin.add(diagonal.scale(2)));
        if (midpoint(List.of(new Axis(origin, Vec3.ZERO))) != null)
            throw new AssertionError("Degenerate axis was accepted");
        System.out.println("Virtual scope geometry checks passed");
    }

    private static void near(Vec3 actual, Vec3 expected) {
        if (actual.distanceToSqr(expected) > 1.0e-12)
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
