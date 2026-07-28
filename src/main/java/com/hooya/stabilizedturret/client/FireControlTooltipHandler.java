package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class FireControlTooltipHandler {
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!event.getItemStack().is(ModItems.STABILIZER_CONTROLLER.get())) {
            return;
        }
        event.getToolTip().add(Component.empty());
        if (!Screen.hasShiftDown()) {
            event.getToolTip().add(
                    Component.translatable("tooltip.stabilized_turret.hold_shift")
                            .withStyle(ChatFormatting.DARK_GRAY)
            );
            return;
        }
        addSection(event, "stabilization", "tooltip.stabilized_turret.fire_control.stabilization");
        addSection(event, "aiming", "tooltip.stabilized_turret.fire_control.aiming");
        addSection(event, "binding", "tooltip.stabilized_turret.fire_control.binding");
        addSection(event, "remote", "tooltip.stabilized_turret.fire_control.remote");
    }

    private static void addSection(ItemTooltipEvent event, String title, String body) {
        event.getToolTip().add(
                Component.translatable("tooltip.stabilized_turret.fire_control." + title + ".title")
                        .withStyle(ChatFormatting.GOLD)
        );
        event.getToolTip().add(
                Component.literal("  ").append(Component.translatable(body))
                        .withStyle(ChatFormatting.GRAY)
        );
    }

    private FireControlTooltipHandler() {
    }
}
