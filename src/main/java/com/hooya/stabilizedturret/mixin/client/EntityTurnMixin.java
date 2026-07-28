package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
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
        if ((Object) this instanceof LocalPlayer player
                && ClientAimState.turnStableFirstPerson(player, yaw, pitch)) {
            ci.cancel();
        }
    }
}
