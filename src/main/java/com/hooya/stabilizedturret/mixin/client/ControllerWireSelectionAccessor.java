package com.hooya.stabilizedturret.mixin.client;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "edn.stratodonut.drivebywire.client.ClientWireNetworkHandler", remap = false)
public interface ControllerWireSelectionAccessor {
    @Accessor("selectedSource") static BlockPos fireControl$selectedSource() { throw new AssertionError(); }
    @Accessor("currentChannel") static void fireControl$setChannel(String channel) { throw new AssertionError(); }
    @Accessor("currentChannel") static String fireControl$currentChannel() { throw new AssertionError(); }
}
