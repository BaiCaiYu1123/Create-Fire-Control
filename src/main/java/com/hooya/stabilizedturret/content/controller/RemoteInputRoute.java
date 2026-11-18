package com.hooya.stabilizedturret.content.controller;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-terminal circuit breaker. A failed wire route stays bypassed until a new input session. */
public final class RemoteInputRoute {
    private UUID session;
    private final Set<String> observed = new HashSet<>();
    private boolean nativeRoute;
    private String failureReason = "";

    public Map<String, Integer> select(boolean wireAvailable, Map<String, Integer> wire,
                                       NativeInputSnapshot input) {
        if (input.session() != null && !input.session().equals(session)) {
            session = input.session();
            observed.clear();
            nativeRoute = false;
            failureReason = "";
        }
        observed.addAll(input.observed());
        if (!wireAvailable && !nativeRoute) {
            nativeRoute = true;
            failureReason = "wire signal bridge unavailable or failed";
        }
        if (input.session() != null) {
            for (String key : input.observed()) {
                // Wheel pulses deliberately insert low ticks independently of the wire store.
                if (!nativeRoute && !input.pulses().contains(key)
                        && (wire.getOrDefault(key, 0) > 0) != (input.signals().getOrDefault(key, 0) > 0)) {
                    nativeRoute = true;
                    failureReason = "input mismatch for " + key + ": wire=" + wire.getOrDefault(key, 0)
                            + ", native=" + input.signals().getOrDefault(key, 0);
                }
            }
        }
        Map<String, Integer> result = new HashMap<>(wireAvailable ? wire : Map.of());
        // A timed-out/closed session must release its keys even if the wire store is stuck high.
        if (input.session() == null) for (String key : observed) result.remove(key);
        if (input.session() != null) {
            for (String key : input.observed()) {
                if (nativeRoute || input.pulses().contains(key)) {
                    result.put(key, input.signals().getOrDefault(key, 0));
                }
            }
        }
        return Map.copyOf(result);
    }

    public boolean usesNativeRoute() { return nativeRoute; }
    public String failureReason() { return failureReason; }
}
