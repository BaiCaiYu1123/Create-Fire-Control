package com.hooya.stabilizedturret.content.turbine;

/** The default delegates to an explicit rule at the other end, then uses outgoing rates. */
public enum EngineTransitionRule {
    DEFAULT, OUTGOING, INCOMING;

    public static EngineTransitionRule stored(int value) {
        return value >= 0 && value < values().length ? values()[value] : DEFAULT;
    }

    public static EngineTransitionRule network(int value) {
        if (value < 0 || value >= values().length) throw new IllegalArgumentException("Invalid transition rule");
        return values()[value];
    }
}
