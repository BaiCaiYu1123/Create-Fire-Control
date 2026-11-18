package com.hooya.stabilizedturret.content.controller;

/** A held key selects once, including the first launch after an empty press. */
public final class WeaponViewSelection<T> {
    private T latest;
    private T selected;
    private boolean held;
    private boolean consumed;

    public void launched(T weapon) {
        latest = weapon;
        if (held && !consumed) select();
    }

    public void hold(boolean pressed) {
        if (!pressed) {
            selected = null;
            consumed = false;
        } else if (!held) {
            select();
        }
        held = pressed;
    }

    private void select() {
        selected = latest;
        consumed = latest != null;
    }

    public T selected() { return selected; }
    public T latest() { return latest; }

    /** Destruction must never fall back to an older shot or select a new one mid-hold. */
    public void removed(T weapon) {
        if (latest == weapon) latest = null;
        if (selected == weapon) selected = null;
    }
}
