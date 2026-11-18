package com.hooya.stabilizedturret.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/** Authoritative lock readouts; velocities use Minecraft blocks per tick. */
public record TargetLockTelemetry(
        Vec3 velocity, double distance, double closingRate, double heightAboveGround
) {
    public static final TargetLockTelemetry UNAVAILABLE =
            new TargetLockTelemetry(Vec3.ZERO, Double.NaN, Double.NaN, Double.NaN);

    public static TargetLockTelemetry measure(
            Vec3 observer, Vec3 observerVelocity, Vec3 target, Vec3 targetVelocity,
            double groundHeight
    ) {
        Vec3 lineOfSight = target.subtract(observer);
        double distance = lineOfSight.length();
        double closing = distance < 1.0e-6 ? 0.0
                : -targetVelocity.subtract(observerVelocity).dot(lineOfSight.scale(1.0 / distance)) * 20.0;
        return new TargetLockTelemetry(targetVelocity, distance, closing,
                Double.isFinite(groundHeight) ? Math.max(0.0, target.y - groundHeight) : Double.NaN);
    }

    public static void write(RegistryFriendlyByteBuf buffer, TargetLockTelemetry value) {
        buffer.writeDouble(value.velocity.x);
        buffer.writeDouble(value.velocity.y);
        buffer.writeDouble(value.velocity.z);
        buffer.writeDouble(value.distance);
        buffer.writeDouble(value.closingRate);
        buffer.writeDouble(value.heightAboveGround);
    }

    public static TargetLockTelemetry read(RegistryFriendlyByteBuf buffer) {
        return new TargetLockTelemetry(
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
}
