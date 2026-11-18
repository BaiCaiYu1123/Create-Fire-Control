package com.hooya.stabilizedturret.content.controller;

/** Stable, network-safe identifiers for the fire-control computer's gun-sight styles. */
public enum ScopePreset {
    DEFAULT("default"),
    AMERICAN("american"),
    CHINESE("chinese"),
    ITALIAN("italian"),
    FRENCH("french"),
    SQUID("squid"),
    ELB("elb"),
    GERMAN("german");

    private static final ScopePreset[] VALUES = values();

    private final String serializedName;

    ScopePreset(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public String translationKey() {
        return "screen.create_fire_control.scope_preset." + serializedName;
    }

    public static ScopePreset fromNetworkId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : DEFAULT;
    }

    public static ScopePreset fromSerializedName(String name) {
        if (name != null) {
            for (ScopePreset preset : VALUES) {
                if (preset.serializedName.equals(name)) return preset;
            }
        }
        return DEFAULT;
    }
}
