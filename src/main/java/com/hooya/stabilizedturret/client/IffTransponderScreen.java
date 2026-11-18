package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.iff.IffTransponderBlockEntity;
import com.hooya.stabilizedturret.network.OpenIffTransponderPayload;
import com.hooya.stabilizedturret.network.SetIffChannelPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public final class IffTransponderScreen extends Screen {
    private static final int PANEL_OUTLINE = 0xFF342F28;
    private static final int PANEL_BRASS = 0xFFB8903D;
    private static final int PANEL_INNER_BORDER = 0xFF76664A;
    private static final int PANEL_BACKGROUND = 0xFFE2D2AC;
    private static final int PANEL_HEADER = 0xFF4B4438;
    private static final int LABEL_COLOR = 0xFF3D382F;
    private static final int PANEL_WIDTH = 286;
    private static final int PANEL_HEIGHT = 142;

    private final OpenIffTransponderPayload initial;
    private EditBox channel;
    private Component validationMessage = Component.empty();

    public IffTransponderScreen(OpenIffTransponderPayload initial) {
        super(Component.translatable("screen.create_fire_control.iff_transponder"));
        this.initial = initial;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        channel = new EditBox(font, left + 18, top + 61, 174, 20,
                Component.translatable("screen.create_fire_control.iff_transponder.channel"));
        channel.setMaxLength(IffTransponderBlockEntity.MAX_CHANNEL_LENGTH);
        channel.setFilter(IffTransponderBlockEntity::isValidChannel);
        channel.setValue(initial.channel());
        addRenderableWidget(channel);
        addRenderableWidget(new BrassButton(left + 198, top + 61, 70, 20,
                Component.translatable("screen.create_fire_control.iff_transponder.copy"),
                button -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(channel.getValue());
                    validationMessage = Component.translatable(
                            "screen.create_fire_control.iff_transponder.copied");
                }));
        addRenderableWidget(new BrassButton(left + 103, top + 103, 80, 20,
                Component.translatable("gui.done"), button -> saveAndClose()));
        setInitialFocus(channel);
    }

    private void saveAndClose() {
        String value = channel.getValue();
        if (!IffTransponderBlockEntity.isValidChannel(value)) {
            validationMessage = Component.translatable(
                    "screen.create_fire_control.iff_transponder.invalid");
            return;
        }
        PacketDistributor.sendToServer(new SetIffChannelPayload(
                initial.pos(), initial.subLevelId(), value));
        onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            saveAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x68000000);
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_OUTLINE);
        graphics.fill(left + 2, top + 2, left + PANEL_WIDTH - 2,
                top + PANEL_HEIGHT - 2, PANEL_BRASS);
        graphics.fill(left + 4, top + 4, left + PANEL_WIDTH - 4,
                top + PANEL_HEIGHT - 4, PANEL_INNER_BORDER);
        graphics.fill(left + 6, top + 6, left + PANEL_WIDTH - 6,
                top + PANEL_HEIGHT - 6, PANEL_BACKGROUND);
        graphics.fill(left + 6, top + 6, left + PANEL_WIDTH - 6, top + 31,
                PANEL_HEADER);
        graphics.drawCenteredString(font, title, width / 2, top + 14, 0xFFF4EBD5);
        graphics.drawString(font, Component.translatable(
                        "screen.create_fire_control.iff_transponder.channel"),
                left + 18, top + 47, LABEL_COLOR, false);
        if (!validationMessage.getString().isEmpty()) {
            graphics.drawCenteredString(font, validationMessage, width / 2,
                    top + 87, 0xFF625A4C);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.flush();
    }

    @Override
    public void renderBackground(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The dim layer is drawn explicitly in render(). Calling the inherited
        // method lets Modern UI blur the panel and text after they are drawn.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class BrassButton extends Button {
        private BrassButton(int x, int y, int width, int height,
                            Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int background = isHovered() ? 0xFF777064 : 0xFF5F5A50;
            graphics.fill(getX(), getY(), getX() + width, getY() + height,
                    PANEL_OUTLINE);
            graphics.fill(getX() + 1, getY() + 1, getX() + width - 1,
                    getY() + height - 1, PANEL_BRASS);
            graphics.fill(getX() + 2, getY() + 2, getX() + width - 2,
                    getY() + height - 2, background);
            graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                    getX() + width / 2, getY() + (height - 8) / 2, 0xFFF4EBD5);
        }
    }
}
