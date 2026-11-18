package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.content.turbine.EngineControlSettings;
import com.hooya.stabilizedturret.content.turbine.EngineDriveBehavior;
import com.hooya.stabilizedturret.content.turbine.EngineTransitionRule;
import com.hooya.stabilizedturret.content.turbine.EngineRedstoneChannel;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

public final class EngineSettingsCodec {
    private EngineSettingsCodec() {}

    public static void write(RegistryFriendlyByteBuf buffer, EngineControlSettings settings) {
        buffer.writeFloat(settings.soundVolume());
        buffer.writeUtf(settings.engineToggleKey(), 64);
        buffer.writeBoolean(settings.soundRpmEnabled());
        buffer.writeFloat(settings.soundRpmAcceleration());
        buffer.writeFloat(settings.soundRpmDeceleration());
        buffer.writeFloat(settings.soundMaxRpm());
        buffer.writeVarInt(settings.behaviors().size());
        for (var behavior : settings.behaviors()) {
            buffer.writeVarInt(behavior.keys().size());
            for (String key : behavior.keys()) buffer.writeUtf(key, 64);
            buffer.writeFloat(behavior.leftSpeed());
            buffer.writeFloat(behavior.leftAcceleration());
            buffer.writeFloat(behavior.leftDeceleration());
            buffer.writeFloat(behavior.rightSpeed());
            buffer.writeFloat(behavior.rightAcceleration());
            buffer.writeFloat(behavior.rightDeceleration());
            buffer.writeVarInt(behavior.enteringRates().ordinal());
            buffer.writeVarInt(behavior.leavingRates().ordinal());
            writeChannel(buffer, behavior.redstoneChannel());
        }
        writeChannel(buffer, settings.toggleChannel());
    }

    public static EngineControlSettings read(RegistryFriendlyByteBuf buffer) {
        float volume = buffer.readFloat();
        String toggle = buffer.readUtf(64);
        boolean soundRpmEnabled = buffer.readBoolean();
        float soundRpmAcceleration = buffer.readFloat();
        float soundRpmDeceleration = buffer.readFloat();
        float soundMaxRpm = buffer.readFloat();
        int count = buffer.readVarInt();
        if (count < 1 || count > EngineControlSettings.MAX_BEHAVIORS)
            throw new IllegalArgumentException("Invalid engine behavior count");
        var behaviors = new ArrayList<EngineDriveBehavior>(count);
        for (int i = 0; i < count; i++) {
            int keyCount = buffer.readVarInt();
            if (keyCount < 0 || keyCount > EngineDriveBehavior.MAX_KEYS)
                throw new IllegalArgumentException("Invalid engine chord size");
            var keys = new ArrayList<String>(keyCount);
            for (int k = 0; k < keyCount; k++) keys.add(buffer.readUtf(64));
            behaviors.add(new EngineDriveBehavior(keys, buffer.readFloat(), buffer.readFloat(),
                    buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                    EngineTransitionRule.network(buffer.readVarInt()), EngineTransitionRule.network(buffer.readVarInt()), readChannel(buffer)));
        }
        return new EngineControlSettings(volume, toggle, behaviors, soundRpmEnabled,
                soundRpmAcceleration, soundRpmDeceleration, soundMaxRpm, readChannel(buffer));
    }

    public static void save(CompoundTag tag, EngineControlSettings settings) {
        save(tag, settings, RegistryAccess.EMPTY);
    }
    public static void save(CompoundTag tag, EngineControlSettings settings, HolderLookup.Provider registries) {
        tag.putFloat("SoundVolume", settings.soundVolume());
        tag.putString("EngineToggleKey", settings.engineToggleKey());
        tag.putBoolean("SoundRpmEnabled", settings.soundRpmEnabled());
        tag.putFloat("SoundRpmAcceleration", settings.soundRpmAcceleration());
        tag.putFloat("SoundRpmDeceleration", settings.soundRpmDeceleration());
        tag.remove("SoundRpmRate");
        tag.putFloat("SoundMaxRpm", settings.soundMaxRpm());
        saveChannel(tag, "EngineToggleChannel", settings.toggleChannel(), registries);
        var behaviors = new ListTag();
        for (var behavior : settings.behaviors()) {
            var entry = new CompoundTag();
            var keys = new ListTag();
            for (String key : behavior.keys()) keys.add(StringTag.valueOf(key));
            entry.put("Keys", keys);
            entry.putFloat("LeftSpeed", behavior.leftSpeed());
            entry.putFloat("LeftAcceleration", behavior.leftAcceleration());
            entry.putFloat("LeftDeceleration", behavior.leftDeceleration());
            entry.putFloat("RightSpeed", behavior.rightSpeed());
            entry.putFloat("RightAcceleration", behavior.rightAcceleration());
            entry.putFloat("RightDeceleration", behavior.rightDeceleration());
            entry.putInt("EnteringRates", behavior.enteringRates().ordinal());
            entry.putInt("LeavingRates", behavior.leavingRates().ordinal());
            saveChannel(entry, "RedstoneChannel", behavior.redstoneChannel(), registries);
            behaviors.add(entry);
        }
        tag.put("EngineBehaviors", behaviors);
    }

    public static EngineControlSettings load(CompoundTag tag) {
        return load(tag, RegistryAccess.EMPTY);
    }
    public static EngineControlSettings load(CompoundTag tag, HolderLookup.Provider registries) {
        float volume = value(tag, "SoundVolume", 150, 0, 300);
        if (!tag.contains("EngineBehaviors", Tag.TAG_LIST)) return migrate(tag, volume);
        var entries = tag.getList("EngineBehaviors", Tag.TAG_COMPOUND);
        var behaviors = new ArrayList<EngineDriveBehavior>();
        for (int i = 0; i < Math.min(entries.size(), EngineControlSettings.MAX_BEHAVIORS); i++) {
            var entry = entries.getCompound(i);
            var storedKeys = entry.getList("Keys", Tag.TAG_STRING);
            var keys = new ArrayList<String>();
            for (int k = 0; k < Math.min(storedKeys.size(), EngineDriveBehavior.MAX_KEYS); k++) {
                String key = storedKeys.getString(k);
                if (!key.isEmpty() && key.length() <= 64 && !keys.contains(key)) keys.add(key);
            }
            behaviors.add(new EngineDriveBehavior(keys,
                    value(entry, "LeftSpeed", 256, -1024, 1024),
                    value(entry, "LeftAcceleration", 128, 0, 1024),
                    value(entry, "LeftDeceleration", 128, 0, 1024),
                    value(entry, "RightSpeed", 256, -1024, 1024),
                    value(entry, "RightAcceleration", 128, 0, 1024),
                    value(entry, "RightDeceleration", 128, 0, 1024),
                    EngineTransitionRule.stored(entry.getInt("EnteringRates")), EngineTransitionRule.stored(entry.getInt("LeavingRates")),
                    loadChannel(entry, "RedstoneChannel", registries)));
        }
        if (behaviors.isEmpty()) behaviors.add(EngineDriveBehavior.defaults());
        String toggle = tag.getString("EngineToggleKey");
        float legacySoundRate = value(tag, "SoundRpmRate", EngineControlSettings.DEFAULT_SOUND_RPM_RATE, 0, 1024);
        return new EngineControlSettings(volume, toggle.length() > 64 ? "" : toggle, behaviors,
                tag.getBoolean("SoundRpmEnabled"),
                value(tag, "SoundRpmAcceleration", legacySoundRate, 0, 1024),
                value(tag, "SoundRpmDeceleration", legacySoundRate, 0, 1024),
                value(tag, "SoundMaxRpm", EngineControlSettings.DEFAULT_SOUND_MAX_RPM, 0, 1024),
                loadChannel(tag, "EngineToggleChannel", registries));
    }

    private static void writeChannel(RegistryFriendlyByteBuf buffer, EngineRedstoneChannel channel) {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, channel.first());
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, channel.second());
    }
    private static EngineRedstoneChannel readChannel(RegistryFriendlyByteBuf buffer) {
        return new EngineRedstoneChannel(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer), ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
    }
    private static void saveChannel(CompoundTag owner, String key, EngineRedstoneChannel channel, HolderLookup.Provider registries) {
        if (!channel.configured()) { owner.remove(key); return; }
        var tag = new CompoundTag();
        tag.put("First", channel.first().saveOptional(registries));
        tag.put("Second", channel.second().saveOptional(registries));
        owner.put(key, tag);
    }
    private static EngineRedstoneChannel loadChannel(CompoundTag owner, String key, HolderLookup.Provider registries) {
        if (!owner.contains(key, Tag.TAG_COMPOUND)) return EngineRedstoneChannel.EMPTY;
        var tag = owner.getCompound(key);
        return new EngineRedstoneChannel(ItemStack.parseOptional(registries, tag.getCompound("First")),
                ItemStack.parseOptional(registries, tag.getCompound("Second")));
    }

    private static EngineControlSettings migrate(CompoundTag tag, float volume) {
        String forward = tag.getString("AdvancedRemoteKey_0");
        String reverse = tag.getString("AdvancedRemoteKey_1");
        String left = tag.getString("AdvancedRemoteKey_2");
        String right = tag.getString("AdvancedRemoteKey_3");
        float speed = value(tag, "DriveSpeed", 256, 0, 1024);
        float f = value(tag, "ForwardSpeed", speed, 0, 1024);
        float r = value(tag, "ReverseSpeed", speed, 0, 1024);
        float t = value(tag, "TurnSpeed", 128, 0, 1024);
        float rate = value(tag, "Acceleration", 128, 0, 1024);
        float fa = value(tag, "ForwardAcceleration", rate, 0, 1024);
        float fd = value(tag, "ForwardDeceleration", rate, 0, 1024);
        float ra = value(tag, "ReverseAcceleration", rate, 0, 1024);
        float rd = value(tag, "ReverseDeceleration", rate, 0, 1024);
        float ta = value(tag, "TurnAcceleration", rate, 0, 1024);
        float td = value(tag, "TurnDeceleration", rate, 0, 1024);
        var behaviors = new ArrayList<EngineDriveBehavior>();
        legacy(behaviors, List.of(forward), f, f, fa, fd);
        legacy(behaviors, List.of(reverse), -r, -r, ra, rd);
        legacy(behaviors, List.of(left), -t, t, ta, td);
        legacy(behaviors, List.of(right), t, -t, ta, td);
        legacy(behaviors, List.of(forward, left), 0, f, fa, fd);
        legacy(behaviors, List.of(forward, right), f, 0, fa, fd);
        legacy(behaviors, List.of(reverse, left), -r, 0, ra, rd);
        legacy(behaviors, List.of(reverse, right), 0, -r, ra, rd);
        if (behaviors.isEmpty()) behaviors.add(EngineDriveBehavior.defaults());
        String toggle = tag.getString("AdvancedRemoteKey_4");
        return new EngineControlSettings(volume, toggle.length() > 64 ? "" : toggle, behaviors);
    }

    private static void legacy(List<EngineDriveBehavior> behaviors, List<String> keys,
                               float left, float right, float acceleration, float deceleration) {
        if (keys.stream().anyMatch(k -> k.isEmpty() || k.length() > 64)) return;
        behaviors.add(new EngineDriveBehavior(keys.stream().distinct().toList(),
                left, acceleration, deceleration, right, acceleration, deceleration));
    }

    private static float value(CompoundTag tag, String key, float fallback, float min, float max) {
        float value = tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getFloat(key) : fallback;
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
