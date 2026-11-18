package com.hooya.stabilizedturret.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Send the inactive state once per player instance/dimension and after leaving a source. */
final class InactiveSessionSync<K> {
    private record Context(Object player, Object dimension, boolean active) {}
    private final Map<K, Context> sent = new HashMap<>();

    boolean shouldSend(K id, Object player, Object dimension, boolean active) {
        Context previous = sent.get(id);
        if (!active && previous != null && !previous.active
                && previous.player == player && Objects.equals(previous.dimension, dimension)) return false;
        sent.put(id, new Context(player, dimension, active));
        return true;
    }

    void forget(K id) { sent.remove(id); }
    void clear() { sent.clear(); }
}
