package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.FireControlSettings;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Actual native entity joins routed by the TOML setting, across server processes and worlds. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class FireControlTrajectoryChecks {
    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        Throwable failure = null;
        try {
            GuidedMissileModeIntegration.verify(event.getServer().overworld());
            boolean reload = Boolean.getBoolean("create_fire_control.modePolicyReload");
            String expected = reload ? "MATHEMATICAL" : "ENTITY";
            Path file = FMLPaths.CONFIGDIR.get().resolve(FireControlSettings.SERVER_FILE);
            require(Files.readString(file).contains("trajectoryMode = \"" + expected + "\""), "Config route was not saved globally");
            // Legacy command aliases must update the same global config instead of world SavedData.
            event.getServer().getCommands().performPrefixedCommand(event.getServer().createCommandSourceStack(),
                    "cfc missile mode " + (reload ? "entity" : "math"));
            require(FireControlSettings.mathematicalMissileFlight() != reload, "Compatibility command did not apply to config");
            FireControlSettings.saveServerChanges(java.util.Map.of("flight.trajectoryMode", expected));
            require(FireControlSettings.mathematicalMissileFlight() == reload, "Fixture failed to restore final config");
            String world = reload ? "world-b" : "world-a";
            require(!Files.exists(Path.of(world, "serverconfig", FireControlSettings.SERVER_FILE)), "Created per-world route config");
        } catch (Throwable thrown) { failure = thrown; thrown.printStackTrace(); }
        try {
            Files.writeString(Path.of("config-trajectory-result.txt"), failure == null
                    ? "PASS: global trajectory config, 22 actual native missile backends, entity/math selection, excluded types, "
                    + "pinned in-flight routes, old shots, command alias writes same file, server restart and cross-world persistence"
                    : "FAIL: " + failure);
        } catch (Exception io) { io.printStackTrace(); }
        event.getServer().halt(false);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
