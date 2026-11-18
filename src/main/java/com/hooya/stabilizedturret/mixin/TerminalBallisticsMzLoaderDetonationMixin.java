package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.loader.MzLoaderDetonation;
import com.hooya.stabilizedturret.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the terminal-ballistics spall hook conditionally on actual loader inventory. */
@Mixin(targets = "com.cbc_terminal_ballistics.ballistics.TBImpactService", remap = false)
public abstract class TerminalBallisticsMzLoaderDetonationMixin {
    @Inject(method = "triggerExplosiveBlockHit", at = @At("HEAD"),
            cancellable = true, require = 0)
    private static void stabilizedTurret$conditionallyDetonateMzRack(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            Direction hitFace,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (state.is(ModBlocks.MZ_LOADER.get()) || state.is(ModBlocks.AZ_LOADER.get())
                || state.is(ModBlocks.TYPE_10_LOADER.get())) {
            // Armed racks continue through CTB's native tag + block-event path so it can
            // clear impact marks normally. Empty racks must not be treated as explosives.
            if (!MzLoaderDetonation.isArmed(level, pos)) cir.setReturnValue(false);
        }
    }
}
