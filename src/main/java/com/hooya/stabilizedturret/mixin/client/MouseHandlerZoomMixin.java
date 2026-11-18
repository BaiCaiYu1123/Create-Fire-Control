package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.SableCameraZoomCompat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replaces Sable's limited scroll handler in both contraption camera modes
 * while the player is seated on a physical structure. The higher priority
 * makes this wrapper run before Sable's own wrapper, so its 0..4 setter is
 * never reached for these views.
 */
@Mixin(value = MouseHandler.class, priority = 2000)
public abstract class MouseHandlerZoomMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @WrapOperation(
            method = "onScroll",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;swapPaint(D)V"
            )
    )
    private void stabilizedTurret$extendBoundSableZoom(
            Inventory inventory,
            double scrollDelta,
            Operation<Void> original
    ) {
        if (SableCameraZoomCompat.applyScroll(
                minecraft,
                minecraft.gameRenderer.getMainCamera(),
                scrollDelta
        )) {
            return;
        }
        original.call(inventory, scrollDelta);
    }
}
