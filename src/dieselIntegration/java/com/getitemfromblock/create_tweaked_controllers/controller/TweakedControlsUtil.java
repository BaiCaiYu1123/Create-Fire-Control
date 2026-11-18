package com.getitemfromblock.create_tweaked_controllers.controller;

/** Test-only implementation of the remote profile's public reflection contract. Never packaged. */
public final class TweakedControlsUtil {
    public static final Profile profile = new Profile();
    public static final class Profile {
        public final Input[] layout = {new Input(87),new Input(83),new Input(65),new Input(68),
                new Input(81),new Input(69),new Input(82),new Input(70),new Input(73)};
    }
    public record Input(int value) {
        public String GetType() { return "KEYBOARD_KEY"; }
        public int GetValue() { return value; }
    }
}
