package com.hooya.stabilizedturret.content.missile;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server configuration for virtual ballistic flight. Speeds use blocks/second. */
public final class MissilePhysicsConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue INITIAL_SPEED;
    public static final ModConfigSpec.DoubleValue BOOST_ACCELERATION;
    public static final ModConfigSpec.DoubleValue CRUISE_SPEED;
    public static final ModConfigSpec.DoubleValue TERMINAL_ACCELERATION;
    public static final ModConfigSpec.DoubleValue TERMINAL_MAX_SPEED;
    public static final ModConfigSpec.DoubleValue BOOST_TURN_RATE;
    public static final ModConfigSpec.DoubleValue MIDCOURSE_TURN_RATE;
    public static final ModConfigSpec.DoubleValue TERMINAL_TURN_RATE;
    public static final ModConfigSpec.DoubleValue MIN_APOGEE;
    public static final ModConfigSpec.DoubleValue MAX_APOGEE;
    public static final ModConfigSpec.DoubleValue APOGEE_DISTANCE_FACTOR;
    public static final ModConfigSpec.DoubleValue TERMINAL_START_DISTANCE;
    public static final ModConfigSpec.DoubleValue IMPACT_PREPARE_SECONDS;
    public static final ModConfigSpec.IntValue IMPACT_CHUNK_RADIUS;
    public static final ModConfigSpec.IntValue SYNC_INTERVAL;
    public static final ModConfigSpec.DoubleValue MAX_RENDER_DISTANCE;
    public static final ModConfigSpec.BooleanValue FLARE_ENABLED;
    public static final ModConfigSpec.IntValue FLARE_BURST_INTERVAL;
    public static final ModConfigSpec.IntValue FLARE_COUNT_PER_BURST;
    public static final ModConfigSpec.IntValue FLARE_LIFETIME;
    public static final ModConfigSpec.DoubleValue FLARE_HEAT;
    public static final ModConfigSpec.DoubleValue FLARE_DISPERSION;
    public static final ModConfigSpec.DoubleValue FLARE_BACKWARD_IMPULSE;
    public static final ModConfigSpec.DoubleValue FLARE_RENDER_DISTANCE;
    public static final ModConfigSpec.EnumValue<FlareStartPhase> FLARE_START_PHASE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Virtual ballistic missile physics; speed is blocks/second and acceleration is blocks/second^2")
                .push("virtualBallisticMissile");
        INITIAL_SPEED = b.defineInRange("initialSpeed", 50.0, 1.0, 2000.0);
        BOOST_ACCELERATION = b.defineInRange("boostAcceleration", 40.0, 0.0, 1000.0);
        CRUISE_SPEED = b.defineInRange("cruiseSpeed", 500.0, 1.0, 2000.0);
        TERMINAL_ACCELERATION = b.defineInRange("terminalAcceleration", 120.0, 0.0, 2000.0);
        TERMINAL_MAX_SPEED = b.defineInRange("terminalMaxSpeed", 1020.0, 1.0, 4000.0);
        BOOST_TURN_RATE = b.defineInRange("boostTurnRateDegreesPerSecond", 12.0, 0.1, 180.0);
        MIDCOURSE_TURN_RATE = b.defineInRange("midcourseTurnRateDegreesPerSecond", 25.0, 0.1, 360.0);
        TERMINAL_TURN_RATE = b.defineInRange("terminalTurnRateDegreesPerSecond", 55.0, 0.1, 720.0);
        MIN_APOGEE = b.defineInRange("minApogee", 160.0, 0.0, 10000.0);
        MAX_APOGEE = b.defineInRange("maxApogee", 1800.0, 1.0, 50000.0);
        APOGEE_DISTANCE_FACTOR = b.defineInRange("apogeeDistanceFactor", 0.06, 0.0, 2.0);
        TERMINAL_START_DISTANCE = b.defineInRange("terminalStartDistance", 1200.0, 16.0, 10000.0);
        IMPACT_PREPARE_SECONDS = b.defineInRange("impactChunkPrepareSeconds", 6.0, 0.5, 60.0);
        IMPACT_CHUNK_RADIUS = b.defineInRange("impactChunkRadius", 6, 1, 8);
        SYNC_INTERVAL = b.defineInRange("missileSyncIntervalTicks", 4, 1, 40);
        MAX_RENDER_DISTANCE = b.defineInRange("missileMaxRenderDistance", 32000.0, 64.0, 100000.0);
        FLARE_ENABLED = b.define("flareEnabled", true);
        FLARE_START_PHASE = b.defineEnum("flareStartPhase", FlareStartPhase.TERMINAL);
        FLARE_BURST_INTERVAL = b.defineInRange("flareBurstIntervalTicks", 8, 1, 100);
        FLARE_COUNT_PER_BURST = b.defineInRange("flareCountPerBurst", 4, 1, 16);
        FLARE_LIFETIME = b.defineInRange("flareLifetimeTicks", 100, 10, 1200);
        FLARE_HEAT = b.defineInRange("flareHeat", 1.0, 0.0, 100.0);
        FLARE_DISPERSION = b.defineInRange("flareDispersionBlocksPerSecond", 16.0, 0.0, 200.0);
        FLARE_BACKWARD_IMPULSE = b.defineInRange("flareBackwardImpulseBlocksPerSecond", 24.0, 0.0, 500.0);
        FLARE_RENDER_DISTANCE = b.defineInRange("flareRenderDistance", 16000.0, 64.0, 100000.0);
        b.pop();
        SPEC = b.build();
    }

    public static double speedPerTick(ModConfigSpec.DoubleValue value) {
        return value.get() / 20.0;
    }

    public static double accelerationPerTickSquared(ModConfigSpec.DoubleValue value) {
        return value.get() / 400.0;
    }

    public enum FlareStartPhase { BOOST, MIDCOURSE, TERMINAL }

    private MissilePhysicsConfig() {}
}
