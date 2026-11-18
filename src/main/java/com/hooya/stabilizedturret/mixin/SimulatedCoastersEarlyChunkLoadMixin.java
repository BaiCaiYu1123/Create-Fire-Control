package com.hooya.stabilizedturret.mixin;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents Simulated Coasters from reading overworld SavedData before the overworld exists. */
@Pseudo
@Mixin(
        targets = "dev.silvergold.simulatedcoasters.track.graph.CoasterPathSubLevelHooks",
        remap = false
)
public abstract class SimulatedCoastersEarlyChunkLoadMixin {
    @Inject(method = "onChunkLoad", at = @At("HEAD"), cancellable = true, require = 0)
    private static void stabilizedTurret$skipBeforeOverworld(ChunkEvent.Load event, CallbackInfo ci) {
        if (event.getLevel() instanceof ServerLevel level
                && level.getServer().overworld() == null) {
            ci.cancel();
        }
    }
}
