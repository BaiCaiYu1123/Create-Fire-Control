package com.hooya.stabilizedturret.content.controller;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class FireControlWeaponInputChecks {
    public static void main(String[] args) {
        var ids = new HashSet<Integer>();
        for (var type : FireControlWeaponInputs.GroundWeapon.values()) {
            int action = FireControlWeaponInputs.action(type);
            require(action >= 17 && ids.add(action), "Ground actions must preserve existing input IDs");
            require(FireControlWeaponInputs.groundWeapon(action) == type, "Ground selection round trip");
            require(FireControlWeaponInputs.aircraftWeapon(action) == null, "Ground inputs cannot select aircraft weapons");
        }
        for (var type : MianbaoAircraftCompat.WeaponType.values()) {
            int action = FireControlWeaponInputs.action(type);
            require(ids.add(action), "Aircraft selections cannot collide with ground inputs");
            require(FireControlWeaponInputs.aircraftWeapon(action) == type, "Aircraft selection round trip");
            require(FireControlWeaponInputs.groundWeapon(action) == null, "Aircraft inputs cannot select ground weapons");
        }
        require(FireControlWeaponInputs.action(MianbaoAircraftCompat.WeaponType.FAR) == 20,
                "First aircraft action must remain stable when aircraft types are appended");
        require(ids.size() == FireControlWeaponInputs.ACTION_COUNT - 17 - 2, "Selection IDs must exclude ground cycle and weapon view");
        require(FireControlWeaponInputs.action(MianbaoAircraftCompat.WeaponType.ANTI_RADIATION) == 44,
                "Anti-radiation selection must append after ground target cycling without changing old keys");
        for (int invalid : new int[]{-1, 0, 3, 4, 16, FireControlWeaponInputs.ACTION_COUNT, Integer.MAX_VALUE}) {
            require(!FireControlWeaponInputs.isSelection(invalid), "Legacy or invalid input treated as selection");
            require(FireControlWeaponInputs.groundWeapon(invalid) == null
                    && FireControlWeaponInputs.aircraftWeapon(invalid) == null, "Invalid selection accepted");
        }

        var ground = FireControlWeaponInputs.GroundWeapon.class;
        require(ground.getEnumConstants().length == 3, "Ground list must contain only the three Mianbao missile types");
        for (var type : ground.getEnumConstants()) {
            require(type.acceptsAaRound(true) == (type == FireControlWeaponInputs.GroundWeapon.AIR_DEFENSE),
                    "Ordinary AA selection must never fire high-speed or anti-tank rounds");
            require(type.acceptsAaRound(false) == (type == FireControlWeaponInputs.GroundWeapon.HIGH_SPEED_AIR_DEFENSE),
                    "High-speed AA selection must never fire ordinary or anti-tank rounds");
        }
        for (String oldAntiTank : new String[]{"", "key_87"}) {
            for (String oldAa : new String[]{"", "mouse_0"}) {
                String[] keys = new String[FireControlWeaponInputs.ACTION_COUNT];
                Arrays.fill(keys, ""); keys[3] = oldAntiTank; keys[4] = oldAa; keys[16] = "scroll_up";
                int direct = FireControlWeaponInputs.action(MianbaoAircraftCompat.WeaponType.MICA_EM);
                keys[direct] = "key_49";
                FireControlWeaponInputs.migrateGroundFireKeys(keys);
                require(keys[3].equals(oldAntiTank.isEmpty() ? oldAa : oldAntiTank),
                        "Merged fire binding must retain AT binding or fall back to old AA binding");
                require(keys[4].isEmpty(), "Hidden legacy AA action must not fire a second missile");
                require(keys[16].equals("scroll_up") && keys[direct].equals("key_49"), "Unrelated bindings changed during migration");
                String[] migrated = keys.clone(); FireControlWeaponInputs.migrateGroundFireKeys(keys);
                require(Arrays.equals(keys, migrated), "Repeated world loads must preserve migrated bindings");
            }
        }
        radarTargetCycling();
        System.out.println("PASS: weapon selection IDs, aircraft/ground separation, AA filtering, merged legacy fire bindings and ground radar target cycling");
    }

    private static void radarTargetCycling() {
        int cycleAction = StabilizerControllerBlockEntity.INPUT_ACTION_GROUND_TARGET_CYCLE;
        require(cycleAction == 43 && !FireControlWeaponInputs.isSelection(cycleAction),
                "New ground radar input must not replace any weapon selection or existing radar input");
        require(StabilizerControllerBlockEntity.INPUT_ACTION_COUNT == FireControlWeaponInputs.ACTION_COUNT
                        && StabilizerControllerBlockEntity.WIRELESS_INPUT_ACTION_COUNT == FireControlWeaponInputs.ACTION_COUNT,
                "Ground target cycling must support both remote keys and wireless frequencies");
        UUID first = new UUID(0, 1), second = new UUID(0, 2), third = new UUID(0, 3);
        UUID member = new UUID(0, 4), missing = new UUID(0, 5);
        var contacts = List.of(contact(first, Set.of(first, member)), contact(second, Set.of(second)),
                contact(third, Set.of(third)));
        UUID current = null;
        for (UUID expected : List.of(first, second, third, first, second, third)) {
            current = RadarTargetCycle.next(contacts, current);
            require(expected.equals(current), "Each press must advance one contact and wrap after the last");
        }
        require(second.equals(RadarTargetCycle.next(contacts, member)),
                "A lock on a welded member must advance from its scanned structure");
        require(first.equals(RadarTargetCycle.next(contacts, missing)),
                "A vanished target must restart from the first scanned contact");
        require(first.equals(RadarTargetCycle.next(List.of(contacts.getFirst()), first)),
                "A single scanned target must stay selected");
        require(first.equals(RadarTargetCycle.next(List.of(), first))
                        && RadarTargetCycle.next(List.of(), null) == null,
                "An empty scan must not create or change a lock");
    }

    private static StabilizerControllerBlockEntity.RadarContact contact(UUID id, Set<UUID> members) {
        return new StabilizerControllerBlockEntity.RadarContact(id, Vec3.ZERO, Vec3.ZERO,
                new AABB(0, 0, 0, 1, 1, 1), members);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
