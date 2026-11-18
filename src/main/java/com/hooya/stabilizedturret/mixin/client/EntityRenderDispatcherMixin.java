package com.hooya.stabilizedturret.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.hooya.stabilizedturret.client.MissileLongRangeEffects;
import com.hooya.stabilizedturret.client.renderer.Df26BallisticMissileEntityVisualRenderer;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoCountermeasureCompat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Narrow distance override for supported Mianbao missiles. */
@Mixin(value = EntityRenderDispatcher.class, priority = 2000)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void stabilizedTurret$trackMissileRender(
            E entity,
            double x,
            double y,
            double z,
            float yaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int packedLight,
            CallbackInfo ci
    ) {
        if (com.hooya.stabilizedturret.client.VirtualScopeVisibility.hides(entity)
                || com.hooya.stabilizedturret.client.ClientWeaponViewState.suppressDuplicate(entity)
                || MissileLongRangeEffects.shouldSuppressNativeMissile(entity)) {
            ci.cancel();
            return;
        }
        MissileLongRangeEffects.onMissileRendered(entity);
        if (MissileLongRangeEffects.isDf26Visual(entity)
                && Df26BallisticMissileEntityVisualRenderer.render(
                entity, x, y, z, poseStack, buffers, packedLight)) {
            ci.cancel();
        }
    }

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void stabilizedTurret$forceSupportedMissileVisibility(
            E entity,
            Frustum frustum,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!MianbaoAirDefenseCompat.isAnyFireControlMissile(entity)
                && !MianbaoCountermeasureCompat.isCountermeasure(entity)) {
            return;
        }

        // Only these exact ATGM entity types bypass distance/frustum culling.
        // Their lifetime is capped server-side, so render work cannot
        // accumulate permanently.
        cir.setReturnValue(true);
    }
}
