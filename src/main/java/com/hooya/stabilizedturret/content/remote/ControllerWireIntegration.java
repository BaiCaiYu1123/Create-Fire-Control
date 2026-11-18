package com.hooya.stabilizedturret.content.remote;

import edn.stratodonut.drivebywire.wire.WireNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.Direction;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import java.util.*;

/** Never referenced by static initialization when the optional dependency is absent. */
final class ControllerWireIntegration {
    static Set<String> connected(Level level, BlockPos pos) {
        var channels = WireNetworkManager.get(level).getNetwork().get(pos.asLong());
        if (channels == null) return Set.of();
        Set<String> connected = new HashSet<>();
        channels.forEach((channel, sinks) -> {
            if (ControllerInput.fromChannel(channel) != null && sinks.stream().anyMatch(sink ->
                    level.hasChunkAt(BlockPos.of(sink.position()))
                            && !level.getBlockState(BlockPos.of(sink.position())).isAir())) connected.add(channel);
        });
        return Set.copyOf(connected);
    }
    static void set(Level level, BlockPos pos, String channel, int value) {
        WireNetworkManager.trySetSignalAt(level, pos, channel, value);
    }
    static void clear(Level level, BlockPos pos) {
        WireNetworkManager.get(level).clearSourceSignals(level, pos);
    }
    static void connect(Level level,BlockPos terminal,BlockPos device,net.minecraft.core.Direction side,String channel) {
        WireNetworkManager.createConnection(level,terminal,device,side,channel);
    }
    static void disconnect(Level level,BlockPos terminal,BlockPos device,net.minecraft.core.Direction side,String channel) {
        WireNetworkManager.removeConnection(level,terminal,device,side,channel);
    }

    static ListTag snapshot(Level level, BlockPos terminal) {
        ListTag result = new ListTag();
        var channels = WireNetworkManager.get(level).getNetwork().get(terminal.asLong());
        if (channels == null) return result;
        channels.forEach((channel, sinks) -> sinks.forEach(sink -> {
            CompoundTag entry = new CompoundTag();
            entry.put("Sink", BindingRef.capture(level, BlockPos.of(sink.position())).save());
            entry.putString("Channel", channel);
            entry.putInt("Side", sink.direction());
            result.add(entry);
        }));
        return result;
    }

    static ListTag restore(Level level, BlockPos terminal, ListTag entries) {
        ListTag pending = new ListTag();
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            CompoundTag sink = entry.getCompound("Sink");
            if (!sink.contains("Pos", Tag.TAG_LONG) || !sink.contains("Block", Tag.TAG_STRING)
                    || !entry.contains("Channel", Tag.TAG_STRING) || entry.getString("Channel").isEmpty()
                    || !entry.contains("Side", Tag.TAG_ANY_NUMERIC)
                    || entry.getInt("Side") < 0 || entry.getInt("Side") > 5) continue;
            BlockPos pos = BlockPos.of(sink.getLong("Pos"));
            // Imported plots and endpoints may become available on different ticks.
            if (!level.hasChunkAt(pos) || level.getBlockState(pos).isAir()) {
                pending.add(entry.copy()); continue;
            }
            if (sink.hasUUID("SubLevel") && dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)
                    .getSubLevel(sink.getUUID("SubLevel")) == null) {
                pending.add(entry.copy()); continue;
            }
            // A block state can arrive before its block entity during plot loading.
            if (sink.getBoolean("HasBlockEntity") && level.getBlockState(pos).hasBlockEntity()
                    && level.getBlockEntity(pos) == null) {
                pending.add(entry.copy()); continue;
            }
            if (!BindingRef.loadTag(sink).isSameTarget(level)) continue;
            Direction side = Direction.from3DDataValue(entry.getInt("Side"));
            String channel = entry.getString("Channel");
            if (!WireNetworkManager.hasConnection(level, terminal, pos, side, channel)
                    && !WireNetworkManager.createConnection(level, terminal, pos, side, channel).isSuccess()) {
                pending.add(entry.copy());
            }
        }
        return pending;
    }
}
