package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight;
import com.hooya.stabilizedturret.config.MissileSettings;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;

/** Keep native particles/motion intact; change only adopted AAM clocks, burn windows and native timeouts. */
@Pseudo
@Mixin(targets = {"net.mcreator.myfirstmod.procedures.FarMissile1trailProcedure",
        "net.mcreator.myfirstmod.procedures.Missile2trailProcedure",
        "net.mcreator.myfirstmod.procedures.CloseMissile1trailProcedure"}, remap = false)
public abstract class MianbaoAirToAirTrailTimingMixin {
    @ModifyConstant(method = "execute", constant = {@Constant(intValue = 100), @Constant(intValue = 200), @Constant(intValue = 300)},
            slice = @Slice(to = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V")), require = 0)
    private static int createFireControl$burnTicks(int value, LevelAccessor level, double x, double y, double z, Entity missile) {
        return AirToAirMissileFlight.managed(missile)
                ? MissileSettings.poweredTicks(missile) : value;
    }

    @ModifyConstant(method = "execute", constant = {@Constant(doubleValue = 100), @Constant(doubleValue = 200), @Constant(doubleValue = 300)},
            slice = @Slice(to = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V")), require = 0)
    private static double createFireControl$burnTicksDouble(double value, LevelAccessor level, double x, double y, double z, Entity missile) {
        return createFireControl$burnTicks((int) value, level, x, y, z, missile);
    }

    @ModifyConstant(method = "execute", constant = {@Constant(intValue = 220), @Constant(intValue = 320), @Constant(intValue = 420)}, require = 0)
    private static int createFireControl$deviceOwnsExpiry(int value, LevelAccessor level, double x, double y, double z, Entity missile) {
        return AirToAirMissileFlight.managed(missile) ? Integer.MAX_VALUE : value;
    }

    @ModifyConstant(method = "execute", constant = {@Constant(doubleValue = 220), @Constant(doubleValue = 320), @Constant(doubleValue = 420)}, require = 0)
    private static double createFireControl$deviceOwnsExpiryDouble(double value, LevelAccessor level, double x, double y, double z, Entity missile) {
        return AirToAirMissileFlight.managed(missile) ? Double.MAX_VALUE : value;
    }

    @ModifyExpressionValue(method = "execute", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;tickCount:I"), require = 0)
    private static int createFireControl$poweredTick(int age, LevelAccessor level, double x, double y, double z, Entity missile) {
        return AirToAirMissileFlight.managed(missile) ? AirToAirMissileFlight.nativeTickAge(missile) : age;
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/CompoundTag;getDouble(Ljava/lang/String;)D"), require = 0)
    private static double createFireControl$poweredEngine(CompoundTag data, String key, Operation<Double> original,
                                                        LevelAccessor level, double x, double y, double z, Entity missile) {
        return key.equals("fadongji1") && AirToAirMissileFlight.managed(missile)
                ? Math.max(0, AirToAirMissileFlight.poweredAge(missile)) : original.call(data, key);
    }
}
