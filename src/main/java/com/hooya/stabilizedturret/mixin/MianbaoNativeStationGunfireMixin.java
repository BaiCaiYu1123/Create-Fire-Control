package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.compat.mianbao.MianbaoNativeGunfire;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leave native ammunition, heat, cadence, effects and damage intact. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.remote.RemoteWeaponStationFireService", remap = false)
public abstract class MianbaoNativeStationGunfireMixin {
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void createFireControl$ready(CallbackInfo ci) {
        MianbaoNativeGunfire.stationHookReady();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private static void createFireControl$sampleOnce(@Coerce Object station, CallbackInfo ci) {
        if (!MianbaoNativeGunfire.beginStationTick(station)) ci.cancel();
    }

    @ModifyVariable(method = "tick", at = @At("STORE"), ordinal = 0)
    private static boolean createFireControl$trigger(boolean nativeTrigger) {
        return MianbaoNativeGunfire.stationTrigger(nativeTrigger);
    }

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/mcreator/myfirstmod/block/entity/HeavyCannonWeaponStationBlockEntity;isAntiTankMissileSelected()Z"))
    private static boolean createFireControl$gunNotMissile(boolean selected) {
        return MianbaoNativeGunfire.stationMissileSelected(selected);
    }

    @ModifyExpressionValue(method = "tick", at = @At(value = "FIELD",
            target = "Lnet/mcreator/myfirstmod/remote/RemoteWeaponStationFireService$ThermalState;missileTriggerLatched:Z",
            opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private static boolean createFireControl$independentGunLatch(boolean latched) {
        return MianbaoNativeGunfire.stationMissileSelected(latched);
    }
}
