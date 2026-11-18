package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.GuidedMissileMode;
import com.hooya.stabilizedturret.config.MissileSettings;
import com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight;
import com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Changes only the native range/upper-height comparisons, never discard or hit callbacks. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.entity.LegacyProjectileArrow", remap = false)
public abstract class MianbaoGuidedMissileLifetimeMixin {
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 1200))
    private int createFireControl$aircraftOwnsTimedExpiry(int ticks) {
        Entity missile = (Entity) (Object) this;
        if (LandAttackMissileFlight.managed(missile)) return MissileSettings.lifetimeTicks(missile);
        return AirToAirMissileFlight.managed(missile) ? AirToAirMissileFlight.fallbackLifetimeTicks(missile) : MissileSettings.adopted(missile) ? Math.max(ticks, MissileSettings.lifetimeTicks(missile)) : ticks;
    }
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/mcreator/myfirstmod/entity/LegacyProjectileArrow;distanceToFlightOriginSqr()D"))
    private double createFireControl$noFixedRange(@Coerce Entity missile, Operation<Double> original) {
        return createFireControl$hasGuidanceLifetime() ? 0 : original.call(missile);
    }

    // The slice contains only the Y read used by the upper-height comparison.
    // This alters the comparison operand, never the entity's actual position.
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/mcreator/myfirstmod/entity/LegacyProjectileArrow;getY()D"),
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"),
                    to = @At(value = "INVOKE",
                            target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I")))
    private double createFireControl$noBuildingCeiling(double y) {
        Entity missile = (Entity) (Object) this;
        return createFireControl$hasGuidanceLifetime() ? Math.min(y, missile.level().getMaxBuildHeight()) : y;
    }

    @Unique
    private boolean createFireControl$hasGuidanceLifetime() {
        Entity missile = (Entity) (Object) this;
        var data = missile.getPersistentData();
        return GuidedMissileMode.eligible(missile)
                && (data.getBoolean("CreateFireControlAircraftGuided")
                || data.getBoolean("CreateFireControlAaGuided")
                || data.getBoolean("CreateFireControlDisplayGuided")
                || data.getBoolean("CreateFireControlCruiseGuided"));
    }
}
