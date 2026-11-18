package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.content.display.AutoDefenseMissileGuidance;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.missile.MissileManager;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Builds one bounded RWR snapshot for the seated aircraft operator. */
public final class RwrThreatCollector {
    private static final int MAX_THREATS = 64;

    public static List<RwrThreat> collect(StabilizerControllerBlockEntity receiver) {
        if (receiver == null || receiver.getLevel() == null
                || !receiver.isAircraftRadarActive()) return List.of();
        Set<UUID> structure = receiver.getOwnControlledSubLevelIds();
        if (structure.isEmpty()) return List.of();
        Vec3 origin = receiver.getRadarOrigin();
        double range = receiver.getRwrRange();
        List<RwrThreat> found = new ArrayList<>();
        found.addAll(ControllerTracker.collectRwrRadarThreats(
                receiver, structure, origin, range));
        found.addAll(ConnectedDisplayBlockEntity.collectRwrRadarThreats(
                receiver.getLevel(), structure, origin, range));
        found.addAll(MianbaoAircraftGuidanceHandler.collectRwrMissileThreats(
                receiver.getLevel(), structure, origin, range));
        found.addAll(MianbaoAirDefenseGuidanceHandler.collectRwrMissileThreats(
                receiver.getLevel(), structure, origin, range));
        found.addAll(AutoDefenseMissileGuidance.collectRwrMissileThreats(
                receiver.getLevel(), structure, origin, range));
        var bounds = receiver.getRwrReceiverBounds();
        found.addAll(RwrManagedMissiles.collect(receiver, structure, bounds));
        found.addAll(MissileManager.collectRwrMissileThreats(receiver, structure, bounds));

        Map<UUID, RwrThreat> unique = new LinkedHashMap<>();
        found.stream()
                .sorted(Comparator.comparing((RwrThreat threat) -> threat.type() != RwrThreat.Type.MSL)
                        .thenComparingDouble(threat -> threat.position().distanceToSqr(origin)))
                .forEach(threat -> unique.putIfAbsent(threat.id(), threat));
        return unique.values().stream().limit(MAX_THREATS).toList();
    }

    private RwrThreatCollector() {
    }
}
