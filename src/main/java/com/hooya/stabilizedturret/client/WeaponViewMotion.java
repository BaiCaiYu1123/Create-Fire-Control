package com.hooya.stabilizedturret.client;

import net.minecraft.world.phys.Vec3;

/** Ordered server poses on a monotonic client clock, with continuous network corrections. */
public final class WeaponViewMotion {
    private long serverTick = Long.MIN_VALUE;
    private Vec3 position;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 correction = Vec3.ZERO;
    private Vec3 displayed;
    private Vec3 lastTarget;
    private double received;
    private double sampled;

    public boolean accept(long tick, Vec3 point, Vec3 speed, double clientTime) {
        if (tick <= serverTick || !finite(point) || !finite(speed) || !Double.isFinite(clientTime)) return false;
        Vec3 continuous = position == null ? null : sample(clientTime);
        received = position == null ? clientTime : Math.max(clientTime, sampled);
        position = point;
        velocity = speed;
        serverTick = tick;
        // Keep one tick of interpolation, matching the public missile visuals.
        lastTarget = point.subtract(speed);
        correction = continuous == null ? Vec3.ZERO : continuous.subtract(lastTarget);
        displayed = continuous == null ? lastTarget : continuous;
        sampled = received;
        return true;
    }

    public Vec3 sample(double clientTime) {
        if (position == null) return Vec3.ZERO;
        double time = Math.max(sampled, clientTime);
        double delta = time - sampled;
        double prediction = Math.clamp(time - received - 1, -1, 3);
        Vec3 target = position.add(velocity.scale(prediction));
        Vec3 travel = target.subtract(lastTarget);
        // Never repay network error faster than half of this frame's actual
        // predicted travel. A late pose cannot pull a forward-flying missile back.
        Vec3 repayment = correction.scale(1 - Math.exp(-delta * 0.5));
        double limit = travel.length() * 0.5;
        double length = repayment.length();
        if (length > limit && length > 0) repayment = repayment.scale(limit / length);
        correction = correction.subtract(repayment);
        displayed = target.add(correction);
        sampled = time;
        lastTarget = target;
        return displayed;
    }

    public Vec3 velocity() { return velocity; }

    public void clear() {
        serverTick = Long.MIN_VALUE; position = null; velocity = Vec3.ZERO;
        correction = Vec3.ZERO; displayed = null; lastTarget = null;
        received = 0; sampled = 0;
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
