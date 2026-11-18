package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoBombCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A commanded release satisfies the native gate without powering adjacent racks. */
@Pseudo
@Mixin(targets = {
        "net.mcreator.myfirstmod.procedures.BombrackgoProcedure",
        "net.mcreator.myfirstmod.procedures.MediumBombrackgoProcedure",
        "net.mcreator.myfirstmod.procedures.ClusterBombrackgoProcedure",
        "net.mcreator.myfirstmod.procedures.HighresistancebombrackgoProcedure",
        "net.mcreator.myfirstmod.procedures.Nuclear_bomb_rack_dropProcedure"
}, remap = false)
public abstract class MianbaoBombRackSignalMixin {
    @Redirect(method = "execute", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;hasNeighborSignal(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean createFireControl$commandedRelease(Level level, BlockPos pos) {
        return MianbaoBombCompat.isCommanded(level, pos) || level.hasNeighborSignal(pos);
    }
}
