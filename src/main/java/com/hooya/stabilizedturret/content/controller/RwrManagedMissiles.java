package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Only the fire-control adoption paths register missiles here; no entity-type scanning. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class RwrManagedMissiles {
    private static final Map<Entity, Set<UUID>> MANAGED = new WeakHashMap<>();

    public static void track(Entity missile, Set<UUID> launcherStructure) {
        if (missile != null && !missile.level().isClientSide) {
            MANAGED.put(missile, Set.copyOf(launcherStructure));
        }
    }

    public static List<RwrThreat> collect(StabilizerControllerBlockEntity receiver,
                                          Set<UUID> structure, AABB bounds) {
        List<RwrThreat> found = new ArrayList<>();
        Vec3 origin = receiver.getRadarOrigin();
        double rangeSqr = receiver.getRwrRange() * receiver.getRwrRange();
        MANAGED.entrySet().removeIf(entry -> entry.getKey().isRemoved());
        for (Map.Entry<Entity, Set<UUID>> entry : MANAGED.entrySet()) {
            Entity missile = entry.getKey();
            if (!missile.isAlive()
                    || missile.level() != receiver.getLevel()
                    || entry.getValue().stream().anyMatch(structure::contains)
                    || missile.getPersistentData().getBoolean("CreateFireControlCountermeasureDecoyed")) continue;
            Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
            if (position.distanceToSqr(origin) <= rangeSqr
                    && RwrMissileApproach.isIncoming(position,
                    MianbaoMissileCompat.getWorldVelocity(missile), bounds,
                    receiver.getAircraftVelocity())) {
                found.add(new RwrThreat(missile.getUUID(), RwrThreat.Type.MSL, position, true));
            }
        }
        return found;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MANAGED.clear();
    }

    private RwrManagedMissiles() {}
}
