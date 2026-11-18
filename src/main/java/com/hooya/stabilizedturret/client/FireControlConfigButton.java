package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.FireControlSettings;
import com.hooya.stabilizedturret.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** A vanilla-size item button aligned to the actual mod-list row on title and pause menus. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class FireControlConfigButton extends Button {
    private ItemStack icon;

    private FireControlConfigButton(int x, int y, Screen parent) {
        super(x, y, 20, 20, FireControlConfigMenu.text("title"),
                button -> Minecraft.getInstance().setScreen(new FireControlConfigMenu(parent)), DEFAULT_NARRATION);
        setTooltip(Tooltip.create(FireControlConfigMenu.text("title")));
    }

    @SubscribeEvent
    public static void addToMenus(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        boolean pauseMenu = screen instanceof PauseScreen;
        if (!(screen instanceof TitleScreen) && !pauseMenu) return;
        if (pauseMenu && !FireControlSettings.showPauseButton()) return;
        if (event.getListenersList().stream().anyMatch(FireControlConfigButton.class::isInstance)) return;
        AbstractWidget anchor = null;
        AbstractWidget disconnect = null;
        for (var listener : event.getListenersList()) {
            if (!(listener instanceof AbstractWidget widget) || !widget.visible) continue;
            String label = widget.getMessage().getString();
            if (label.equals(Component.translatable("fml.menu.mods").getString())
                    || label.equalsIgnoreCase("Mod") || label.equalsIgnoreCase("Mods")) anchor = widget;
            if (widget.getMessage().getContents() instanceof TranslatableContents translated) {
                String key = translated.getKey();
                if (key.equals("fml.menu.mods")) anchor = widget;
                if (key.equals("menu.returnToMenu") || key.equals("menu.disconnect")) disconnect = widget;
            }
        }
        if (anchor == null && pauseMenu) anchor = disconnect;
        // Do not add a button to the minimal pause overlay, which has no menu controls.
        if (anchor == null) return;
        int x = anchor.getX() - 24;
        int y = anchor.getY();
        // Other mods may already occupy the preferred slot. Keep the same row and move outwards.
        boolean occupied;
        do {
            occupied = false;
            for (var listener : event.getListenersList()) {
                if (listener instanceof AbstractWidget other && other.visible
                        && x < other.getRight() && x + 20 > other.getX()
                        && y < other.getBottom() && y + 20 > other.getY()) {
                    x -= 24;
                    occupied = true;
                    break;
                }
            }
        } while (occupied && x >= 0);
        if (x < 0) x = Math.min(screen.width - 20, anchor.getRight() + 4);
        event.addListener(new FireControlConfigButton(x, y, screen));
    }

    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        if (icon == null) icon = new ItemStack(ModItems.STABILIZER_CONTROLLER.get());
        graphics.renderItem(icon, getX() + 2, getY() + 2);
    }
}
