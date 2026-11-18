package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.compat.YuushyaShowBlockRenderSnapshot;
import com.yuushya.modelling.blockentity.showblock.ShowBlockModel;
import com.yuushya.modelling.blockentity.transformData.TransformBlockData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Iterator;
import java.util.List;

@Mixin(value = ShowBlockModel.class, remap = false)
public abstract class YuushyaShowBlockQuadSnapshotMixin {
    @Redirect(
            method = "getQuads(Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;"
                    + "Ljava/util/List;)Ljava/util/List;",
            at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;", ordinal = 0)
    )
    private Iterator<TransformBlockData> createFireControl$snapshotShowBlockComponents(
            List<TransformBlockData> components
    ) {
        return YuushyaShowBlockRenderSnapshot.copyOf(components).iterator();
    }
}
