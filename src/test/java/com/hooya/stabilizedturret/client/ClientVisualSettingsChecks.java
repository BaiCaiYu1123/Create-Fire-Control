package com.hooya.stabilizedturret.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Independently compares original native smoke lifetimes and the pre-change camera response. */
public final class ClientVisualSettingsChecks {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(root);
        FMLPaths.loadAbsolutePaths(root); FMLConfig.load();
        FMLConfig.updateConfig(FMLConfig.ConfigValue.DISABLE_CONFIG_WATCHER, true);
        var tracker = new ConfigTracker();
        tracker.registerConfig(ModConfig.Type.CLIENT, ClientVisualConfig.SPEC, new TestContainer(), ClientVisualConfig.FILE);
        Path directory = root.resolve("config"); Files.createDirectories(directory);
        Files.deleteIfExists(directory.resolve(ClientVisualConfig.FILE));
        tracker.loadConfigs(ModConfig.Type.CLIENT, directory);
        require(ClientVisualConfig.showAircraftHud() && ClientVisualConfig.showBombIndicator() && ClientVisualConfig.showRocketImpact(), "Original visible defaults changed");
        require(ClientVisualConfig.hudTextColor(0xEEDDE5E8) == 0xEEDDE5E8
                && ClientVisualConfig.hudTextColor(0xEEF2F2F2) == 0xEEF2F2F2, "Original HUD colours changed");
        try (var nativeJar = new JarFile(args[1]); var baseline = new JarFile(args[2])) {
            for (String type : new String[]{"Newsmoke2Particle", "Newsmoke3Particle"}) {
                ClassNode node = read(nativeJar, "net/mcreator/myfirstmod/client/particle/" + type);
                int ticks = 0;
                for (var method : node.methods) if (method.name.equals("<init>")) for (var insn : method.instructions)
                    if (insn instanceof FieldInsnNode field && field.name.equals("lifetime") && field.getPrevious() instanceof IntInsnNode value) ticks = value.operand;
                require(ticks > 0 && ClientVisualConfig.smokeLifetime(ticks) == ticks, type + " default does not preserve installed native lifetime");
            }
            double response = ((Number) read(baseline, "com/hooya/stabilizedturret/client/ClientAimState").fields.stream()
                    .filter(field -> field.name.equals("THIRD_PERSON_CAMERA_FOLLOW_RESPONSE_PER_SECOND")).findFirst().orElseThrow().value).doubleValue();
            for (int fps : new int[]{20, 30, 60, 144}) {
                double dt = 1.0 / fps;
                near(ClientVisualConfig.cameraFollowAlpha(dt), 1 - Math.exp(-response * dt), "Default changed the original follow response");
            }
        }
        for (int ticks : new int[]{32, 124, 134, 420, 539}) require(ClientVisualConfig.smokeLifetime(ticks) == ticks, "Default changed random smoke samples");
        ClientVisualConfig.SMOKE_LIFETIME_MULTIPLIER.set(2.0);
        require(ClientVisualConfig.smokeLifetime(124) == 248 && ClientVisualConfig.smokeLifetime(539) == 1078, "Smoke duration does not scale actual lifetimes");
        ClientVisualConfig.THIRD_PERSON_CAMERA_DELAY.set(0.0);
        require(ClientVisualConfig.cameraFollowAlpha(0) == 1 && ClientVisualConfig.cameraFollowAlpha(1.0 / 144) == 1, "Zero delay must follow immediately, even on the first frame");
        ClientVisualConfig.THIRD_PERSON_CAMERA_DELAY.set(.5);
        require(ClientVisualConfig.cameraFollowAlpha(.05) < 1 - Math.exp(-8 * .05), "Larger delay did not slow camera response");
        ClientVisualConfig.HUD_TEXT_COLOR.set("#00FF00");
        require(ClientVisualConfig.hudTextColor(0xEEDDE5E8) == 0xEE00FF00, "RGB setting did not retain opacity");
        ClientVisualConfig.HUD_TEXT_COLOR.set("#8033AAFF");
        require(ClientVisualConfig.hudTextColor(0xEEDDE5E8) == 0x8033AAFF, "ARGB colour not supported");
        require(!ClientVisualConfig.validColor("red") && !ClientVisualConfig.validColor("#12345Z"), "Invalid colour accepted");
        ClientVisualConfig.SHOW_AIRCRAFT_HUD.set(false); ClientVisualConfig.SHOW_BOMB_INDICATOR.set(false); ClientVisualConfig.SHOW_ROCKET_IMPACT.set(false);
        ClientVisualConfig.SPEC.save();
        tracker.unloadConfigs(ModConfig.Type.CLIENT); tracker.loadConfigs(ModConfig.Type.CLIENT, directory);
        require(!ClientVisualConfig.showAircraftHud() && !ClientVisualConfig.showBombIndicator() && !ClientVisualConfig.showRocketImpact(), "Display flags did not persist");
        require(ClientVisualConfig.smokeLifetime(124) == 248 && ClientVisualConfig.cameraFollowAlpha(.05) < .1
                && ClientVisualConfig.hudTextColor(0xEEDDE5E8) == 0x8033AAFF, "Visual preferences did not survive reload");
        tracker.unloadConfigs(ModConfig.Type.CLIENT);
        System.out.println("PASS: original provider lifetimes and pre-change camera response, original colours/visibility, zero-lag camera, duration scaling, colour validation and instance TOML save/reload");
    }
    private static ClassNode read(JarFile jar, String type) throws Exception {
        var node = new ClassNode(); try (var stream = jar.getInputStream(jar.getJarEntry(type + ".class"))) { new ClassReader(stream).accept(node, 0); } return node;
    }
    private static void near(double actual, double expected, String message) { require(Math.abs(actual - expected) < 1e-12, message); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class TestContainer extends ModContainer {
        TestContainer() {
            super((IModInfo) Proxy.newProxyInstance(IModInfo.class.getClassLoader(), new Class<?>[]{IModInfo.class},
                    (proxy, method, args) -> method.getName().equals("getModId") ? "visual_config_test" : null));
        }
        @Override public IEventBus getEventBus() { return null; }
    }
}
