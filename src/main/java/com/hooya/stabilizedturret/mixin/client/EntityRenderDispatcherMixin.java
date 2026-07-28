package com.hooya.stabilizedturret.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.MissileLongRangeEffects;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scope visibility plus a narrowly-scoped distance override for the exact
 * Mianbao ATGM entity types supported by the fire-control integration.
 */
@Mixin(value = EntityRenderDispatcher.class, priority = 2000)
public abstract class EntityRenderDispatcherMixin {
    /**
     * Create contraptions can be submitted directly without first calling
     * shouldRender. Cancelling the actual dispatcher entry point guarantees
     * the bound CBC cannon cannot draw over a camera located inside its barrel.
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void stabilizedTurret$hideScopedCannonRender(
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
        if (MissileLongRangeEffects.shouldSuppressNativeMissile(entity)) {
            ci.cancel();
            return;
        }
        MissileLongRangeEffects.onMissileRendered(entity);
        if (ClientAimState.shouldHideScopedEntity(entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
    private <E extends Entity> void stabilizedTurret$fullBrightThermalEntity(
            E entity,
            float partialTick,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (ClientAimState.isThermal() && !ClientAimState.shouldHideScopedEntity(entity)) {
            cir.setReturnValue(LightTexture.FULL_BRIGHT);
        }
    }

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void stabilizedTurret$hideScopedCannon(
            E entity,
            Frustum frustum,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (ClientAimState.shouldHideScopedEntity(entity)) {
            cir.setReturnValue(false);
            return;
        }
        if (!MianbaoAirDefenseCompat.isAnyFireControlMissile(entity)) {
            return;
        }

        // Only these exact ATGM entity types bypass distance/frustum culling.
        // Their lifetime is capped server-side, so render work cannot
        // accumulate permanently.
        cir.setReturnValue(true);
    }
}
