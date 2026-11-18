package com.hooya.stabilizedturret.client;

import net.minecraft.world.phys.Vec3;
import java.util.*;

public final class WeaponViewMotionChecks {
    private static final Vec3 SPEED = new Vec3(34.03, 3, 7);
    private record Delivery(double time, int tick) {}

    public static void main(String[] args) {
        for (int fps : new int[]{30, 60, 144, 240}) {
            jitter(fps, 0);
            jitter(fps, 4); // 200 ms baseline latency plus jitter, packet gaps and reordered delivery.
        }
        WeaponViewMotion motion = new WeaponViewMotion();
        require(motion.accept(50, SPEED.scale(50), SPEED, 10), "Initial snapshot rejected");
        Vec3 first = motion.sample(10.9);
        require(motion.sample(10.2).equals(first), "Client partial-tick correction rewound the pose");
        require(!motion.accept(49, new Vec3(-10000, 0, 0), Vec3.ZERO, 11), "Older server snapshot accepted");
        require(!motion.accept(50, Vec3.ZERO, Vec3.ZERO, 11), "Duplicate snapshot accepted");
        Vec3 frozen = motion.sample(30);
        require(motion.sample(40).distanceTo(frozen) < 1e-8, "Prediction did not stop during a long packet gap");
        motion.accept(51, SPEED.scale(51), SPEED, 40);
        require(motion.sample(40).distanceTo(frozen) < 1e-8, "Late snapshot snapped to a past position");
        require(motion.sample(40.2).subtract(frozen).dot(SPEED) >= -1e-7, "Late correction pulled the missile backward");
        Vec3 turnStart = motion.sample(40.3);
        motion.accept(52, turnStart, SPEED.scale(-1), 40.3);
        require(motion.sample(40.5).subtract(turnStart).dot(SPEED.scale(-1)) > 0,
                "Correction smoothing prevented an actual missile turn");
        motion.clear();
        require(motion.accept(1, new Vec3(1000, 10, 10), Vec3.ZERO, 0), "New weapon inherited the old sequence");
        require(motion.sample(0).equals(new Vec3(1000, 10, 10)), "New session retained the old correction");
        System.out.println("PASS: 30/60/144/240 FPS, latency, jitter, packet loss/reordering, monotonic time, real turns and independent clients");
    }

    private static void jitter(int fps, int baseDelay) {
        var events = new ArrayList<Delivery>();
        for (int tick = 0; tick < 100; tick++) {
            if (tick % 17 == 9 || tick % 17 == 10) continue;
            int jitter = switch (tick % 9) { case 2 -> 3; case 4 -> 7; case 6 -> 1; default -> 0; };
            events.add(new Delivery(tick + baseDelay + jitter, tick));
        }
        events.sort(Comparator.comparingDouble(Delivery::time));
        var client = new WeaponViewMotion();
        var peer = new WeaponViewMotion();
        int index = 0, peerTick = -1;
        double step = 20.0 / fps;
        Vec3 previous = null, peerPrevious = null;
        for (double now = 0; now < 115; now += step) {
            while (index < events.size() && events.get(index).time <= now) {
                var event = events.get(index++);
                client.accept(event.tick, SPEED.scale(event.tick), SPEED, now);
            }
            int fresh = (int) Math.floor(now);
            if (fresh != peerTick) { peer.accept(fresh, SPEED.scale(fresh), SPEED, now); peerTick = fresh; }
            Vec3 point = client.sample(now), peerPoint = peer.sample(now);
            if (previous != null && index > 0) {
                Vec3 travel = point.subtract(previous);
                require(travel.dot(SPEED) >= -1e-6, "Backward flash at " + fps + " FPS, tick " + now);
                require(travel.length() <= SPEED.length() * step * 1.51 + 1e-6,
                        "Instant position correction at " + fps + " FPS, tick " + now);
            }
            if (peerPrevious != null) require(peerPoint.subtract(peerPrevious).dot(SPEED) >= -1e-6,
                    "One client's jitter disturbed another client's motion");
            if (index > 0) previous = point;
            peerPrevious = peerPoint;
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
