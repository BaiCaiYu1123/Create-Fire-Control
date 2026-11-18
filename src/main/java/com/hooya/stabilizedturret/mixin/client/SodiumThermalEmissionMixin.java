package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ThermalEmissionLightmap;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks source emission in Sodium's mesh light coordinate without touching geometry. */
@Pseudo
@Mixin(
        targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer",
        remap = false
)
public abstract class SodiumThermalEmissionMixin {
    @Unique
    private int createFireControl$sourceEmission;

    @Inject(method = "renderModel", at = @At("HEAD"), require = 0)
    private void createFireControl$beginSourceBlock(
            BakedModel model,
            BlockState state,
            BlockPos pos,
            BlockPos origin,
            CallbackInfo ci
    ) {
        createFireControl$sourceEmission = state.getLightEmission();
    }

    @Inject(method = "renderModel", at = @At("RETURN"), require = 0)
    private void createFireControl$endSourceBlock(
            BakedModel model,
            BlockState state,
            BlockPos pos,
            BlockPos origin,
            CallbackInfo ci
    ) {
        createFireControl$sourceEmission = 0;
    }

    @ModifyExpressionValue(
            method = "bufferQuad",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/frapi/mesh/"
                            + "MutableQuadViewImpl;lightmap(I)I"
            )
    )
    private int createFireControl$encodeSourceEmission(int packedLight) {
        return ThermalEmissionLightmap.encodeSodium(
                packedLight,
                createFireControl$sourceEmission
        );
    }
}
