package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Starts a client-only reconstruction of a Mianbao detonation. */
public record MianbaoDetonationVisualPayload(
        byte profile, double x, double y, double z, long seed,
        boolean initialEffects, boolean closePlayerEffects
) implements CustomPacketPayload {
    public static final byte BOMB = 0;
    public static final byte GROUND = 1;
    public static final byte LITTLE_BOMB = 2;
    public static final byte NUCLEAR = 3;
    /** Mianbao's ballistic warhead runs the native nuclear procedure at 2x scale. */
    public static final byte BALLISTIC_NUCLEAR = 4;
    public static final long SCHEDULE_SEED_XOR = 0x6A09E667F3BCC909L;
    /** Mianbao 2.5.0's longest nuclear particle lasts 2,199 ticks. */
    private static final int NUCLEAR_VISUAL_DURATION_TICKS = 2_200;

    public static final Type<MianbaoDetonationVisualPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "mianbao_detonation_visual"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MianbaoDetonationVisualPayload>
            STREAM_CODEC = StreamCodec.of(
                    MianbaoDetonationVisualPayload::write,
                    MianbaoDetonationVisualPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer,
                              MianbaoDetonationVisualPayload payload) {
        buffer.writeByte(payload.profile);
        buffer.writeDouble(payload.x);
        buffer.writeDouble(payload.y);
        buffer.writeDouble(payload.z);
        buffer.writeLong(payload.seed);
        buffer.writeBoolean(payload.initialEffects);
        buffer.writeBoolean(payload.closePlayerEffects);
    }

    private static MianbaoDetonationVisualPayload read(RegistryFriendlyByteBuf buffer) {
        return new MianbaoDetonationVisualPayload(
                buffer.readByte(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readLong(), buffer.readBoolean(),
                buffer.readBoolean());
    }

    public static void handle(MianbaoDetonationVisualPayload payload,
                              IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.handleMianbaoDetonationVisual(payload);
            }
        });
    }

    /** Server/client shared lifetime for releasing remote impact chunks exactly
     * after the detached Mianbao effect has finished. */
    public static int visualDurationTicks(byte profile, long seed) {
        if (profile == NUCLEAR || profile == BALLISTIC_NUCLEAR) {
            return NUCLEAR_VISUAL_DURATION_TICKS;
        }
        if (profile == LITTLE_BOMB) return 1;
        if (profile != BOMB && profile != GROUND) return 1;
        RandomSource schedule = RandomSource.create(seed ^ SCHEDULE_SEED_XOR);
        if (!schedule.nextBoolean()) return 1;
        int smokeStart = 320 + schedule.nextInt(61);
        int smokeInterval = 18 + schedule.nextInt(5);
        int smokeCount = 35 + schedule.nextInt(6);
        return smokeStart + smokeInterval * (smokeCount - 1);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
