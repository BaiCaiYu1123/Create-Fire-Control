package com.hooya.stabilizedturret.content.remote;

import java.util.*;

/** Event-driven ownership and release barriers; no global keyboard grab. */
public final class ControllerInputRouting {
    private Set<String> uses = Set.of();
    private final Set<String> pressed = new HashSet<>(), waitForRelease = new HashSet<>();
    public void updateUses(Set<String> next, Set<String> physicallyDown) {
        Set<String> changed = new HashSet<>(uses); changed.addAll(next);
        Set<String> common = new HashSet<>(uses); common.retainAll(next); changed.removeAll(common);
        changed.retainAll(physicallyDown); waitForRelease.addAll(changed);
        pressed.retainAll(next); pressed.removeAll(waitForRelease); uses = Set.copyOf(next);
    }
    public boolean event(String channel, boolean down) {
        if (!blocks(channel)) return false;
        if (!down) { pressed.remove(channel); waitForRelease.remove(channel); }
        else if (uses.contains(channel) && !waitForRelease.contains(channel)) pressed.add(channel);
        return true;
    }
    public boolean blocks(String channel) { return uses.contains(channel) || waitForRelease.contains(channel); }
    public Set<String> pressed() { return Set.copyOf(pressed); }
    public Set<String> uses() { return uses; }
    public void suppressUntilRelease(String channel) {
        if (ControllerInput.fromChannel(channel) != null) {waitForRelease.add(channel);pressed.remove(channel);}
    }
    public void stop(Set<String> physicallyDown) {
        Set<String> heldOwned = new HashSet<>(uses); heldOwned.retainAll(physicallyDown);
        waitForRelease.addAll(heldOwned); uses = Set.of(); pressed.clear();
    }
    public void reconcile(Set<String> physicallyDown) {
        pressed.retainAll(physicallyDown); waitForRelease.retainAll(physicallyDown);
    }
    public void reset() { uses = Set.of(); pressed.clear(); waitForRelease.clear(); }
}
