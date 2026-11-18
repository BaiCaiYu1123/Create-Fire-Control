package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoRemoteWeaponStationDirectAimCompat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Repairs native direct and controller-item aim on a Sable craft. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.remote.RemoteWeaponStationSessionManager",
        remap = false)
public abstract class MianbaoRemoteWeaponStationSessionAimMixin {
    @Inject(
            method = "onPlayerTick(Lnet/neoforged/neoforge/event/tick/PlayerTickEvent$Post;)V",
            at = @At("TAIL"),
            require = 1
    )
    private static void createFireControl$writeFinalSableAim(
            PlayerTickEvent.Post event, CallbackInfo ci
    ) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MianbaoRemoteWeaponStationDirectAimCompat.applyIndependentAim(player);
        }
    }
}
