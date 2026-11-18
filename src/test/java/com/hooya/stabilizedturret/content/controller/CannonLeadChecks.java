package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.client.RadarLockCameraTracker;
import com.hooya.stabilizedturret.client.LockCameraAcquisitionMemory;
import com.hooya.stabilizedturret.network.CannonLockLeadPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public final class CannonLeadChecks {
    public static void main(String[] args) {
        interceptsActualMovingTrajectories();
        updatesWithAmmunitionAndBothVelocities();
        lateralLeadLeavesTheFrameAndUnreachableFlightIsExplicit();
        invalidAndUnreachableShots();
        smoothTrackingPreservesManualLead();
        acquisitionRunsOnlyOnNewLockEvents();
        cannonLeadIsIndependentOfMissileCameraPolicy();
        payloadRoundTrips();
        System.out.println("PASS: live cannon interception, gravity/linear/quadratic drag, carrier motion, smooth radar tracking and payload codec");
    }

    private static CannonBallisticsCompat.ShotProfile shot(Vec3 velocity, double speed, double gravity,
                                                          double drag, boolean quadratic, int lifetime) {
        return new CannonBallisticsCompat.ShotProfile(new Vec3(4, 3, -8), new Vec3(0, 0, 1), velocity,
                speed, gravity, drag, quadratic, "loaded-round", "test-cannon", 12, 3, lifetime, 5, 20, 3);
    }

    private static void interceptsActualMovingTrajectories() {
        for (boolean quadratic : new boolean[]{false, true}) {
            for (Vec3 carrier : new Vec3[]{Vec3.ZERO, new Vec3(.65, .08, -.3), new Vec3(-.4, -.1, .25)}) {
                var profile = shot(carrier, 12, -.05, quadratic ? .0008 : .004, quadratic, 600);
                for (Vec3 velocity : new Vec3[]{Vec3.ZERO, new Vec3(.9, .08, -.15), new Vec3(-1.2, -.05, .3),
                        new Vec3(0, 0, -8)}) {
                    Vec3 target = new Vec3(12, 15, 240);
                    var solution = CannonLeadSolver.solve(profile, target, velocity);
                    require(solution != null, "reachable moving target: quadratic=" + quadratic + " v=" + velocity);
                    // Independently advance both bodies through CBC movement segments.
                    Vec3 position = profile.muzzle();
                    Vec3 projectileVelocity = solution.launchDirection().scale(profile.muzzleSpeed()).add(carrier);
                    double remaining = solution.flightTicks();
                    while (remaining > 1e-8) {
                        var step = CannonProjectileFlight.step(position, projectileVelocity,
                                profile.gravity(), profile.drag(), profile.quadraticDrag());
                        position = position.lerp(step.position(), Math.min(1, remaining));
                        projectileVelocity = step.velocity();
                        remaining -= 1;
                    }
                    Vec3 movingTarget = target.add(velocity.scale(solution.flightTicks()));
                    require(position.distanceTo(movingTarget) < .026, "actual swept shot intercepts the moving target");
                    require(solution.flightTicks() > 0 && solution.flightTicks() <= profile.maxFlightTicks(), "inside projectile lifetime");
                }
            }
        }
    }

    private static void updatesWithAmmunitionAndBothVelocities() {
        Vec3 target = new Vec3(0, 3, 300), velocity = new Vec3(1, 0, 0);
        var slow = CannonLeadSolver.solve(shot(Vec3.ZERO, 8, -.05, .002, false, 600), target, velocity);
        var fast = CannonLeadSolver.solve(shot(Vec3.ZERO, 16, -.05, .002, false, 600), target, velocity);
        require(slow != null && fast != null && slow.flightTicks() > fast.flightTicks(), "ammunition changes recompute flight time");
        require(slow.launchDirection().x > fast.launchDirection().x, "slower ammunition needs more lead");
        var reversed = CannonLeadSolver.solve(shot(Vec3.ZERO, 8, -.05, .002, false, 600), target, velocity.scale(-1));
        require(reversed != null && reversed.launchDirection().x < 0, "direction reversal immediately reverses lead");
        var ownMotion = CannonLeadSolver.solve(shot(new Vec3(1, 0, 0), 8, -.05, .002, false, 600), target, velocity);
        require(ownMotion != null && ownMotion.launchDirection().x < slow.launchDirection().x, "inherited carrier velocity is included");
        var later = CannonLeadSolver.solve(shot(Vec3.ZERO, 8, -.05, .002, false, 600), target.add(velocity.scale(20)), velocity);
        require(later != null && later.launchDirection().distanceTo(slow.launchDirection()) > .02, "later samples move the cue");
    }

    private static void invalidAndUnreachableShots() {
        var profile = shot(Vec3.ZERO, 8, -.05, .002, false, 20);
        require(CannonLeadSolver.solve(profile, new Vec3(0, 3, 1000), Vec3.ZERO) == null, "out of lifetime hides cue");
        require(CannonLeadSolver.solve(profile, new Vec3(0, 3, 100), new Vec3(0, 0, 20)) == null, "faster receding target has no cue");
        require(CannonLeadSolver.solve(null, Vec3.ZERO, Vec3.ZERO) == null, "no ammunition profile has no cue");
        require(CannonLeadSolver.solve(profile, new Vec3(Double.NaN, 0, 0), Vec3.ZERO) == null, "nonfinite input rejected");
    }

    private static void lateralLeadLeavesTheFrameAndUnreachableFlightIsExplicit() {
        // Same speed, drag and life reported by the user's loaded flak rotary cannon.
        var profile = shot(Vec3.ZERO, 9, -.025, .01, false, 85);
        Vec3 target = profile.muzzle().add(0, 190, 350);
        var solution = CannonLeadSolver.solve(profile, target, new Vec3(1.65, 0, 0));
        require(solution != null, "33 m/s lateral target remains reachable at this distance");
        Vec3 forward = target.subtract(profile.muzzle()).normalize();
        double depth = solution.launchDirection().dot(forward);
        double focal = 720 / Math.tan(Math.toRadians(70) / 2);
        double pixels = Math.abs(solution.launchDirection().x * focal / depth);
        require(pixels > 36 + 20, "real ballistic cue leaves the 72 px frame and its connection line has room");
        require(CannonLeadSolver.position(profile, solution.launchDirection(), solution.flightTicks())
                        .distanceTo(target.add(1.65 * solution.flightTicks(), 0, 0)) < .026,
                "moving the cue out of the frame still produces an actual hit");
        // The saved aircraft travels about 172 m/s, almost the cannon's initial
        // 180 m/s. At ~400 m, drag/lifetime can make interception impossible.
        require(CannonLeadSolver.solve(profile, profile.muzzle().add(0, 190, 400),
                        new Vec3(8.6, 0, 0)) == null,
                "unreachable aircraft cannot be given a fake zero-lead firing cue");
        var unavailable = CannonLockLeadTracker.radarTrack(null, true, UUID.randomUUID(),
                target, Vec3.ZERO, () -> null);
        require(unavailable.leadStatus() == CannonLockLeadPayload.LeadStatus.UNREACHABLE,
                "an unavailable firing solution reaches the HUD as an explicit state");
    }

    private static void smoothTrackingPreservesManualLead() {
        var tracker = new RadarLockCameraTracker();
        UUID id = UUID.randomUUID();
        Vec3 target = new Vec3(100, 0, 100), origin = Vec3.ZERO, sight = new Vec3(0, 0, 1);
        double yaw = 0;
        var first = tracker.update(id, 0, target, origin, sight, .05);
        require(first.yaw() < 0 && first.yaw() > -44, "first tick pulls smoothly, not an instant 45-degree snap");
        yaw += first.yaw();
        for (int tick = 0; tick < 60; tick++) {
            var correction = tracker.update(id, 0, target, origin, direction(yaw), .05);
            yaw += correction.yaw();
        }
        require(Math.abs(yaw + 45) < .001, "acquisition reaches target bearing");
        yaw += 7;
        var still = tracker.update(id, 0, target, origin, direction(yaw), .05);
        require(Math.abs(still.yaw()) < .001, "manual lead is preserved after acquisition");
        var moving = tracker.update(id, 0, new Vec3(200, 0, 100), origin, direction(yaw), .05);
        require(Math.abs(moving.yaw() + 18.43495) < .002, "follows target bearing delta");
        var mode = tracker.update(id, 1, target, origin, sight, .05);
        require(mode.yaw() == 0 && mode.pitch() == 0, "scope/reticle mode switch only rebases tracking without reacquisition");
        tracker.reset();
        Vec3 offsetReticle = new Vec3(.2, .1, 1).normalize();
        var offsetAligned = tracker.update(id, 0, offsetReticle.scale(100), origin, offsetReticle, .05);
        require(Math.abs(offsetAligned.yaw()) < .001 && Math.abs(offsetAligned.pitch()) < .001,
                "already aligned offset reticle is not pulled toward screen centre");
        tracker.reset();
        var scoped = tracker.update(id, 1, offsetReticle.scale(100), origin, sight, .05);
        require(Math.abs(scoped.yaw()) > .1 && Math.abs(scoped.pitch()) > .1,
                "scope uses its centre sight instead of the unscoped reticle offset");
        tracker.reset();
        tracker.update(id, 0, target, origin, target.normalize(), .05);
        var ownMotion = tracker.update(id, 0, target, new Vec3(10, 0, 0), target.normalize(), .05);
        require(ownMotion.yaw() > 0, "moving observer changes the tracking bearing even for a stationary target");
        tracker.reset();
        var aroundBack = tracker.update(id, 0, direction(-179).scale(100), origin, direction(179), .05);
        require(aroundBack.yaw() > 0 && aroundBack.yaw() < 2, "yaw wrap follows the short path across 180 degrees");
    }

    private static Vec3 direction(double yaw) {
        return new Vec3(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
    }

    private static void acquisitionRunsOnlyOnNewLockEvents() {
        var tracker = new RadarLockCameraTracker();
        UUID firstLock = UUID.randomUUID(), switchedTarget = UUID.randomUUID(), relockedSameTarget = UUID.randomUUID();
        Vec3 target = new Vec3(100, 0, 100), origin = Vec3.ZERO, manualSight = direction(25);
        var acquire = tracker.update(firstLock, 0, target, origin, manualSight, .05);
        require(acquire.yaw() < 0 && Math.abs(acquire.yaw()) < 70, "first lock starts one smooth acquisition");
        // Switching views during the initial pull cancels it instead of restarting it.
        var scope = tracker.update(firstLock, 1, target, origin, manualSight, .05);
        require(scope.yaw() == 0 && scope.pitch() == 0, "opening scope cannot restart or continue an old acquisition pull");
        var third = tracker.update(firstLock, 2, target, new Vec3(0, 4, -7), direction(-15), .05);
        require(third.yaw() == 0 && third.pitch() == 0, "third-person camera/height change rebases without a lock-centering jump");
        tracker.suspend();
        var mount = tracker.update(firstLock, 2, target, new Vec3(0, 4, -7), direction(30), .05);
        require(mount.yaw() == 0 && mount.pitch() == 0, "leaving/re-entering seat preserves the already consumed acquisition");
        Vec3 moved = target.add(10, 0, 0);
        var follow = tracker.update(firstLock, 2, moved, new Vec3(0, 4, -7), direction(30), .05);
        require(Math.abs(follow.yaw()) > .1, "target movement continues to be tracked after remount without deleting manual lead");
        var next = tracker.update(switchedTarget, 2, target.scale(-1), origin, manualSight, .05);
        require(Math.abs(next.yaw()) > 1 && Math.abs(next.yaw()) < 90, "switching locked target creates one fresh smooth acquisition");
        tracker.suspend();
        var returnToVehicle = tracker.update(firstLock, 0, target, origin, manualSight, .05);
        require(returnToVehicle.yaw() == 0 && returnToVehicle.pitch() == 0, "returning to an earlier vehicle/lock cannot replay its acquisition");
        var relock = tracker.update(relockedSameTarget, 0, target, origin, manualSight, .05);
        require(Math.abs(relock.yaw()) > 1, "explicitly unlocking/relocking the same physical target is a new lock event");

        var optical = new LockCameraAcquisitionMemory();
        require(!optical.begin(null) && optical.begin(firstLock) && !optical.begin(firstLock),
                "optical acquisition is consumed once even if session activation is repeated");
        require(optical.begin(switchedTarget) && !optical.begin(firstLock), "optical target switches retain earlier consumed events");
        require(optical.begin(relockedSameTarget), "optical relock of same target uses fresh event identity");
        optical.clear();
        require(optical.begin(firstLock), "a new game connection clears acquisition history");
    }

    private static void cannonLeadIsIndependentOfMissileCameraPolicy() {
        UUID id = UUID.randomUUID();
        Vec3 target = new Vec3(10, 20, 30), velocity = new Vec3(1, 0, -1);
        Vec3 aim = new Vec3(12, 25, 31);
        for (boolean cannon : new boolean[]{false, true}) {
            var ordinary = CannonLockLeadTracker.radarTrack(FireControlWeaponInputs.GroundWeapon.AIR_DEFENSE,
                    cannon, id, target, velocity, () -> {
                        require(cannon, "without a cannon, AA must not request ballistic lead"); return aim;
                    });
            require(ordinary != null && ordinary.id().equals(id) && ordinary.target().equals(target)
                    && ordinary.velocity().equals(velocity), "ordinary AA supplies a live camera target with or without a cannon");
            require(ordinary.cameraTracking(), "ordinary AA continues to enable camera tracking");
            require(cannon ? aim.equals(ordinary.aimPoint()) : ordinary.aimPoint() == null,
                    "ordinary AA has a cannon cue exactly when an assembled cannon is bound");
            for (var other : new FireControlWeaponInputs.GroundWeapon[]{
                    FireControlWeaponInputs.GroundWeapon.HIGH_SPEED_AIR_DEFENSE,
                    FireControlWeaponInputs.GroundWeapon.ANTI_TANK}) {
                var otherTrack = CannonLockLeadTracker.radarTrack(other, cannon, id, target, velocity,
                        () -> { require(cannon, "no unassembled cannon lookup"); return aim; });
                require(cannon ? otherTrack != null && !otherTrack.cameraTracking()
                                && aim.equals(otherTrack.aimPoint()) : otherTrack == null,
                        "high-speed AA/anti-tank keep cannon cues but cannot pull or track the camera");
            }
        }
        require(CannonLockLeadTracker.radarTrack(null, false, id, target, velocity,
                () -> { throw new AssertionError("Unassembled cannon cannot solve lead"); }) == null,
                "unselected missiles and no cannon cannot enable camera tracking");
        var cannon = CannonLockLeadTracker.radarTrack(null, true, id, target, velocity, () -> aim);
        require(cannon != null && aim.equals(cannon.aimPoint()), "original cannon-only camera and lead remain available");
        var cameraOnly = new CannonLockLeadPayload(null, new CannonLockLeadPayload.Track(id, target, velocity, null));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            CannonLockLeadPayload.STREAM_CODEC.encode(buffer, cameraOnly);
            require(cameraOnly.equals(CannonLockLeadPayload.STREAM_CODEC.decode(buffer)),
                    "ordinary AA camera-only target reaches the client without a lead point");
        } finally { buffer.release(); }
    }

    private static void payloadRoundTrips() {
        var radar = new CannonLockLeadPayload.Track(UUID.randomUUID(), new Vec3(1, 2, 3),
                new Vec3(.1, -.2, .3), new Vec3(15, 5, 8), UUID.randomUUID(),
                CannonLockLeadPayload.LeadStatus.READY, false);
        var optical = new CannonLockLeadPayload.Track(null, Vec3.ZERO, Vec3.ZERO, null, UUID.randomUUID(),
                CannonLockLeadPayload.LeadStatus.UNREACHABLE, true);
        for (var payload : new CannonLockLeadPayload[]{CannonLockLeadPayload.INACTIVE,
                new CannonLockLeadPayload(optical, radar), new CannonLockLeadPayload(null, radar)}) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                CannonLockLeadPayload.STREAM_CODEC.encode(buffer, payload);
                require(payload.equals(CannonLockLeadPayload.STREAM_CODEC.decode(buffer)), "lead and tracking codec round trip");
                require(buffer.readableBytes() == 0, "codec reads every field");
            } finally { buffer.release(); }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
