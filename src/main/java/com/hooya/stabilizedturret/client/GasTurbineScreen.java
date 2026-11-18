package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.content.turbine.EngineControlSettings;
import com.hooya.stabilizedturret.content.turbine.EngineDriveBehavior;
import com.hooya.stabilizedturret.content.turbine.EngineTransitionRule;
import com.hooya.stabilizedturret.content.turbine.EngineRedstoneChannel;
import com.hooya.stabilizedturret.content.controller.DriveByWireCompat;
import com.hooya.stabilizedturret.network.OpenGasTurbinePayload;
import com.hooya.stabilizedturret.network.SetGasTurbineSettingsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public final class GasTurbineScreen extends Screen {
    private static final int OUTLINE = 0xFF342F28;
    private static final int BRASS = 0xFFB8903D;
    private static final int BACKGROUND = 0xFFE2D2AC;
    private static final int LABEL = 0xFF3D382F;
    private static final String PREFIX = "screen.create_fire_control.engine_behavior.";
    private final RemoteControllerCompat.Type REMOTE_TYPE;
    private static final String[] TRANSITION_CHOICES = {"transition_default_short", "transition_previous_short", "transition_current_short",
            "transition_default_short", "transition_current_short", "transition_next_short"};
    private static final String[] TRANSITION_DETAILS = {"transition_default", "transition_entry_previous", "transition_entry_current",
            "transition_default", "transition_exit_current", "transition_exit_next"};
    private final OpenGasTurbinePayload initial;
    private final Consumer<SetGasTurbineSettingsPayload> settingsSender;
    private final List<BehaviorDraft> behaviors = new ArrayList<>();
    private final List<AbstractWidget> globalWidgets = new ArrayList<>();
    private final List<CardWidget> cardWidgets = new ArrayList<>();
    private final List<BrassButton> transitionWidgets = new ArrayList<>();
    private BehaviorDraft transitionDraft;
    private int transitionX, transitionY, transitionWidth, transitionHeight, transitionRowHeight, transitionLabelWidth, transitionFocus;
    private final LinkedHashSet<String> capturedSignals = new LinkedHashSet<>();
    private final Set<String> heldInputs = new HashSet<>();
    private EngineControlLayout layout;
    private String soundValue;
    private String toggleKey;
    private EngineRedstoneChannel toggleChannel;
    private boolean soundRpmEnabled;
    private String soundRpmAccelerationValue;
    private String soundRpmDecelerationValue;
    private String soundMaxRpmValue;
    private BrassButton toggleButton;
    private BehaviorDraft capturingBehavior;
    private boolean capturingToggle;
    private boolean draggingScrollbar;
    private double scrollbarGrab;
    private double scroll;
    private Component validationMessage = Component.empty();

    public GasTurbineScreen(OpenGasTurbinePayload initial) {
        this(initial, Component.translatable("screen.create_fire_control.gas_turbine.title"), PacketDistributor::sendToServer);
    }
    public GasTurbineScreen(OpenGasTurbinePayload initial, Component title,
                           Consumer<SetGasTurbineSettingsPayload> settingsSender) {
        super(title);
        this.initial = initial;
        REMOTE_TYPE=initial.wirelessHub()?RemoteControllerCompat.Type.WIRELESS:RemoteControllerCompat.Type.ADVANCED;
        this.settingsSender = settingsSender;
        soundValue = format(initial.controls().soundVolume());
        toggleKey = initial.controls().engineToggleKey();
        toggleChannel = initial.controls().toggleChannel();
        soundRpmEnabled = initial.controls().soundRpmEnabled();
        soundRpmAccelerationValue = format(initial.controls().soundRpmAcceleration());
        soundRpmDecelerationValue = format(initial.controls().soundRpmDeceleration());
        soundMaxRpmValue = format(initial.controls().soundMaxRpm());
        for (var behavior : initial.controls().behaviors()) behaviors.add(new BehaviorDraft(behavior));
    }

    @Override protected void init() {
        closeTransitionMenu();
        layout = EngineControlLayout.calculate(width, height, behaviors.size(), soundRpmEnabled);
        globalWidgets.clear();
        cardWidgets.clear();
        int x = layout.left() + 10;
        int y = layout.settingsTop();
        global(numericField(x + 60, y, 52, soundValue, false, 300, value -> soundValue = value,
                text("volume_hint")));
        toggleButton = global(new BrassButton(x + 122, y, layout.width() - 194, 20,
                bindingText(true, null), button -> beginCapture(null)));
        toggleButton.active = initial.advancedHubConnected();
        toggleButton.setTooltip(Tooltip.create(text(initial.advancedHubConnected() ? "toggle_hint" : "hub_connect_hint")));
        var toggleFrequency = global(new BrassButton(x + layout.width() - 66, y, 46, 20,
                text("channel_short"), button -> openChannel(null)));
        toggleFrequency.setTooltip(Tooltip.create(channelText(toggleChannel).copy().append("\n").append(text("redstone_hint"))));
        var soundRpmButton = global(new BrassButton(x + 122, y + 22, layout.width() - 142, 20,
                soundRpmText(), button -> {
                    soundRpmEnabled = !soundRpmEnabled;
                    rebuildWidgets();
                }));
        soundRpmButton.setTooltip(Tooltip.create(text("sound_rpm_hint")));
        if (soundRpmEnabled) {
            int third = (layout.width() - 28) / 3;
            global(numericField(x + 40, y + 44, third - 44, soundRpmAccelerationValue, false, 1024,
                    value -> soundRpmAccelerationValue = value, text("rate_hint")));
            global(numericField(x + third + 40, y + 44, third - 44, soundRpmDecelerationValue, false, 1024,
                    value -> soundRpmDecelerationValue = value, text("rate_hint")));
            global(numericField(x + third * 2 + 40, y + 44, third - 44, soundMaxRpmValue, false, 1024,
                    value -> soundMaxRpmValue = value, text("sound_max_rpm_hint")));
        }
        var add = global(new BrassButton(layout.right() - 104, layout.behaviorHeaderTop(), 86, 20,
                text("add"), button -> {
                    if (behaviors.size() >= EngineControlSettings.MAX_BEHAVIORS) return;
                    behaviors.add(new BehaviorDraft(EngineDriveBehavior.defaults()));
                    rebuildWidgets();
                    scroll = layout.maxScroll(behaviors.size());
                    positionCards();
                }));
        add.active = behaviors.size() < EngineControlSettings.MAX_BEHAVIORS;
        for (int i = 0; i < behaviors.size(); i++) createCard(i, behaviors.get(i));
        int buttonWidth = Math.min(82, (layout.width() - 30) / 2);
        global(new BrassButton(layout.right() - buttonWidth * 2 - 18, layout.bottom() - 26,
                buttonWidth, 18, Component.translatable("gui.done"), button -> saveAndClose()));
        global(new BrassButton(layout.right() - buttonWidth - 10, layout.bottom() - 26,
                buttonWidth, 18, Component.translatable("gui.cancel"), button -> onClose()));
        positionCards();
    }
    private <T extends AbstractWidget> T global(T widget) {
        globalWidgets.add(widget);
        return addRenderableWidget(widget);
    }
    private void createCard(int index, BehaviorDraft draft) {
        int x = layout.cardLeft();
        int base = index * (EngineControlLayout.CARD_HEIGHT + EngineControlLayout.CARD_GAP);
        int transitionWidth = 84;
        int transitionX = x + layout.cardWidth() - 50 - transitionWidth;
        draft.bindingButton = new BrassButton(x + 60, 0, transitionX - x - 66, 20,
                bindingText(false, draft), button -> beginCapture(draft));
        draft.bindingButton.setTooltip(Tooltip.create(text(initial.advancedHubConnected() ? "bind_hint" : "hub_connect_hint")));
        card(draft.bindingButton, base + 5, true, false);
        var frequency = new BrassButton(x + 60, 0, 46, 20,
                text("channel_short"), button -> openChannel(draft));
        frequency.setTooltip(Tooltip.create(channelText(draft.channel).copy().append("\n").append(text("redstone_hint"))));
        card(frequency, base + 31, false, false);
        draft.transitionButton = new BrassButton(transitionX, 0, transitionWidth, 20,
                text("transition").copy().append(" ▾"), button -> openTransitionMenu(draft));
        draft.transitionButton.setTooltip(Tooltip.create(text("transition_hint")));
        card(draft.transitionButton, base + 5, false, false);
        var delete = new BrassButton(x + layout.cardWidth() - 44, 0, 38, 20,
                text("delete"), button -> {
                    if (behaviors.size() == 1) return;
                    if (capturingBehavior == draft) cancelCapture();
                    behaviors.remove(draft);
                    rebuildWidgets();
                });
        card(delete, base + 5, false, true);
        for (int side = 0; side < 2; side++) {
            for (int column = 0; column < 3; column++) {
                int field = side * 3 + column;
                var edit = numericField(layout.columnX(column), 0, layout.columnWidth(), draft.values[field],
                        column == 0, 1024, value -> draft.values[field] = value,
                        text(column == 0 ? "speed_hint" : "rate_hint"));
                card(edit, base + 71 + side * 24, false, false);
            }
        }
    }
    private void card(AbstractWidget widget, int offset, boolean binding, boolean deletion) {
        cardWidgets.add(new CardWidget(addRenderableWidget(widget), offset, binding, deletion));
    }
    private EditBox numericField(int x, int y, int fieldWidth, String value, boolean signed,
                                 float maximum, Consumer<String> setter, Component hint) {
        EditBox field = new EditBox(font, x, y, fieldWidth, 20, hint);
        field.setMaxLength(8);
        field.setTextShadow(false);
        field.setFilter(s -> s.matches((signed ? "-?" : "") + "\\d{0,4}(\\.\\d{0,2})?"));
        field.setValue(value);
        field.setTooltip(Tooltip.create(hint));
        field.setResponder(s -> {
            setter.accept(s);
            field.setTextColor(validNumber(s, signed ? -maximum : 0, maximum) ? 0xFFF4EACF : 0xFFFF7777);
            validationMessage = Component.empty();
        });
        return field;
    }
    private static boolean validNumber(String value, float minimum, float maximum) {
        try {
            EngineDriveBehavior.requireRange(Float.parseFloat(value), minimum, maximum);
            return true;
        } catch (IllegalArgumentException ignored) { return false; }
    }
    private void positionCards() {
        scroll = Mth.clamp(scroll, 0, layout.maxScroll(behaviors.size()));
        for (var card : cardWidgets) {
            var widget = card.widget;
            widget.setY(layout.listTop() + card.offset - (int) scroll);
            widget.visible = widget.getY() + widget.getHeight() > layout.listTop()
                    && widget.getY() < layout.listBottom();
            widget.active = (!card.binding || initial.advancedHubConnected())
                    && (!card.deletion || behaviors.size() > 1);
        }
    }
    private boolean insideList(double x, double y) {
        return x >= layout.cardLeft() && x < layout.right() - 8
                && y >= layout.listTop() && y < layout.listBottom();
    }
    private boolean isCapturing() { return capturingToggle || capturingBehavior != null; }
    private void beginCapture(BehaviorDraft behavior) {
        closeTransitionMenu();
        if (!initial.advancedHubConnected()) return;
        setFocused(null);
        capturingBehavior = behavior;
        capturingToggle = behavior == null;
        capturedSignals.clear();
        heldInputs.clear();
        validationMessage = text("capture_hint");
        refreshBindings();
    }
    private void openChannel(BehaviorDraft behavior) {
        closeTransitionMenu();
        cancelCapture();
        EngineRedstoneChannel channel = behavior == null ? toggleChannel : behavior.channel;
        Component title = behavior == null ? text("toggle") : text("behavior", behaviors.indexOf(behavior) + 1);
        minecraft.setScreen(new com.hooya.stabilizedturret.client.remote.FireControlFrequencyScreen(this,
                title.copy().append(" · ").append(text("redstone_channel")), channel.first(), channel.second(), (first, second) -> {
                    var selected = new EngineRedstoneChannel(first, second);
                    if (behavior == null) toggleChannel = selected; else behavior.channel = selected;
                }));
    }
    private Component channelText(EngineRedstoneChannel channel) {
        return text("redstone_channel").copy().append(": ")
                .append(channel.first().isEmpty() ? Component.literal("—") : channel.first().getHoverName())
                .append(" + ").append(channel.second().isEmpty() ? Component.literal("—") : channel.second().getHoverName());
    }
    private void cancelCapture() {
        capturingBehavior = null;
        capturingToggle = false;
        capturedSignals.clear();
        heldInputs.clear();
        validationMessage = Component.empty();
        refreshBindings();
    }
    private void finishCapture() {
        if (capturingToggle) toggleKey = REMOTE_TYPE==RemoteControllerCompat.Type.WIRELESS?String.join("+",capturedSignals):capturedSignals.stream().findFirst().orElse("");
        else if (capturingBehavior != null) capturingBehavior.keys = List.copyOf(capturedSignals);
        cancelCapture();
    }
    private void capture(String input, String signal) {
        if (signal == null || !DriveByWireCompat.accepts(REMOTE_TYPE, signal)) {
            validationMessage = Component.translatable("message.create_fire_control.advanced_key_not_bound");
            return;
        }
        int maximum = capturingToggle && REMOTE_TYPE!=RemoteControllerCompat.Type.WIRELESS ? 1 : 2;
        if (!capturedSignals.contains(signal) && capturedSignals.size() >= maximum) {
            validationMessage = text("too_many_keys", maximum);
            return;
        }
        capturedSignals.add(signal);
        heldInputs.add(input);
        validationMessage = text("capture_release");
        refreshBindings();
    }
    private void releaseCaptureInput(String input) {
        heldInputs.remove(input);
        if (heldInputs.isEmpty() && !capturedSignals.isEmpty()) finishCapture();
    }
    private void refreshBindings() {
        if (toggleButton != null) toggleButton.setMessage(bindingText(true, null));
        for (var behavior : behaviors) {
            if (behavior.bindingButton != null) behavior.bindingButton.setMessage(bindingText(false, behavior));
        }
    }
    private Component bindingText(boolean toggle, BehaviorDraft behavior) {
        boolean capturing = toggle ? capturingToggle : capturingBehavior == behavior;
        Component prefix = toggle ? text("toggle") : text("binding");
        List<String> keys = toggle ? (toggleKey.isEmpty() ? List.of() : List.of(toggleKey)) : behavior.keys;
        if (capturing) keys = List.copyOf(capturedSignals);
        var result = prefix.copy().append(": ");
        if (keys.isEmpty()) return result.append(capturing ? text("press_keys") : Component.literal("—"));
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) result.append(" + ");
            result.append(ControllerKeyMappingCompat.displayKey(REMOTE_TYPE, keys.get(i)));
        }
        return result;
    }
    private void saveAndClose() {
        if (isCapturing()) {
            validationMessage = text("capture_hint");
            return;
        }
        try {
            var settings = new EngineControlSettings(Float.parseFloat(soundValue), toggleKey,
                    behaviors.stream().map(BehaviorDraft::settings).toList(), soundRpmEnabled,
                    soundNumber(soundRpmAccelerationValue, initial.controls().soundRpmAcceleration()),
                    soundNumber(soundRpmDecelerationValue, initial.controls().soundRpmDeceleration()),
                    soundNumber(soundMaxRpmValue, initial.controls().soundMaxRpm()), toggleChannel);
            settingsSender.accept(new SetGasTurbineSettingsPayload(initial.pos(), settings));
            onClose();
        } catch (IllegalArgumentException ignored) {
            validationMessage = text("invalid_number");
        }
    }

    private void openTransitionMenu(BehaviorDraft draft) {
        if (transitionDraft == draft) { closeTransitionMenu(); return; }
        closeTransitionMenu();
        setFocused(null);
        transitionDraft = draft;
        transitionLabelWidth = Math.max(font.width(text("transition_enter_short")), font.width(text("transition_leave_short")));
        int[] columns = new int[3];
        int choicesWidth = 0;
        for (int column = 0; column < 3; column++) {
            columns[column] = Math.max(font.width(transitionOptionText(column, true)),
                    font.width(transitionOptionText(column + 3, true))) + 12;
            choicesWidth += columns[column];
        }
        int rowWidth = 12 + transitionLabelWidth + 8 + choicesWidth + 4;
        int contentWidth = Math.max(font.width(transitionTitle()), font.width(text("transition_priority"))) + 12;
        transitionWidth = Math.min(Math.max(rowWidth, contentWidth), width - 8);
        transitionRowHeight = font.lineHeight + 9;
        transitionHeight = 22 + 2 * transitionRowHeight + 3 + 5 + font.lineHeight + 6;
        transitionX = Mth.clamp(draft.transitionButton.getX(), 4, width - transitionWidth - 4);
        int below = draft.transitionButton.getY() + draft.transitionButton.getHeight() + 2;
        int preferredY = below + transitionHeight <= height - 4 ? below : draft.transitionButton.getY() - transitionHeight - 2;
        transitionY = Mth.clamp(preferredY, 4, height - transitionHeight - 4);
        var rules = new EngineTransitionRule[]{EngineTransitionRule.DEFAULT, EngineTransitionRule.OUTGOING,
                EngineTransitionRule.INCOMING};
        int availableChoices = transitionWidth - 12 - transitionLabelWidth - 8 - 4;
        int used = 0;
        for (int column = 0; column < 3; column++) {
            columns[column] = column == 2 ? availableChoices - used : columns[column] * availableChoices / choicesWidth;
            used += columns[column];
        }
        for (int option = 0; option < 6; option++) {
            final int chosen = option;
            int column = option % 3;
            int x = transitionX + 6 + transitionLabelWidth + 8;
            for (int previous = 0; previous < column; previous++) x += columns[previous] + 2;
            int y = transitionY + 22 + (option / 3) * (transitionRowHeight + 3);
            var widget = new BrassButton(x, y, columns[column], transitionRowHeight,
                    transitionOptionText(option, false), button -> chooseTransition(chosen < 3, rules[chosen % 3]));
            widget.setTooltip(Tooltip.create(text(TRANSITION_DETAILS[option]).copy().append("\n").append(text("transition_hint"))));
            transitionWidgets.add(addRenderableWidget(widget));
        }
        transitionFocus = draft.enteringRates.ordinal();
        refreshTransitionMenu();
        setFocused(transitionWidgets.get(transitionFocus));
    }
    private void chooseTransition(boolean entering, EngineTransitionRule rule) {
        if (entering) transitionDraft.enteringRates = rule;
        else transitionDraft.leavingRates = rule;
        refreshTransitionMenu();
    }
    private Component transitionTitle() {
        return text("behavior", behaviors.indexOf(transitionDraft) + 1).copy().append(" · ").append(text("transition_rates"));
    }
    private Component transitionOptionText(int option, boolean selected) {
        return Component.literal(selected ? "✓ " : "  ").append(text(TRANSITION_CHOICES[option]));
    }
    private void refreshTransitionMenu() {
        if (transitionDraft == null) return;
        for (int option = 0; option < transitionWidgets.size(); option++) {
            var widget = transitionWidgets.get(option);
            var rule = option < 3 ? transitionDraft.enteringRates : transitionDraft.leavingRates;
            widget.chosen = rule.ordinal() == option % 3;
            widget.setMessage(transitionOptionText(option, widget.chosen));
        }
    }
    private void closeTransitionMenu() {
        for (var widget : transitionWidgets) removeWidget(widget);
        transitionWidgets.clear();
        transitionDraft = null;
        setFocused(null);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (transitionDraft != null) {
            if (keyCode == 256) { closeTransitionMenu(); return true; }
            if (keyCode == 265 || keyCode == 264 || keyCode == 263 || keyCode == 262 || keyCode == 258) {
                if (keyCode == 265 || keyCode == 264) transitionFocus = (transitionFocus + 3) % 6;
                else if (keyCode == 263 || keyCode == 262)
                    transitionFocus = transitionFocus / 3 * 3 + Math.floorMod(transitionFocus % 3 + (keyCode == 263 ? -1 : 1), 3);
                else transitionFocus = Math.floorMod(transitionFocus + ((modifiers & 1) != 0 ? -1 : 1), 6);
                setFocused(transitionWidgets.get(transitionFocus));
            } else if (keyCode == 257 || keyCode == 32 || keyCode == 335) {
                transitionWidgets.get(transitionFocus).onPress();
            }
            return true;
        }
        if (!isCapturing()) return super.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == 256) { capturedSignals.clear(); finishCapture(); return true; }
        if (keyCode == 259 || keyCode == 261) { capturedSignals.clear(); finishCapture(); return true; }
        capture("key:" + keyCode, ControllerKeyMappingCompat.signalForKeyboardKey(REMOTE_TYPE, keyCode, scanCode));
        return true;
    }
    @Override public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (!isCapturing()) return super.keyReleased(keyCode, scanCode, modifiers);
        releaseCaptureInput("key:" + keyCode);
        return true;
    }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (transitionDraft != null) {
            if (button == 0) for (int i = 0; i < transitionWidgets.size(); i++) {
                var widget = transitionWidgets.get(i);
                if (widget.isMouseOver(mouseX, mouseY)) {
                    transitionFocus = i;
                    setFocused(widget);
                    widget.onPress();
                    return true;
                }
            }
            if (mouseX < transitionX || mouseX >= transitionX + transitionWidth
                    || mouseY < transitionY || mouseY >= transitionY + transitionHeight) closeTransitionMenu();
            return true;
        }
        if (isCapturing()) {
            capture("mouse:" + button, ControllerKeyMappingCompat.signalForMouseButton(REMOTE_TYPE, button));
            return true;
        }
        if (button == 0 && layout.maxScroll(behaviors.size()) > 0
                && mouseX >= layout.right() - 15 && mouseX < layout.right() - 8
                && mouseY >= layout.listTop() && mouseY < layout.listBottom()) {
            draggingScrollbar = true;
            scrollbarGrab = mouseY >= thumbTop() && mouseY < thumbTop() + thumbHeight()
                    ? mouseY - thumbTop() : thumbHeight() / 2.0;
            dragScrollbar(mouseY);
            return true;
        }
        if (insideList(mouseX, mouseY)) return super.mouseClicked(mouseX, mouseY, button);
        // Offscreen list controls must not steal clicks from the fixed header/footer.
        for (var card : cardWidgets) card.widget.visible = false;
        try { return super.mouseClicked(mouseX, mouseY, button); }
        finally { positionCards(); }
    }
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (isCapturing() && heldInputs.contains("mouse:" + button)) {
            releaseCaptureInput("mouse:" + button);
            return true;
        }
        draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }
    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar) { dragScrollbar(mouseY); return true; }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (transitionDraft != null) closeTransitionMenu();
        if (isCapturing()) {
            String signal = ControllerKeyMappingCompat.signalForMouseScroll(REMOTE_TYPE, scrollY);
            capture("wheel", signal);
            releaseCaptureInput("wheel");
            return true;
        }
        if (!insideList(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        setFocused(null);
        scroll -= scrollY * 24;
        positionCards();
        return true;
    }
    private int thumbHeight() {
        return Math.min(layout.listHeight(), Math.max(18,
                layout.listHeight() * layout.listHeight() / Math.max(1, layout.contentHeight(behaviors.size()))));
    }
    private int thumbTop() {
        int range = layout.maxScroll(behaviors.size());
        return layout.listTop() + (range == 0 ? 0 : (int) (scroll / range * (layout.listHeight() - thumbHeight())));
    }
    private void dragScrollbar(double mouseY) {
        setFocused(null);
        int track = layout.listHeight() - thumbHeight();
        if (track > 0) scroll = (mouseY - layout.listTop() - scrollbarGrab) / track * layout.maxScroll(behaviors.size());
        positionCards();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x68000000);
        int l = layout.left(), t = layout.top(), r = layout.right(), b = layout.bottom();
        graphics.fill(l, t, r, b, OUTLINE);
        graphics.fill(l + 2, t + 2, r - 2, b - 2, BRASS);
        graphics.fill(l + 4, t + 4, r - 4, b - 4, BACKGROUND);
        graphics.fill(l + 4, t + 4, r - 4, t + 24, 0xFF4B4438);
        drawCentered(graphics, title, l + layout.width() / 2, t + 10, 0xFFF4EBD5, layout.width() - 20);
        Component state = Component.translatable(initial.engineEnabled()
                ? "screen.create_fire_control.gas_turbine.engine_running"
                : "screen.create_fire_control.gas_turbine.engine_stopped");
        if (!layout.compact()) {
            drawClipped(graphics, text(initial.advancedHubConnected() ? "hub_connected" : "hub_missing"),
                    l + 10, t + 30, layout.width() - font.width(state) - 28,
                    initial.advancedHubConnected() ? 0xFF3F7B39 : 0xFF9A3F35);
            graphics.drawString(font, state, r - 10 - font.width(state), t + 30, LABEL, false);
        }
        graphics.drawString(font, text("volume"), l + 10, layout.settingsTop() + 6, LABEL, false);
        if (soundRpmEnabled) {
            int third = (layout.width() - 28) / 3;
            drawClipped(graphics, text("sound_rpm_acceleration"), l + 10, layout.settingsTop() + 50, 36, LABEL);
            drawClipped(graphics, text("sound_rpm_deceleration"), l + 10 + third, layout.settingsTop() + 50, 36, LABEL);
            drawClipped(graphics, text("sound_max_rpm"), l + 10 + third * 2, layout.settingsTop() + 50, 36, LABEL);
        }
        drawClipped(graphics, text("behaviors", behaviors.size()), l + 10, layout.behaviorHeaderTop() + 6, layout.width() - 126, LABEL);
        int widgetMouseX = transitionDraft == null ? mouseX : -1;
        int widgetMouseY = transitionDraft == null ? mouseY : -1;
        for (var widget : globalWidgets) widget.render(graphics, widgetMouseX, widgetMouseY, partialTick);
        graphics.enableScissor(layout.cardLeft(), layout.listTop(), r - 17, layout.listBottom());
        for (int i = 0; i < behaviors.size(); i++) renderCard(graphics, i);
        for (var card : cardWidgets) card.widget.render(graphics, widgetMouseX, widgetMouseY, partialTick);
        graphics.disableScissor();
        if (layout.maxScroll(behaviors.size()) > 0) {
            graphics.fill(r - 14, layout.listTop(), r - 9, layout.listBottom(), 0xFFB5A078);
            graphics.fill(r - 14, thumbTop(), r - 9, thumbTop() + thumbHeight(), 0xFF665A42);
        }
        if (!validationMessage.getString().isEmpty())
            drawClipped(graphics, validationMessage, l + 10, layout.footerHeight() < 42 ? layout.settingsTop() + 28 : b - 38,
                    layout.footerHeight() < 42 ? 108 : layout.width() - 20, 0xFF9A3F35);
        if (transitionDraft != null) renderTransitionMenu(graphics, mouseX, mouseY, partialTick);
        graphics.flush();
    }
    private void renderTransitionMenu(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        graphics.fill(transitionX + 3, transitionY + 3, transitionX + transitionWidth + 3,
                transitionY + transitionHeight + 3, 0x68000000);
        graphics.fill(transitionX, transitionY, transitionX + transitionWidth, transitionY + transitionHeight, OUTLINE);
        graphics.fill(transitionX + 1, transitionY + 1, transitionX + transitionWidth - 1,
                transitionY + transitionHeight - 1, BRASS);
        graphics.fill(transitionX + 3, transitionY + 3, transitionX + transitionWidth - 3,
                transitionY + transitionHeight - 3, BACKGROUND);
        drawClipped(graphics, transitionTitle(), transitionX + 6, transitionY + 6, transitionWidth - 12, LABEL);
        int labelY = transitionY + 22 + (transitionRowHeight - font.lineHeight) / 2;
        graphics.drawString(font, text("transition_enter_short"), transitionX + 6, labelY, LABEL, false);
        graphics.drawString(font, text("transition_leave_short"), transitionX + 6,
                labelY + transitionRowHeight + 3, LABEL, false);
        for (var widget : transitionWidgets) widget.render(graphics, mouseX, mouseY, partialTick);
        drawClipped(graphics, text("transition_priority"), transitionX + 6,
                transitionY + transitionHeight - font.lineHeight - 6, transitionWidth - 12, LABEL);
        graphics.pose().popPose();
    }
    @Override public boolean charTyped(char codePoint, int modifiers) {
        return transitionDraft != null || super.charTyped(codePoint, modifiers);
    }
    private void renderCard(GuiGraphics graphics, int index) {
        int x = layout.cardLeft();
        int y = layout.listTop() + index * (EngineControlLayout.CARD_HEIGHT + EngineControlLayout.CARD_GAP) - (int) scroll;
        if (y >= layout.listBottom() || y + EngineControlLayout.CARD_HEIGHT <= layout.listTop()) return;
        graphics.fill(x, y, x + layout.cardWidth(), y + EngineControlLayout.CARD_HEIGHT, 0xFFAC9363);
        graphics.fill(x + 1, y + 1, x + layout.cardWidth() - 1, y + EngineControlLayout.CARD_HEIGHT - 1, 0xFFECDDB9);
        drawClipped(graphics, text("behavior", index + 1), x + 6, y + 11, 50, LABEL);
        var channel = behaviors.get(index).channel;
        for (int slot = 0; slot < 2; slot++) {
            int sx = x + 112 + slot * 26, sy = y + 31;
            graphics.fill(sx, sy, sx + 20, sy + 20, BRASS);
            graphics.fill(sx + 1, sy + 1, sx + 19, sy + 19, 0xFF665A42);
            graphics.renderItem(slot == 0 ? channel.first() : channel.second(), sx + 2, sy + 2);
        }
        String[] labels = {"speed", "acceleration", "deceleration"};
        for (int column = 0; column < 3; column++)
            drawCentered(graphics, text(labels[column]), layout.columnX(column) + layout.columnWidth() / 2,
                    y + 58, LABEL, layout.columnWidth());
        graphics.drawString(font, text("left"), x + 7, y + 77, LABEL, false);
        graphics.drawString(font, text("right"), x + 7, y + 101, LABEL, false);
    }
    private void drawClipped(GuiGraphics graphics, Component text, int x, int y, int available, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(text.getString(), Math.max(0, available)), x, y, color, false);
    }
    private void drawCentered(GuiGraphics graphics, Component text, int center, int y, int color, int available) {
        String visible = font.plainSubstrByWidth(text.getString(), Math.max(0, available));
        graphics.drawString(font, visible, center - font.width(visible) / 2, y, color, false);
    }
    private static Component text(String key, Object... args) { return Component.translatable(PREFIX + key, args); }
    private Component soundRpmText() {
        return text("sound_rpm").copy().append(": ").append(text(soundRpmEnabled ? "on" : "off"));
    }
    private float soundNumber(String value, float fallback) {
        return soundRpmEnabled || validNumber(value, 0, 1024) ? Float.parseFloat(value) : fallback;
    }
    private static String format(float value) {
        if (value == 0) return "0";
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.?0+$", "");
    }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override public boolean isPauseScreen() { return false; }

    private static final class BehaviorDraft {
        private List<String> keys;
        private final String[] values;
        private BrassButton bindingButton;
        private BrassButton transitionButton;
        private EngineTransitionRule enteringRates, leavingRates;
        private EngineRedstoneChannel channel;
        private BehaviorDraft(EngineDriveBehavior behavior) {
            keys = behavior.keys();
            channel = behavior.redstoneChannel();
            enteringRates = behavior.enteringRates();leavingRates = behavior.leavingRates();
            values = new String[]{format(behavior.leftSpeed()), format(behavior.leftAcceleration()),
                    format(behavior.leftDeceleration()), format(behavior.rightSpeed()),
                    format(behavior.rightAcceleration()), format(behavior.rightDeceleration())};
        }
        private EngineDriveBehavior settings() {
            return new EngineDriveBehavior(keys, Float.parseFloat(values[0]), Float.parseFloat(values[1]),
                    Float.parseFloat(values[2]), Float.parseFloat(values[3]), Float.parseFloat(values[4]), Float.parseFloat(values[5]),
                    enteringRates, leavingRates, channel);
        }
    }
    private record CardWidget(AbstractWidget widget, int offset, boolean binding, boolean deletion) {}
    private static final class BrassButton extends Button {
        private boolean chosen;
        private BrassButton(int x, int y, int width, int height, Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int background = !active ? 0xFF4A4842 : chosen ? 0xFF4B6545
                    : isHovered() || isFocused() ? 0xFF777064 : 0xFF5F5A50;
            graphics.fill(getX(), getY(), getX() + width, getY() + height, OUTLINE);
            graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, BRASS);
            graphics.fill(getX() + 2, getY() + 2, getX() + width - 2, getY() + height - 2, background);
            renderScrollingString(graphics, Minecraft.getInstance().font, 4, active ? 0xFFF4EBD5 : 0xFF9A958A);
        }
    }
}
