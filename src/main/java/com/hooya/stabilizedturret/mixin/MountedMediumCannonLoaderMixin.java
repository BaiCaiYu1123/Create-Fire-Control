package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.loader.MzLoaderAutomation;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption;

/** Hooks the one successful-shot point at which CBC Neo Warfare creates the casing. */
@Mixin(targets = "riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption",
        remap = false)
public abstract class MountedMediumCannonLoaderMixin {
    @Inject(
            method = "fireShot",
            at = @At(
                    value = "INVOKE",
                    target = "Lriftyboi/cbcmodernwarfare/cannons/medium_cannon/breech/MediumcannonBreechBlockEntity;setInputBuffer(Lnet/minecraft/world/item/ItemStack;)V",
                    shift = At.Shift.AFTER
            ),
            require = 1
    )
    private void stabilizedTurret$runBoundLoader(
            ServerLevel level,
            PitchOrientedContraptionEntity entity,
            CallbackInfo ci
    ) {
        MzLoaderAutomation.onCannonFired(
                (MountedMediumcannonContraption) (Object) this, entity);
    }
}
