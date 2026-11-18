package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.content.bearing.ArticulatedServoPredictor;
import com.hooya.stabilizedturret.content.bearing.FiniteAimTarget;
import com.hooya.stabilizedturret.content.bearing.LegacyFiniteAimSolver;
import com.hooya.stabilizedturret.network.RemoteInputStatePayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class LaserRangeCompensationChecks {
    private static final Vec3 FORWARD = new Vec3(0, 0, 1);

    public static void main(String[] args) {
        measuredDropMatchesActualTrajectory();
        headAimKeepsSavedElevationAtEveryDepth();
        movementSwitchDoesNotChangeSavedDrop();
        movementPreservesActualImpactWithoutRefreshingRange();
        legacyInverseKeepsLongMuzzleParallax();
        virtualScopeKeepsParallelSightAfterRanging();
        virtualScopeRetainsMovementCompensation();
        skyReticleAndZeroReset();
        rangeInputSnapshotRoundTrips();
        transientRangeSamplesDoNotCrash();
        distantTerrainMeasurement();
        System.out.println("PASS: laser range freezes drop elevation, movement switch is independent, sky resets to zero, and reticle removes saved elevation");
    }

    private static void measuredDropMatchesActualTrajectory() {
        for (boolean quadratic : new boolean[]{false, true}) {
            var ammunition = profile(Vec3.ZERO, quadratic ? .0003 : .004, quadratic);
            Vec3 target = new Vec3(0, 5, 160);
            var sample = LaserRangeCompensation.measure(ammunition, target);
            require(sample != null && sample.elevationRadians() > 0, "loaded ammunition yields positive drop elevation");
            Vec3 launch = LaserRangeCompensation.raise(target, sample.elevationRadians());
            Vec3 hit = planeHit(ammunition, launch, target.z);
            require(hit != null && hit.distanceTo(target) < .15, "saved angle reaches the measured point with actual gravity and drag");
            var moving = LaserRangeCompensation.measure(CannonBallisticsCompat.withCarrierVelocity(
                    ammunition, new Vec3(.5, -.1, .2)), target);
            require(sample.equals(moving), "measurement does not bake transient vehicle speed into saved drop");
            var fast = profile(Vec3.ZERO, 0, false);
            var slow = new CannonBallisticsCompat.ShotProfile(Vec3.ZERO, FORWARD, Vec3.ZERO,
                    5, -.05, 0, false, "slow", "test", 8, 1, 300, 1, 1, 0);
            require(LaserRangeCompensation.measure(slow, target).elevationRadians()
                    > LaserRangeCompensation.measure(fast, target).elevationRadians(),
                    "each cannon uses its own ammunition speed");
        }
    }

    private static void headAimKeepsSavedElevationAtEveryDepth() {
        var ammunition = profile(Vec3.ZERO, 0, false);
        var sample = LaserRangeCompensation.measure(ammunition, new Vec3(0, 0, 160));
        var motionProfile = ammunition;
        for (double range : new double[]{20, 80, 160, 350}) {
            for (double yaw : new double[]{-.8, 0, .9}) {
                Vec3 target = new Vec3(Math.sin(yaw), .15, Math.cos(yaw)).normalize().scale(range);
                var finite = new FiniteAimTarget(new UUID(0, 1), Vec3.ZERO, FORWARD, target, motionProfile)
                        .withCarrierVelocityCompensation(false).withElevationCorrection(sample.elevationRadians());
                Vector3d direction = finite.predictedDirection(body(new Vector3d()), new Vector3d(), new Vector3d());
                Vec3 expected = LaserRangeCompensation.raise(target, sample.elevationRadians());
                require(direction != null && direction.distance(vector(expected)) < 1e-10,
                        "changing sight heading and target depth retains exactly the last sampled angle");
                var unranged = finite.withElevationCorrection(0);
                require(unranged.predictedDirection(body(new Vector3d()), new Vector3d(), new Vector3d())
                        .distance(vector(target.normalize())) < 1e-10, "no automatic drop before the first range pulse");
            }
        }
        var next = LaserRangeCompensation.measure(ammunition, new Vec3(0, 0, 80));
        require(next.elevationRadians() < sample.elevationRadians(), "a second measurement replaces the previous angle");
        require(ammunition.gravity() == -.05, "aiming profile never changes real projectile physics");
    }

    private static void movementSwitchDoesNotChangeSavedDrop() {
        Vec3 target = new Vec3(0, 0, 160);
        var ammunition = profile(Vec3.ZERO, 0, false);
        double saved = LaserRangeCompensation.measure(ammunition, target).elevationRadians();
        var finite = new FiniteAimTarget(new UUID(0, 1), Vec3.ZERO, FORWARD, target,
                ammunition).withElevationCorrection(saved);
        for (double speed : new double[]{-8, 0, 8}) {
            var frame = body(new Vector3d(speed, 0, 0));
            Vector3d on = finite.predictedDirection(frame, new Vector3d(), new Vector3d());
            Vector3d off = finite.withCarrierVelocityCompensation(false)
                    .predictedDirection(frame, new Vector3d(), new Vector3d());
            require(on != null && off != null && off.distance(vector(LaserRangeCompensation.raise(FORWARD, saved))) < 1e-10,
                    "turning motion compensation off retains saved elevation at every vehicle speed");
            require(Math.abs(off.y - Math.sin(saved)) < 1e-10, "switch cannot disable drop elevation");
            if (speed != 0) require(on.x * speed < 0, "enabled motion compensation opposes vehicle drift");
        }
    }

    private static void legacyInverseKeepsLongMuzzleParallax() {
        Vec3 muzzle = new Vec3(0, 0, 8), target = new Vec3(0, 1, 20);
        double saved = Math.toRadians(5);
        Vec3 axis = new Vec3(1, 0, 0);
        double angle = LegacyFiniteAimSolver.correction(List.of(new LegacyFiniteAimSolver.Ray(
                muzzle, FORWARD, target, null, Vec3.ZERO, null, saved)), Vec3.ZERO, axis,
                new Vec3(0, 1, 0), false);
        Quaterniond rotation = new Quaterniond().rotationAxis(angle, axis.x, axis.y, axis.z);
        Vec3 movedMuzzle = vec(rotation.transform(vector(muzzle))), bore = vec(rotation.transform(vector(FORWARD)));
        Vec3 desired = LaserRangeCompensation.raise(target.subtract(movedMuzzle), saved);
        require(bore.distanceTo(desired) < 1e-7, "finite inverse rotates long muzzle and bore together while preserving saved lift: angle="
                + angle + ", bore=" + bore + ", desired=" + desired + ", error=" + bore.distanceTo(desired));
    }

    private static void virtualScopeKeepsParallelSightAfterRanging() {
        double saved = Math.toRadians(7.244054267964293), worstOld = 0;
        Vec3 axis = new Vec3(1, 0, 0), up = new Vec3(0, 1, 0);
        for (double length : new double[]{1, 6.5, 20}) {
            for (double range : new double[]{24, 50, 160, 490, 1300}) {
                Vec3 muzzle = FORWARD.scale(length), target = FORWARD.scale(range);
                double finiteAngle = LegacyFiniteAimSolver.correction(List.of(new LegacyFiniteAimSolver.Ray(
                        muzzle, FORWARD, target, null, Vec3.ZERO, null, saved)), Vec3.ZERO, axis, up, false);
                Vec3 finiteBore = vec(new Quaterniond().rotationAxis(finiteAngle, 1, 0, 0).transform(vector(FORWARD)));
                worstOld = Math.max(worstOld, LaserRangeCompensation.raise(finiteBore, -saved).distanceTo(FORWARD));

                double parallelAngle = LegacyFiniteAimSolver.correction(List.of(new LegacyFiniteAimSolver.Ray(
                        muzzle, FORWARD, target, null, Vec3.ZERO, null, saved, true, FORWARD)),
                        Vec3.ZERO, axis, up, false);
                Vec3 bore = vec(new Quaterniond().rotationAxis(parallelAngle, 1, 0, 0).transform(vector(FORWARD)));
                Vec3 sight = LaserRangeCompensation.raise(bore, -saved);
                require(sight.distanceTo(FORWARD) < 1e-8,
                        "virtual scope must keep the sky direction when terrain depth or barrel length changes: length="
                                + length + ", range=" + range + ", angle=" + parallelAngle + ", bore=" + bore
                                + ", forward=" + FORWARD + ", sight=" + sight);
                for (double trackingError : new double[]{-.03, 0, .03}) {
                    Vec3 measured = vec(new Quaterniond().rotationAxis(trackingError, 1, 0, 0).transform(vector(bore)));
                    Vec3 displayed = CannonSightDirection.direction(measured, bore, sight);
                    require(Math.abs(displayed.distanceTo(sight) - measured.distanceTo(bore)) < 1e-10,
                            "virtual sight correction cannot hide real barrel lag");
                }
                var finite = new FiniteAimTarget(new UUID(0, 1), muzzle, FORWARD, target, null)
                        .withParallelSight(FORWARD).withMovingRay(null).withCarrierVelocityCompensation(false)
                        .withElevationCorrection(saved);
                require(finite.predictedDirection(body(new Vector3d()), new Vector3d(), new Vector3d())
                        .distance(vector(LaserRangeCompensation.raise(FORWARD, saved))) < 1e-10,
                        "physical predictor must use the same parallel sight as the legacy inverse");
                require(finite.currentRaisedSightDirection(null).distanceTo(
                        LaserRangeCompensation.raise(FORWARD, saved)) < 1e-10,
                        "motion correction baseline must exclude finite muzzle convergence");
            }
        }
        require(worstOld > .05, "old finite convergence reproduces a visible ranged virtual-scope drift");
        // Yaw must not reintroduce lateral muzzle parallax for grouped guns.
        Vec3 sight = new Vec3(.3, 0, 1).normalize();
        for (double side : new double[]{-4, 4}) {
            double angle = LegacyFiniteAimSolver.correction(List.of(new LegacyFiniteAimSolver.Ray(
                    new Vec3(side, 0, 6.5), FORWARD, sight.scale(30), null, Vec3.ZERO, null,
                    saved, true, sight)), Vec3.ZERO, up, up, true);
            Vec3 bore = vec(new Quaterniond().rotationAxis(angle, 0, 1, 0).transform(vector(FORWARD)));
            require(bore.distanceTo(sight) < 1e-8, "virtual yaw ignores each gun's lateral muzzle offset");
        }
        System.out.printf("PASS: ranged virtual-scope legacy/predictive aim stays parallel; old maximum drift %.6f deg%n",
                Math.toDegrees(2 * Math.asin(worstOld / 2)));
    }

    private static void virtualScopeRetainsMovementCompensation() {
        Vec3 muzzle = new Vec3(0, 1.5, 6.5), target = new Vec3(0, 0, 160);
        var ammunition = profile(muzzle, .001, false);
        double saved = Math.toRadians(7.244054267964293);
        Vec3 stationary = LaserRangeCompensation.raise(FORWARD, saved);
        double depth = target.distanceTo(muzzle), planeZ = muzzle.z + depth;
        Vec3 expected = planeHit(ammunition, stationary, planeZ);
        require(expected != null, "parallel stationary shot reaches its depth plane");
        for (Vec3 velocity : new Vec3[]{new Vec3(-.4, .1, .2), new Vec3(.4, -.1, -.2)}) {
            var finite = new FiniteAimTarget(new UUID(0, 1), muzzle, FORWARD, target, ammunition)
                    .withElevationCorrection(saved).withParallelSight(FORWARD);
            var predicted = finite.predictedDirection(body(vector(velocity).mul(20)), new Vector3d(), new Vector3d());
            require(predicted != null, "parallel virtual sight has a motion-compensated solution");
            Vec3 actual = planeHit(CannonBallisticsCompat.withCarrierVelocity(ammunition, velocity), vec(predicted), planeZ);
            require(actual != null && actual.distanceTo(expected) < .25,
                    "parallel virtual aim retains real carrier-velocity compensation");
            require(finite.withCarrierVelocityCompensation(false).predictedDirection(body(vector(velocity).mul(20)),
                    new Vector3d(), new Vector3d()).distance(vector(stationary)) < 1e-10,
                    "disabling vehicle compensation leaves the sampled virtual-scope lift intact");
        }
    }

    private static void movementPreservesActualImpactWithoutRefreshingRange() {
        var ammunition = profile(Vec3.ZERO, .001, false);
        double measured = LaserRangeCompensation.measure(ammunition, new Vec3(0, 0, 160)).elevationRadians();
        for (double saved : new double[]{0, measured}) {
            for (double range : new double[]{160, 600}) {
                Vec3 target = FORWARD.scale(range);
                Vec3 heldBore = LaserRangeCompensation.raise(FORWARD, saved);
                Vec3 stationaryImpact = planeHit(ammunition, heldBore, range);
                require(stationaryImpact != null, "stationary held-angle trajectory crosses the target plane");
                for (Vec3 velocity : new Vec3[]{new Vec3(-.5, .1, .2), new Vec3(.5, -.1, -.2)}) {
                    Vec3 launch = LaserRangeCompensation.launchDirection(ammunition, Vec3.ZERO, FORWARD,
                            velocity, target, saved);
                    require(launch != null, "vehicle motion has a reachable solution");
                    Vec3 movingImpact = planeHit(CannonBallisticsCompat.withCarrierVelocity(ammunition, velocity), launch, range);
                    require(movingImpact != null && movingImpact.distanceTo(stationaryImpact) < .25,
                            "motion compensation preserves the stationary actual impact even with stale/zero ranging");
                }
                if (range == 600) require(stationaryImpact.y < -50,
                        "stale range remains stale; movement compensation cannot refresh drop by itself");
            }
        }
    }

    private static void skyReticleAndZeroReset() {
        double saved = Math.toRadians(6);
        for (double yaw : new double[]{-2, -.5, .7, 2}) {
            Vec3 sight = new Vec3(Math.sin(yaw), .1, Math.cos(yaw)).normalize();
            Vec3 bore = LaserRangeCompensation.raise(sight, saved);
            Vec3 marker = LaserRangeCompensation.raise(bore, -saved);
            require(marker.distanceTo(sight) < 1e-12, "sky keeps the saved angle but the marker stays on the original sight");
            require(CannonSightDirection.direction(bore, bore, marker).distanceTo(sight) < 1e-12,
                    "render-time reticle removes saved elevation from physical bore");
            Vec3 zero = LaserRangeCompensation.raise(sight, LaserRangeCompensation.ZERO.elevationRadians());
            require(zero.distanceTo(sight) < 1e-12 && LaserRangeCompensation.ZERO.distance() == 0,
                    "explicit infinite-range reset aligns barrel and sight");
        }
    }

    private static void rangeInputSnapshotRoundTrips() {
        for (double distance : new double[]{160, 20000, 48000, 65536}) {
            for (boolean hit : new boolean[]{false, true}) {
                var sample = new RemoteInputStatePayload.RangeSample(new Vec3(1, 2, 3), FORWARD,
                        new Vec3(1, 2, hit ? distance + 3 : 4099), hit);
                var input = new RemoteInputStatePayload(true, new BlockPos(4, 5, 6), Set.of("a"), sample);
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                try {
                    RemoteInputStatePayload.STREAM_CODEC.encode(buffer, input);
                    require(input.equals(RemoteInputStatePayload.STREAM_CODEC.decode(buffer)) && !buffer.isReadable(),
                            "input carries the press-time ray and finite/sky distinction unchanged");
                } finally { buffer.release(); }
            }
        }
        for (Vec3 invalid : new Vec3[]{new Vec3(1, 2, 65540), new Vec3(1, 2, -10),
                new Vec3(1, 2, Double.NaN)}) {
            boolean rejected = false;
            try { new RemoteInputStatePayload.RangeSample(new Vec3(1, 2, 3), FORWARD, invalid, true); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "range snapshots reject unbounded, behind-camera and non-finite targets");
        }
    }

    private static void distantTerrainMeasurement() {
        // Synthetic ammunition can reach the far terrain; do not extend real ammunition lifetime.
        var longRange = new CannonBallisticsCompat.ShotProfile(Vec3.ZERO, FORWARD, Vec3.ZERO,
                300, -.05, 0, false, "long-range-test", "test", 8, 1, 300, 1, 1, 0);
        for (double range : new double[]{20000, 48000}) {
            Vec3 target = FORWARD.scale(range);
            var sample = LaserRangeCompensation.measure(longRange, target);
            require(sample != null && sample.distance() == range && sample.elevationRadians() > 0,
                    "LOD-distance samples reach the same fixed-elevation calculator as nearby terrain");
            Vec3 impact = farPlaneHit(longRange, LaserRangeCompensation.raise(FORWARD, sample.elevationRadians()), range);
            require(impact != null && impact.distanceTo(target) < 1,
                    "stored elevation follows actual swept projectile physics at LOD distance: range="
                            + range + ", sample=" + sample + ", impact=" + impact);
        }
        require(LaserRangeCompensation.measure(profile(Vec3.ZERO, 0, false), FORWARD.scale(48000)) == null,
                "reading distant LOD terrain cannot give short-lived ammunition unlimited reach");
    }

    private static void transientRangeSamplesDoNotCrash() {
        Vec3 origin = new Vec3(-188.9522747150417, 138.62138574295125, 49.09857847414027);
        Vec3 direction = new Vec3(0.5786269969596592, 0.7884643764395098, 0.20860183478412023);
        for (Vec3 invalid : new Vec3[]{origin, origin.subtract(direction),
                new Vec3(Double.NaN, 0, 0), origin.add(direction.scale(70000)), null}) {
            require(RemoteInputStatePayload.RangeSample.tryCreate(origin, direction, invalid, true) == null,
                    "transient zero/behind/out-of-range/invalid local hits skip the sample without throwing");
        }
        require(RemoteInputStatePayload.RangeSample.tryCreate(origin, Vec3.ZERO,
                origin.add(direction), true) == null, "invalid local camera direction skips the sample");
        for (boolean hit : new boolean[]{false, true}) {
            Vec3 target = origin.add(direction.scale(hit ? 0.01 : 4096));
            var sample = RemoteInputStatePayload.RangeSample.tryCreate(origin, direction, target, hit);
            require(sample != null && sample.hit() == hit && sample.target().equals(target),
                    "valid near hits and sky misses keep their measurement/reset semantics");
        }
        // Wire decoding still rejects the zero-distance point. The tolerant
        // factory is only for local capture, never for received packets.
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeBoolean(true);
            buffer.writeBlockPos(BlockPos.ZERO);
            buffer.writeVarInt(0);
            buffer.writeBoolean(true);
            for (Vec3 vector : new Vec3[]{origin, direction, origin}) {
                buffer.writeDouble(vector.x); buffer.writeDouble(vector.y); buffer.writeDouble(vector.z);
            }
            buffer.writeBoolean(true);
            boolean rejected = false;
            try { RemoteInputStatePayload.STREAM_CODEC.decode(buffer); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "invalid received range samples remain rejected");
        } finally { buffer.release(); }
    }

    private static Vec3 farPlaneHit(CannonBallisticsCompat.ShotProfile p, Vec3 launch, double z) {
        // The production impact-preview trace has a 4096-block safety cap.
        // Validate the far solve using its actual force step, without changing that cap.
        Vec3 position = p.muzzle(), velocity = launch.scale(p.muzzleSpeed());
        for (int tick = 0; tick < p.maxFlightTicks(); tick++) {
            var step = CannonProjectileFlight.step(position, velocity, p.gravity(), p.drag(), p.quadraticDrag());
            if (position.z <= z && step.position().z >= z)
                return position.lerp(step.position(), (z - position.z) / (step.position().z - position.z));
            position = step.position();
            velocity = step.velocity();
        }
        return null;
    }

    private static CannonBallisticsCompat.ShotProfile profile(Vec3 muzzle, double drag, boolean quadratic) {
        return new CannonBallisticsCompat.ShotProfile(muzzle, FORWARD, Vec3.ZERO,
                8, -.05, drag, quadratic, "test", "test", 8, 1, 300, 1, 1, 0);
    }
    private static ArticulatedServoPredictor.BodyState body(Vector3d velocity) {
        return new ArticulatedServoPredictor.BodyState(100, new Matrix3d().scaling(1000), new Vector3d(),
                new Quaterniond(), velocity, new Vector3d());
    }
    private static Vec3 planeHit(CannonBallisticsCompat.ShotProfile p, Vec3 launch, double z) {
        return CannonProjectileFlight.trace(p.muzzle(), launch.scale(p.muzzleSpeed()).add(p.carrierVelocity()), p.gravity(), p.drag(),
                p.quadraticDrag(), 300, -10000, (from, to) -> {
                    double fraction = (z - from.z) / (to.z - from.z);
                    return fraction >= 0 && fraction <= 1 ? from.lerp(to, fraction) : null;
                });
    }
    private static Vector3d vector(Vec3 v) { return new Vector3d(v.x, v.y, v.z); }
    private static Vec3 vec(Vector3d v) { return new Vec3(v.x, v.y, v.z); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
