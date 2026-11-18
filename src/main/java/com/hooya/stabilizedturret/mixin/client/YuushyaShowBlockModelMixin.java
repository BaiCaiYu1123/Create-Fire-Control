package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonCompat;
import com.yuushya.modelling.client.NeoShowBlockModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(NeoShowBlockModel.class)
public abstract class YuushyaShowBlockModelMixin {
    @Inject(
            method = "getQuads(Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;"
                    + "Lnet/neoforged/neoforge/client/model/data/ModelData;"
                    + "Lnet/minecraft/client/renderer/RenderType;)Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void stabilizedTurret$hideStaticRecoilBarrel(
            BlockState state,
            Direction side,
            RandomSource random,
            ModelData modelData,
            RenderType renderType,
            CallbackInfoReturnable<List<BakedQuad>> cir
    ) {
        if (state != null && YuushyaCannonCompat.isBarrel(state)) {
            cir.setReturnValue(List.of());
        }
    }
}
