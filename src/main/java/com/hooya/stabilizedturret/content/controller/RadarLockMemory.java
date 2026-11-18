package com.hooya.stabilizedturret.content.controller;

import java.util.*;

/** Remembers operator intent, never an authoritative contact or illumination. Server thread only. */
public final class RadarLockMemory {
    private static final Map<StabilizerControllerBlockEntity, State> STATES = new WeakHashMap<>();
    private static final class State { Intent lock, selection; }
    private record Intent(UUID id, long seen) {}

    public static void clear(StabilizerControllerBlockEntity controller) { STATES.remove(controller); }
    public static void clearLock(StabilizerControllerBlockEntity controller) {
        State state = STATES.get(controller); if (state != null) state.lock = null;
    }

    private static int hold(StabilizerControllerBlockEntity c) {
        return c.isAircraftRadarMode() ? RadarContactMemory.AIRCRAFT_TICKS : RadarContactMemory.GROUND_TICKS;
    }
    private static boolean valid(Intent intent, long now, int hold) {
        return intent != null && now >= intent.seen && now - intent.seen < hold;
    }
    public static UUID updateLock(StabilizerControllerBlockEntity c, UUID current, long now) {
        State state = STATES.computeIfAbsent(c, ignored -> new State());
        if (current != null && c.getRadarContact(current) != null) {
            state.lock = new Intent(current, now); return current;
        }
        if (!valid(state.lock, now, hold(c))) state.lock = null;
        if (state.lock != null && c.getRadarContact(state.lock.id) != null) {
            state.lock = new Intent(state.lock.id, now); return state.lock.id;
        }
        return null;
    }
    public static UUID updateSelection(StabilizerControllerBlockEntity c, UUID current, long now) {
        State state = STATES.computeIfAbsent(c, ignored -> new State());
        if (current != null && c.getRadarContact(current) != null) {
            state.selection = new Intent(current, now); return current;
        }
        if (!valid(state.selection, now, hold(c)) || current == null
                || !current.equals(state.selection.id)) state.selection = null;
        return state.selection == null ? null : state.selection.id;
    }
    public static UUID displayLock(StabilizerControllerBlockEntity c) {
        UUID actual = c.getRadarLockedSubLevelId();
        if (actual != null) return actual;
        State state = STATES.get(c);
        return state != null && c.getLevel() != null && c.isRadarActive()
                && valid(state.lock, c.getLevel().getGameTime(), hold(c)) ? state.lock.id : null;
    }
}
