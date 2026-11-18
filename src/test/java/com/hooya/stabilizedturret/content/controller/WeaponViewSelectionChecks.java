package com.hooya.stabilizedturret.content.controller;

/** User-visible hold/release behavior, independent of Minecraft rendering. */
public final class WeaponViewSelectionChecks {
    public static void main(String[] args) {
        Object missile = new Object(), bomb = new Object(), rocket = new Object();
        var selection = new WeaponViewSelection<Object>();
        selection.hold(true);
        require(selection.selected() == null, "An empty press must wait for launch");
        selection.launched(missile);
        require(selection.selected() == missile, "Pre-held input must select the first shot immediately");
        selection.launched(bomb);
        selection.hold(true);
        require(selection.selected() == missile, "A new bomb cannot replace the observed missile mid-hold");
        selection.hold(false);
        require(selection.selected() == null, "Release must leave view");
        selection.hold(true);
        require(selection.selected() == bomb, "A new press must select the most recent weapon across types");
        selection.removed(bomb);
        selection.launched(rocket);
        selection.hold(true);
        require(selection.selected() == null, "Destruction cannot switch to the next launch while held");
        selection.hold(false);
        selection.hold(true);
        require(selection.selected() == rocket, "Repress after destruction must select the latest rocket");
        selection.removed(missile);
        require(selection.selected() == rocket, "Removal of an older weapon cannot affect the observer");
        selection.hold(false);
        selection.launched(missile);
        selection.launched(bomb);
        selection.hold(true);
        require(selection.selected() == bomb, "Several shots before pressing must select only the newest");
        require(FireControlWeaponInputs.WEAPON_VIEW == 45
                        && !FireControlWeaponInputs.isSelection(FireControlWeaponInputs.WEAPON_VIEW),
                "The appended view action cannot replace an existing weapon selection");
        System.out.println("PASS: pre-held launch, latest shot, fixed held selection, release/repress, destruction and weapon-type switching");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
