package com.hooya.stabilizedturret.content.turbine;

import java.util.HashSet;
import java.util.List;

/** One remote chord and two independent signed output targets. */
public record EngineDriveBehavior(List<String> keys,
        float leftSpeed, float leftAcceleration, float leftDeceleration,
        float rightSpeed, float rightAcceleration, float rightDeceleration,
        EngineTransitionRule enteringRates, EngineTransitionRule leavingRates, EngineRedstoneChannel redstoneChannel) {
    public static final int MAX_KEYS = 8;
    public static final float MAX_SPEED = 1024;
    public static final float DEFAULT_RATE = 128;

    public EngineDriveBehavior(List<String> keys, float leftSpeed, float leftAcceleration, float leftDeceleration,
                               float rightSpeed, float rightAcceleration, float rightDeceleration,
                               EngineTransitionRule enteringRates, EngineTransitionRule leavingRates) {
        this(keys, leftSpeed, leftAcceleration, leftDeceleration, rightSpeed, rightAcceleration, rightDeceleration,
                enteringRates, leavingRates, EngineRedstoneChannel.EMPTY);
    }

    public EngineDriveBehavior(List<String> keys, float leftSpeed, float leftAcceleration, float leftDeceleration,
                               float rightSpeed, float rightAcceleration, float rightDeceleration) {
        this(keys, leftSpeed, leftAcceleration, leftDeceleration, rightSpeed, rightAcceleration, rightDeceleration,
                EngineTransitionRule.DEFAULT, EngineTransitionRule.DEFAULT);
    }

    public EngineDriveBehavior {
        java.util.Objects.requireNonNull(enteringRates);
        java.util.Objects.requireNonNull(leavingRates);
        java.util.Objects.requireNonNull(redstoneChannel);
        keys = List.copyOf(keys);
        if (keys.size() > MAX_KEYS || new HashSet<>(keys).size() != keys.size()
                || keys.stream().anyMatch(k -> k.isEmpty() || k.length() > 64)) {
            throw new IllegalArgumentException("Invalid engine chord");
        }
        requireRange(leftSpeed, -MAX_SPEED, MAX_SPEED);
        requireRange(rightSpeed, -MAX_SPEED, MAX_SPEED);
        requireRange(leftAcceleration, 0, MAX_SPEED);
        requireRange(rightAcceleration, 0, MAX_SPEED);
        requireRange(leftDeceleration, 0, MAX_SPEED);
        requireRange(rightDeceleration, 0, MAX_SPEED);
    }

    public static EngineDriveBehavior defaults() {
        return new EngineDriveBehavior(List.of(), 256, DEFAULT_RATE, DEFAULT_RATE,
                256, DEFAULT_RATE, DEFAULT_RATE);
    }

    public float maximumMagnitude() {
        return Math.max(Math.abs(leftSpeed), Math.abs(rightSpeed));
    }

    public static void requireRange(float value, float minimum, float maximum) {
        if (!Float.isFinite(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException("Engine value outside " + minimum + ".." + maximum);
    }
}
