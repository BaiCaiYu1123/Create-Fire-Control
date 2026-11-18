package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseLauncher;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Allow hopper automation to load ordinary AA rounds into the existing firing slot. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.procedures.Antiairmissilelauncherlogic1Procedure", remap = false)
public abstract class MianbaoOrdinaryAaInventoryMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void createFireControl$ordinaryRound(ItemStack stack, double index, CallbackInfoReturnable<Boolean> cir) {
        if (index == 0.0 && OrdinaryAirDefenseLauncher.isRound(stack)) cir.setReturnValue(true);
    }
}
