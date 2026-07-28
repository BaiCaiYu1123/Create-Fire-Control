package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.DriveByWireCompat;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import com.hooya.stabilizedturret.network.OpenGasTurbinePayload;
import com.hooya.stabilizedturret.network.SetGasTurbineSettingsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public final class GasTurbineScreen extends Screen {
    private static final int PANEL_OUTLINE = 0xFF342F28;
    private static final int PANEL_BRASS = 0xFFB8903D;
    private static final int PANEL_INNER_BORDER = 0xFF76664A;
    private static final int PANEL_BACKGROUND = 0xFFE2D2AC;
    private static final int PANEL_HEADER = 0xFF4B4438;
    private static final int LABEL_COLOR = 0xFF3D382F;
    private static final RemoteControllerCompat.Type REMOTE_TYPE =
            RemoteControllerCompat.Type.ADVANCED;
    private static final int PANEL_WIDTH = 470;
    private static final int PANEL_HEIGHT = 310;

    private final OpenGasTurbinePayload initial;
    private final List<String> keys;
    private final Button[] keyButtons = new Button[GasTurbineBlockEntity.INPUT_COUNT];
    private EditBox forwardSpeed;
    private EditBox reverseSpeed;
    private EditBox turnSpeed;
    private EditBox acceleration;
    private EditBox soundVolume;
    private int capturingAction = -1;
    private Component validationMessage = Component.empty();

    public GasTurbineScreen(OpenGasTurbinePayload initial) {
        super(Component.translatable("screen.stabilized_turret.gas_turbine.title"));
        this.initial = initial;
        this.keys = new ArrayList<>(initial.keys());
        while (keys.size() < GasTurbineBlockEntity.INPUT_COUNT) keys.add("");
    }

    @Override
    protected void init() {
        PanelBounds panel = panelBounds();
        int left = panel.left;
        int top = panel.top;
        int panelWidth = panel.right - panel.left;
        int fieldWidth = Math.min(170, Math.max(86, panelWidth / 2 - 36));
        int fieldX = panel.right - fieldWidth - 18;

        forwardSpeed = numericField(
                fieldX, top + 48, fieldWidth, initial.forwardSpeed());
        reverseSpeed = numericField(
                fieldX, top + 76, fieldWidth, initial.reverseSpeed());
        turnSpeed = numericField(
                fieldX, top + 104, fieldWidth, initial.turnSpeed());
        acceleration = numericField(
                fieldX, top + 132, fieldWidth, initial.acceleration());
        soundVolume = numericField(
                fieldX, top + 160, fieldWidth, initial.soundVolume());
        addRenderableWidget(forwardSpeed);
        addRenderableWidget(reverseSpeed);
        addRenderableWidget(turnSpeed);
        addRenderableWidget(acceleration);
        addRenderableWidget(soundVolume);

        int buttonY = top + 224;
        int gap = 5;
        int buttonWidth = Math.max(54,
                (panelWidth - 36 - gap * (keyButtons.length - 1))
                        / keyButtons.length);
        for (int action = 0; action < keyButtons.length; action++) {
            final int selectedAction = action;
            BrassButton button = new BrassButton(
                    left + 18 + action * (buttonWidth + gap),
                    buttonY,
                    buttonWidth,
                    24,
                    bindingText(action),
                    ignored -> beginCapture(selectedAction)
            );
            button.active = initial.advancedHubConnected();
            keyButtons[action] = addRenderableWidget(button);
        }

        addRenderableWidget(new BrassButton(
                panel.right - 206, panel.bottom - 32, 90, 20,
                Component.translatable("gui.done"), ignored -> saveAndClose()));
        addRenderableWidget(new BrassButton(
                panel.right - 108, panel.bottom - 32, 90, 20,
                Component.translatable("gui.cancel"), ignored -> onClose()));
    }

    private EditBox numericField(int x, int y, int fieldWidth, float value) {
        EditBox field = new EditBox(font, x, y, fieldWidth, 20, Component.empty());
        field.setTextShadow(false);
        field.setTextColor(0xFFF4EACF);
        field.setTextColorUneditable(0xFF9D9585);
        field.setMaxLength(8);
        field.setFilter(text -> text.matches("\\d{0,4}(\\.\\d{0,2})?"));
        field.setValue(format(value));
        return field;
    }

    private void beginCapture(int action) {
        if (!initial.advancedHubConnected()) return;
        capturingAction = action;
        refreshBindingButtons();
    }

    private void selectKey(int action, String key) {
        keys.set(action, key == null ? "" : key);
        capturingAction = -1;
        refreshBindingButtons();
    }

    private void refreshBindingButtons() {
        for (int action = 0; action < keyButtons.length; action++) {
            keyButtons[action].setMessage(bindingText(action));
        }
    }

    private Component bindingText(int action) {
        Component actionName = Component.translatable(
                "screen.stabilized_turret.gas_turbine.input." + action);
        if (!initial.advancedHubConnected()) {
            return actionName.copy().append(": -");
        }
        if (capturingAction == action) {
            return actionName.copy().append(": ").append(
                    Component.translatable("screen.stabilized_turret.press_key"));
        }
        String key = keys.get(action);
        Component keyName = key.isEmpty()
                ? Component.literal("-")
                : ControllerKeyMappingCompat.displayKey(REMOTE_TYPE, key);
        return actionName.copy().append(": ").append(keyName);
    }

    private void saveAndClose() {
        try {
            float forward = clamp(
                    parse(forwardSpeed), 0, GasTurbineBlockEntity.MAX_SPEED);
            float reverse = clamp(
                    parse(reverseSpeed), 0, GasTurbineBlockEntity.MAX_SPEED);
            float turn = clamp(parse(turnSpeed), 0, GasTurbineBlockEntity.MAX_SPEED);
            float accel = clamp(parse(acceleration), 1, GasTurbineBlockEntity.MAX_SPEED);
            float volume = clamp(
                    parse(soundVolume), 0, GasTurbineBlockEntity.MAX_SOUND_VOLUME);
            PacketDistributor.sendToServer(new SetGasTurbineSettingsPayload(
                    initial.pos(), forward, reverse, turn, accel,
                    volume, List.copyOf(keys)));
            onClose();
        } catch (NumberFormatException ignored) {
            validationMessage = Component.translatable(
                    "screen.stabilized_turret.gas_turbine.invalid_number");
        }
    }

    private static float parse(EditBox field) {
        return Float.parseFloat(field.getValue());
    }

    private static float clamp(float value, float minimum, float maximum) {
        if (!Float.isFinite(value)) throw new NumberFormatException();
        return Mth.clamp(value, minimum, maximum);
    }

    private static String format(float value) {
        return value == Math.round(value)
                ? Integer.toString(Math.round(value))
                : Float.toString(value);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (capturingAction < 0) return super.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == 256) {
            capturingAction = -1;
            refreshBindingButtons();
            return true;
        }
        if (keyCode == 259 || keyCode == 261) {
            selectKey(capturingAction, "");
            return true;
        }
        String signal = ControllerKeyMappingCompat.signalForKeyboardKey(
                REMOTE_TYPE, keyCode, scanCode);
        if (signal == null) {
            validationMessage = Component.translatable(
                    "message.stabilized_turret.advanced_key_not_bound");
        } else {
            selectKey(capturingAction, signal);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (capturingAction >= 0) {
            String signal = ControllerKeyMappingCompat.signalForMouseButton(REMOTE_TYPE, button);
            if (signal == null) {
                validationMessage = Component.translatable(
                        "message.stabilized_turret.advanced_key_not_bound");
            } else {
                selectKey(capturingAction, signal);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x68000000);
        renderPanel(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderLabels(graphics);
        graphics.flush();
    }

    @Override
    public void renderBackground(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        // The world dimming layer is drawn explicitly in render().  Calling
        // the vanilla implementation here lets Modern UI blur the panel and
        // its labels after they have already been drawn.
    }

    private void renderPanel(GuiGraphics graphics) {
        PanelBounds panel = panelBounds();
        graphics.fill(panel.left + 4, panel.top + 5,
                panel.right + 5, panel.bottom + 6, 0x90000000);
        graphics.fill(panel.left, panel.top, panel.right, panel.bottom, PANEL_OUTLINE);
        graphics.fill(panel.left + 2, panel.top + 2,
                panel.right - 2, panel.bottom - 2, PANEL_BRASS);
        graphics.fill(panel.left + 5, panel.top + 5,
                panel.right - 5, panel.bottom - 5, PANEL_INNER_BORDER);
        graphics.fill(panel.left + 7, panel.top + 28,
                panel.right - 7, panel.bottom - 7, PANEL_BACKGROUND);
        graphics.fill(panel.left + 7, panel.top + 7,
                panel.right - 7, panel.top + 27, PANEL_HEADER);
        graphics.fill(panel.left + 8, panel.top + 27,
                panel.right - 8, panel.top + 29, 0xFF947436);
        drawBolt(graphics, panel.left + 4, panel.top + 4);
        drawBolt(graphics, panel.right - 6, panel.top + 4);
        drawBolt(graphics, panel.left + 4, panel.bottom - 6);
        drawBolt(graphics, panel.right - 6, panel.bottom - 6);
    }

    private void renderLabels(GuiGraphics graphics) {
        PanelBounds panel = panelBounds();
        int left = panel.left;
        int top = panel.top;
        drawCenteredNoShadow(graphics, title, width / 2, top + 12, 0xFFFFFFFF);

        Component connection = Component.translatable(
                initial.advancedHubConnected()
                        ? "screen.stabilized_turret.gas_turbine.hub_connected"
                        : "screen.stabilized_turret.gas_turbine.hub_missing");
        graphics.drawString(font, connection, left + 18, top + 34,
                initial.advancedHubConnected() ? 0xFF3F7B39 : 0xFF9A3F35, false);
        Component engineState = Component.translatable(
                initial.engineEnabled()
                        ? "screen.stabilized_turret.gas_turbine.engine_running"
                        : "screen.stabilized_turret.gas_turbine.engine_stopped");
        graphics.drawString(font, engineState,
                panel.right - 18 - font.width(engineState), top + 34,
                initial.engineEnabled() ? 0xFF3F7B39 : 0xFF625A4C, false);

        graphics.drawString(font, Component.translatable(
                "screen.stabilized_turret.gas_turbine.forward_speed"),
                left + 18, top + 54, LABEL_COLOR, false);
        graphics.drawString(font, Component.translatable(
                "screen.stabilized_turret.gas_turbine.reverse_speed"),
                left + 18, top + 82, LABEL_COLOR, false);
        graphics.drawString(font, Component.translatable(
                "screen.stabilized_turret.gas_turbine.turn_speed"),
                left + 18, top + 110, LABEL_COLOR, false);
        graphics.drawString(font, Component.translatable(
                "screen.stabilized_turret.gas_turbine.acceleration"),
                left + 18, top + 138, LABEL_COLOR, false);
        graphics.drawString(font, Component.translatable(
                "screen.stabilized_turret.gas_turbine.sound_volume"),
                left + 18, top + 166, LABEL_COLOR, false);
        graphics.drawString(font, Component.translatable(
                "screen.stabilized_turret.gas_turbine.limit"),
                left + 18, top + 190, 0xFF625A4C, false);
        graphics.fill(left + 18, top + 205, panel.right - 18, top + 206,
                0xFFB39B6B);
        graphics.drawString(font, Component.translatable(
                "screen.stabilized_turret.input_bindings"),
                left + 18, top + 210, LABEL_COLOR, false);
        if (!validationMessage.getString().isEmpty()) {
            drawCenteredNoShadow(graphics, validationMessage, width / 2,
                    panel.bottom - 51, 0xFF9A3F35);
        }
    }

    private PanelBounds panelBounds() {
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(8, width - 8));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(8, height - 8));
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        return new PanelBounds(left, top, left + panelWidth, top + panelHeight);
    }

    private static void drawBolt(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 2, y + 2, 0xFFDEC36C);
        graphics.fill(x + 1, y + 1, x + 2, y + 2, 0xFF5A4828);
    }

    private void drawCenteredNoShadow(
            GuiGraphics graphics,
            Component text,
            int centerX,
            int y,
            int color
    ) {
        graphics.drawString(font, text, centerX - font.width(text) / 2,
                y, color, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class BrassButton extends Button {
        private BrassButton(
                int x,
                int y,
                int width,
                int height,
                Component message,
                OnPress onPress
        ) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics,
                int mouseX,
                int mouseY,
                float partialTick
        ) {
            int background = isHovered() ? 0xFF777064 : 0xFF5F5A50;
            if (!active) background = 0xFF4A4842;
            graphics.fill(getX(), getY(), getX() + width, getY() + height,
                    0xFF302C26);
            graphics.fill(getX() + 1, getY() + 1,
                    getX() + width - 1, getY() + height - 1, PANEL_BRASS);
            graphics.fill(getX() + 2, getY() + 2,
                    getX() + width - 2, getY() + height - 2, background);
            graphics.fill(getX() + 3, getY() + 3,
                    getX() + width - 3, getY() + 4, 0x35FFFFFF);
            graphics.enableScissor(getX() + 4, getY() + 2,
                    getX() + width - 4, getY() + height - 2);
            var font = Minecraft.getInstance().font;
            graphics.drawString(
                    font,
                    getMessage(),
                    getX() + (width - font.width(getMessage())) / 2,
                    getY() + (height - 8) / 2,
                    active ? 0xFFF4EBD5 : 0xFF9A958A,
                    false
            );
            graphics.disableScissor();
        }
    }

    private record PanelBounds(int left, int top, int right, int bottom) {
    }
}
