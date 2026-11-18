package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.Locale;

/** Reuses Mianbao's Switchblade/UCAV full-screen television-guidance overlay. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class TelevisionGuidanceHudRenderer {
    private static final ResourceLocation UCAV_HUD = ResourceLocation.fromNamespaceAndPath(
            "mianbaos_modernwarfare", "textures/screens/suo_ding_zhun_xing_3.png");

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(RenderGuiEvent.Post event) {
        if (ClientWeaponViewState.isActive()) return;
        if (!ClientTelevisionGuidanceState.isActive()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) return;

        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        graphics.blit(UCAV_HUD,
                0, 0, 0.0F, 0.0F,
                width, height, width, height);

        int centreX = width / 2;
        int centreY = height / 2;
        graphics.drawString(minecraft.font,
                Component.translatable("hud.create_fire_control.television.time_remaining",
                        String.format(Locale.ROOT, "%.1f", ClientTelevisionGuidanceState.getRemainingSeconds())),
                centreX - 130, centreY - 65, 0xFFFFFFFF, false);
        graphics.drawString(minecraft.font,
                Component.translatable("hud.create_fire_control.television.airburst_status",
                        Component.translatable("hud.create_fire_control.television.ready").withStyle(ChatFormatting.RED)),
                centreX - 130, centreY - 46, 0xFFFFFFFF, false);
    }

    private TelevisionGuidanceHudRenderer() {
    }
}
