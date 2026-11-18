package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalEmissionLightmap;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Marks emissive fluids in Sodium's independent fluid mesh pipeline. */
@Pseudo
@Mixin(
        targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer",
        remap = false
)
public abstract class SodiumThermalFluidEmissionMixin {
    @Unique
    private int createFireControl$fluidEmission;

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, require = 0)
    private FluidState createFireControl$beginFluid(FluidState fluidState) {
        createFireControl$fluidEmission =
                fluidState.createLegacyBlock().getLightEmission();
        return fluidState;
    }

    @ModifyExpressionValue(
            method = "writeQuad",
            require = 0,
            at = @At(
                    value = "FIELD",
                    target = "Lnet/caffeinemc/mods/sodium/client/model/light/data/QuadLightData;lm:[I",
                    remap = false
            )
    )
    private int[] createFireControl$encodeFluidEmission(int[] packedLights) {
        int emission = createFireControl$fluidEmission;
        // QuadLightData is recalculated before every quad. Updating its four
        // values in place avoids allocations in Sodium's chunk-build hot path.
        for (int index = 0; index < packedLights.length; index++) {
            packedLights[index] = ThermalEmissionLightmap.encodeSodium(
                    packedLights[index],
                    emission
            );
        }
        return packedLights;
    }
}
