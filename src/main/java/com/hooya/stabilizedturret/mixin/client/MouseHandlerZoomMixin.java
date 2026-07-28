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
 * Extends only the bound operator's Sable orbit-camera near zoom. This wraps
 * the same post-vanilla scroll value used by Sable and consumes it before
 * Sable's normal 0..4 setter can clamp away the negative extension.
 */
@Mixin(value = MouseHandler.class, priority = 900)
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
