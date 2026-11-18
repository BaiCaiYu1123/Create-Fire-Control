package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.content.bearing.BearingHeadVisibility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A hidden bearing head must not cull the face of the block touching it. */
@Mixin(Block.class)
public abstract class BearingHeadFaceCullingMixin {
    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private static void stabilizedTurret$renderFaceBesideHiddenBearingHead(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            Direction direction,
            BlockPos neighborPos,
            CallbackInfoReturnable<Boolean> cir
    ) {
        BlockEntity neighbor = level.getBlockEntity(neighborPos);
        if (neighbor instanceof BearingHeadVisibility visibility
                && visibility.stabilizedTurret$isBearingHeadHidden()) {
            cir.setReturnValue(true);
        }
    }
}
