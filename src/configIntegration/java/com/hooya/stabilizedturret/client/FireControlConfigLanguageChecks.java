package com.hooya.stabilizedturret.client;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.FireControlSettings;
import net.createmod.catnip.config.ui.ConfigHelper;
import net.createmod.catnip.config.ui.ConfigScreenList;
import net.createmod.catnip.config.ui.SubMenuConfigScreen;
import net.createmod.catnip.config.ui.BaseConfigScreen;
import net.createmod.catnip.config.ui.ConfigScreen;
import net.createmod.catnip.gui.widget.BoxWidget;
import net.createmod.catnip.animation.PhysicalFloat;
import net.createmod.catnip.config.ui.entries.EnumEntry;
import net.createmod.catnip.config.ui.entries.SubMenuEntry;
import net.createmod.catnip.config.ui.entries.ValueEntry;
import net.createmod.catnip.gui.element.TextStencilElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Isolated real-client checks: actual resource reloads, translated widgets and pending-edit retention. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class FireControlConfigLanguageChecks {
    private interface Step { void run() throws Exception; }
    private static final Deque<Step> steps = new ArrayDeque<>();
    private static final ModConfigSpec[] SPECS = {ClientScopeConfig.SPEC, ClientVisualConfig.SPEC, TigerISoundConfig.SPEC,
            Ztz99aDieselSoundConfig.SPEC, FireControlSettings.COMMON_SPEC, FireControlSettings.SERVER_SPEC};
    private static int age, settle, checked;
    private static boolean started, finished;
    private static String locale;
    private static Map<String, String> expected, baseline;
    private static CompletableFuture<Void> reload;
    private static FireControlConfigMenu root;
    private static FireControlConfigScreen cached;
    private static double cachedScroll;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.configLanguageChecks") || finished) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (++age > 12000) throw new AssertionError("Language check timed out");
            if (!started) {
                if (age < 80) return;
                if (mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                    mc.setScreen(new TitleScreen());
                    return;
                }
                if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
                started = true;
                prepare(mc);
            }
            if (reload != null) {
                if (!reload.isDone() || mc.getOverlay() != null) return;
                reload.join();
                reload = null;
                expected = languageFile("en_us");
                if (!locale.equals("fr_fr")) expected.putAll(languageFile(locale));
            }
            if (++settle < 10 || mc.getOverlay() != null) return;
            settle = 0;
            if (steps.isEmpty()) {
                ConfigHelper.changes.clear();
                require(snapshotValues().equals(baseline), "Localization changed stored settings or defaults");
                finish(null);
            } else steps.removeFirst().run();
        } catch (Throwable failure) { finish(failure); }
    }

    private static void prepare(Minecraft mc) throws Exception {
        Files.createDirectories(Path.of("config-language-qa"));
        mc.options.pauseOnLostFocus = false;
        mc.options.guiScale().set(2);
        mc.resizeDisplay();
        baseline = snapshotValues();
        for (String code : new String[]{"en_us", "zh_cn", "ru_ru", "zh_tw", "zh_hk", "fr_fr"}) {
            steps.add(() -> {
                locale = code;
                mc.getLanguageManager().setSelected(code);
                mc.options.languageCode = code;
                reload = mc.reloadResourcePacks();
            });
            steps.add(() -> {
                require(mc.getLanguageManager().getSelected().equals(code), "Game language did not switch");
                if (cached != null) {
                    require(mc.screen == cached, "Resource reload replaced the configuration screen");
                    require(search(cached).getValue().equals("reference"), "Language reload lost search text");
                    require(Math.abs(entries(cached).getScrollAmount() - cachedScroll) < 1, "Language reload lost scroll");
                    require(ConfigHelper.changes.size() == 1, "Language reload lost pending edits");
                    require(firstEnum(cached).getValue() == TigerISoundConfig.Mode.LEGACY, "Pending enum value changed");
                    verifyEnum(firstEnum(cached));
                    require(firstEnum(cached).getLabelTooltip().getFirst().getString().equals(tr("config.create_fire_control.mode")),
                            "Cached field label did not refresh");
                    snapshot(code + "-cached-engine");
                }
                ConfigHelper.changes.clear();
                root = new FireControlConfigMenu(new TitleScreen());
                mc.setScreen(root);
                require(hasButton("client") && hasButton("common") && hasButton("server"), "Untranslated root buttons");
                String expectedClient = switch (code) {
                    case "zh_cn" -> "客户端配置";
                    case "zh_tw" -> "客戶端配置";
                    case "zh_hk" -> "客户端配置";
                    case "ru_ru" -> "Настройки клиента";
                    default -> "Client Config";
                };
                require(tr("screen.create_fire_control.config.client").equals(expectedClient), "Wrong root locale: " + code);
                require(tr("screen.create_fire_control.config.mod_name").equals("Create:Fire Control"), "Brand name must stay English");
                verifyNativeGeometry(mc);
                for (ModConfigSpec spec : SPECS) verifyTree(spec, spec.getValues(), List.of());
            });
            steps.add(() -> { snapshot(code + "-root"); click("client"); click("engine_audio"); });
            steps.add(() -> {
                require(hasButton("tiger_audio") && hasButton("ztz_audio"), "Grouped engine menu not localized");
                click("tiger_audio");
                clickGroup(tr("config.create_fire_control.reference"));
            });
            steps.add(() -> {
                var screen = (FireControlConfigScreen) mc.screen;
                verifyEnum(firstEnum(screen));
                snapshot(code + "-engine");
                click("reset");
            });
            steps.add(() -> {
                require(hasButton("confirm") && hasButton("cancel"), "Untranslated confirmation");
                snapshot(code + "-confirmation");
                click("cancel");
                root.openSpec("missiles_radar", FireControlSettings.SERVER_SPEC, true);
                clickGroup(tr("create_fire_control.config.missiles"));
            });
            steps.add(() -> {
                require(entries((SubMenuConfigScreen) mc.screen).children().size() == 33, "Missing missile models");
                snapshot(code + "-missiles");
                clickGroup(tr("create_fire_control.config.missile.antitankmissile_1tanshe"));
            });
            steps.add(() -> {
                var rows = entries((SubMenuConfigScreen) mc.screen).children();
                require(rows.size() == 3, "Missile field count changed");
                var life = (ValueEntry<?>) rows.getFirst();
                require(life.getLabelTooltip().getFirst().getString().equals(tr("create_fire_control.config.lifetimeSeconds")), "Untranslated lifetime");
                String hints = String.join(" ", life.getLabelTooltip().stream().map(c -> c.getString()).toList());
                require(!hints.contains("screen.create_fire_control.config.default_value"), "Unresolved default hint");
                snapshot(code + "-missile-fields");
                root.openSpec("missiles_radar", FireControlSettings.SERVER_SPEC, true);
                clickGroup(tr("create_fire_control.configuration.radar"));
            });
            steps.add(() -> {
                var row = (ValueEntry<?>) entries((SubMenuConfigScreen) mc.screen).children().getFirst();
                String tooltip = String.join(" ", row.getLabelTooltip().stream().map(c -> c.getString()).toList());
                require(tooltip.replaceAll("\\s", "").contains(tr("create_fire_control.configuration.radar.raycast.tooltip")
                        .replaceAll("\\s", "")), "Radar hint not translated");
                snapshot(code + "-radar");
                root.openSpec("missiles_radar", FireControlSettings.SERVER_SPEC, true);
                clickGroup(tr("create_fire_control.configuration.flight"));
            });
            steps.add(() -> {
                var entry = firstEnum((SubMenuConfigScreen) mc.screen);
                verifyEnum(entry);
                require(entry.getValue() == FireControlSettings.MISSILE_TRAJECTORY_MODE.get(), "Flight mode display changed value");
                snapshot(code + "-flight");
                root.openSpec("scope", ClientScopeConfig.SPEC, false);
            });
            steps.add(() -> {
                require(entries((SubMenuConfigScreen) mc.screen).children().size() == 4, "Scope menu must contain four range limits");
                snapshot(code + "-scope");
                root.openSpec("visuals", ClientVisualConfig.SPEC, false);
            });
            steps.add(() -> {
                snapshot(code + "-visual-categories");
                clickGroup(tr("config.create_fire_control.aircraftHud"));
            });
            steps.add(() -> {
                require(entries((SubMenuConfigScreen) mc.screen).children().size() == 4, "HUD settings missing");
                snapshot(code + "-aircraft-hud");
                root.openSpec("visuals", ClientVisualConfig.SPEC, false);
                clickGroup(tr("config.create_fire_control.missileSmoke"));
            });
            steps.add(() -> {
                snapshot(code + "-smoke-lifetime");
                root.openSpec("visuals", ClientVisualConfig.SPEC, false);
                clickGroup(tr("config.create_fire_control.headAim"));
            });
            steps.add(() -> {
                snapshot(code + "-camera-delay");
                root.openSpec("ztz_audio", Ztz99aDieselSoundConfig.SPEC, false);
                clickGroup(tr("config.create_fire_control.takes"));
            });
            steps.add(() -> {
                verifyEnum(firstEnum((SubMenuConfigScreen) mc.screen));
                snapshot(code + "-99a");
                root.openSpec("tiger_audio", TigerISoundConfig.SPEC, false);
                clickGroup(tr("config.create_fire_control.reference"));
                cached = (FireControlConfigScreen) mc.screen;
                @SuppressWarnings("unchecked") var mode = (ValueEntry<Enum<?>>) firstEnum(cached);
                mode.setValue(TigerISoundConfig.Mode.LEGACY);
                search(cached).setValue("reference");
                entries(cached).setScrollAmount(40);
                cachedScroll = entries(cached).getScrollAmount();
                require(ConfigHelper.changes.size() == 1, "Fixture failed to stage enum edit");
            });
        }
        // Exercise the new server enum through real menu controls and global-file persistence.
        steps.add(() -> {
            ConfigHelper.changes.clear();
            root.openSpec("missiles_radar", FireControlSettings.SERVER_SPEC, true);
            clickGroup(tr("create_fire_control.configuration.flight"));
            @SuppressWarnings("unchecked") var entry = (ValueEntry<Enum<?>>) firstEnum((SubMenuConfigScreen) mc.screen);
            entry.setValue(FireControlSettings.TrajectoryMode.ENTITY);
            click("save");
        });
        steps.add(() -> click("confirm"));
        steps.add(() -> {
            require(!FireControlSettings.mathematicalMissileFlight(), "Menu failed to apply entity trajectory");
            require(Files.readString(Path.of("config", FireControlSettings.SERVER_FILE)).contains("trajectoryMode = \"ENTITY\""),
                    "Menu failed to persist trajectory in instance config");
            @SuppressWarnings("unchecked") var entry = (ValueEntry<Enum<?>>) firstEnum((SubMenuConfigScreen) mc.screen);
            entry.setValue(FireControlSettings.TrajectoryMode.MATHEMATICAL);
            click("save");
        });
        steps.add(() -> click("confirm"));
        steps.add(() -> require(FireControlSettings.mathematicalMissileFlight(), "Menu failed to restore mathematical default"));
        steps.add(FireControlConfigLanguageChecks::verifyWheelZoom);
    }

    private static void verifyNativeGeometry(Minecraft mc) {
        var reference = new BaseConfigScreen(new TitleScreen(), "create");
        reference.init(mc, root.width, root.height);
        require(rectangles(root).equals(rectangles(reference)), "Fire-control menu geometry differs from Create's native menu");
        ConfigScreen.modID = StabilizedTurret.MOD_ID;
    }

    private static List<String> rectangles(net.minecraft.client.gui.screens.Screen screen) {
        return screen.children().stream().filter(BoxWidget.class::isInstance).map(BoxWidget.class::cast)
                .map(w -> w.getX() + "," + w.getY() + "," + w.getWidth() + "," + w.getHeight()).sorted().toList();
    }

    private static void verifyWheelZoom() throws Exception {
        var original = new LinkedHashMap<String, Integer>();
        for (String key : List.of("minZoom", "maxZoom", "podMinZoom", "podMaxZoom")) {
            ModConfigSpec.ConfigValue<Integer> field = ClientScopeConfig.SPEC.getValues().get("scope." + key);
            original.put(key, field.get());
        }
        byte[] disk = Files.readAllBytes(Path.of("config", "create_fire_control-client.toml"));
        try {
            zoomLimit("minZoom", 2); zoomLimit("maxZoom", 9); zoomLimit("podMinZoom", 3); zoomLimit("podMaxZoom", 7);
            setAim("active", true);
            for (boolean pod : new boolean[]{false, true}) {
                setAim("scoped", !pod); setAim("podView", pod);
                ClientScopeConfig.setZoom(2); ClientScopeConfig.setPodZoom(3);
                for (int i = 0; i < 120; i++) scroll(1, true);
                require((pod ? ClientScopeConfig.getPodZoom() : ClientScopeConfig.getZoom()) == (pod ? 7 : 9), "Scroll escaped custom upper bound");
                require((pod ? ClientScopeConfig.getZoom() : ClientScopeConfig.getPodZoom()) == (pod ? 2 : 3), "Scroll changed the other optic");
                for (int i = 0; i < 120; i++) scroll(-1, true);
                require((pod ? ClientScopeConfig.getPodZoom() : ClientScopeConfig.getZoom()) == (pod ? 3 : 2), "Scroll escaped custom lower bound");
                zoomLimit(pod ? "podMaxZoom" : "maxZoom", 100);
                for (int i = 0; i < 120; i++) scroll(1, true);
                require((pod ? ClientScopeConfig.getPodZoom() : ClientScopeConfig.getZoom()) == 100, "Wheel did not reach 100x");
                setAim("displayedScopeZoom", 100.0); setAim("displayedPodZoom", 100.0);
                zoomLimit(pod ? "podMaxZoom" : "maxZoom", 8);
                require(ClientAimState.getScopeZoom() <= 8, "Rendered zoom ignored live limit changes");
            }
            setAim("active", false);
            scroll(1, false);
            require(Arrays.equals(disk, Files.readAllBytes(Path.of("config", "create_fire_control-client.toml"))), "Wheel changed saved limits");
        } finally {
            original.forEach(FireControlConfigLanguageChecks::zoomLimit);
            setAim("active", false); setAim("scoped", false); setAim("podView", false);
        }
    }

    private static void zoomLimit(String key, int value) {
        ModConfigSpec.ConfigValue<Integer> field = ClientScopeConfig.SPEC.getValues().get("scope." + key);
        field.set(value);
    }
    private static void setAim(String key, Object value) throws Exception {
        var field = ClientAimState.class.getDeclaredField(key); field.setAccessible(true); field.set(null, value);
    }
    private static void scroll(double direction, boolean expectedCancel) {
        var event = new InputEvent.MouseScrollingEvent(0, direction, false, false, false, 0, 0);
        ClientAimState.onMouseScroll(event);
        require(event.isCanceled() == expectedCancel, "Scroll consumption does not match optical activation");
    }

    private static void verifyTree(ModConfigSpec spec, UnmodifiableConfig group, List<String> path) throws Exception {
        for (var pair : group.valueMap().entrySet()) {
            var full = new ArrayList<>(path); full.add(pair.getKey());
            String explicit = pair.getValue() instanceof ModConfigSpec.ConfigValue<?> value
                    ? ((ModConfigSpec.ValueSpec) spec.getSpec().get(value.getPath())).getTranslationKey()
                    : spec.getLevelTranslationKey(full);
            String[] keys = {explicit, "config.create_fire_control." + String.join(".", full),
                    "config.create_fire_control." + pair.getKey()};
            String key = Arrays.stream(keys).filter(Objects::nonNull).filter(expected::containsKey).findFirst()
                    .orElseThrow(() -> new AssertionError("Missing translation: " + locale + " / " + full));
            require(FireControlConfigLanguage.label(full, explicit).getString().equals(tr(key)), "Wrong label: " + full);
            checked++;
            if (pair.getValue() instanceof UnmodifiableConfig nested) verifyTree(spec, nested, full);
        }
    }

    private static Map<String, String> languageFile(String code) throws Exception {
        var id = ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "lang/" + code + ".json");
        try (var reader = Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).openAsReader()) {
            return new Gson().fromJson(reader, new TypeToken<LinkedHashMap<String, String>>() {}.getType());
        }
    }

    private static String tr(String key) {
        require(expected.containsKey(key), "Missing locale key " + locale + ": " + key);
        require(I18n.get(key).equals(expected.get(key)), "Wrong runtime translation " + key);
        return expected.get(key);
    }

    private static Map<String, String> snapshotValues() {
        var result = new TreeMap<String, String>();
        for (int i = 0; i < SPECS.length; i++) collectValues(SPECS[i].getValues(), i + ":", result);
        return result;
    }
    private static void collectValues(UnmodifiableConfig group, String path, Map<String, String> values) {
        group.valueMap().forEach((key, obj) -> {
            if (obj instanceof UnmodifiableConfig nested) collectValues(nested, path + key + ".", values);
            else if (obj instanceof ModConfigSpec.ConfigValue<?> value)
                values.put(path + key, value.get() + " / default=" + value.getDefault());
        });
    }

    private static void verifyEnum(EnumEntry entry) throws Exception {
        var field = EnumEntry.class.getDeclaredField("valueText"); field.setAccessible(true);
        var text = (TextStencilElement) field.get(entry);
        String key = "config.create_fire_control.enum." + entry.getValue().name().toLowerCase(Locale.ROOT);
        require(text.getComponent().getString().equals(tr(key)), "Enum choice did not translate");
    }
    private static EnumEntry firstEnum(SubMenuConfigScreen screen) throws Exception {
        return entries(screen).children().stream().filter(EnumEntry.class::isInstance).map(EnumEntry.class::cast)
                .findFirst().orElseThrow();
    }
    private static ConfigScreenList entries(SubMenuConfigScreen screen) throws Exception {
        var field = SubMenuConfigScreen.class.getDeclaredField("list"); field.setAccessible(true);
        return (ConfigScreenList) field.get(screen);
    }
    private static EditBox search(SubMenuConfigScreen screen) throws Exception {
        var field = SubMenuConfigScreen.class.getDeclaredField("search"); field.setAccessible(true);
        return (EditBox) field.get(screen);
    }
    private static boolean hasButton(String key) {
        return Minecraft.getInstance().screen.children().stream().anyMatch(child -> child instanceof AbstractWidget widget
                && widget.getMessage().getString().equals(tr("screen.create_fire_control.config." + key)));
    }
    private static void click(String key) {
        String label = tr("screen.create_fire_control.config." + key);
        var widget = Minecraft.getInstance().screen.children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(w -> w.getMessage().getString().equals(label)).findFirst().orElseThrow();
        int before = cogForces();
        require(widget.mouseClicked(widget.getX() + widget.getWidth() / 2., widget.getY() + widget.getHeight() / 2., 0), "Click failed");
        if (Minecraft.getInstance().screen instanceof FireControlConfigMenu || key.equals("reset") || key.equals("save"))
            require(cogForces() > before, "Click did not accelerate the native cog");
    }
    private static int cogForces() {
        try {
            var field = PhysicalFloat.class.getDeclaredField("forces"); field.setAccessible(true);
            return ((List<?>) field.get(ConfigScreen.cogSpin)).size();
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void clickGroup(String label) throws Exception {
        var screen = (SubMenuConfigScreen) Minecraft.getInstance().screen;
        var row = entries(screen).children().stream().filter(SubMenuEntry.class::isInstance)
                .filter(e -> e.getNarration().getString().equals(label)).findFirst().orElseThrow();
        var widget = row.getGuiListeners().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .findFirst().orElseThrow();
        require(widget.mouseClicked(widget.getX() + 2, widget.getY() + 2, 0), "Submenu click failed");
    }
    private static void snapshot(String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            image.writeToFile(Path.of("config-language-qa", name + ".png"));
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void finish(Throwable failure) {
        finished = true;
        if (failure != null) failure.printStackTrace();
        try {
            Files.writeString(Path.of("config-language-client-result.txt"), failure == null
                    ? "PASS: " + checked + " translated config labels, en_us/zh_cn/ru_ru/zh_tw/zh_hk, fr_fr English fallback, "
                    + "actual game resource reloads, cached labels/enums/search/scroll/pending edits, unchanged stored values/defaults"
                    : "FAIL: " + failure);
        } catch (Exception io) { io.printStackTrace(); }
        Minecraft.getInstance().stop();
    }
}
