package com.hooya.stabilizedturret.compat.schematic;

import com.hooya.stabilizedturret.content.bearing.YawLimits;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/** Regression checks for portable per-bearing settings. */
public final class BearingSettingsBlueprintCodecChecks {
    public static void main(String[] args) {
        BlockPos sourcePos = new BlockPos(100, 20, -30);
        BlockPos localPos = new BlockPos(4, 5, 6);
        BlockPos placedPos = new BlockPos(700, 30, 900);
        UUID sourceSubLevel = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID placedSubLevel = UUID.fromString("22222222-2222-2222-2222-222222222222");

        CompoundTag owner = settingsOwner(sourcePos, sourceSubLevel);
        BearingSettingsBlueprintCodec.save(owner, pos -> pos.equals(sourcePos)
                ? new BearingSettingsBlueprintCodec.PortableRef(7, localPos) : null);
        if (owner.contains("BearingSettings")
                || !owner.contains("stabilized_turret:portable_bearing_settings", Tag.TAG_LIST)) {
            throw new AssertionError("save must replace absolute settings with portable settings");
        }
        CompoundTag portableRef = owner.getList(
                "stabilized_turret:portable_bearing_settings", Tag.TAG_COMPOUND)
                .getCompound(0).getCompound("Ref");
        if (portableRef.getInt("SubLevelId") != 7
                || !BlockPos.of(portableRef.getLong("LocalPos")).equals(localPos)
                || portableRef.hasUUID("SubLevel") || portableRef.contains("Dimension")) {
            throw new AssertionError("portable reference retained source-world identity");
        }

        BearingSettingsBlueprintCodec.load(owner, ref ->
                ref.subLevelId() == 7 && ref.localPos().equals(localPos)
                        ? new BearingSettingsBlueprintCodec.PlacedRef(
                        placedPos, placedSubLevel) : null);
        if (owner.contains("stabilized_turret:portable_bearing_settings")) {
            throw new AssertionError("portable settings leaked into placed block-entity NBT");
        }
        CompoundTag mappedEntry = owner.getList("BearingSettings", Tag.TAG_COMPOUND)
                .getCompound(0);
        CompoundTag mappedRef = mappedEntry.getCompound("Ref");
        if (!BlockPos.of(mappedRef.getLong("Pos")).equals(placedPos)
                || !placedSubLevel.equals(mappedRef.getUUID("SubLevel"))
                || mappedRef.contains("Dimension")
                || !mappedEntry.getBoolean("HeadHidden")
                || mappedEntry.getDouble("Speed") != 42.0
                || !YawLimits.load(mappedEntry).equals(new YawLimits(60, 110, YawLimits.NORTH))) {
            throw new AssertionError("placed bearing settings were not remapped losslessly");
        }

        CompoundTag legacy = settingsOwner(sourcePos, sourceSubLevel);
        BearingSettingsBlueprintCodec.load(legacy, ignored -> {
            throw new AssertionError("legacy absolute settings must not be mapped");
        });
        if (legacy.contains("BearingSettings")) {
            throw new AssertionError("legacy absolute settings were not discarded");
        }

        CompoundTag display = settingsOwner(sourcePos, sourceSubLevel);
        display.put("PreviewBearingSettings", display.getList("BearingSettings", Tag.TAG_COMPOUND).copy());
        display.remove("BearingSettings");
        String portableDisplay = "stabilized_turret:portable_display_binding_bearing_settings";
        BearingSettingsBlueprintCodec.save(display, "PreviewBearingSettings", portableDisplay,
                pos -> new BearingSettingsBlueprintCodec.PortableRef(7, localPos));
        if (display.contains("PreviewBearingSettings")) throw new AssertionError("display blueprint retained absolute settings");
        BearingSettingsBlueprintCodec.load(display, "PreviewBearingSettings", portableDisplay,
                ref -> new BearingSettingsBlueprintCodec.PlacedRef(placedPos, placedSubLevel));
        CompoundTag displaySettings = display.getList("PreviewBearingSettings", Tag.TAG_COMPOUND).getCompound(0);
        if (display.contains(portableDisplay) || displaySettings.getDouble("Speed") != 42
                || !displaySettings.getBoolean("HeadHidden")
                || !placedSubLevel.equals(displaySettings.getCompound("Ref").getUUID("SubLevel"))
                || !YawLimits.load(displaySettings).equals(new YawLimits(60, 110, YawLimits.NORTH))) {
            throw new AssertionError("display per-device settings did not survive blueprint remapping");
        }

        CompoundTag neutral = settingsOwner(sourcePos, sourceSubLevel);
        ListTag defaults = neutral.getList("BearingSettings", Tag.TAG_COMPOUND).copy();
        defaults.getCompound(0).putDouble("Angle", -35.25);
        neutral.remove("BearingSettings");
        neutral.put("BearingNeutralAngles", defaults);
        String portableNeutral = "stabilized_turret:portable_display_binding_bearing_neutral_angles";
        BearingSettingsBlueprintCodec.save(neutral, "BearingNeutralAngles", portableNeutral,
                pos -> new BearingSettingsBlueprintCodec.PortableRef(7, localPos));
        BearingSettingsBlueprintCodec.load(neutral, "BearingNeutralAngles", portableNeutral,
                ref -> new BearingSettingsBlueprintCodec.PlacedRef(placedPos, placedSubLevel));
        CompoundTag boundPose = neutral.getList("BearingNeutralAngles", Tag.TAG_COMPOUND).getCompound(0);
        if (neutral.contains(portableNeutral) || boundPose.getDouble("Angle") != -35.25
                || !BlockPos.of(boundPose.getCompound("Ref").getLong("Pos")).equals(placedPos)
                || !placedSubLevel.equals(boundPose.getCompound("Ref").getUUID("SubLevel"))) {
            throw new AssertionError("display bound default pose retained source-world identity or lost its angle");
        }
    }

    private static CompoundTag settingsOwner(BlockPos pos, UUID subLevelId) {
        CompoundTag ref = new CompoundTag();
        ref.putLong("Pos", pos.asLong());
        ref.putUUID("SubLevel", subLevelId);
        ref.putString("Dimension", "minecraft:overworld");
        ref.putString("Block", "simulated:swivel_bearing");
        ref.putBoolean("HasBlockEntity", true);

        CompoundTag setting = new CompoundTag();
        setting.put("Ref", ref);
        setting.putDouble("Depression", 12.0);
        setting.putDouble("Elevation", 34.0);
        setting.putDouble("Speed", 42.0);
        new YawLimits(60, 110, YawLimits.NORTH).save(setting);
        setting.putBoolean("Reversed", true);
        setting.putBoolean("HeadHidden", true);
        setting.putDouble("OffsetX", 1.0);
        setting.putDouble("OffsetY", 2.0);
        setting.putDouble("OffsetZ", 3.0);

        ListTag settings = new ListTag();
        settings.add(setting);
        CompoundTag owner = new CompoundTag();
        owner.put("BearingSettings", settings);
        return owner;
    }

    private BearingSettingsBlueprintCodecChecks() {
    }
}
