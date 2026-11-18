package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.MissileExplosionPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

public final class AircraftExplosionParticleChecks {
    public static void main(String[] args) {
        AircraftExplosionParticles nativeEffect = AircraftExplosionParticles.forStrength(6);
        AircraftExplosionParticles boosted = AircraftExplosionParticles.forStrength(18);
        require(AircraftExplosionParticles.PARTICLE_COUNT == 84,
                "Aircraft explosions must retain the unboosted particle count");
        require(boosted.scale() == 3, "Individual particle size must triple");
        RandomSource nativeRandom = RandomSource.create(4127);
        RandomSource boostedRandom = RandomSource.create(4127);
        Vec3 center = Vec3.ZERO;
        boolean above = false;
        boolean below = false;
        for (int i = 0; i < 10000; i++) {
            Vec3 original = nativeEffect.offset(nativeRandom);
            Vec3 enlarged = boosted.offset(boostedRandom);
            require(original.scale(3).distanceTo(enlarged) < 1e-9,
                    "Particle spacing must scale uniformly about the impact");
            require(Math.abs(enlarged.y) <= 11.475,
                    "Vertical spread must straddle the impact instead of starting above it");
            above |= enlarged.y > 0;
            below |= enlarged.y < 0;
            center = center.add(enlarged);
            require(nativeEffect.velocity(nativeRandom).scale(3)
                            .distanceTo(boosted.velocity(boostedRandom)) < 1e-9,
                    "Expansion velocity must scale with spacing and size");
        }
        require(above && below && center.scale(1.0 / 10000).length() < 0.3,
                "Explosion distribution must be centered at the impact");
        for (boolean aircraft : new boolean[]{false, true}) {
            var payload = new MissileExplosionPayload(123.5, -40, 987.25, 18,
                    false, false, aircraft);
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                MissileExplosionPayload.STREAM_CODEC.encode(buffer, payload);
                require(payload.equals(MissileExplosionPayload.STREAM_CODEC.decode(buffer))
                                && !buffer.isReadable(),
                        "Explosion profile and exact impact must survive network serialization");
            } finally {
                buffer.release();
            }
        }
        AirDefenseExplosionParticleChecks.main(args);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
