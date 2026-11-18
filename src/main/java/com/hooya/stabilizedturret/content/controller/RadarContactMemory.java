package com.hooya.stabilizedturret.content.controller;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Presentation-only coast. Only confirmed measurements refresh its clock. */
public final class RadarContactMemory<K, T> {
    public static final int AIRCRAFT_TICKS = 60, GROUND_TICKS = 40;
    private final int capacity;
    private final Map<K, Entry<T>> entries = new LinkedHashMap<>();
    private Set<K> measured = Set.of();
    private long tick = Long.MIN_VALUE;
    private List<T> input, output = List.of();

    private record Entry<T>(T measurement, long tick) {}
    public RadarContactMemory(int capacity) { this.capacity = capacity; }

    public List<T> update(long now, int hold, List<T> confirmed, Function<T, K> key,
                          BiFunction<T, Double, T> extrapolate) {
        if (now < tick) clear();
        if (tick == now && input == confirmed) return output;
        tick = now; input = confirmed;
        Set<K> live = new HashSet<>();
        List<T> result = new ArrayList<>();
        // Network entry points already cap these lists at 20/64. Also bound this cache independently.
        for (T contact : confirmed) {
            if (result.size() >= capacity) break;
            K id = key.apply(contact);
            if (!live.add(id)) continue;
            entries.put(id, new Entry<>(contact, now)); result.add(contact);
        }
        measured = live;
        entries.entrySet().removeIf(e -> now < e.getValue().tick || now - e.getValue().tick >= hold);
        while (entries.size() > capacity) {
            K remove = entries.keySet().stream().filter(id -> !live.contains(id)).findFirst().orElse(entries.keySet().iterator().next());
            entries.remove(remove);
        }
        for (var e : entries.entrySet()) {
            if (result.size() >= capacity) break;
            if (!live.contains(e.getKey())) result.add(extrapolate.apply(e.getValue().measurement, (double) (now - e.getValue().tick)));
        }
        output = List.copyOf(result); return output;
    }

    public boolean isRemembered(K id) { return entries.containsKey(id) && !measured.contains(id); }
    public int size() { return entries.size(); }
    public void clear() { entries.clear(); measured = Set.of(); input = null; output = List.of(); tick = Long.MIN_VALUE; }

    /** War Thunder screen-frame envelope: one half-cosine ramp per 0.5 seconds (10 ticks). */
    public static int blinkColor(int color, double tickTime) {
        double phase = (tickTime % 10 + 10) % 10 / 10;
        double opacity = .2 + .8 * .5 * (1 - Math.cos(phase * Math.PI));
        return ((int) Math.round((color >>> 24) * opacity) << 24) | (color & 0xffffff);
    }
}
