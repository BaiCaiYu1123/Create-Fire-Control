package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.MissileExplosionPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;

import java.util.HashSet;

public final class AirDefenseExplosionParticleChecks {
    public static void main(String[] args) {
        var nativeEffect = new AirDefenseExplosionParticles(1);
        var enlarged = new AirDefenseExplosionParticles(AirDefenseExplosionParticles.ADOPTED_SCALE);
        require(AirDefenseExplosionParticles.PARTICLE_COUNT == 84, "Native AA particle count changed");
        require(AirDefenseExplosionParticles.PARTICLE.equals("explosionsmall"), "AA lost its native particle type");
        require(enlarged.scale() == 3, "All AA variants require the same threefold visual enlargement");

        // Remove random displacement to check the original procedure's hemisphere,
        // rather than accepting the cylindrical aircraft/rocket distribution.
        RandomSource centered = new LegacyRandomSource(0) {
            @Override public double nextDouble() { return 0.5; }
            @Override public double nextGaussian() { return 0; }
        };
        int[] layers = new int[4];
        var cells = new HashSet<>();
        for (int i = 0; i < 84; i++) {
            var sample = nativeEffect.sample(i, centered);
            var point = sample.offset();
            require(point.y >= 0 && point.y <= 3, "AA hemisphere moved below its native grid");
            layers[(int) point.y]++;
            cells.add(point);
            require(sample.velocity().lengthSqr() == 0, "Zero packet scatter gained expansion");
        }
        require(cells.size() == 84 && java.util.Arrays.equals(layers, new int[]{29, 25, 21, 9}),
                "Native AA hemisphere layers or per-impact density changed");

        for (int seed = 0; seed < 64; seed++) {
            RandomSource originalRandom = RandomSource.create(seed);
            RandomSource scaledRandom = RandomSource.create(seed);
            RandomSource batchedRandom = RandomSource.create(seed);
            var samples = new AirDefenseExplosionParticles.Sample[84];
            for (int i = 0; i < samples.length; i++) {
                var original = nativeEffect.sample(i, originalRandom);
                samples[i] = enlarged.sample(i, scaledRandom);
                require(original.offset().scale(3).distanceTo(samples[i].offset()) < 1e-12,
                        "AA spacing and packet jitter must enlarge about the impact");
                require(original.velocity().scale(3).distanceTo(samples[i].velocity()) < 1e-12,
                        "AA expansion speed must enlarge with particle size");
            }
            for (int begin = 0; begin < 84; begin += 12) {
                for (int i = begin; i < Math.min(84, begin + 12); i++) {
                    require(samples[i].equals(enlarged.sample(i, batchedRandom)),
                            "Client particle budgeting dropped/reordered AA samples");
                }
            }
        }
        for (byte profile : new byte[]{MissileExplosionPayload.GENERIC,
                MissileExplosionPayload.AIRCRAFT, MissileExplosionPayload.AIR_DEFENSE}) {
            for (float strength : new float[]{12, 15, 18}) {
                var payload = new MissileExplosionPayload(1250.5, 183.25, -8700.75,
                        strength, false, false, profile);
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                try {
                    MissileExplosionPayload.STREAM_CODEC.encode(buffer, payload);
                    require(payload.equals(MissileExplosionPayload.STREAM_CODEC.decode(buffer)) && !buffer.isReadable(),
                            "AA/aircraft/rocket profiles must stay distinct over the network");
                    require(payload.airDefenseExplosion() == (profile == MissileExplosionPayload.AIR_DEFENSE),
                            "AA impact was classified as a generic/aircraft explosion");
                } finally { buffer.release(); }
            }
        }
        System.out.println("PASS: native AA small particles, 84-point hemisphere, triple size/spacing/speed, batched samples and three network profiles");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
