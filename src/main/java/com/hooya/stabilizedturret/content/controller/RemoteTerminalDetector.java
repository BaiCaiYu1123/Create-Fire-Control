package com.hooya.stabilizedturret.content.controller;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class RemoteTerminalDetector {
    public static boolean ownsTerminal(
            StabilizerControllerBlockEntity controller,
            Level level,
            BlockPos terminalPos,
            RemoteControllerCompat.Type type
    ) {
        BindingRef binding = controller.getBinding(slotFor(type));
        return binding != null
                && binding.matches(level, terminalPos)
                && binding.isSameTarget(level)
                && RemoteControllerCompat.isTerminal(level, terminalPos, type);
    }

    public static boolean hasAnyTerminal(StabilizerControllerBlockEntity controller) {
        Level level = controller.getLevel();
        if (level == null) return false;
        return hasTerminal(controller, level, RemoteControllerCompat.Type.NORMAL)
                || hasTerminal(controller, level, RemoteControllerCompat.Type.ADVANCED);
    }

    public static boolean hasTerminal(
            StabilizerControllerBlockEntity controller,
            Level level,
            RemoteControllerCompat.Type type
    ) {
        BindingRef binding = controller.getBinding(slotFor(type));
        return binding != null
                && binding.isSameTarget(level)
                && RemoteControllerCompat.isTerminal(level, binding.pos(), type);
    }

    public static BindingSlot slotFor(RemoteControllerCompat.Type type) {
        return type == RemoteControllerCompat.Type.ADVANCED
                ? BindingSlot.TWEAKED_CONTROLLER_HUB
                : BindingSlot.REMOTE_CONTROLLER_HUB;
    }

    private RemoteTerminalDetector() {
    }
}