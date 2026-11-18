package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoCountermeasureCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoCruiseMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileDetonation;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileEffects;
import com.hooya.stabilizedturret.content.controller.MianbaoRocketPodCompat;
import com.hooya.stabilizedturret.content.controller.MissileFuseSafety;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Projectile.class, priority = 2000)
public abstract class MissileSafetyMixin {
    private static final int SAFETY_TICKS = 10;

    @Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"), cancellable = true)
    private void createFireControl$missileSafetyAndLongRangeExplosion(
            HitResult hitResult,
            CallbackInfoReturnable<ProjectileDeflection> cir
    ) {
        Entity missile = (Entity) (Object) this;
        if (!MianbaoAirDefenseCompat.isAnyFireControlMissile(missile)) return;

        // A flare is a guidance target only. Letting the native projectile
        // collision path treat it as a hit would detonate the missile before
        // the guidance handler can continue the chase.
        if (hitResult instanceof EntityHitResult entityHit
                && MianbaoCountermeasureCompat.isCountermeasure(entityHit.getEntity())) {
            cir.setReturnValue(ProjectileDeflection.NONE);
            return;
        }

        if (missile.getPersistentData().getBoolean(
                "CreateFireControlCountermeasureDecoyed")
                && missile.tickCount <= missile.getPersistentData().getInt(
                "CreateFireControlCountermeasureGraceUntil")) {
            cir.setReturnValue(ProjectileDeflection.NONE);
            return;
        }

        MianbaoAircraftCompat.WeaponType aircraftType = MianbaoAircraftCompat.typeOf(missile);
        boolean aircraft = aircraftType != null;
        boolean aircraftAdopted =
                missile.getPersistentData().getBoolean("CreateFireControlAircraftGuided");
        boolean rocket = MianbaoRocketPodCompat.isRocket(missile);
        boolean adopted = missile.getPersistentData().getBoolean("CreateFireControlGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlAaGuided")
                || aircraftAdopted
                || missile.getPersistentData().getBoolean(MianbaoRocketPodCompat.ADOPTED_TAG)
                || missile.getPersistentData().getBoolean("CreateFireControlCruiseGuided");
        // Persistent data is authoritative only on the server. On the client,
        // suppress the exact Mianbao missile collision cosmetically for the
        // same ten ticks; the server still decides whether it was adopted.
        int configuredSafety = missile.getPersistentData().getInt("CreateFireControlSafetyTicks");
        int aircraftFallback = aircraftType != null
                && !MianbaoAircraftCompat.isAirToAir(aircraftType) ? 20 : 6;
        int safetyTicks = rocket
                ? (missile.getPersistentData().contains("CreateFireControlSafetyTicks")
                ? configuredSafety : SAFETY_TICKS)
                : aircraft
                ? (missile.getPersistentData().contains("CreateFireControlSafetyTicks")
                ? configuredSafety : aircraftFallback)
                : SAFETY_TICKS;
        boolean protectedFlight = !MissileFuseSafety.armed(missile, safetyTicks)
                && (adopted || missile.level().isClientSide());
        if (protectedFlight) {
            cir.setReturnValue(ProjectileDeflection.NONE);
            return;
        }
        if (adopted && !missile.level().isClientSide()) {
            if (MianbaoAirDefenseCompat.isMissile(missile)
                    || MianbaoMissileDetonation.usesTripledStrength(aircraftType)) {
                if (MianbaoAirDefenseCompat.isMissile(missile)
                        && missile.getPersistentData().getBoolean(
                        "CreateFireControlDisplayGuided")) {
                    MianbaoMissileDetonation.detonateWithoutDrops(missile);
                } else {
                    MianbaoMissileDetonation.detonate(missile);
                }
                cir.setReturnValue(ProjectileDeflection.NONE);
                return;
            }
            if (MianbaoCruiseMissileCompat.isMissile(missile)) {
                Vec3 localImpact = hitResult.getLocation();
                Vec3 position = MianbaoMissileCompat.getWorldPosition(missile, localImpact);
                MianbaoCruiseMissileCompat.detonate(missile, position);
                cir.setReturnValue(ProjectileDeflection.NONE);
                return;
            }
            if (rocket) {
                Vec3 localImpact = hitResult.getLocation();
                Vec3 position = MianbaoMissileCompat.getWorldPosition(missile, localImpact);
                if (MianbaoRocketPodCompat.deferDetonation(missile, position)) {
                    cir.setReturnValue(ProjectileDeflection.NONE);
                }
                return;
            }
            MianbaoMissileEffects.broadcastExplosion(
                    missile, MianbaoMissileEffects.visualStrength(missile), true);
        }
    }
}
