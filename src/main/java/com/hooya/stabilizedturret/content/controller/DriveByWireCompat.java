package com.hooya.stabilizedturret.content.controller;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** Optional bridge kept reflection-only so this mod still loads without DriveByWire. */
public final class DriveByWireCompat {
    private static final String[] NORMAL_KEYS = {
            "keyUp", "keyDown", "keyLeft", "keyRight", "keyJump", "keyShift"
    };
    private static final String[] ADVANCED_KEYS = {
            "buttonA", "buttonB", "buttonX", "buttonY", "shoulderLeft", "shoulderRight",
            "buttonBack", "buttonStart", "buttonGuide", "leftJoyStickClick", "rightJoyStickClick",
            "dPadUp", "dPadRight", "dPadDown", "dPadLeft",
            "axisLeftX+", "axisLeftX-", "axisLeftY+", "axisLeftY-",
            "axisRightX+", "axisRightX-", "axisRightY+", "axisRightY-",
            "axisTriggerLeft", "axisTriggerRight"
    };
    private static final String[] NORMAL_NAMES = {
            "Up", "Down", "Left", "Right", "Jump", "Shift"
    };
    private static final String[] ADVANCED_NAMES = {
            "A", "B", "X", "Y", "Left Shoulder", "Right Shoulder", "Back", "Start", "Guide",
            "Left Stick", "Right Stick", "D-pad Up", "D-pad Right", "D-pad Down", "D-pad Left",
            "Left X+", "Left X-", "Left Y+", "Left Y-", "Right X+", "Right X-", "Right Y+", "Right Y-",
            "Left Trigger", "Right Trigger"
    };
    private static final Method GET_SIGNALS;

    static {
        Method method = null;
        try {
            Class<?> type = Class.forName("edn.stratodonut.drivebywire.compat.ControllerSignalStore");
            method = type.getMethod("getSignals", Level.class, BlockPos.class);
        } catch (ReflectiveOperationException ignored) {
        }
        GET_SIGNALS = method;
    }

    public static String[] keys(RemoteControllerCompat.Type type) {
        return (type == RemoteControllerCompat.Type.ADVANCED ? ADVANCED_KEYS : NORMAL_KEYS).clone();
    }

    public static String[] names(RemoteControllerCompat.Type type) {
        return (type == RemoteControllerCompat.Type.ADVANCED ? ADVANCED_NAMES : NORMAL_NAMES).clone();
    }

    public static String translationKey(RemoteControllerCompat.Type type, String key) {
        String prefix;
        if (type == RemoteControllerCompat.Type.NORMAL) {
            prefix = switch (key) {
                case "keyUp" -> "up";
                case "keyDown" -> "down";
                case "keyLeft" -> "left";
                case "keyRight" -> "right";
                case "keyJump" -> "jump";
                case "keyShift" -> "shift";
                default -> "";
            };
            return prefix.isEmpty() ? "" : "drivebywire.controller.key." + prefix;
        }
        prefix = switch (key) {
            case "buttonA" -> "a";
            case "buttonB" -> "b";
            case "buttonX" -> "x";
            case "buttonY" -> "y";
            case "shoulderLeft" -> "shoulder_left";
            case "shoulderRight" -> "shoulder_right";
            case "buttonBack" -> "back";
            case "buttonStart" -> "start";
            case "buttonGuide" -> "guide";
            case "leftJoyStickClick" -> "left_joystick_click";
            case "rightJoyStickClick" -> "right_joystick_click";
            case "dPadUp" -> "dpad_up";
            case "dPadRight" -> "dpad_right";
            case "dPadDown" -> "dpad_down";
            case "dPadLeft" -> "dpad_left";
            case "axisLeftX+" -> "axis.left_x_positive";
            case "axisLeftX-" -> "axis.left_x_negative";
            case "axisLeftY+" -> "axis.left_y_positive";
            case "axisLeftY-" -> "axis.left_y_negative";
            case "axisRightX+" -> "axis.right_x_positive";
            case "axisRightX-" -> "axis.right_x_negative";
            case "axisRightY+" -> "axis.right_y_positive";
            case "axisRightY-" -> "axis.right_y_negative";
            case "axisTriggerLeft" -> "axis.left_trigger";
            case "axisTriggerRight" -> "axis.right_trigger";
            default -> "";
        };
        if (prefix.isEmpty()) return "";
        return prefix.startsWith("axis.")
                ? "drivebywire.controller." + prefix
                : "drivebywire.controller.button." + prefix;
    }

    /** Localized explanation key used by the fire-control input screen. */
    public static String descriptionKey(RemoteControllerCompat.Type type, String key) {
        String translation = translationKey(type, key);
        if (translation.isEmpty()) {
            return "screen.stabilized_turret.remote_key.unknown";
        }
        String prefix = "drivebywire.controller.";
        String suffix = translation.startsWith(prefix)
                ? translation.substring(prefix.length()) : translation;
        if (suffix.startsWith("key.")) {
            suffix = suffix.substring("key.".length());
        } else if (suffix.startsWith("button.")) {
            suffix = suffix.substring("button.".length());
        }
        return "screen.stabilized_turret.remote_key."
                + (type == RemoteControllerCompat.Type.ADVANCED ? "advanced." : "normal.")
                + suffix.replace('.', '_');
    }

    public static boolean isAvailable() {
        return GET_SIGNALS != null;
    }

    public static Map<?, ?> signals(Level level, BlockPos pos) {
        if (GET_SIGNALS == null || level == null || pos == null) return Map.of();
        try {
            Object result = GET_SIGNALS.invoke(null, level, pos);
            return result instanceof Map<?, ?> signals ? signals : Map.of();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Map.of();
        }
    }

    public static int signal(Map<?, ?> signals, String key) {
        if (key == null || key.isEmpty()) return 0;
        Object value = signals.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    public static boolean accepts(RemoteControllerCompat.Type type, String key) {
        for (String allowed : type == RemoteControllerCompat.Type.ADVANCED ? ADVANCED_KEYS : NORMAL_KEYS) {
            if (allowed.equals(key)) return true;
        }
        return false;
    }

    public static List<String> keyList(RemoteControllerCompat.Type type) {
        return List.of(keys(type));
    }

    private DriveByWireCompat() {
    }
}
