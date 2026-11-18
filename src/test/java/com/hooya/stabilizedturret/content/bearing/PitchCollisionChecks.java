package com.hooya.stabilizedturret.content.bearing;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PitchCollisionChecks {
    public static void main(String[] args) {
        UUID carrier = id(1), deck = id(2), yaw = id(3), pitch = id(4), barrel = id(5), sight = id(6), sibling = id(7);
        Map<UUID, Set<UUID>> welds = Map.of(carrier, Set.of(deck), deck, Set.of(carrier),
                pitch, Set.of(barrel), barrel, Set.of(pitch, sight), sight, Set.of(barrel));
        require(PitchStructureCollision.selectBodies(Set.of(pitch), Set.of(carrier, yaw), welds)
                .equals(Set.of(pitch, barrel, sight)), "pitch plate, welded barrel and sight only");
        require(PitchStructureCollision.selectBodies(Set.of(pitch, sibling), Set.of(carrier, yaw), welds)
                .equals(Set.of(pitch, barrel, sight, sibling)), "independent pitch bindings coexist");
        require(PitchStructureCollision.selectBodies(Set.of(), Set.of(carrier), welds).isEmpty(),
                "unbound/legacy/yaw-only state restores all owned collisions");
        require(PitchStructureCollision.selectBodies(Set.of(deck), Set.of(carrier), welds).isEmpty(),
                "a fixed bypass never disables the carrier's rigid group");
        require(PitchStructureCollision.selectBodies(Set.of(sight), Set.of(carrier, yaw), welds)
                .equals(Set.of(pitch, barrel, sight)), "weld selection is independent of member order");
        System.out.println("PASS: pitch-only fixed-weld collision selection, carrier protection and binding removal");
    }

    private static UUID id(long value) { return new UUID(0, value); }
    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
