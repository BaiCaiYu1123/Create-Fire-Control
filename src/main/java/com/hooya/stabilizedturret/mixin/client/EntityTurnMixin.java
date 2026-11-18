package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.ClientTelevisionGuidanceState;
import com.hooya.stabilizedturret.client.MianbaoRemoteWeaponStationClientCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps mouse input in an independent world-space first-person orientation. */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$turnWorldStableView(double yaw, double pitch, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        Minecraft minecraft = Minecraft.getInstance();
        if ((self instanceof LocalPlayer || self == minecraft.getCameraEntity())
                && com.hooya.stabilizedturret.client.ClientWeaponViewState.turn(yaw, pitch)) {
            ci.cancel();
            return;
        }
        if (ClientTelevisionGuidanceState.isActive()
                && (self instanceof LocalPlayer || self == minecraft.getCameraEntity())
                && ClientTelevisionGuidanceState.turn(yaw, pitch)) {
            ci.cancel();
            return;
        }
        if (self instanceof LocalPlayer player
                && MianbaoRemoteWeaponStationClientCompat.turn(
                player, yaw, pitch)) {
            ci.cancel();
            return;
        }
        if (self instanceof LocalPlayer player
                && ClientAimState.turnStableFirstPerson(player, yaw, pitch)) {
            ci.cancel();
        }
    }
}
