package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.FireControlSettings;
import net.createmod.catnip.config.ui.ConfigHelper;
import net.createmod.catnip.config.ui.ConfigScreen;
import net.createmod.catnip.config.ui.BaseConfigScreen;
import net.createmod.catnip.config.ui.ConfigModListScreen;
import net.createmod.catnip.gui.UIRenderHelper;
import net.createmod.catnip.gui.element.TextStencilElement;
import net.createmod.catnip.gui.widget.BoxWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Uses the same stencil widgets, animated colours and cog background as Create's config menu. */
public final class FireControlConfigMenu extends BaseConfigScreen {
    private final String section;
    private Language renderedLanguage;

    public FireControlConfigMenu(Screen parent) {
        this(parent, "root");
    }

    private FireControlConfigMenu(Screen parent, String section) {
        super(parent, StabilizedTurret.MOD_ID);
        withSpecs(ClientScopeConfig.SPEC, FireControlSettings.COMMON_SPEC, FireControlSettings.SERVER_SPEC);
        this.section = section;
        ConfigScreen.modID = StabilizedTurret.MOD_ID;
    }

    public static void register(ModContainer container) {
        IConfigScreenFactory factory = (mod, parent) -> new FireControlConfigMenu(parent);
        container.registerExtensionPoint(IConfigScreenFactory.class, factory);
    }

    @Override
    protected void init() {
        super.init();
        renderedLanguage = Language.getInstance();
        ConfigScreen.modID = StabilizedTurret.MOD_ID;
        int top = height / 2 - 45;
        if (section.equals("client")) {
            specButton("scope", top, ClientScopeConfig.SPEC);
            button("engine_audio", top + 30,
                    () -> Minecraft.getInstance().setScreen(new FireControlConfigMenu(this, "engine_audio")));
            specButton("visuals", top + 60, ClientVisualConfig.SPEC);
        } else if (section.equals("engine_audio")) {
            specButton("tiger_audio", top, TigerISoundConfig.SPEC);
            specButton("ztz_audio", top + 30, Ztz99aDieselSoundConfig.SPEC);
            hideRow(top + 60);
        } else if (section.equals("server")) {
            button("missiles_radar", top, () -> openSpec("missiles_radar", FireControlSettings.SERVER_SPEC, true));
            button("armor",top+30,()->{
                ConfigHelper.changes.clear();ConfigScreen.modID=StabilizedTurret.MOD_ID;
                Minecraft.getInstance().setScreen(new FireControlConfigScreen(this,text("armor"),FireControlSettings.SERVER_SPEC,
                        FireControlSettings.SERVER_SPEC.getValues().get("armor"),true,"server"));
            });
            hideRow(top + 60);
        } else {
            button("client", top, () -> Minecraft.getInstance().setScreen(new FireControlConfigMenu(this, "client")));
            button("common", top + 30, () -> openSpec("common", FireControlSettings.COMMON_SPEC, false));
            button("server", top + 60, () -> Minecraft.getInstance().setScreen(new FireControlConfigMenu(this, "server")));
        }
        // Keep Catnip's actual title widget, gradient, doubled lettering and back-arrow geometry.
        var title = widgetAt(-5, height / 2 - 110);
        var name = new TextStencilElement(font, text("mod_name")).centered(true, true)
                .withElementRenderer((graphics, w, h, alpha) -> {
                    UIRenderHelper.angledGradient(graphics, 0, 0, h / 2, h, w / 2, COLOR_TITLE_A, COLOR_TITLE_B);
                    UIRenderHelper.angledGradient(graphics, 0, w / 2, h / 2, h, w / 2, COLOR_TITLE_B, COLOR_TITLE_C);
                });
        title.showingElement(name.at(0, 7));
        var back = widgetAt(width / 2 - 134, height / 2);
        back.setMessage(text("back"));
        back.getToolTip().clear();
        back.getToolTip().add(text("back"));
        back.withCallback(() -> interact(this::onClose));
        button("other_mods", top + 120,
                () -> Minecraft.getInstance().setScreen(new ConfigModListScreen(this)));
    }

    private void specButton(String key, int y, ModConfigSpec spec) {
        button(key, y, () -> openSpec(key, spec, false));
    }

    void openSpec(String key, ModConfigSpec spec, boolean server) {
        ConfigHelper.changes.clear();
        ConfigScreen.modID = StabilizedTurret.MOD_ID;
        Minecraft.getInstance().setScreen(new FireControlConfigScreen(this,
                text(key), spec, spec.getValues(), server,
                server ? "server" : key.equals("common") ? "common" : "client"));
    }

    private void button(String key, int y, Runnable action) {
        BoxWidget widget = widgetAt(width / 2 - 100, y);
        widget.active = true;
        widget.visible = true;
        widget.getToolTip().clear();
        TextStencilElement label = new TextStencilElement(font, text(key)).centered(true, true);
        label.withElementRenderer(BoxWidget.gradientFactory.apply(widget));
        widget.showingElement(label);
        widget.withCallback(() -> interact(action));
        widget.setMessage(text(key));
    }

    private BoxWidget widgetAt(int x, int y) {
        return children().stream().filter(BoxWidget.class::isInstance).map(BoxWidget.class::cast)
                .filter(widget -> widget.getX() == x && widget.getY() == y).findFirst().orElseThrow();
    }

    private void hideRow(int y) {
        var widget = widgetAt(width / 2 - 100, y);
        widget.visible = false;
        widget.active = false;
    }

    static void interact(Runnable action) {
        ConfigScreen.cogSpin.bump(3, 10);
        action.run();
    }

    static net.minecraft.network.chat.MutableComponent text(String key) {
        return Component.translatable("screen.create_fire_control.config." + key);
    }

    @Override
    public void tick() {
        super.tick();
        if (renderedLanguage != Language.getInstance()) rebuildWidgets();
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.drawCenteredString(font, Component.literal("Access Configs for Mod:"), width / 2,
                height / 2 - 105, UIRenderHelper.COLOR_TEXT_STRONG_ACCENT.getFirst().getRGB());
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
