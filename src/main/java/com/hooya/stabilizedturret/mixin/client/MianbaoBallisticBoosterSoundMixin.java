package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** The existing booster loop stops on burnout, without silencing launch or impact sounds. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.client.sound.BallisticMissileBoosterSound", remap = false)
public abstract class MianbaoBallisticBoosterSoundMixin {
    @WrapOperation(method = {"tick", "canPlaySound"}, at = @At(value = "INVOKE",
            target = "Lnet/mcreator/myfirstmod/entity/BallisticMissileEntity;isIgnited()Z"))
    private boolean createFireControl$poweredSound(@Coerce Entity missile, Operation<Boolean> original) {
        return original.call(missile) && LandAttackMissileFlight.emitting(missile);
    }
}
