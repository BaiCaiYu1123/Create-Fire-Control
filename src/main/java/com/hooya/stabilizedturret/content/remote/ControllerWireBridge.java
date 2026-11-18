package com.hooya.stabilizedturret.content.remote;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import java.util.*;

/** Only this dispatch loads the optional implementation. */
public final class ControllerWireBridge {
    private static boolean reportedFailure;
    private static void failed(Throwable error) {
        if (!reportedFailure) {
            reportedFailure = true;
            com.hooya.stabilizedturret.StabilizedTurret.LOGGER.warn(
                    "Drive By Wire bridge failed; fire-control native input remains available", error);
        }
    }
    public static boolean available() { return ModList.get().isLoaded("drivebywire"); }
    public static Set<String> connected(Level level, BlockPos pos) {
        try { return available() ? ControllerWireIntegration.connected(level, pos) : Set.of(); }
        catch (RuntimeException | LinkageError error) { failed(error); return Set.of(); }
    }
    public static void set(Level level, BlockPos pos, String channel, int value) {
        try { if (available()) ControllerWireIntegration.set(level, pos, channel, value); }
        catch (RuntimeException | LinkageError error) { failed(error); }
    }
    public static void clear(Level level, BlockPos pos) {
        try { if (available()) ControllerWireIntegration.clear(level, pos); }
        catch (RuntimeException | LinkageError error) { failed(error); }
    }
    public static boolean connect(Level level,BlockPos terminal,BlockPos device,net.minecraft.core.Direction side,String channel) {
        if (!available()) return false;
        try { ControllerWireIntegration.connect(level,terminal,device,side,channel);return true; }
        catch (RuntimeException | LinkageError error) { failed(error);return false; }
    }
    public static boolean disconnect(Level level,BlockPos terminal,BlockPos device,net.minecraft.core.Direction side,String channel) {
        if (!available()) return false;
        try { ControllerWireIntegration.disconnect(level,terminal,device,side,channel);return true; }
        catch (RuntimeException | LinkageError error) { failed(error);return false; }
    }
    public static net.minecraft.nbt.ListTag snapshot(Level level, BlockPos terminal) {
        try { return available() ? ControllerWireIntegration.snapshot(level, terminal) : new net.minecraft.nbt.ListTag(); }
        catch (RuntimeException | LinkageError error) { failed(error); return new net.minecraft.nbt.ListTag(); }
    }
    public static net.minecraft.nbt.ListTag restore(Level level, BlockPos terminal, net.minecraft.nbt.ListTag entries) {
        try { return available() ? ControllerWireIntegration.restore(level, terminal, entries) : entries; }
        catch (RuntimeException | LinkageError error) { failed(error); return entries; }
    }
    private ControllerWireBridge() {}
}
