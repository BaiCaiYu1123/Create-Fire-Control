package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.compat.mianbao.MountedHeavyMachineGunCompat;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.cannon_control.carriage.CannonCarriageEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/** Lets a ridden cannon carriage pulse its attached HMGs with the normal fire input. */
@Mixin(CannonCarriageEntity.class)
public abstract class CannonCarriageMountedHeavyMachineGunMixin {
    @Shadow private @Nullable PitchOrientedContraptionEntity cannonContraption;

    @Inject(method = "tryFiringShot", at = @At("HEAD"))
    private void createFireControl$fireMountedHeavyMachineGuns(CallbackInfo ci) {
        MountedHeavyMachineGunCompat.tickFromCannonMount(cannonContraption, true);
    }
}
