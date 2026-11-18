package com.hooya.stabilizedturret.content.missile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Queryable gameplay registry for virtual infrared decoys. */
public final class FlareManager {
    private final ServerLevel level;
    private final Map<UUID, VirtualFlare> flares = new LinkedHashMap<>();
    FlareManager(ServerLevel level) { this.level = level; }

    void maybeDispense(VirtualMissile missile) {
        if (!MissilePhysicsConfig.FLARE_ENABLED.get()
                || !phaseAllows(missile.phase())
                || missile.age() % MissilePhysicsConfig.FLARE_BURST_INTERVAL.get() != 0) return;
        RandomSource random = level.getRandom();
        double dispersion = MissilePhysicsConfig.FLARE_DISPERSION.get() / 20.0;
        double backward = MissilePhysicsConfig.FLARE_BACKWARD_IMPULSE.get() / 20.0;
        for (int i = 0; i < MissilePhysicsConfig.FLARE_COUNT_PER_BURST.get(); i++) {
            Vec3 spread = new Vec3(random.nextGaussian(), random.nextGaussian(),
                    random.nextGaussian()).scale(dispersion);
            Vec3 velocity = missile.velocity().subtract(
                    missile.direction().scale(backward)).add(spread);
            UUID id = UUID.randomUUID();
            flares.put(id, new VirtualFlare(id, missile.position(), velocity,
                    level.getGameTime(), MissilePhysicsConfig.FLARE_LIFETIME.get(),
                    MissilePhysicsConfig.FLARE_HEAT.get().floatValue(),
                    random.nextLong(), 0.98, 0.04));
        }
    }

    private static boolean phaseAllows(VirtualMissile.Phase phase) {
        return switch (MissilePhysicsConfig.FLARE_START_PHASE.get()) {
            case BOOST -> phase == VirtualMissile.Phase.BOOST
                    || phase == VirtualMissile.Phase.MIDCOURSE
                    || phase == VirtualMissile.Phase.TERMINAL;
            case MIDCOURSE -> phase == VirtualMissile.Phase.MIDCOURSE
                    || phase == VirtualMissile.Phase.TERMINAL;
            case TERMINAL -> phase == VirtualMissile.Phase.TERMINAL;
        };
    }

    void tick() { flares.values().removeIf(flare -> flare.expired(level.getGameTime())); }
    public Collection<VirtualFlare> all() { return List.copyOf(flares.values()); }
    public Collection<VirtualFlare> nearby(Vec3 position, double radius) {
        double radiusSqr = radius * radius;
        return flares.values().stream().filter(flare ->
                flare.positionAt(level.getGameTime()).distanceToSqr(position) <= radiusSqr).toList();
    }
}
