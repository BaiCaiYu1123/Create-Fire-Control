package com.hooya.stabilizedturret.config;

import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import com.hooya.stabilizedturret.content.missile.MissilePhysicsConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforgespi.language.IModInfo;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Real TOML persistence and remote-view isolation using the NeoForge loader's config lifecycle. */
public final class FireControlSettingsChecks {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        FMLPaths.loadAbsolutePaths(root);
        FMLConfig.load();
        FMLConfig.updateConfig(FMLConfig.ConfigValue.DISABLE_CONFIG_WATCHER, true);
        var tracker = new ConfigTracker();
        var container = new TestContainer();
        var gameplay = tracker.registerConfig(ModConfig.Type.COMMON, FireControlSettings.SERVER_SPEC,
                container, FireControlSettings.SERVER_FILE);
        tracker.registerConfig(ModConfig.Type.COMMON, FireControlSettings.COMMON_SPEC,
                container, "create_fire_control-common.toml");
        tracker.registerConfig(ModConfig.Type.COMMON, MissilePhysicsConfig.SPEC,
                container, "create_fire_control-ballistics.toml");
        Path configDir = root.resolve("config");
        Files.createDirectories(configDir);
        Path ballisticsFile = configDir.resolve("create_fire_control-ballistics.toml");
        var legacy = com.electronwill.nightconfig.core.CommentedConfig.inMemory();
        if (Files.exists(ballisticsFile)) {
            try (var reader = Files.newBufferedReader(ballisticsFile)) {
                legacy = new TomlParser().parse(reader);
            }
        }
        legacy.set("virtualBallisticMissile.proximityFuzeRadius", 63.0);
        new TomlWriter().write(legacy, ballisticsFile, WritingMode.REPLACE_ATOMIC);
        tracker.loadConfigs(ModConfig.Type.COMMON, configDir);
        require(!Files.readString(ballisticsFile).contains("proximityFuzeRadius"),
                "legacy global fuze setting must be removed from the actual TOML");
        require(MissilePhysicsConfig.SPEC.getValues().get("virtualBallisticMissile.proximityFuzeRadius") == null,
                "device-owned fuze setting must not appear in the global editor or synchronization");
        Path file = configDir.resolve(FireControlSettings.SERVER_FILE);
        require(gameplay.getFullPath().equals(file), "gameplay config must live in instance/config");
        require(FireControlSettings.serverValuesSynced() && FireControlSettings.canEditServer(),
                "offline local gameplay settings must be editable");
        String lifetime = "missiles.farmissile_1tanshe.lifetimeSeconds";
        String trajectory = "flight.trajectoryMode";
        String armor="armor.equivalentArmor";
        require(!FireControlSettings.EQUIVALENT_ARMOR.getDefault(),"equivalent armor must default off");
        require(FireControlSettings.MISSILE_TRAJECTORY_MODE.getDefault() == FireControlSettings.TrajectoryMode.MATHEMATICAL,
                "trajectory default must be mathematical");
        var changes = Map.<String, Object>of("radar.raycast", false, lifetime, 45.5,
                trajectory, "ENTITY",armor,true,
                "virtualBallisticMissile.cruiseSpeed", 650.0);
        FireControlSettings.saveServerChanges(changes);
        require(!FireControlSettings.mathematicalMissileFlight(), "trajectory config must select entity flight immediately");
        require(FireControlSettings.equivalentArmorEnabled(),"armor switch must apply immediately");
        require(!FireControlSettings.radarRaycastEnabled(), "radar toggle must apply immediately");
        require(MissileSettings.lifetimeTicks("farmissile_1tanshe") == 910, "per-model lifetime must apply");
        require(MissilePhysicsConfig.CRUISE_SPEED.get() == 650, "existing ballistic settings must use same save path");
        byte[] saved = Files.readAllBytes(file);
        require(new String(saved, java.nio.charset.StandardCharsets.UTF_8).contains("45.5"), "save must reach TOML");
        require(new String(saved, java.nio.charset.StandardCharsets.UTF_8).contains("trajectoryMode = \"ENTITY\""),
                "trajectory enum must persist with its stable identifier");

        // Closing one world and opening another does not choose a different configuration directory.
        Files.createDirectories(root.resolve("saves/world-a"));
        Files.createDirectories(root.resolve("saves/world-b"));
        tracker.unloadConfigs(ModConfig.Type.COMMON);
        tracker.loadConfigs(ModConfig.Type.COMMON, configDir);
        require(MissileSettings.lifetimeTicks("farmissile_1tanshe") == 910
                && !FireControlSettings.radarRaycastEnabled(), "reload must retain values across worlds");
        require(!FireControlSettings.mathematicalMissileFlight(), "trajectory must survive reload across worlds");
        require(FireControlSettings.equivalentArmorEnabled(),"armor switch must survive TOML reload");
        require(!Files.exists(root.resolve("saves/world-a/serverconfig/" + FireControlSettings.SERVER_FILE)),
                "must not create per-world gameplay config");

        FireControlSettings.receiveSnapshot(FireControlSettings.encode(Map.of(
                "radar.raycast", true, lifetime, 80.0, trajectory, "MATHEMATICAL",armor,false)), false);
        require(!FireControlSettings.equivalentArmorEnabled(),"server armor switch must override the local client value");
        require(FireControlSettings.mathematicalMissileFlight(), "remote trajectory must be authoritative");
        require(FireControlSettings.radarRaycastEnabled()
                && MissileSettings.lifetimeTicks("farmissile_1tanshe") == 1600,
                "server snapshot must drive effects and configuration display");
        require(!FireControlSettings.canEditServer(), "non-operators must have read-only server settings");
        FireControlSettings.beginConnection();
        require(FireControlSettings.serverValuesSynced(), "late login event must not invalidate an already received snapshot");
        require(java.util.Arrays.equals(saved, Files.readAllBytes(file)),
                "server snapshot must never overwrite the local TOML");
        FireControlSettings.clearRemoteView();
        require(FireControlSettings.equivalentArmorEnabled(),"disconnect must restore the local armor setting");
        require(!FireControlSettings.mathematicalMissileFlight(), "disconnect must restore local trajectory");
        require(!FireControlSettings.radarRaycastEnabled()
                && MissileSettings.lifetimeTicks("farmissile_1tanshe") == 910,
                "disconnect must restore local settings");

        // Simulate an external local file edit racing with another remote snapshot. The file wins at logout.
        FireControlSettings.receiveSnapshot(FireControlSettings.encode(Map.of(lifetime, 80.0)), false);
        com.electronwill.nightconfig.core.CommentedConfig disk;
        try (var reader = Files.newBufferedReader(file)) {
            disk = new TomlParser().parse(reader);
        }
        disk.set(java.util.List.of("missiles", "farmissile_1tanshe", "lifetimeSeconds"), 55.5);
        new TomlWriter().write(disk, file, WritingMode.REPLACE_ATOMIC);
        tracker.unloadConfigs(ModConfig.Type.COMMON);
        tracker.loadConfigs(ModConfig.Type.COMMON, configDir);
        FireControlSettings.receiveSnapshot(FireControlSettings.encode(Map.of(lifetime, 80.0)), false);
        var reloaded = FireControlSettings.class.getDeclaredMethod("onReload", ModConfigEvent.Reloading.class);
        reloaded.setAccessible(true);
        reloaded.invoke(null, new ModConfigEvent.Reloading(gameplay));
        require(MissileSettings.lifetimeTicks("farmissile_1tanshe") == 1600,
                "local file watcher must retain authoritative remote view");
        FireControlSettings.clearRemoteView();
        require(MissileSettings.lifetimeTicks("farmissile_1tanshe") == 1110,
                "disconnect must restore the latest externally edited local file");

        for (Map<String, Object> invalid : java.util.List.of(
                Map.<String, Object>of("unknown.option", 1),
                Map.<String, Object>of(trajectory, "unknown"),
                Map.<String, Object>of(trajectory, true),
                Map.<String, Object>of(lifetime, Double.NaN),
                Map.<String, Object>of(lifetime, Double.POSITIVE_INFINITY),
                Map.<String, Object>of(lifetime, -10),
                Map.<String, Object>of("radar.raycast", "false"),
                Map.<String, Object>of("virtualBallisticMissile.proximityFuzeRadius", 6.0),
                Map.<String, Object>of("aaProximityFuseRange", 6.0),
                Map.<String, Object>of("aircraftProximityFuseRange", 6.0),
                Map.<String, Object>of("aircraftFuseSafetySeconds", 1.0),
                Map.<String, Object>of("radarRange", 5000.0),
                Map.<String, Object>of("radarHudScale", 75.0),
                Map.<String, Object>of("yawSpeed", 60.0),
                Map.<String, Object>of("radar.raycast", true, lifetime, -1))) {
            require(FireControlSettings.validatedChanges(invalid) == null, "invalid edit must be rejected: " + invalid);
        }
        require(FireControlSettings.validatedChanges(Map.of(lifetime, 60)) != null,
                "finite numeric input must normalize to the declared type");
        tracker.unloadConfigs(ModConfig.Type.COMMON);
        System.out.println("PASS: instance-wide TOML persistence/reload, offline editing, ballistic config, "
                + "remote snapshot isolation/disconnect restore, login/file-watcher races, legacy global fuze removal "
                + "and device-setting edit rejection");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestContainer extends ModContainer {
        TestContainer() {
            super((IModInfo) Proxy.newProxyInstance(IModInfo.class.getClassLoader(), new Class<?>[]{IModInfo.class},
                    (proxy, method, args) -> method.getName().equals("getModId") ? "create_fire_control_test" : null));
        }
        @Override public IEventBus getEventBus() { return null; }
    }
}
