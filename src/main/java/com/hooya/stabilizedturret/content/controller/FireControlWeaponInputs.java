package com.hooya.stabilizedturret.content.controller;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Stable input IDs: existing actions keep their IDs, and aircraft types are append-only. */
public final class FireControlWeaponInputs {
    public static final int GROUND_FIRE = 3;
    public static final int LEGACY_AA_FIRE = 4;
    public static final int FIRST_GROUND_SELECTION = 17;
    public static final int FIRST_AIRCRAFT_SELECTION = 20;
    // Keep this a compile-time constant so packet codecs can use it without
    // loading the controller's optional cannon classes. The regression check
    // requires updating it whenever an aircraft weapon is appended.
    // 43 was already shipped as ground radar target cycling. Never reuse it.
    public static final int RESERVED_GROUND_TARGET_CYCLE = 43;
    public static final int WEAPON_VIEW = 45;
    public static final int ACTION_COUNT = WEAPON_VIEW + 1;

    public enum GroundWeapon {
        ANTI_TANK, AIR_DEFENSE, HIGH_SPEED_AIR_DEFENSE;

        public Component displayName() {
            return Component.translatable("weapon.create_fire_control.ground."
                    + name().toLowerCase(Locale.ROOT));
        }

        public boolean acceptsAaRound(boolean ordinary) {
            return this == (ordinary ? AIR_DEFENSE : HIGH_SPEED_AIR_DEFENSE);
        }
    }

    public static int action(GroundWeapon type) {
        return FIRST_GROUND_SELECTION + type.ordinal();
    }

    public static int action(MianbaoAircraftCompat.WeaponType type) {
        int action = FIRST_AIRCRAFT_SELECTION + type.ordinal();
        return action >= RESERVED_GROUND_TARGET_CYCLE ? action + 1 : action;
    }

    public static @Nullable GroundWeapon groundWeapon(int action) {
        int index = action - FIRST_GROUND_SELECTION;
        return index >= 0 && index < GroundWeapon.values().length ? GroundWeapon.values()[index] : null;
    }

    public static @Nullable MianbaoAircraftCompat.WeaponType aircraftWeapon(int action) {
        if (action == RESERVED_GROUND_TARGET_CYCLE) return null;
        int index = action - FIRST_AIRCRAFT_SELECTION - (action > RESERVED_GROUND_TARGET_CYCLE ? 1 : 0);
        var types = MianbaoAircraftCompat.WeaponType.values();
        return index >= 0 && index < types.length ? types[index] : null;
    }

    public static boolean isSelection(int action) {
        return groundWeapon(action) != null || aircraftWeapon(action) != null;
    }

    public static Component label(int action) {
        var ground = groundWeapon(action);
        if (ground != null) return ground.displayName();
        var aircraft = aircraftWeapon(action);
        return aircraft != null ? aircraft.displayName()
                : Component.translatable("screen.create_fire_control.input.action." + action);
    }

    public static void migrateGroundFireKeys(String[] keys) {
        if (keys[GROUND_FIRE].isEmpty()) keys[GROUND_FIRE] = keys[LEGACY_AA_FIRE];
        keys[LEGACY_AA_FIRE] = "";
    }

    private FireControlWeaponInputs() {}
}
