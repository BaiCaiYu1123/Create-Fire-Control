package com.hooya.stabilizedturret.client;

import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public final class LockTrackingSmoothChecks {
    public static void main(String[] args) {
        constantMotionDoesNotStepAtPacketFrequency();
        packetCorrectionsPreservePositionAndVelocity();
        boundedPredictionAndStopping();
        frameRateIndependentAcquisitionAndManualLead();
        sameEventRemountDoesNotAcquireAgain();
        System.out.println("PASS: continuous lock tracking at 30/60/144/240 FPS, 10/20 Hz packets, packet jitter, stops/reversals, bounded prediction and acquisition-once");
    }

    private static void constantMotionDoesNotStepAtPacketFrequency() {
        for (int fps : new int[]{30, 60, 144, 240}) for (int networkHz : new int[]{10, 20}) {
            var smoother = new LockTargetSmoother();
            UUID event = UUID.randomUUID();
            Vec3 start = new Vec3(0, 5, 100), velocity = new Vec3(.6, .1, -.2);
            smoother.accept(event, start, velocity, 0);
            double nextPacket = 1.0 / networkHz;
            Vec3 previous = start;
            for (int frame = 1; frame <= fps * 2; frame++) {
                double time = frame / (double) fps;
                while (nextPacket <= time + 1e-10) {
                    Vec3 packetPoint = start.add(velocity.scale(20 * nextPacket));
                    Vec3 before = smoother.sample(nextPacket);
                    smoother.accept(event, packetPoint, velocity, nextPacket);
                    require(before.distanceTo(smoother.sample(nextPacket)) < 1e-9, "packet receipt has no position snap");
                    nextPacket += 1.0 / networkHz;
                }
                Vec3 displayed = smoother.sample(time);
                require(displayed.distanceTo(start.add(velocity.scale(20 * time))) < 1e-8,
                        "moving target is evaluated every frame, not held until another network packet");
                require(Math.abs(displayed.distanceTo(previous) - velocity.length() * 20 / fps) < 1e-8,
                        "all frames have equal motion, including packet boundaries");
                previous = displayed;
            }
        }
    }

    private static void packetCorrectionsPreservePositionAndVelocity() {
        var smoother = new LockTargetSmoother();
        UUID event = UUID.randomUUID();
        smoother.accept(event, new Vec3(0, 0, 100), new Vec3(.6, 0, 0), 0);
        double time = 0;
        for (int packet = 1; packet < 40; packet++) {
            time += packet % 2 == 0 ? .085 : .035;
            Vec3 motion = new Vec3(packet < 20 ? .6 : -.6, 0, 0);
            Vec3 measured = smoother.sample(time).add(packet % 2 == 0 ? .3 : -.2, .03, 0);
            Vec3 before = smoother.sample(time);
            double epsilon = 1e-6;
            Vec3 beforeVelocity = before.subtract(smoother.sample(time - epsilon)).scale(1 / epsilon);
            smoother.accept(event, measured, motion, time);
            require(smoother.sample(time).distanceTo(before) < 1e-9, "jitter/reversal packet cannot move the camera instantly");
            Vec3 afterVelocity = smoother.sample(time + epsilon).subtract(smoother.sample(time)).scale(1 / epsilon);
            require(beforeVelocity.distanceTo(afterVelocity) < .02, "packet boundary also keeps angular/position velocity continuous");
        }
        // A new lock gets its own starting point instead of blending two unrelated targets.
        Vec3 switched = new Vec3(500, 40, -500);
        smoother.accept(UUID.randomUUID(), switched, Vec3.ZERO, time);
        require(smoother.sample(time).equals(switched), "target switch resets only the motion history for that lock event");
    }

    private static void boundedPredictionAndStopping() {
        var smoother = new LockTargetSmoother();
        UUID event = UUID.randomUUID();
        Vec3 start = new Vec3(0, 0, 100), velocity = new Vec3(1, 0, 0);
        smoother.accept(event, start, velocity, 0);
        require(smoother.sample(5).distanceTo(start) <= 3.00001, "network loss cannot predict indefinitely");
        require(smoother.sample(5).distanceTo(smoother.sample(6)) < 1e-9, "prediction eventually freezes");
        smoother.clear();
        smoother.accept(event, start, velocity, 0);
        Vec3 stop = start.add(1, 0, 0);
        smoother.accept(event, stop, Vec3.ZERO, .05);
        require(smoother.sample(1).distanceTo(stop) < .001, "stationary target settles without endless drift");
        require(smoother.sample(.5).equals(smoother.sample(.5)), "paused render clock cannot advance target motion");
    }

    private static void frameRateIndependentAcquisitionAndManualLead() {
        for (int fps : new int[]{30, 60, 144, 240}) {
            var tracker = new RadarLockCameraTracker();
            var smoother = new LockTargetSmoother();
            UUID event = UUID.randomUUID();
            Vec3 target = new Vec3(100, 0, 100);
            tracker.update(event, 0, target, Vec3.ZERO, direction(0), 0);
            double yaw = 0;
            for (int frame = 1; frame <= fps; frame++)
                yaw += tracker.update(event, 0, target, Vec3.ZERO, direction(yaw), 1.0 / fps).yaw();
            require(Math.abs(yaw - -45 * (1 - Math.exp(-6))) < .001, "same acquisition response at every frame rate");
            tracker.suspend();
            yaw = -38; // Seven degrees of manually chosen lead.
            Vec3 motion = new Vec3(.5, 0, 0);
            smoother.accept(event, target, motion, 0);
            tracker.update(event, 0, target, Vec3.ZERO, direction(yaw), 0);
            double packetTime = .1;
            for (int frame = 1; frame <= fps; frame++) {
                double time = frame / (double) fps;
                while (packetTime <= time + 1e-10) {
                    smoother.accept(event, target.add(motion.scale(20 * packetTime)), motion, packetTime);
                    packetTime += .1;
                }
                Vec3 origin = new Vec3(time * 2, 0, 0);
                yaw += tracker.update(event, 0, smoother.sample(time), origin, direction(yaw), 1.0 / fps).yaw();
            }
            Vec3 bearing = smoother.sample(1).subtract(new Vec3(2, 0, 0));
            double actualBearing = Math.toDegrees(Math.atan2(-bearing.x, bearing.z));
            require(Math.abs(yaw - actualBearing - 7) < .001, "per-frame tracking retains manual lead while both bodies move");
        }
    }

    private static void sameEventRemountDoesNotAcquireAgain() {
        var tracker = new RadarLockCameraTracker();
        UUID event = UUID.randomUUID();
        Vec3 target = new Vec3(100, 0, 100);
        tracker.update(event, 0, target, Vec3.ZERO, direction(0), 1.0 / 144);
        tracker.suspend();
        var afterMount = tracker.update(event, 1, target, new Vec3(0, 4, -6), direction(60), 1.0 / 144);
        require(afterMount.yaw() == 0 && afterMount.pitch() == 0, "new frame loop cannot re-centre old lock on mount/scope change");
    }

    private static Vec3 direction(double yaw) {
        return new Vec3(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
