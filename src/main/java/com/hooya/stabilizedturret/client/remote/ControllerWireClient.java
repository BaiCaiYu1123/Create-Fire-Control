package com.hooya.stabilizedturret.client.remote;

import com.hooya.stabilizedturret.mixin.client.ControllerWireSelectionAccessor;
import com.hooya.stabilizedturret.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** Called only after the optional dependency check. */
public final class ControllerWireClient {
    public static BlockPos selected() { return ControllerWireSelectionAccessor.fireControl$selectedSource(); }
    public static String current() { return ControllerWireSelectionAccessor.fireControl$currentChannel(); }
    public static boolean canSelect() {
        Minecraft mc = Minecraft.getInstance(); BlockPos pos = selected();
        return mc.player != null && mc.level != null && pos != null
                && BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).equals(
                ResourceLocation.fromNamespaceAndPath("drivebywire", "wire"))
                && mc.level.hasChunkAt(pos) && mc.level.getBlockState(pos).is(ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get());
    }
    public static void select(String channel) {
        ControllerWireSelectionAccessor.fireControl$setChannel(channel);
        Minecraft.getInstance().player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "remote.create_fire_control.channel_selected",WirelessControllerClient.name(channel)),true);
    }
    private ControllerWireClient() {}
}
