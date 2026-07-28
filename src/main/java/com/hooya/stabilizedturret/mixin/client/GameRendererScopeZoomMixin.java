package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererScopeZoomMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void stabilizedTurret$applyScopeMagnification(
            Camera camera,
            float partialTick,
            boolean useFovSetting,
            CallbackInfoReturnable<Double> cir
    ) {
        if (!ClientAimState.isOpticalView()) {
            return;
        }
        double baseFov = cir.getReturnValue();
        double zoom = ClientAimState.getScopeZoom();
        double magnifiedFov = Math.toDegrees(2.0 * Math.atan(
                Math.tan(Math.toRadians(baseFov) * 0.5) / zoom
        ));
        cir.setReturnValue(magnifiedFov);
    }
}
