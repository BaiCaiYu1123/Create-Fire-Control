package com.hooya.stabilizedturret.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforgespi.language.IModInfo;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Real TOML migration/persistence and independent wheel-selection ranges. */
public final class ClientScopeConfigChecks {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        FMLPaths.loadAbsolutePaths(root);
        FMLConfig.load();
        FMLConfig.updateConfig(FMLConfig.ConfigValue.DISABLE_CONFIG_WATCHER, true);
        var tracker = new ConfigTracker();
        tracker.registerConfig(ModConfig.Type.CLIENT, ClientScopeConfig.SPEC, new TestContainer(), "create_fire_control-client.toml");
        Path config = root.resolve("config");
        Files.createDirectories(config);
        Path file = config.resolve("create_fire_control-client.toml");
        Files.writeString(file, "[scope]\nzoom = 16.0\npodZoom = 8.0\n");
        tracker.loadConfigs(ModConfig.Type.CLIENT, config);
        String migrated = Files.readString(file);
        require(!migrated.contains("zoom =") && !migrated.contains("podZoom ="), "Old current-zoom keys were retained");
        require(ClientScopeConfig.getMinZoom() == 1 && ClientScopeConfig.getMaxZoom() == 32
                && ClientScopeConfig.getPodMinZoom() == 1 && ClientScopeConfig.getPodMaxZoom() == 32,
                "Old wheel selection changed the requested 1-32x defaults");
        require(ClientScopeConfig.getZoom() == 1 && ClientScopeConfig.getPodZoom() == 1, "New sessions must start at their minimum");
        ClientScopeConfig.setZoom(100); ClientScopeConfig.setPodZoom(100);
        require(ClientScopeConfig.getZoom() == 32 && ClientScopeConfig.getPodZoom() == 32, "Default maximum was not applied");
        set("minZoom", 4); set("maxZoom", 100); set("podMinZoom", 2); set("podMaxZoom", 70);
        ClientScopeConfig.SPEC.save();
        byte[] saved = Files.readAllBytes(file);
        for (int i = 0; i < 150; i++) ClientScopeConfig.setZoom(ClientScopeConfig.getZoom() + 1);
        require(ClientScopeConfig.getZoom() == 100 && ClientScopeConfig.getPodZoom() == 32, "Sight range is not independent");
        for (int i = 0; i < 150; i++) ClientScopeConfig.setPodZoom(ClientScopeConfig.getPodZoom() + 1);
        require(ClientScopeConfig.getPodZoom() == 70, "Pod range is not independent");
        for (int i = 0; i < 150; i++) {
            ClientScopeConfig.setZoom(ClientScopeConfig.getZoom() - 1);
            ClientScopeConfig.setPodZoom(ClientScopeConfig.getPodZoom() - 1);
        }
        require(ClientScopeConfig.getZoom() == 4 && ClientScopeConfig.getPodZoom() == 2, "Minimum range was not enforced");
        require(Arrays.equals(saved, Files.readAllBytes(file)), "Wheel selections wrote into the global limits file");
        tracker.unloadConfigs(ModConfig.Type.CLIENT);
        tracker.loadConfigs(ModConfig.Type.CLIENT, config);
        require(ClientScopeConfig.getMinZoom() == 4 && ClientScopeConfig.getMaxZoom() == 100
                && ClientScopeConfig.getPodMinZoom() == 2 && ClientScopeConfig.getPodMaxZoom() == 70,
                "Limits did not persist across reload");
        ClientScopeConfig.setZoom(100); ClientScopeConfig.setPodZoom(70);
        set("maxZoom", 8); set("podMaxZoom", 6);
        require(ClientScopeConfig.getZoom() == 8 && ClientScopeConfig.getPodZoom() == 6, "Live range narrowing did not clamp current zoom");
        set("minZoom", 80); set("podMinZoom", 90);
        require(ClientScopeConfig.getMinZoom() == 8 && ClientScopeConfig.getPodMinZoom() == 6,
                "Inverted limits escaped their configured maximum");
        tracker.unloadConfigs(ModConfig.Type.CLIENT);
        System.out.println("PASS: old current-zoom TOML migration, default 1-32x, independent scope/pod bounds up to 100x, "
                + "wheel selection does not write limits, live clamping and persistence/reload");
    }
    private static void set(String name, int value) {
        ModConfigSpec.ConfigValue<Integer> field = ClientScopeConfig.SPEC.getValues().get("scope." + name);
        field.set(value);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class TestContainer extends ModContainer {
        TestContainer() {
            super((IModInfo) Proxy.newProxyInstance(IModInfo.class.getClassLoader(), new Class<?>[]{IModInfo.class},
                    (proxy, method, args) -> method.getName().equals("getModId") ? "scope_config_test" : null));
        }
        @Override public IEventBus getEventBus() { return null; }
    }
}
