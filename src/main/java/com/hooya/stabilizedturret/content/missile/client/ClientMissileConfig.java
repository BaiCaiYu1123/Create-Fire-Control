package com.hooya.stabilizedturret.content.missile.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only visual policy. Call {@link #register(ModContainer)} during client setup. */
public final class ClientMissileConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue MAX_DISTANCE, LOD_NEAR, LOD_MID,
            FLARE_DISTANCE, LOW_ALTITUDE;
    public static final ModConfigSpec.IntValue TRAIL_SAMPLES;
    public static final ModConfigSpec.BooleanValue UNKNOWN_LOW_ALTITUDE_FADE;
    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder().comment("Virtual ballistic missile client rendering").push("virtualMissiles");
        MAX_DISTANCE = b.defineInRange("renderDistance", 32000.0, 64.0, 100000.0);
        LOD_NEAR = b.defineInRange("nearLodDistance", 256.0, 8.0, 10000.0);
        LOD_MID = b.defineInRange("midLodDistance", 2048.0, 16.0, 20000.0);
        FLARE_DISTANCE = b.defineInRange("flareRenderDistance", 16000.0, 64.0, 100000.0);
        LOW_ALTITUDE = b.defineInRange("unknownTerrainFadeAltitude", 96.0, 0.0, 1000.0);
        TRAIL_SAMPLES = b.defineInRange("trailSamples", 48, 4, 128);
        UNKNOWN_LOW_ALTITUDE_FADE = b.define("fadeWhenLowAndTerrainUnknown", true);
        b.pop(); SPEC = b.build();
    }
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC,
                "create_fire_control-missiles-client.toml");
    }
    private ClientMissileConfig() {}
}
