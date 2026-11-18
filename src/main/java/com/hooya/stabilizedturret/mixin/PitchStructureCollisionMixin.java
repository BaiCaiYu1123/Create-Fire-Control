package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.bearing.PitchStructureCollision;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep disabled pitch plots absent from the physical collider grid, including later block edits. */
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class PitchStructureCollisionMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "handleChunkSectionAddition", at = @At("HEAD"), cancellable = true)
    private void createFireControl$skipPitchSection(LevelChunkSection section, int x, int y, int z,
                                                   boolean active, CallbackInfo ci) {
        if (PitchStructureCollision.isPlotDisabled(level, x, z)) ci.cancel();
    }

    @Inject(method = "handleBlockChange", at = @At("HEAD"), cancellable = true)
    private void createFireControl$skipPitchBlock(SectionPos section, LevelChunkSection blocks,
            int x, int y, int z, BlockState before, BlockState after, CallbackInfo ci) {
        if (PitchStructureCollision.isPlotDisabled(level, section.x(), section.z())) ci.cancel();
    }

    @Redirect(method = "add(Ldev/ryanhcode/sable/api/sublevel/KinematicContraption;)V",
            at = @At(value = "INVOKE", target =
                    "Ldev/ryanhcode/sable/api/sublevel/KinematicContraption;sable$shouldCollide()Z"))
    private boolean createFireControl$skipPitchContraption(KinematicContraption contraption) {
        return !PitchStructureCollision.isContraptionDisabled(level, contraption)
                && contraption.sable$shouldCollide();
    }
}
