package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
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

        MianbaoAircraftCompat.WeaponType aircraftType = MianbaoAircraftCompat.typeOf(missile);
        boolean aircraft = aircraftType != null;
        boolean aircraftAdopted =
                missile.getPersistentData().getBoolean("CreateFireControlAircraftGuided");
        boolean adopted = missile.getPersistentData().getBoolean("CreateFireControlGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlAaGuided")
                || aircraftAdopted;
        // Persistent data is authoritative only on the server. On the client,
        // suppress the exact Mianbao missile collision cosmetically for the
        // same ten ticks; the server still decides whether it was adopted.
        int configuredSafety = missile.getPersistentData().getInt("CreateFireControlSafetyTicks");
        int aircraftFallback = aircraftType != null
                && !MianbaoAircraftCompat.isAirToAir(aircraftType) ? 20 : 6;
        int safetyTicks = aircraft
                ? (missile.getPersistentData().contains("CreateFireControlSafetyTicks")
                ? configuredSafety : aircraftFallback)
                : SAFETY_TICKS;
        boolean protectedFlight = missile.tickCount < safetyTicks
                && (adopted || missile.level().isClientSide());
        if (protectedFlight) {
            cir.setReturnValue(ProjectileDeflection.NONE);
            return;
        }
        if (adopted && !missile.level().isClientSide()) {
            MianbaoMissileEffects.broadcastExplosion(
                    missile, MianbaoMissileEffects.visualStrength(missile), true);
        }
    }
}
