package com.hooya.stabilizedturret.compat.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Function;

/** Converts per-bearing controller settings between world and blueprint references. */
final class BearingSettingsBlueprintCodec {
    private static final String SETTINGS = "BearingSettings";
    private static final String PORTABLE_SETTINGS =
            "stabilized_turret:portable_bearing_settings";

    static void save(
            CompoundTag owner,
            Function<BlockPos, @Nullable PortableRef> mapper
    ) {
        save(owner, SETTINGS, PORTABLE_SETTINGS, mapper);
    }

    static void save(CompoundTag owner, String settingsKey, String portableKey,
                     Function<BlockPos, @Nullable PortableRef> mapper) {
        ListTag portableSettings = new ListTag();
        ListTag settings = owner.getList(settingsKey, Tag.TAG_COMPOUND);
        for (int index = 0; index < settings.size(); index++) {
            CompoundTag entry = settings.getCompound(index);
            if (!entry.contains("Ref", Tag.TAG_COMPOUND)) continue;
            CompoundTag savedRef = entry.getCompound("Ref");
            PortableRef mapped = mapper.apply(BlockPos.of(savedRef.getLong("Pos")));
            if (mapped == null) continue;

            CompoundTag portableRef = new CompoundTag();
            portableRef.putInt("SubLevelId", mapped.subLevelId());
            portableRef.putLong("LocalPos", mapped.localPos().asLong());
            if (savedRef.contains("Block")) {
                portableRef.putString("Block", savedRef.getString("Block"));
            }
            portableRef.putBoolean(
                    "HasBlockEntity", savedRef.getBoolean("HasBlockEntity"));

            CompoundTag portableEntry = entry.copy();
            portableEntry.put("Ref", portableRef);
            portableSettings.add(portableEntry);
        }
        owner.remove(settingsKey);
        owner.remove(portableKey);
        if (!portableSettings.isEmpty()) {
            owner.put(portableKey, portableSettings);
        }
    }

    static void load(
            CompoundTag owner,
            Function<PortableRef, @Nullable PlacedRef> mapper
    ) {
        load(owner, SETTINGS, PORTABLE_SETTINGS, mapper);
    }

    static void load(CompoundTag owner, String settingsKey, String portableKey,
                     Function<PortableRef, @Nullable PlacedRef> mapper) {
        // Pre-fix blueprints can contain absolute source-world references.
        owner.remove(settingsKey);
        ListTag mappedSettings = new ListTag();
        ListTag portableSettings = owner.getList(
                portableKey, Tag.TAG_COMPOUND);
        for (int index = 0; index < portableSettings.size(); index++) {
            CompoundTag entry = portableSettings.getCompound(index);
            if (!entry.contains("Ref", Tag.TAG_COMPOUND)) continue;
            CompoundTag portableRef = entry.getCompound("Ref");
            PortableRef source = new PortableRef(
                    portableRef.getInt("SubLevelId"),
                    BlockPos.of(portableRef.getLong("LocalPos")));
            PlacedRef mapped = mapper.apply(source);
            if (mapped == null) continue;

            CompoundTag mappedRef = new CompoundTag();
            mappedRef.putLong("Pos", mapped.pos().asLong());
            if (portableRef.contains("Block")) {
                mappedRef.putString("Block", portableRef.getString("Block"));
            }
            mappedRef.putBoolean(
                    "HasBlockEntity", portableRef.getBoolean("HasBlockEntity"));
            if (mapped.subLevelId() != null) {
                mappedRef.putUUID("SubLevel", mapped.subLevelId());
            }

            CompoundTag mappedEntry = entry.copy();
            mappedEntry.put("Ref", mappedRef);
            mappedSettings.add(mappedEntry);
        }
        if (!mappedSettings.isEmpty()) {
            owner.put(settingsKey, mappedSettings);
        }
        owner.remove(portableKey);
    }

    record PortableRef(int subLevelId, BlockPos localPos) {
    }

    record PlacedRef(BlockPos pos, @Nullable UUID subLevelId) {
    }

    private BearingSettingsBlueprintCodec() {
    }
}
