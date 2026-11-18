package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.RemoteImpactRenderManager;
import com.hooya.stabilizedturret.client.MathematicalMissileVisuals;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererRemoteImpactMixin {
    @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
    private void stabilizedTurret$extendRemoteImpactFarPlane(
            CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(Math.max(cir.getReturnValue(), Math.max(
                RemoteImpactRenderManager.requiredFarDistance(), Math.max(MathematicalMissileVisuals.antiRadiationFarDistance(),
                        com.hooya.stabilizedturret.client.ClientWeaponViewState.requiredTrailFarDistance()))));
    }
}
