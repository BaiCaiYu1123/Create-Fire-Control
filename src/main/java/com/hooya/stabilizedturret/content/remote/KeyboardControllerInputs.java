package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.content.controller.RemoteTerminalDetector;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Native keyboard -> paired processor -> FCC/engine mappings. No wire API is used by this path. */
public final class KeyboardControllerInputs {
    public static Map<String, Integer> signals(Level level, BlockPos terminal) {
        if (!(level instanceof ServerLevel) || terminal == null || !level.hasChunkAt(terminal)
                || !(level.getBlockEntity(terminal) instanceof WirelessSignalProcessorBlockEntity)) return Map.of();
        return ControllerSessions.inputSnapshot(level, terminal).signals();
    }

    public static Set<String> channels(ServerLevel level, BlockPos terminal) {
        Set<String> channels = new HashSet<>();
        for (var controller : ControllerTracker.loadedControllers()) {
            if (controller.isRemoved() || controller.getLevel() != level
                    || !RemoteTerminalDetector.ownsTerminal(controller, level, terminal, RemoteControllerCompat.Type.WIRELESS)) continue;
            for (String key : controller.getRemoteKeys(RemoteControllerCompat.Type.WIRELESS))
                channels.addAll(ControllerInput.chordInputs(key));
        }
        // Engines bind to the processor themselves; no FCC is required for discovery or input.
        channels.addAll(GasTurbineBlockEntity.channelsForTerminal(level, terminal));
        return Set.copyOf(channels);
    }

    private KeyboardControllerInputs() { }
}
