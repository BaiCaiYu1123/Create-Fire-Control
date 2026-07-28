package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.DriveByWireCompat;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Resolves a captured keyboard key through the controller's live client profile. */
public final class ControllerKeyMappingCompat {
    private static final String CONTROLS_CLASS =
            "com.getitemfromblock.create_tweaked_controllers.controller.TweakedControlsUtil";

    public static @Nullable String signalForKeyboardKey(
            RemoteControllerCompat.Type type,
            int keyCode,
            int scanCode
    ) {
        return type == RemoteControllerCompat.Type.ADVANCED
                ? advancedSignalForInput("KEYBOARD_KEY", keyCode)
                : normalSignalForKeyboardKey(keyCode, scanCode);
    }

    public static @Nullable String signalForMouseButton(
            RemoteControllerCompat.Type type,
            int button
    ) {
        return type == RemoteControllerCompat.Type.ADVANCED
                ? advancedSignalForInput("MOUSE_BUTTON", button)
                : normalSignalForMouseButton(button);
    }

    public static Component displayKey(
            RemoteControllerCompat.Type type,
            String signal
    ) {
        if (type == RemoteControllerCompat.Type.NORMAL) {
            KeyMapping mapping = normalMappingForSignal(signal);
            if (mapping != null) return mapping.getTranslatedKeyMessage();
        } else {
            InputBinding binding = advancedBindingForSignal(signal);
            if (binding != null) {
                return "MOUSE_BUTTON".equals(binding.type())
                        ? InputConstants.Type.MOUSE.getOrCreate(binding.value()).getDisplayName()
                        : InputConstants.getKey(binding.value(), 0).getDisplayName();
            }
        }
        {
            String translation = DriveByWireCompat.translationKey(type, signal);
            return translation.isEmpty() ? Component.literal("-") : Component.translatable(translation);
        }
    }

    private static @Nullable String normalSignalForKeyboardKey(int keyCode, int scanCode) {
        Minecraft minecraft = Minecraft.getInstance();
        KeyMapping[] mappings = normalMappings(minecraft);
        String[] signals = DriveByWireCompat.keys(RemoteControllerCompat.Type.NORMAL);
        for (int index = 0; index < mappings.length; index++) {
            if (mappings[index].matches(keyCode, scanCode)) return signals[index];
        }
        return null;
    }

    private static @Nullable String normalSignalForMouseButton(int button) {
        Minecraft minecraft = Minecraft.getInstance();
        KeyMapping[] mappings = normalMappings(minecraft);
        String[] signals = DriveByWireCompat.keys(RemoteControllerCompat.Type.NORMAL);
        for (int index = 0; index < mappings.length; index++) {
            if (mappings[index].matchesMouse(button)) return signals[index];
        }
        return null;
    }

    private static KeyMapping[] normalMappings(Minecraft minecraft) {
        return new KeyMapping[]{
                minecraft.options.keyUp,
                minecraft.options.keyDown,
                minecraft.options.keyLeft,
                minecraft.options.keyRight,
                minecraft.options.keyJump,
                minecraft.options.keyShift
        };
    }

    private static @Nullable KeyMapping normalMappingForSignal(String signal) {
        Minecraft minecraft = Minecraft.getInstance();
        KeyMapping[] mappings = normalMappings(minecraft);
        String[] signals = DriveByWireCompat.keys(RemoteControllerCompat.Type.NORMAL);
        for (int index = 0; index < signals.length; index++) {
            if (signals[index].equals(signal)) return mappings[index];
        }
        return null;
    }

    private static @Nullable String advancedSignalForInput(String inputType, int value) {
        Object[] layout = advancedLayout();
        if (layout == null) return null;
        String[] signals = DriveByWireCompat.keys(RemoteControllerCompat.Type.ADVANCED);
        int size = Math.min(layout.length, signals.length);
        for (int index = 0; index < size; index++) {
            Object input = layout[index];
            if (input != null && inputMatches(input, inputType, value)) return signals[index];
        }
        return null;
    }

    private static @Nullable InputBinding advancedBindingForSignal(String signal) {
        Object[] layout = advancedLayout();
        if (layout == null) return null;
        String[] signals = DriveByWireCompat.keys(RemoteControllerCompat.Type.ADVANCED);
        int size = Math.min(layout.length, signals.length);
        for (int index = 0; index < size; index++) {
            if (!signals[index].equals(signal) || layout[index] == null) continue;
            InputBinding binding = inputBinding(layout[index]);
            if (binding != null) return binding;
        }
        return null;
    }

    private static boolean inputMatches(Object input, String type, int value) {
        InputBinding binding = inputBinding(input);
        return binding != null && type.equals(binding.type()) && binding.value() == value;
    }

    private static @Nullable InputBinding inputBinding(Object input) {
        try {
            Method getType = input.getClass().getMethod("GetType");
            String type = String.valueOf(getType.invoke(input));
            if (!"KEYBOARD_KEY".equals(type) && !"MOUSE_BUTTON".equals(type)) return null;
            Object value = input.getClass().getMethod("GetValue").invoke(input);
            return value instanceof Number number ? new InputBinding(type, number.intValue()) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private record InputBinding(String type, int value) {
    }

    private static @Nullable Object[] advancedLayout() {
        try {
            Class<?> controls = Class.forName(CONTROLS_CLASS);
            Field profileField = controls.getField("profile");
            Object profile = profileField.get(null);
            if (profile == null) return null;
            Object value = profile.getClass().getField("layout").get(profile);
            return value instanceof Object[] layout ? layout : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private ControllerKeyMappingCompat() {
    }
}
