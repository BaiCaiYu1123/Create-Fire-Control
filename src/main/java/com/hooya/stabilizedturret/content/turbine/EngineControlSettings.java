package com.hooya.stabilizedturret.content.turbine;

import java.util.List;

public record EngineControlSettings(float soundVolume, String engineToggleKey,
                                    List<EngineDriveBehavior> behaviors,
                                    boolean soundRpmEnabled, float soundRpmAcceleration,
                                    float soundRpmDeceleration, float soundMaxRpm, EngineRedstoneChannel toggleChannel) {
    public static final int MAX_BEHAVIORS = 64;
    public static final float DEFAULT_SOUND_RPM_RATE = 128;
    public static final float DEFAULT_SOUND_MAX_RPM = 256;
    public EngineControlSettings(float soundVolume, String engineToggleKey, List<EngineDriveBehavior> behaviors,
                                 boolean soundRpmEnabled, float soundRpmAcceleration,
                                 float soundRpmDeceleration, float soundMaxRpm) {
        this(soundVolume, engineToggleKey, behaviors, soundRpmEnabled, soundRpmAcceleration,
                soundRpmDeceleration, soundMaxRpm, EngineRedstoneChannel.EMPTY);
    }
    public EngineControlSettings(float soundVolume, String engineToggleKey, List<EngineDriveBehavior> behaviors) {
        this(soundVolume, engineToggleKey, behaviors, false, DEFAULT_SOUND_RPM_RATE,
                DEFAULT_SOUND_RPM_RATE, DEFAULT_SOUND_MAX_RPM);
    }
    public EngineControlSettings {
        java.util.Objects.requireNonNull(toggleChannel);
        EngineDriveBehavior.requireRange(soundVolume, 0, 300);
        EngineDriveBehavior.requireRange(soundRpmAcceleration, 0, 1024);
        EngineDriveBehavior.requireRange(soundRpmDeceleration, 0, 1024);
        EngineDriveBehavior.requireRange(soundMaxRpm, 0, 1024);
        if (engineToggleKey == null || engineToggleKey.length() > 64)
            throw new IllegalArgumentException("Invalid engine toggle key");
        behaviors = List.copyOf(behaviors);
        if (behaviors.isEmpty() || behaviors.size() > MAX_BEHAVIORS)
            throw new IllegalArgumentException("Invalid engine behavior count");
    }

    public static EngineControlSettings defaults() {
        return new EngineControlSettings(150, "", List.of(EngineDriveBehavior.defaults()));
    }
}
