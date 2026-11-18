package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class MianbaoMissileDetonation {
    public static final float STANDARD_STRENGTH = 3.0F;
    /** All five aircraft missiles use native strength 6.0. */
    public static final float TRIPLED_STRENGTH = 6.0F * 3.0F;
    private static final String DETONATED_TAG = "CreateFireControlDetonated";
    static final String CAUSE_TAG = "CreateFireControlDetonationCause";

    public static void detonate(Entity missile) {
        detonate(missile, MianbaoMissileCompat.getWorldPosition(missile));
    }

    public static void detonate(Entity missile, Vec3 position) {
        detonate(missile, position, explosionStrength(missile));
    }

    public static void detonate(Entity missile, float strength) {
        detonate(missile, MianbaoMissileCompat.getWorldPosition(missile), strength);
    }

    public static void detonate(Entity missile, Vec3 position, float strength) {
        if (!beginDetonation(missile, position)) return;
        MianbaoMissileEffects.broadcastExplosion(missile, position, strength, false);
        ServerLevel level = MianbaoMissileCompat.getRootServerLevel(missile);
        if (level != null && MianbaoMissileCompat.areRootChunksLoaded(missile, position, strength)) {
            level.explode(null, position.x, position.y, position.z, strength,
                    Level.ExplosionInteraction.BLOCK);
            applyTripledNativeDamage(level, missile, position);
        }
        missile.discard();
    }

    public static void detonateWithoutDrops(Entity missile) {
        detonateWithoutDrops(missile, MianbaoMissileCompat.getWorldPosition(missile),
                explosionStrength(missile));
    }

    public static void detonateWithoutDrops(Entity missile, Vec3 position, float strength) {
        if (!beginDetonation(missile, position)) return;
        MianbaoMissileEffects.broadcastExplosion(missile, position, strength, false);
        ServerLevel level = MianbaoMissileCompat.getRootServerLevel(missile);
        if (level != null) {
            MianbaoDeferredDetonation.explodeWithoutDrops(missile, position, strength);
            applyTripledNativeDamage(level, missile, position);
        }
        missile.discard();
    }

    public static float explosionStrength(Entity missile) {
        if (MianbaoAirDefenseCompat.isMissile(missile)) {
            return MianbaoAirDefenseCompat.nativeExplosionStrength(missile) * 3.0F;
        }
        return aircraftStrength(MianbaoAircraftCompat.typeOf(missile));
    }

    private static boolean beginDetonation(Entity missile, Vec3 position) {
        if (missile == null || missile.level().isClientSide()
                || missile.getPersistentData().getBoolean(DETONATED_TAG)) return false;
        var data = missile.getPersistentData();
        if (MissileFuseSafety.protectedAircraft(missile)) {
            if (!data.getBoolean("CreateFireControlInsuranceBlocked")) {
                data.putBoolean("CreateFireControlInsuranceBlocked", true);
                StabilizedTurret.LOGGER.warn("Suppressed unarmed aircraft detonation entity={} elapsedTicks={} safetyTicks={} position={}",
                        missile.getUUID(), MissileFuseSafety.elapsedTicks(missile),
                        MissileFuseSafety.aircraftSafetyTicks(missile), position);
            }
            return false;
        }
        data.putBoolean(DETONATED_TAG, true);
        if (data.getBoolean("CreateFireControlAircraftGuided")) {
            String cause = data.getString(CAUSE_TAG);
            StabilizedTurret.LOGGER.info("Detonating aircraft missile type={} entity={} flightAge={} fuseElapsedTicks={} safetyTicks={} cause={} position={}",
                    MianbaoAircraftCompat.typeOf(missile), missile.getUUID(), missile.tickCount,
                    MissileFuseSafety.elapsedTicks(missile), MissileFuseSafety.aircraftSafetyTicks(missile),
                    cause.isEmpty() ? "impact-or-external" : cause, position);
        }
        return true;
    }

    public static float aircraftStrength(MianbaoAircraftCompat.WeaponType type) {
        if (MianbaoAircraftCompat.isAntiRadiation(type)) return 6.0F;
        return usesTripledStrength(type) ? TRIPLED_STRENGTH : STANDARD_STRENGTH;
    }

    public static boolean usesTripledStrength(MianbaoAircraftCompat.WeaponType type) {
        return type != null && (MianbaoAircraftCompat.isAirToAir(type)
                || MianbaoAircraftCompat.isAirToGround(type)
                || MianbaoAircraftCompat.isOpticalGuided(type));
    }

    /**
     * Mianbao adds unoccluded radial damage after its vanilla explosion. Preserve that
     * behavior while applying the requested multiplier. Anti-air damage range is also
     * tripled; AGM range stays native because only its power was requested to change.
     */
    private static void applyTripledNativeDamage(
            ServerLevel level, Entity missile, Vec3 position
    ) {
        double radius;
        float damage;
        if (MianbaoAirDefenseCompat.isMissile(missile)) {
            radius = 7.5 * 3.0;
            damage = 30.0F * 3.0F;
        } else {
            MianbaoAircraftCompat.WeaponType type = MianbaoAircraftCompat.typeOf(missile);
            if (MianbaoAircraftCompat.isAirToAir(type)) {
                radius = 10.5 * 3.0;
                damage = 40.0F * 3.0F;
            } else if (MianbaoAircraftCompat.isAirToGround(type)
                    || MianbaoAircraftCompat.isOpticalGuided(type)) {
                radius = 14.5;
                damage = 45.0F * 3.0F;
            } else {
                return;
            }
        }
        DamageSource source = new DamageSource(level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(DamageTypes.EXPLOSION));
        AABB area = new AABB(position, position).inflate(radius);
        for (Entity target : level.getEntities(missile, area, target -> !target.isRemoved())) {
            target.hurt(source, damage);
        }
    }

    private MianbaoMissileDetonation() {
    }
}
