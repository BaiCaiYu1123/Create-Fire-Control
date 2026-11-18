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
import java.util.LinkedHashSet;
import java.util.Set;
import org.lwjgl.glfw.GLFW;

/** Resolves a captured keyboard key through the controller's live client profile. */
public final class ControllerKeyMappingCompat {
    private static final String CONTROLS_CLASS =
            "com.getitemfromblock.create_tweaked_controllers.controller.TweakedControlsUtil";

    public static @Nullable String signalForKeyboardKey(
            RemoteControllerCompat.Type type,
            int keyCode,
            int scanCode
    ) {
        if(type==RemoteControllerCompat.Type.WIRELESS) {String channel=com.hooya.stabilizedturret.content.remote.ControllerInput.channel(false,keyCode);return channel.isEmpty()?null:channel;}
        return type == RemoteControllerCompat.Type.ADVANCED
                ? advancedSignalForInput("KEYBOARD_KEY", keyCode)
                : normalSignalForKeyboardKey(keyCode, scanCode);
    }

    public static @Nullable String signalForMouseButton(
            RemoteControllerCompat.Type type,
            int button
    ) {
        if(type==RemoteControllerCompat.Type.WIRELESS) {String channel=com.hooya.stabilizedturret.content.remote.ControllerInput.channel(true,button);return channel.isEmpty()?null:channel;}
        return type == RemoteControllerCompat.Type.ADVANCED
                ? advancedSignalForInput("MOUSE_BUTTON", button)
                : normalSignalForMouseButton(button);
    }

    /** Resolves the advanced-controller slot driven by this vertical wheel pulse. */
    public static @Nullable String signalForMouseScroll(
            RemoteControllerCompat.Type type,
            double scrollY
    ) {
        return signalsForMouseScroll(type, scrollY).stream().findFirst().orElse(null);
    }

    /**
     * A wheel binding has no GLFW key code: Tweaked Controllers stores its
     * direction in the input's lower/upper bounds instead. Return every slot
     * activated by this pulse so duplicate custom mappings keep working.
     */
    public static Set<String> signalsForMouseScroll(
            RemoteControllerCompat.Type type,
            double scrollY
    ) {
        if(type==RemoteControllerCompat.Type.WIRELESS) {
            String channel=com.hooya.stabilizedturret.content.remote.ControllerInput.scrollChannel(scrollY);
            return channel.isEmpty()?Set.of():Set.of(channel);
        }
        if (type != RemoteControllerCompat.Type.ADVANCED
                || Math.abs(scrollY) < 1.0e-6) {
            return Set.of();
        }
        Object[] layout = advancedLayout();
        if (layout == null) return Set.of();
        String[] signals = DriveByWireCompat.keys(type);
        Set<String> matched = new LinkedHashSet<>();
        for (int index = 0; index < Math.min(layout.length, signals.length); index++) {
            InputBinding binding = layout[index] == null ? null : inputBinding(layout[index]);
            if (binding != null && "MOUSE_WHEEL".equals(binding.type())
                    && MouseWheelBindingMath.isActive(
                    binding.minimum(), binding.maximum(), scrollY)) {
                matched.add(signals[index]);
            }
        }
        return Set.copyOf(matched);
    }

    public static Component displayKey(
            RemoteControllerCompat.Type type,
            String signal
    ) {
        if(type==RemoteControllerCompat.Type.WIRELESS) {
            Component result=Component.empty();
            for(String key:com.hooya.stabilizedturret.content.remote.ControllerInput.chordInputs(signal)) {
                if(!result.getString().isEmpty())result=result.copy().append(" + ");
                result=result.copy().append(com.hooya.stabilizedturret.client.remote.WirelessControllerClient.name(key));
            }
            return result;
        }
        if (type == RemoteControllerCompat.Type.NORMAL) {
            KeyMapping mapping = normalMappingForSignal(signal);
            if (mapping != null) return mapping.getTranslatedKeyMessage();
        } else {
            InputBinding binding = advancedBindingForSignal(signal);
            if (binding != null) {
                return switch (binding.type()) {
                    case "MOUSE_BUTTON" -> InputConstants.Type.MOUSE
                            .getOrCreate(binding.value()).getDisplayName();
                    case "MOUSE_WHEEL" -> Component.translatable(
                            "remote.create_fire_control."+(binding.maximum() >= binding.minimum() ? "scroll_up" : "scroll_down"));
                    default -> InputConstants.getKey(binding.value(), 0).getDisplayName();
                };
            }
        }
        {
            String translation = DriveByWireCompat.displayTranslationKey(type, signal);
            return translation.isEmpty() ? Component.literal("-") : Component.translatable(translation);
        }
    }

    /** Reads physical input directly, so another mod consuming the key cannot suppress fire-control input. */
    public static Set<String> pressedSignals(RemoteControllerCompat.Type type) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || !minecraft.isWindowActive()) return Set.of();
        Set<String> pressed = new LinkedHashSet<>();
        String[] signals = DriveByWireCompat.keys(type);
        if (type == RemoteControllerCompat.Type.NORMAL) {
            KeyMapping[] mappings = normalMappings(minecraft);
            for (int index = 0; index < mappings.length; index++) if (physicalDown(minecraft, mappings[index])) pressed.add(signals[index]);
            return Set.copyOf(pressed);
        }
        Object[] layout = advancedLayout();
        if (layout == null) return Set.of();
        long window = minecraft.getWindow().getWindow();
        for (int index = 0; index < Math.min(layout.length, signals.length); index++) {
            InputBinding binding = layout[index] == null ? null : inputBinding(layout[index]);
            if (binding != null && !com.hooya.stabilizedturret.client.remote.WirelessControllerClient.blocks(
                    "MOUSE_BUTTON".equals(binding.type()), binding.value())
                    && (("KEYBOARD_KEY".equals(binding.type()) && InputConstants.isKeyDown(window, binding.value()))
                    || ("MOUSE_BUTTON".equals(binding.type()) && GLFW.glfwGetMouseButton(window, binding.value()) == GLFW.GLFW_PRESS))) pressed.add(signals[index]);
        }
        return Set.copyOf(pressed);
    }

    /** Only locally observable inputs participate in self-checks; gamepad axes keep their wire path. */
    public static Set<String> observedSignals(RemoteControllerCompat.Type type) {
        if (type == RemoteControllerCompat.Type.NORMAL) return Set.copyOf(DriveByWireCompat.keyList(type));
        Object[] layout = advancedLayout();
        if (layout == null) return Set.of();
        String[] signals = DriveByWireCompat.keys(type);
        Set<String> observed = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(layout.length, signals.length); i++)
            if (layout[i] != null && inputBinding(layout[i]) != null) observed.add(signals[i]);
        return Set.copyOf(observed);
    }

    private static boolean physicalDown(Minecraft minecraft, KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        long window = minecraft.getWindow().getWindow();
        if (com.hooya.stabilizedturret.client.remote.WirelessControllerClient.blocks(
                key.getType() == InputConstants.Type.MOUSE, key.getValue())) return false;
        if (key.getType() == InputConstants.Type.MOUSE)
            return key.getValue() >= 0 && GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        if (key.getType() == InputConstants.Type.KEYSYM)
            return key.getValue() >= 0 && InputConstants.isKeyDown(window, key.getValue());
        for (var input : com.hooya.stabilizedturret.content.remote.ControllerInput.ALL)
            if (!input.mouse() && GLFW.glfwGetKeyScancode(input.code()) == key.getValue()
                    && InputConstants.isKeyDown(window, input.code())) return true;
        return false;
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
            try {
                if (Boolean.FALSE.equals(input.getClass().getMethod("IsInputValid").invoke(input))) return null;
            } catch (NoSuchMethodException ignored) { }
            Method getType = input.getClass().getMethod("GetType");
            String type = String.valueOf(getType.invoke(input));
            if (!"KEYBOARD_KEY".equals(type)
                    && !"MOUSE_BUTTON".equals(type)
                    && !"MOUSE_WHEEL".equals(type)) {
                return null;
            }
            Object value = input.getClass().getMethod("GetValue").invoke(input);
            if (!(value instanceof Number number)) return null;
            if (!"MOUSE_WHEEL".equals(type) && number.intValue() < 0) return null;
            if (!"MOUSE_WHEEL".equals(type)) {
                return new InputBinding(type, number.intValue(), Double.NaN, Double.NaN);
            }
            Object minimum = input.getClass().getField("minBound").get(input);
            Object maximum = input.getClass().getField("maxBound").get(input);
            return minimum instanceof Number minimumNumber
                    && maximum instanceof Number maximumNumber
                    ? new InputBinding(type, number.intValue(),
                    minimumNumber.doubleValue(), maximumNumber.doubleValue())
                    : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private record InputBinding(
            String type,
            int value,
            double minimum,
            double maximum
    ) {
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
