package com.hooya.stabilizedturret.client;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import java.util.Objects;
import java.util.UUID;

/** Continuous motion between authoritative packets; new packets correct position without a jump. */
public final class LockTargetSmoother {
    private UUID acquisition;
    private Vec3 anchor, velocity = Vec3.ZERO, correction = Vec3.ZERO, correctionSlope = Vec3.ZERO;
    private double receivedAt;

    public void clear() { acquisition = null; anchor = null; velocity = correction = correctionSlope = Vec3.ZERO; }

    public void accept(UUID event, Vec3 position, Vec3 motion, double seconds) {
        if (!finite(position) || !finite(motion) || !Double.isFinite(seconds)) return;
        Vec3 displayed = sample(seconds);
        Vec3 displayedVelocity = velocityAt(seconds);
        boolean same = Objects.equals(event, acquisition) && displayed != null
                && displayed.distanceToSqr(position) < 256.0 * 256.0;
        acquisition = event;
        anchor = position;
        velocity = motion;
        correction = same ? displayed.subtract(position) : Vec3.ZERO;
        correctionSlope = same ? displayedVelocity.subtract(motion.scale(20)).add(correction.scale(16)) : Vec3.ZERO;
        receivedAt = seconds;
    }

    public @Nullable Vec3 sample(double seconds) {
        if (anchor == null || !Double.isFinite(seconds)) return null;
        double age = Math.max(0, seconds - receivedAt);
        // Stop predicting through a long network outage. Blocks/tick -> blocks/second.
        return anchor.add(velocity.scale(20 * Math.min(age, .15)))
                .add(correction.add(correctionSlope.scale(age)).scale(Math.exp(-16 * age)));
    }

    private Vec3 velocityAt(double seconds) {
        if (anchor == null) return Vec3.ZERO;
        double age = Math.max(0, seconds - receivedAt);
        Vec3 predicted = age < .15 ? velocity.scale(20) : Vec3.ZERO;
        return predicted.add(correctionSlope.subtract(correction.add(correctionSlope.scale(age)).scale(16))
                .scale(Math.exp(-16 * age)));
    }

    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
