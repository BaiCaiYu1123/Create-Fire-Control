package com.hooya.stabilizedturret.client;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.FireControlSettings;
import net.createmod.catnip.config.ui.ConfigHelper;
import net.createmod.catnip.config.ui.ConfigScreen;
import net.createmod.catnip.config.ui.ConfigScreenList;
import net.createmod.catnip.config.ui.SubMenuConfigScreen;
import net.createmod.catnip.config.ui.entries.BooleanEntry;
import net.createmod.catnip.config.ui.entries.EnumEntry;
import net.createmod.catnip.config.ui.entries.NumberEntry;
import net.createmod.catnip.config.ui.entries.StringEntry;
import net.createmod.catnip.config.ui.entries.SubMenuEntry;
import net.createmod.catnip.config.ui.entries.ValueEntry;
import net.createmod.catnip.gui.ConfirmationScreen;
import net.createmod.catnip.gui.element.TextStencilElement;
import net.createmod.catnip.gui.widget.BoxWidget;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Translates Catnip's generated entries and routes server changes through our instance-wide config. */
public final class FireControlConfigScreen extends SubMenuConfigScreen {
    private final boolean serverSettings;
    private final String category;
    private final Component localizedTitle;
    private Language renderedLanguage;
    private List<String> groupPath;
    private final List<ConfigScreenList.Entry> translatedEntries = new ArrayList<>();
    private final Map<ConfigScreenList.Entry, String> searchNames = new IdentityHashMap<>();
    private final Map<ValueEntry<?>, ModConfigSpec.ConfigValue<?>> valueFields = new IdentityHashMap<>();
    private final Map<ValueEntry<?>, Object> observedValues = new IdentityHashMap<>();

    FireControlConfigScreen(Screen parent, Component title, ModConfigSpec spec, UnmodifiableConfig group,
                            boolean serverSettings, String category) {
        this(parent, title, spec, group, serverSettings, category, List.of());
    }

    private FireControlConfigScreen(Screen parent, Component title, ModConfigSpec spec, UnmodifiableConfig group,
                                    boolean serverSettings, String category, List<String> groupPath) {
        // Native SERVER editing saves into a world and uses Catnip's single-spec packet. Our explicit
        // COMMON spec is persisted in config/ and synced by FireControlSettings instead.
        super(parent, title.getString(), ModConfig.Type.COMMON, spec, group);
        this.localizedTitle = title;
        this.serverSettings = serverSettings;
        this.category = category;
        this.groupPath = groupPath;
        // Catnip automatically opens a plain (untranslated) screen for single-group specs. Flatten
        // those levels ourselves so every descendant continues to use this translated editor.
        while (this.configGroup.valueMap().size() == 1) {
            var only = this.configGroup.valueMap().entrySet().iterator().next();
            if (!(only.getValue() instanceof UnmodifiableConfig nested)) break;
            var path = new ArrayList<>(this.groupPath);
            path.add(only.getKey());
            this.groupPath = List.copyOf(path);
            this.configGroup = nested;
        }
        ConfigScreen.modID = StabilizedTurret.MOD_ID;
    }

    @Override
    protected void init() {
        // Minecraft can reinitialize the active screen itself during a language/resource reload,
        // before tick() sees the new Language instance. Preserve navigation state on every init.
        String previousQuery = search == null ? "" : search.getValue();
        double previousScroll = list == null ? 0 : list.getScrollAmount();
        if (list != null) ConfigScreenList.currentText = null;
        ConfigScreen.modID = StabilizedTurret.MOD_ID;
        title = localizedTitle.getString();
        super.init();
        renderedLanguage = Language.getInstance();
        translatedEntries.clear();
        searchNames.clear();
        valueFields.clear();
        observedValues.clear();
        for (var pair : configGroup.valueMap().entrySet()) {
            String key = pair.getKey();
            Object setting = pair.getValue();
            if (setting instanceof UnmodifiableConfig nested) {
                var path = new ArrayList<>(groupPath);
                path.add(key);
                Component name = FireControlConfigLanguage.label(path, spec.getLevelTranslationKey(path));
                var entry = new TranslatedSubMenuEntry(this, name, spec, nested, path);
                translatedEntries.add(entry);
                searchNames.put(entry, name.getString() + " " + String.join(".", path));
            } else if (setting instanceof ModConfigSpec.ConfigValue<?> value) {
                ModConfigSpec.ValueSpec valueSpec = spec.getSpec().get(value.getPath());
                String label = label(value.getPath(), valueSpec.getTranslationKey());
                ConfigScreenList.Entry entry = entry(label, value, valueSpec);
                if (entry instanceof ValueEntry<?> valueEntry) {
                    valueFields.put(valueEntry, value);
                    observedValues.put(valueEntry, value.get());
                }
                if (entry instanceof ConfigScreenList.LabeledEntry labeled) {
                    String tooltip = FireControlConfigLanguage.tooltipKey(value.getPath(), valueSpec.getTranslationKey());
                    labeled.getLabelTooltip().clear();
                    labeled.getLabelTooltip().add(Component.literal(label));
                    if (tooltip != null) {
                        labeled.getLabelTooltip().addAll(FontHelper.cutTextComponent(
                                Component.translatable(tooltip), FontHelper.Palette.ALL_GRAY));
                    }
                    labeled.getLabelTooltip().add(Component.translatable("screen.create_fire_control.config.default_value",
                            FireControlConfigLanguage.value(value.getDefault())));
                }
                translatedEntries.add(entry);
                searchNames.put(entry, label + " " + String.join(".", value.getPath()));
            }
        }
        list.children().clear();
        list.children().addAll(translatedEntries);
        search.setHint(FireControlConfigMenu.text("search"));
        search.setResponder(query -> {
            String needle = query.toLowerCase(Locale.ROOT);
            list.children().clear();
            for (var entry : translatedEntries) {
                if (searchNames.getOrDefault(entry, "").toLowerCase(Locale.ROOT).contains(needle)) {
                    list.children().add(entry);
                }
            }
            list.setScrollAmount(0);
        });
        localizeControl(saveChanges, "save");
        localizeControl(discardChanges, "discard");
        localizeControl(resetAll, "reset");
        localizeControl(goBack, "back");
        saveChanges.withCallback(() -> FireControlConfigMenu.interact(() -> confirm("save_prompt", this::saveChanges)));
        discardChanges.withCallback(() -> FireControlConfigMenu.interact(() -> confirm("discard_prompt", this::clearChanges)));
        resetAll.withCallback(() -> FireControlConfigMenu.interact(() -> confirm("reset_prompt", () -> resetConfig(spec.getValues()))));
        goBack.withCallback(() -> FireControlConfigMenu.interact(this::onClose));
        applyReadOnly();
        search.setValue(previousQuery);
        list.setScrollAmount(previousScroll);
    }

    private void localizeControl(BoxWidget widget, String key) {
        widget.setMessage(FireControlConfigMenu.text(key));
        widget.getToolTip().clear();
        widget.getToolTip().add(FireControlConfigMenu.text(key));
        String hint = "screen.create_fire_control.config." + key + "_tooltip";
        if (I18n.exists(hint)) widget.getToolTip().addAll(FontHelper.cutTextComponent(
                Component.translatable(hint), FontHelper.Palette.ALL_GRAY));
    }

    private void confirm(String key, Runnable action) {
        new LocalizedConfirmation(false).centered().withText(FireControlConfigMenu.text(key))
                .withAction(accepted -> { if (accepted) action.run(); }).open(this);
    }

    @Override
    public void showLeavingPrompt(Consumer<ConfirmationScreen.Response> action) {
        new LocalizedConfirmation(true).centered().withText(
                Component.translatable("screen.create_fire_control.config.leaving_prompt", ConfigHelper.changes.size()))
                .withThreeActions(action).open(this);
    }

    @Override
    public void onClose() {
        ConfigScreenList.currentText = null;
        if (ConfigHelper.changes.isEmpty() || parent instanceof FireControlConfigScreen) {
            Minecraft.getInstance().setScreen(parent);
            return;
        }
        showLeavingPrompt(response -> {
            if (response == ConfirmationScreen.Response.Cancel) return;
            if (response == ConfirmationScreen.Response.Confirm) saveChanges();
            else clearChanges();
            Minecraft.getInstance().setScreen(parent);
        });
    }

    private void applyReadOnly() {
        if (!editable()) {
            resetAll.active = false;
            for (var entry : translatedEntries) {
                if (entry instanceof ValueEntry<?>) {
                    for (var widget : entry.getGuiListeners()) {
                        if (widget instanceof AbstractWidget control) control.active = false;
                        if (widget instanceof EditBox text) text.setEditable(false);
                    }
                }
            }
        }
    }

    @Override
    public void tick() {
        if (renderedLanguage != Language.getInstance()) {
            rebuildWidgets();
        }
        super.tick();
        // Saving to an integrated server is queued, and remote servers acknowledge asynchronously.
        // Refresh text and toggle visuals when that authoritative value arrives, without replacing
        // text the player has started editing in the meantime.
        valueFields.forEach((entry, field) -> {
            String path = String.join(".", field.getPath());
            Object current = field.get();
            if (!ConfigHelper.changes.containsKey(path) && !Objects.equals(observedValues.get(entry), current)) {
                observedValues.put(entry, current);
                entry.onValueChange();
            }
        });
        applyReadOnly();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ConfigScreenList.Entry entry(String label, ModConfigSpec.ConfigValue<?> value,
                                         ModConfigSpec.ValueSpec valueSpec) {
        Object current = value.get();
        if (current instanceof Boolean) return new BooleanEntry(label, (ModConfigSpec.ConfigValue) value, valueSpec);
        if (current instanceof Enum<?>) return new LocalizedEnumEntry(label, (ModConfigSpec.ConfigValue) value, valueSpec);
        if (current instanceof Number) return NumberEntry.create(current, label, value, valueSpec);
        if (current instanceof String) return new StringEntry(label, (ModConfigSpec.ConfigValue) value, valueSpec);
        return new ConfigScreenList.LabeledEntry(label);
    }

    private boolean editable() {
        return !serverSettings || FireControlSettings.canEditServer() && FireControlSettings.serverValuesSynced();
    }

    @Override
    protected void resetConfig(UnmodifiableConfig group) {
        if (editable()) super.resetConfig(group);
    }

    @Override
    protected void saveChanges() {
        if (!editable()) return;
        if (!serverSettings) {
            super.saveChanges();
            return;
        }
        var values = new LinkedHashMap<String, Object>();
        for (String path : ConfigHelper.changes.keySet()) {
            ModConfigSpec.ConfigValue<?> value = spec.getValues().get(path);
            if (value != null) values.put(path, ConfigHelper.getValue(path, value));
        }
        FireControlSettings.saveServerChanges(values);
        clearChanges();
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var heading = FireControlConfigMenu.text("mod_name").append(" · ").append(FireControlConfigMenu.text(category));
        if (!localizedTitle.getString().equals(FireControlConfigMenu.text(category).getString()))
            heading.append(" · ").append(localizedTitle);
        graphics.drawCenteredString(font, heading, width / 2, 15, 0x8AB5CC);
        if (serverSettings && !editable()) {
            graphics.drawCenteredString(font, FireControlConfigMenu.text("server_locked"),
                    width / 2, 27, 0xD8AA77);
        }
    }

    static String label(List<String> path, String explicitKey) {
        return FireControlConfigLanguage.label(path, explicitKey).getString();
    }

    private static final class LocalizedEnumEntry extends EnumEntry {
        LocalizedEnumEntry(String label, ModConfigSpec.ConfigValue<Enum<?>> value, ModConfigSpec.ValueSpec spec) {
            super(label, value, spec);
            onValueChange();
        }
        @Override public void onValueChange(Enum<?> value) {
            super.onValueChange(value);
            valueText.withText(FireControlConfigLanguage.value(value));
        }
    }

    private static final class TranslatedSubMenuEntry extends SubMenuEntry {
        private final Component name;
        TranslatedSubMenuEntry(FireControlConfigScreen parent, Component name, ModConfigSpec spec,
                              UnmodifiableConfig group, List<String> path) {
            super(parent, name.getString(), spec, group);
            this.name = name;
            this.path = String.join(".", path);
            button.withCallback(() -> FireControlConfigMenu.interact(() -> Minecraft.getInstance().setScreen(new FireControlConfigScreen(
                    parent, name, spec, group, parent.serverSettings, parent.category, path))));
        }

        @Override
        public Component getNarration() {
            return name.copy();
        }
    }

    /** Catnip's confirmation geometry and animated widgets, with this mod's language keys. */
    private static final class LocalizedConfirmation extends ConfirmationScreen {
        private final boolean threeChoices;
        private Language renderedLanguage;

        LocalizedConfirmation(boolean threeChoices) {
            this.threeChoices = threeChoices;
        }

        @Override
        protected void init() {
            super.init();
            renderedLanguage = Language.getInstance();
            String[] labels = threeChoices ? new String[]{"save", "dont_save", "cancel"}
                    : new String[]{"confirm", "cancel"};
            int index = 0;
            for (var child : children()) {
                if (!(child instanceof BoxWidget widget) || index >= labels.length) continue;
                var text = FireControlConfigMenu.text(labels[index++]);
                var label = new TextStencilElement(font, text).centered(true, true);
                label.withElementRenderer(BoxWidget.gradientFactory.apply(widget));
                widget.showingElement(label);
                widget.setMessage(text);
            }
        }

        @Override public void tick() {
            super.tick();
            if (renderedLanguage != Language.getInstance()) rebuildWidgets();
        }

        @Override public boolean mouseClicked(double x, double y, int button) {
            boolean handled = super.mouseClicked(x, y, button);
            if (handled) ConfigScreen.cogSpin.bump(3, 10);
            return handled;
        }
    }
}
