package com.hooya.stabilizedturret.content.remote;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.util.*;

public final class ControllerSettings {
    public static final String ROOT = "WirelessController";
    public static final int DEFAULT_SIGNAL_STRENGTH = 16;
    public static final int MAX_SIGNAL_STRENGTH = 16;
    public static final List<String> COLORS = List.of("default", "red", "green", "blue");
    public record Binding(String channel, String label, ItemStack first, ItemStack second, boolean enabled, String secondInput, int signalStrength) {
        public Binding {
            signalStrength = normalizeSignalStrength(signalStrength);
        }
        public Binding(String channel, String label, ItemStack first, ItemStack second, boolean enabled) {
            this(channel, label, first, second, enabled, "");
        }
        public Binding(String channel, String label, ItemStack first, ItemStack second, boolean enabled, String secondInput) {
            this(channel, label, first, second, enabled, secondInput, DEFAULT_SIGNAL_STRENGTH);
        }
        public boolean wireless() { return enabled && (!first.isEmpty() || !second.isEmpty()); }
    }
    public static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(ROOT);
    }
    public static void write(ItemStack stack, CompoundTag data) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(ROOT, data.copy()));
    }
    public static UUID ensureId(ItemStack stack) {
        CompoundTag data = data(stack);
        if (!data.hasUUID("Id")) { data.putUUID("Id", UUID.randomUUID()); write(stack, data); }
        return data.getUUID("Id");
    }
    public static UUID id(ItemStack stack) {
        CompoundTag data = data(stack);
        return data.hasUUID("Id") ? data.getUUID("Id") : null;
    }
    public static String exitChannel(CompoundTag data) { return ControllerInput.exitChannel(data.getString("ExitChannel")); }
    public static String color(CompoundTag data) {
        String color = data.getString("Color");
        return COLORS.contains(color) ? color : COLORS.getFirst();
    }
    public static List<Binding> bindings(CompoundTag data, HolderLookup.Provider registries) {
        List<Binding> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ListTag entries = data.getList("Bindings", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(entries.size(), ControllerInput.ALL.size()); i++) {
            CompoundTag entry = entries.getCompound(i);
            String channel = entry.getString("Channel");
            if (ControllerInput.fromChannel(channel) == null || !seen.add(channel)) continue;
            String label = entry.getString("Label");
            result.add(new Binding(channel, label.substring(0, Math.min(48, label.length())),
                    normalize(ItemStack.parseOptional(registries, entry.getCompound("First"))),
                    normalize(ItemStack.parseOptional(registries, entry.getCompound("Second"))),
                    entry.getBoolean("Enabled"), normalizeSecond(channel, entry.getString("SecondInput")),
                    entry.contains("SignalStrength", Tag.TAG_ANY_NUMERIC) ? entry.getInt("SignalStrength") : DEFAULT_SIGNAL_STRENGTH));
        }
        return result;
    }
    public static void putBindings(CompoundTag data, List<Binding> bindings, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        for (Binding binding : bindings) {
            if (ControllerInput.fromChannel(binding.channel()) == null) continue;
            CompoundTag entry = new CompoundTag();
            entry.putString("Channel", binding.channel()); entry.putString("Label", binding.label());
            entry.putBoolean("Enabled", binding.enabled());
            entry.putInt("SignalStrength", binding.signalStrength());
            entry.putString("SecondInput", normalizeSecond(binding.channel(), binding.secondInput()));
            entry.put("First", normalize(binding.first()).saveOptional(registries));
            entry.put("Second", normalize(binding.second()).saveOptional(registries));
            entries.add(entry);
        }
        data.put("Bindings", entries);
    }
    private static String normalizeSecond(String primary, String second) {
        return ControllerInput.fromChannel(second) != null && !primary.equals(second) ? second : "";
    }
    public static int normalizeSignalStrength(int value) {
        return Math.clamp(value, 0, MAX_SIGNAL_STRENGTH);
    }
    public static Map<String,String> chords(CompoundTag data) {
        Map<String,String> chords = new HashMap<>();
        ListTag entries = data.getList("Bindings", Tag.TAG_COMPOUND);
        for (int i=0; i<Math.min(entries.size(), ControllerInput.ALL.size()); i++) {
            CompoundTag entry=entries.getCompound(i);
            String channel=entry.getString("Channel"), second=normalizeSecond(channel, entry.getString("SecondInput"));
            if (ControllerInput.fromChannel(channel)!=null && !second.isEmpty()) chords.putIfAbsent(channel, second);
        }
        return Map.copyOf(chords);
    }
    public static ItemStack normalize(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        // Frequencies are item identities, not arbitrary client-supplied components.
        return new ItemStack(stack.getItem());
    }
    private ControllerSettings() {}
}
