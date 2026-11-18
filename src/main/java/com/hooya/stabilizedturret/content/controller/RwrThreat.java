package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A server-authoritative emitter shown on an aircraft radar warning receiver. */
public record RwrThreat(UUID id, Type type, Vec3 position, boolean incoming) {
    public enum Type {
        AAA,
        RFL,
        MSL
    }

    public static UUID sourceId(String kind, Level level, BlockPos pos) {
        SubLevel containing = level == null ? null : Sable.HELPER.getContaining(level, pos);
        return sourceId(kind, level, pos,
                containing == null ? null : containing.getUniqueId());
    }

    public static UUID sourceId(
            String kind,
            Level level,
            BlockPos pos,
            @Nullable UUID subLevelId
    ) {
        String dimension = level == null
                ? "unknown"
                : level.dimension().location().toString();
        String key = "create-fire-control-rwr:" + kind + ':' + dimension + ':'
                + (subLevelId == null ? "root" : subLevelId) + ':' + pos.asLong();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
