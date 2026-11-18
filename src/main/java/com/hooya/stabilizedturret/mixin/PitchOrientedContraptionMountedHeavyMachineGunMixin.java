package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.compat.mianbao.MountedHeavyMachineGunCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/** Hooks the common CBC entity, so every cannon and mount family is covered. */
@Mixin(PitchOrientedContraptionEntity.class)
public abstract class PitchOrientedContraptionMountedHeavyMachineGunMixin {
    @Inject(method = "handlePlayerInteraction", at = @At("HEAD"), cancellable = true)
    private void createFireControl$mountOrAdjustHeavyMachineGun(
            Player player,
            BlockPos localPos,
            Direction clickedFace,
            InteractionHand hand,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (MountedHeavyMachineGunCompat.handleInteraction(
                (PitchOrientedContraptionEntity) (Object) this,
                player, localPos, clickedFace, hand)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "tryFiringShot", at = @At("HEAD"))
    private void createFireControl$fireMountedHeavyMachineGuns(CallbackInfo ci) {
        MountedHeavyMachineGunCompat.tickFromCannonMount(
                (PitchOrientedContraptionEntity) (Object) this, true);
    }

    @Inject(method = "tickContraption", at = @At("TAIL"))
    private void createFireControl$tickMountedHeavyMachineGuns(CallbackInfo ci) {
        MountedHeavyMachineGunCompat.tickFromCannonMount(
                (PitchOrientedContraptionEntity) (Object) this);
    }
}
