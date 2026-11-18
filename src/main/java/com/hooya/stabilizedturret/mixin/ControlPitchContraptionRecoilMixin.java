package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.compat.cbc.CannonRecoilCompat;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "rbasamoyai.createbigcannons.cannon_control.ControlPitchContraption", remap = false)
public interface ControlPitchContraptionRecoilMixin {
    @Inject(method = "onRecoil(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;)V",
            at = @At("HEAD"), cancellable = true)
    default void createFireControl$boundRecoil(
            Vec3 recoil, Vec3 point, AbstractContraptionEntity cannon, CallbackInfo ci
    ) {
        if (CannonRecoilCompat.applyBoundRecoil(cannon, point, recoil)) ci.cancel();
    }
}
