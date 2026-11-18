package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalEmissionLightmap;
import com.hooya.stabilizedturret.client.ThermalRenderMode;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks a fluid's own emission on vanilla fluid vertices without changing alpha. */
@Mixin(LiquidBlockRenderer.class)
public abstract class LiquidBlockRendererThermalMixin {
    @Unique
    private static final ThreadLocal<Integer> createFireControl$fluidEmission =
            ThreadLocal.withInitial(() -> 0);

    @Inject(method = "tesselate", at = @At("HEAD"))
    private void createFireControl$beginFluid(
            BlockAndTintGetter level,
            BlockPos pos,
            VertexConsumer buffer,
            BlockState blockState,
            FluidState fluidState,
            CallbackInfo ci
    ) {
        int emission = Math.max(
                blockState.getLightEmission(),
                fluidState.createLegacyBlock().getLightEmission()
        );
        createFireControl$fluidEmission.set(emission);
    }

    @Inject(method = "tesselate", at = @At("RETURN"))
    private void createFireControl$endFluid(
            BlockAndTintGetter level,
            BlockPos pos,
            VertexConsumer buffer,
            BlockState blockState,
            FluidState fluidState,
            CallbackInfo ci
    ) {
        createFireControl$fluidEmission.remove();
    }

    @ModifyVariable(
            method = "vertex(Lcom/mojang/blaze3d/vertex/VertexConsumer;FFFFFFFFFI)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private int createFireControl$encodeFluidEmission(int packedLight) {
        if (!ThermalRenderMode.isFixedLightThermal()) {
            return packedLight;
        }
        return ThermalEmissionLightmap.encodeBlock(
                packedLight,
                createFireControl$fluidEmission.get()
        );
    }
}
