package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.MianbaoRemoteWeaponStationClientCompat;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exposes Mianbao's native direct-control state to Sable camera compatibility. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.client.RemoteWeaponStationRemoteClient",
        remap = false)
public abstract class MianbaoRemoteWeaponStationRemoteClientMixin {
    @Inject(
            method = "accept(ZZLnet/minecraft/core/BlockPos;IFF)V",
            at = @At("HEAD"),
            require = 0
    )
    private static void createFireControl$captureNativeControlState(
            boolean active,
            boolean optical,
            BlockPos stationPos,
            int baseFacing,
            float yaw,
            float pitch,
            CallbackInfo ci
    ) {
        MianbaoRemoteWeaponStationClientCompat.accept(
                active, optical, stationPos, baseFacing, yaw, pitch);
    }

}
