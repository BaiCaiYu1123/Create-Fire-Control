package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

public final class OpticalLockAimChecks {
    public static void main(String[] args) {
        finiteLockRemovesFalseHighMarker();
        manualLeadUsesTheSamePlaneAsTheMeasuredWeapon();
        realTrackingErrorsAndObstaclesRemainVisible();
        movingAndRolledBodiesKeepConvergence();
        invalidRaysDoNotCreateMarkers();
        System.out.println("PASS: optical lock target-depth convergence, launcher/camera height parallax, manual lead, moving/rolled bodies and real tracking error");
    }

    private static void finiteLockRemovesFalseHighMarker() {
        Vec3 camera = new Vec3(0, 4, -6), muzzle = new Vec3(0, 1, 0), target = new Vec3(0, 10, 100);
        Vec3 bore = target.subtract(muzzle).normalize();
        Vec3 oldMarker = muzzle.add(bore.scale(1_000_000));
        Vec3 marker = OpticalLockAimGeometry.atTargetDepth(muzzle, bore, camera, target);
        require(marker != null && marker.distanceTo(target) < 1e-9, "measured launcher converges on finite locked target");
        Vec3 forward = target.subtract(camera).normalize(), right = new Vec3(1, 0, 0);
        Vec3 up = forward.cross(right).normalize();
        require(screenY(oldMarker, camera, forward, up) < -10, "old infinity marker falsely appears above the player's sight");
        require(Math.abs(screenY(marker, camera, forward, up)) < 1e-8, "new marker projects at the player's actual sight");
        // The ordinary sky/direction marker is deliberately outside this lock-only geometry.
    }

    private static void manualLeadUsesTheSamePlaneAsTheMeasuredWeapon() {
        Vec3 camera = new Vec3(2, 4, -7), muzzle = new Vec3(-1, 1, 2), target = new Vec3(5, 20, 120);
        for (Vec3 ray : new Vec3[]{target.subtract(camera), new Vec3(.3, .2, 1), new Vec3(-.25, -.1, 1)}) {
            Vec3 request = OpticalLockAimGeometry.atTargetDepth(camera, ray, camera, target);
            require(request != null, "manual lead intersects the locked depth plane");
            require(request.subtract(camera).normalize().distanceTo(ray.normalize()) < 1e-12,
                    "input ray retains the player's configured view/head-aim direction");
            Vec3 measured = OpticalLockAimGeometry.atTargetDepth(muzzle, request.subtract(muzzle), camera, target);
            require(measured != null && measured.distanceTo(request) < 1e-9,
                    "a physically aligned muzzle displays at the exact requested sight, including manual lead");
            Vec3 newerTarget = target.add(3, .5, -1);
            Vec3 serverAim = newerTarget.add(request.subtract(target));
            require(serverAim.subtract(newerTarget).distanceTo(request.subtract(target)) < 1e-12,
                    "delayed client target reference preserves manual lead without adding target movement twice");
        }
    }

    private static void realTrackingErrorsAndObstaclesRemainVisible() {
        Vec3 camera = new Vec3(0, 4, -6), muzzle = new Vec3(0, 1, 0), target = new Vec3(0, 10, 100);
        Vec3 aligned = target.subtract(muzzle).normalize();
        Vec3 lagging = aligned.add(.02, .03, 0).normalize();
        Vec3 error = OpticalLockAimGeometry.atTargetDepth(muzzle, lagging, camera, target);
        require(error != null && error.distanceTo(target) > 2, "actual traverse/elevation error is not hidden by snapping to sight");
        require(error.subtract(muzzle).normalize().distanceTo(lagging) < 1e-12, "marker stays on the measured weapon ray");
        Vec3 near = muzzle.add(aligned.scale(15)), far = muzzle.add(aligned.scale(500));
        require(near.equals(OpticalLockAimGeometry.marker(muzzle, aligned, camera, target, near)), "near obstacle remains the true marker");
        require(OpticalLockAimGeometry.marker(muzzle, aligned, camera, target, far).distanceTo(target) < 1e-9,
                "terrain or Voxy behind the locked target cannot restore false long-range parallax");
    }

    private static void movingAndRolledBodiesKeepConvergence() {
        for (double angle : new double[]{0, .4, -.8}) for (double tick : new double[]{0, .25, .75, 1}) {
            Quaterniond rotation = new Quaterniond().rotationYXZ(angle, -.2, .7);
            Vec3 movement = new Vec3(2000 + tick * .8, 80 + tick * .1, -400 + tick * .4);
            Vec3 camera = transform(rotation, new Vec3(0, 4, -8)).add(movement);
            Vec3 muzzle = transform(rotation, new Vec3(1, 1, 3)).add(movement);
            Vec3 target = transform(rotation, new Vec3(5, 6, 90)).add(movement).add(tick, tick * .2, 0);
            Vec3 actual = OpticalLockAimGeometry.atTargetDepth(muzzle, target.subtract(muzzle), camera, target);
            require(actual != null && actual.distanceTo(target) < 1e-8,
                    "rendered moving camera/muzzle and target share one finite depth under body roll");
        }
    }

    private static void invalidRaysDoNotCreateMarkers() {
        Vec3 target = new Vec3(0, 0, 100);
        require(OpticalLockAimGeometry.atTargetDepth(Vec3.ZERO, new Vec3(0, 0, -1), Vec3.ZERO, target) == null,
                "backward ray has no false front-facing intersection");
        require(OpticalLockAimGeometry.atTargetDepth(Vec3.ZERO, new Vec3(1, 0, 0), Vec3.ZERO, target) == null,
                "parallel ray cannot jump to an infinite marker");
        require(OpticalLockAimGeometry.atTargetDepth(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, target) == null,
                "zero direction rejected");
        require(OpticalLockAimGeometry.atTargetDepth(Vec3.ZERO, target, Vec3.ZERO, new Vec3(Double.NaN, 0, 1)) == null,
                "nonfinite target rejected");
    }

    private static Vec3 transform(Quaterniond q, Vec3 value) {
        Vector3d v = q.transform(new Vector3d(value.x, value.y, value.z));
        return new Vec3(v.x, v.y, v.z);
    }

    private static double screenY(Vec3 point, Vec3 camera, Vec3 forward, Vec3 up) {
        Vec3 delta = point.subtract(camera);
        return -delta.dot(up) * 720 / delta.dot(forward);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
