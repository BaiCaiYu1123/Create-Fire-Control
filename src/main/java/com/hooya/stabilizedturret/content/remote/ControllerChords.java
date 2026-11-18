package com.hooya.stabilizedturret.content.remote;

import java.util.*;

/** Two physical inputs can drive one stable output channel, in either press order. */
public final class ControllerChords {
    public static Set<String> physicalUses(Collection<String> outputs, Map<String,String> chords, String exit) {
        Set<String> result = new HashSet<>();
        for (String output : outputs) {
            String second = chords.getOrDefault(output, "");
            if (output.equals(exit) || second.equals(exit)) continue;
            result.add(output);
            if (!second.isEmpty()) result.add(second);
        }
        return Set.copyOf(result);
    }
    public static Set<String> outputs(Set<String> held, Collection<String> outputs, Map<String,String> chords, String exit) {
        Set<String> result = new HashSet<>();
        for (String output : outputs) {
            String second = chords.getOrDefault(output, "");
            if (!output.equals(exit) && !second.equals(exit) && held.contains(output)
                    && (second.isEmpty() || held.contains(second))) result.add(output);
        }
        return Set.copyOf(result);
    }
    private ControllerChords() {}
}
