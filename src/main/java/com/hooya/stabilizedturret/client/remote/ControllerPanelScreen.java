package com.hooya.stabilizedturret.client.remote;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

abstract class ControllerPanelScreen extends Screen {
    protected static final int BRASS = 0xFFB8903D, PAPER = 0xFFE2D2AC, INK = 0xFF3D382F;
    protected int left, top, panelWidth, panelHeight;
    ControllerPanelScreen(Component title) { super(title); }
    protected void panel(int wantedHeight) {
        panel(432,wantedHeight);
    }
    protected void panel(int wantedWidth,int wantedHeight) {
        panelWidth = Math.min(wantedWidth, width - 16); panelHeight = Math.min(wantedHeight, height - 16);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
    }
    protected BrassButton button(int x, int y, int w, String suffix, Button.OnPress press) {
        return addRenderableWidget(new BrassButton(x, y, w, 16, text(suffix), press));
    }
    protected int buttonWidth(String suffix) { return font.width(text(suffix))+8; }
    protected BrassButton button(int x,int y,String suffix,Button.OnPress press) {
        return button(x,y,buttonWidth(suffix),suffix,press);
    }
    static Component text(String suffix) { return Component.translatable("remote.create_fire_control." + suffix); }
    @Override public void renderBackground(GuiGraphics graphics, int mx, int my, float partial) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics graphics, int mx, int my, float partial) {
        graphics.fill(0, 0, width, height, 0x88000000);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFF342F28);
        graphics.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, BRASS);
        graphics.fill(left + 5, top + 5, left + panelWidth - 5, top + panelHeight - 5, PAPER);
        graphics.fill(left + 5, top + 5, left + panelWidth - 5, top + 23, 0xFF4B4438);
        graphics.drawCenteredString(font, title, width / 2, top + 10, 0xFFF4EBD5);
        super.render(graphics, mx, my, partial);
    }
    static final class BrassButton extends Button {
        BrassButton(int x, int y, int width, int height, Component text, OnPress press) {
            super(x, y, width, height, text, press, DEFAULT_NARRATION);
        }
        @Override protected void renderWidget(GuiGraphics graphics, int mx, int my, float partial) {
            int bg = !active ? 0xFF4A4842 : isHoveredOrFocused() ? 0xFF777064 : 0xFF5F5A50;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), BRASS);
            graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, bg);
            renderScrollingString(graphics, font(), 3, active ? 0xFFF4EBD5 : 0xFF9A958A);
        }
        private net.minecraft.client.gui.Font font() { return net.minecraft.client.Minecraft.getInstance().font; }
    }
}
