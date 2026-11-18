package com.hooya.stabilizedturret.content.controller;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A complete, authorized input sample owned by fire control, including released keys. */
public record NativeInputSnapshot(UUID session, Map<String, Integer> signals,
                                  Set<String> observed, Set<String> pulses) {
    public static final NativeInputSnapshot EMPTY = new NativeInputSnapshot(null, Map.of(), Set.of(), Set.of());

    public NativeInputSnapshot {
        signals = Map.copyOf(signals);
        observed = Set.copyOf(observed);
        pulses = Set.copyOf(pulses);
    }
}
