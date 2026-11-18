package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Quaterniond;
import org.joml.Vector3d;

public final class TurretAimConvergenceChecks {
    private TurretAimConvergenceChecks() {
    }

    public static void main(String[] args) {
        separatedPitchGroupsConvergeOnTheSameTarget();
        directionOnlyAimRemainsParallel();
        embeddedBarrelKeepsSightDirection();
        closeTargetBoundaryDoesNotToggle();
        ordinaryCloseAndRearwardAcquisitionStillConverge();
        System.out.println("PASS: grouped finite aim and embedded-barrel terrain guard; yaw/pitch, tilted carrier, re-entry and boundary jitter checked");
    }

    private static void embeddedBarrelKeepsSightDirection() {
        int rearwardMuzzleRays = 0;
        Vec3 pivot = new Vec3(0, 1, 0);
        Vec3 sight = new Vec3(.05, -.5, 1).normalize();
        var guard = new TurretAimConvergence.NearTargetGuard();
        for (int frame = 0; frame < 120; frame++) {
            double yaw = .15 * Math.sin(frame * .1), pitch = .4 + .1 * Math.sin(frame * .07);
            Quaterniond pose = new Quaterniond().rotationY(yaw).rotateX(pitch);
            Vec3 muzzle = pivot.add(rotate(pose, new Vec3(0, 0, 8)));
            Vec3 camera = pivot.add(rotate(pose, new Vec3(.4, 1, 1)));
            Vec3 end = camera.add(sight.scale(128));
            BlockHitResult hit = BlockGetter.traverseBlocks(camera, end, null, (unused, pos) ->
                    pos.getY() == -1 ? Shapes.block().clip(camera, end, pos) : null, unused -> null);
            require(hit != null && Math.abs(hit.getLocation().y) < 1e-9, "camera hits real world floor voxel faces");
            Vec3 target = hit.getLocation();
            Vec3 oldRequest = TurretAimConvergence.requestedDirection(true, target, muzzle, sight);
            if (oldRequest.dot(sight) < 0) rearwardMuzzleRays++;
            require(muzzle.y < 0, "fixture's noncolliding barrel penetrates the floor");
            require(guard.update(pivot, muzzle, target), "embedded floor point cannot become a finite motor target");
            Vec3 request = TurretAimConvergence.requestedDirection(false, target, muzzle, sight);
            require(request.distanceToSqr(sight) < 1e-12, "both bearing paths receive the steady operator sight");
            // The decision depends on reach, not the current angle or world axes.
            Quaterniond tilted = new Quaterniond().rotationXYZ(.3, .8, -.2);
            Vec3 shift = new Vec3(300, 70, -200);
            require(new TurretAimConvergence.NearTargetGuard().update(shift.add(rotate(tilted, pivot)),
                    shift.add(rotate(tilted, muzzle)), shift.add(rotate(tilted, target))),
                    "tilted moving carriers retain the same near-target decision");
        }
        require(rearwardMuzzleRays > 100, "old finite request reproduces the backwards aim through terrain");
        require(!guard.update(pivot, pivot.add(0, 0, 8), pivot.add(0, 0, 24)),
                "leaving the floor restores ordinary finite aim without a new session");
    }

    private static void closeTargetBoundaryDoesNotToggle() {
        Vec3 pivot = Vec3.ZERO, muzzle = new Vec3(0, 0, 8);
        var guard = new TurretAimConvergence.NearTargetGuard();
        require(!guard.update(pivot, muzzle, new Vec3(0, 0, 9.1)), "outside barrel sweep keeps convergence");
        require(guard.update(pivot, muzzle, new Vec3(0, 0, 8.49)), "near point enters sight-only control");
        for (int frame = 0; frame < 100; frame++) {
            double depth = 8.6 + .25 * Math.sin(frame);
            require(guard.update(pivot, muzzle, new Vec3(0, 0, depth)), "boundary noise cannot rearm finite aiming");
        }
        require(!guard.update(pivot, muzzle, new Vec3(0, 0, 9.01)), "clear forward range releases the guard");
        require(!guard.update(pivot, muzzle, new Vec3(0, 0, 8.7)), "returning boundary noise keeps finite control");
        var otherGroup = new TurretAimConvergence.NearTargetGuard();
        require(guard.update(pivot, muzzle, new Vec3(0, 0, 2))
                && !otherGroup.update(pivot, new Vec3(0, 0, 1), new Vec3(0, 0, 3)),
                "a long barrel cannot disable a separate short-barrel group");
    }

    private static void ordinaryCloseAndRearwardAcquisitionStillConverge() {
        Vec3 muzzle = new Vec3(0, 0, 8), target = new Vec3(1, 0, 10);
        var guard = new TurretAimConvergence.NearTargetGuard();
        require(!guard.update(Vec3.ZERO, muzzle, target), "reachable two-block muzzle clearance keeps close convergence");
        requireRayPassesThrough(muzzle, TurretAimConvergence.requestedDirection(true, target, muzzle,
                new Vec3(0, 0, 1)), target, "reachable near face keeps exact parallax");
        require(!guard.update(Vec3.ZERO, muzzle, new Vec3(0, 0, -100)),
                "a distant target behind the bore still permits ordinary half-turn acquisition");
    }

    private static Vec3 rotate(Quaterniond rotation, Vec3 value) {
        Vector3d result = rotation.transform(new Vector3d(value.x, value.y, value.z));
        return new Vec3(result.x, result.y, result.z);
    }

    private static void separatedPitchGroupsConvergeOnTheSameTarget() {
        Vec3 target = new Vec3(4.0, 3.0, 24.0);
        Vec3 firstOrigin = new Vec3(-2.0, 0.0, 0.0);
        Vec3 secondOrigin = new Vec3(3.0, 1.0, 0.0);

        Vec3 first = TurretAimConvergence.requestedDirection(
                true, target, firstOrigin, Vec3.ZERO);
        Vec3 second = TurretAimConvergence.requestedDirection(
                true, target, secondOrigin, Vec3.ZERO);

        requireRayPassesThrough(firstOrigin, first, target,
                "first pitch group must converge on the view target");
        requireRayPassesThrough(secondOrigin, second, target,
                "second pitch group must converge on the view target");
        require(first.distanceToSqr(second) > 1.0e-6,
                "separated finite-range muzzles must not receive one parallel direction");
    }

    private static void directionOnlyAimRemainsParallel() {
        Vec3 fallback = new Vec3(2.0, -1.0, 8.0).normalize();
        Vec3 first = TurretAimConvergence.requestedDirection(
                false, Vec3.ZERO, new Vec3(-10.0, 3.0, 1.0), fallback);
        Vec3 second = TurretAimConvergence.requestedDirection(
                false, Vec3.ZERO, new Vec3(20.0, -6.0, 4.0), fallback);
        require(first.distanceToSqr(second) < 1.0e-12,
                "infinite direction aim must remain independent of muzzle origin");
    }

    private static void requireRayPassesThrough(
            Vec3 origin,
            Vec3 direction,
            Vec3 target,
            String message
    ) {
        Vec3 displacement = target.subtract(origin);
        Vec3 closest = origin.add(direction.scale(displacement.dot(direction)));
        require(closest.distanceToSqr(target) < 1.0e-12, message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
