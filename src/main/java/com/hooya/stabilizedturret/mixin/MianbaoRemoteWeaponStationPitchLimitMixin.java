package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoRemoteWeaponStationCompat;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets fire control replace Mianbao's internal pitch clamp for its own write. */
@Pseudo
@Mixin(targets = {
        "net.mcreator.myfirstmod.block.entity.RemoteWeaponStationBlockEntity",
        "net.mcreator.myfirstmod.block.entity.HeavyCannonWeaponStationBlockEntity"
},
        remap = false)
public abstract class MianbaoRemoteWeaponStationPitchLimitMixin {
    @Inject(method = "setRotationDegrees", at = @At("HEAD"),
            cancellable = true, require = 0)
    private void createFireControl$rejectCompetingAimWriter(
            float yawDegrees,
            float pitchDegrees,
            CallbackInfoReturnable<Boolean> cir
    ) {
        BlockEntity station = (BlockEntity) (Object) this;
        if (!MianbaoRemoteWeaponStationCompat.isFireControlWriteActive()
                && MianbaoRemoteWeaponStationCompat.isHeavyCannonStation(
                station.getLevel(), station.getBlockPos())
                && ControllerTracker.shouldBlockCompetingHeavyCannonAim(
                station.getLevel(), station.getBlockPos())) {
            cir.setReturnValue(true);
        }
    }

    @Redirect(
            method = "setRotationDegrees",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/Mth;clamp(FFF)F"),
            require = 0
    )
    private float createFireControl$useControllerPitchEnvelope(
            float pitchDegrees, float nativeMinimum, float nativeMaximum
    ) {
        return MianbaoRemoteWeaponStationCompat.clampPitchForActiveFireControl(
                pitchDegrees, nativeMinimum, nativeMaximum);
    }
}
