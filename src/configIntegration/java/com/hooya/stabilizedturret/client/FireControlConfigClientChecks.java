package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.FireControlSettings;
import net.createmod.catnip.config.ui.ConfigScreenList;
import net.createmod.catnip.config.ui.ConfigHelper;
import net.createmod.catnip.config.ui.SubMenuConfigScreen;
import net.createmod.catnip.config.ui.entries.SubMenuEntry;
import net.createmod.catnip.config.ui.entries.ValueEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Real client navigation, Chinese rendering, global-file save and return-path verification. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class FireControlConfigClientChecks {
    private static int age, settle, stage, titleStage, titleSettle;
    private static boolean launched, finished, originalRadar, originalShortcut, originalArmor;
    private static Screen pause, root, serverRoot, gameplayRoot;
    private static Screen titleParent, titleRoot;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.configClientChecks") || finished) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (++age > 2400) throw new AssertionError("Configuration client check timed out");
            if (!launched && age > 80 && mc.screen != null
                    && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                mc.setScreen(new TitleScreen());
                return;
            }
            if (!launched && age > 80 && mc.getOverlay() == null
                    && (mc.screen instanceof TitleScreen || titleStage > 0)) {
                if (++titleSettle < 20) return;
                titleSettle = 0;
                checkTitleMenu(mc);
                return;
            }
            if (mc.level == null || mc.player == null || ++settle < 30) return;
            settle = 0;
            switch (stage++) {
                case 0 -> {
                    mc.options.pauseOnLostFocus = false;
                    mc.options.guiScale().set(2);
                    mc.resizeDisplay();
                    mc.getToasts().clear();
                    mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                    Files.createDirectories(Path.of("config-ui-qa"));
                    originalRadar = FireControlSettings.RADAR_RAYCAST.get();
                    originalArmor = FireControlSettings.EQUIVALENT_ARMOR.get();
                    originalShortcut = FireControlSettings.SHOW_PAUSE_BUTTON.get();
                    pause = new PauseScreen(true);
                    mc.setScreen(pause);
                }
                case 1 -> {
                    snapshot("01-pause-button");
                    var button = pause.children().stream().filter(FireControlConfigButton.class::isInstance)
                            .map(FireControlConfigButton.class::cast).findFirst()
                            .orElseThrow(() -> new AssertionError("Pause-menu config button missing"));
                    require(button.getWidth() == 20 && button.getHeight() == 20, "Config entry is not square");
                    button.onPress();
                    root = mc.screen;
                }
                case 2 -> {
                    require(mc.screen instanceof FireControlConfigMenu, "Wrong configuration root");
                    for (String name : new String[]{"客户端配置", "通用配置", "服务端配置"}) require(hasButton(mc.screen, name), name);
                    snapshot("02-root-chinese");
                    click("服务端配置");
                    serverRoot = mc.screen;
                }
                case 3 -> {
                    snapshot("03-server-categories");
                    click("导弹参数与雷达");
                    gameplayRoot = mc.screen;
                }
                case 4 -> {
                    require(mc.screen instanceof FireControlConfigScreen, "Untranslated generated config screen");
                    snapshot("04-missiles-and-radar");
                    clickGroup("雷达");
                }
                case 5 -> {
                    snapshot("05-radar-raycast");
                    var screen = (FireControlConfigScreen) mc.screen;
                    var entry = entries(screen).children().stream().filter(ValueEntry.class::isInstance)
                            .map(ValueEntry.class::cast).findFirst().orElseThrow();
                    entry.setValue(!originalRadar);
                }
                case 6 -> click("保存修改");
                case 7 -> {
                    require(hasButton(mc.screen, "确认") && hasButton(mc.screen, "取消"), "Save prompt is not Chinese");
                    snapshot("05b-save-confirmation");
                    click("确认");
                }
                case 8 -> {
                    require(FireControlSettings.RADAR_RAYCAST.get() != originalRadar, "Server change did not apply");
                    Path global = FMLPaths.CONFIGDIR.get().resolve(FireControlSettings.SERVER_FILE);
                    String file = Files.readString(global);
                    require(file.contains("raycast = " + !originalRadar), "Server config not persisted globally");
                    var entry = entries((SubMenuConfigScreen) mc.screen).children().stream().filter(ValueEntry.class::isInstance)
                            .map(ValueEntry.class::cast).findFirst().orElseThrow();
                    entry.setValue(originalRadar);
                }
                case 9 -> click("保存修改");
                case 10 -> click("确认");
                case 11 -> mc.setScreen(gameplayRoot);
                case 12 -> clickGroup(Component.translatable("create_fire_control.config.missiles").getString());
                case 13 -> {
                    snapshot("06-all-missile-models");
                    var first = entries((SubMenuConfigScreen) mc.screen).children().stream()
                            .filter(SubMenuEntry.class::isInstance).findFirst().orElseThrow();
                    clickEntry(first);
                }
                case 14 -> {
                    long fields = entries((SubMenuConfigScreen) mc.screen).children().stream().filter(ValueEntry.class::isInstance).count();
                    require(fields == 3, "A missile must have individual lifetime, speed and powered duration fields");
                    snapshot("07-missile-settings");
                    mc.setScreen(root);
                }
                case 15 -> click("客户端配置");
                case 16 -> {
                    snapshot("08-client-categories");
                    click("瞄准镜与光电吊舱");
                }
                case 17 -> {
                    snapshot("09-client-scope-settings");
                    mc.setScreen(root);
                    click("通用配置");
                }
                case 18 -> {
                    snapshot("10-common-settings");
                    require(FireControlSettings.RADAR_RAYCAST.get() == originalRadar, "Fixture did not restore original config");
                    var entry = entries((SubMenuConfigScreen) mc.screen).children().stream().filter(ValueEntry.class::isInstance)
                            .map(ValueEntry.class::cast).findFirst().orElseThrow();
                    entry.setValue(!originalShortcut);
                    mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
                }
                case 19 -> {
                    require(hasButton(mc.screen, "保存修改") && hasButton(mc.screen, "不保存") && hasButton(mc.screen, "取消"),
                            "Escape did not show the Chinese save/discard/cancel prompt");
                    snapshot("11-pending-exit-confirmation");
                    click("取消");
                }
                case 20 -> {
                    require(mc.screen instanceof FireControlConfigScreen && !ConfigHelper.changes.isEmpty(),
                            "Cancel discarded the user's pending edit");
                    click("返回");
                }
                case 21 -> click("不保存");
                case 22 -> {
                    require(mc.screen == root, "Discard did not return to configuration root");
                    require(FireControlSettings.SHOW_PAUSE_BUTTON.get() == originalShortcut && ConfigHelper.changes.isEmpty(),
                            "Discard wrote the pending common setting");
                    root.onClose();
                }
                case 23 -> {
                    require(mc.screen == pause, "Closing the config root did not return to pause menu");
                    snapshot("12-returned-to-pause");
                    mc.setScreen(serverRoot);
                    click("装甲与穿透");
                }
                case 24 -> {
                    require(mc.screen instanceof FireControlConfigScreen,"Armor button did not open translated server settings");
                    var rows=entries((SubMenuConfigScreen)mc.screen).children();
                    require(rows.size()==1&&rows.getFirst() instanceof ValueEntry<?>,"Armor submenu is missing its global switch");
                    var entry=(ValueEntry<Boolean>)rows.getFirst();
                    require(entry.getLabelTooltip().getFirst().getString().equals("全局等效装甲计算"),"Armor switch is not translated");
                    require(Boolean.valueOf(originalArmor).equals(entry.getValue()),"Armor switch shows the wrong value");
                    snapshot("13-equivalent-armor-server");entry.setValue(!originalArmor);
                }
                case 25 -> click("保存修改");
                case 26 -> click("确认");
                case 27 -> {
                    require(FireControlSettings.equivalentArmorEnabled()!=originalArmor,"Armor switch did not apply");
                    require(Files.readString(FMLPaths.CONFIGDIR.get().resolve(FireControlSettings.SERVER_FILE))
                            .contains("equivalentArmor = "+!originalArmor),"Armor switch was not persisted to the server config");
                    ((ValueEntry<Boolean>)entries((SubMenuConfigScreen)mc.screen).children().getFirst()).setValue(originalArmor);
                }
                case 28 -> click("保存修改");
                case 29 -> click("确认");
                case 30 -> mc.screen.onClose();
                case 31 -> {
                    require(mc.screen==serverRoot&&FireControlSettings.equivalentArmorEnabled()==originalArmor,"Armor submenu did not restore its value and return to server categories");
                    finish(null);
                }
                default -> throw new AssertionError("Unexpected stage");
            }
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private static void checkTitleMenu(Minecraft mc) throws Exception {
        switch (titleStage++) {
            case 0 -> {
                mc.options.pauseOnLostFocus = false;
                mc.options.guiScale().set(2);
                mc.resizeDisplay();
                Files.createDirectories(Path.of("config-ui-qa"));
                titleParent = mc.screen;
            }
            case 1 -> {
                snapshot("00-title-button");
                var button = mc.screen.children().stream().filter(FireControlConfigButton.class::isInstance)
                        .map(FireControlConfigButton.class::cast).findFirst()
                        .orElseThrow(() -> new AssertionError("Title-screen config button missing"));
                require(mc.level == null && button.getWidth() == 20 && button.getHeight() == 20,
                        "Title entry must render without a world and remain square");
                button.onPress();
                titleRoot = mc.screen;
            }
            case 2 -> {
                require(mc.screen instanceof FireControlConfigMenu && mc.level == null,
                        "Title entry did not open the configuration root offline");
                snapshot("00a-title-config-root");
                click("服务端配置");
            }
            case 3 -> click("导弹参数与雷达");
            case 4 -> clickGroup("雷达");
            case 5 -> {
                require(FireControlSettings.serverValuesSynced() && FireControlSettings.canEditServer(),
                        "Offline gameplay settings must be editable from the title screen");
                snapshot("00b-title-offline-settings");
                mc.setScreen(titleRoot);
                titleRoot.onClose();
            }
            case 6 -> {
                require(mc.screen == titleParent, "Closing the title config must return to the original title screen");
                snapshot("00c-returned-to-title");
                launched = true;
                mc.createWorldOpenFlows().createFreshLevel("ConfigMenu-" + System.currentTimeMillis(),
                        new LevelSettings("Configuration UI QA", GameType.CREATIVE, false,
                                Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234, false, false), WorldPresets::createNormalWorldDimensions, mc.screen);
            }
            default -> throw new AssertionError("Unexpected title-menu stage");
        }
    }

    private static boolean hasButton(Screen screen, String name) {
        return screen.children().stream().anyMatch(child -> child instanceof AbstractWidget widget
                && widget.getMessage().getString().equals(name));
    }

    private static void click(String name) {
        var widget = Minecraft.getInstance().screen.children().stream()
                .filter(child -> child instanceof AbstractWidget control && control.getMessage().getString().equals(name))
                .map(AbstractWidget.class::cast).findFirst().orElseThrow(() -> new AssertionError("Missing button " + name));
        require(widget.mouseClicked(widget.getX() + widget.getWidth() / 2., widget.getY() + widget.getHeight() / 2., 0),
                "Button did not accept click: " + name);
    }

    private static ConfigScreenList entries(SubMenuConfigScreen screen) throws Exception {
        var field = SubMenuConfigScreen.class.getDeclaredField("list");
        field.setAccessible(true);
        return (ConfigScreenList) field.get(screen);
    }

    private static void clickGroup(String name) throws Exception {
        var entry = entries((SubMenuConfigScreen) Minecraft.getInstance().screen).children().stream()
                .filter(SubMenuEntry.class::isInstance)
                .filter(row -> row.getNarration().getString().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("Missing translated group " + name));
        clickEntry(entry);
    }

    private static void clickEntry(ConfigScreenList.Entry entry) {
        var widget = entry.getGuiListeners().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).findFirst().orElseThrow();
        require(widget.mouseClicked(widget.getX() + 2, widget.getY() + 2, 0), "Submenu click did not open");
    }

    private static void snapshot(String name) throws Exception {
        var mc = Minecraft.getInstance();
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(Path.of("config-ui-qa", name + ".png"));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void finish(Throwable failure) {
        finished = true;
        if (failure != null) failure.printStackTrace();
        try {
            Files.writeString(Path.of("config-ui-client-result.txt"), failure == null
                    ? "PASS: title and pause square icons, offline title configuration and return, Chinese navigation, "
                    + "individual missile fields, global config save/restore, Chinese prompts and Escape/back/save/discard/cancel, "
                    + "equivalent armor default/off, translated server submenu, authoritative save/restore and return"
                    : "FAIL: " + failure);
        } catch (Exception io) { io.printStackTrace(); }
        Minecraft.getInstance().stop();
    }
}
