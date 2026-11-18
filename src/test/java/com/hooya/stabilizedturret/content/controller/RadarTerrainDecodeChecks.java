package com.hooya.stabilizedturret.content.controller;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.CompoundTag;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.lang.management.ManagementFactory;

/** Optional local benchmark of read-only exported terrain, including NBT parse, palettes and summary. */
public final class RadarTerrainDecodeChecks {
    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        List<byte[]> inputs = new ArrayList<>();
        try (var paths = Files.list(Path.of(args[0]))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".nbt")).sorted().toList()) inputs.add(Files.readAllBytes(path));
        }
        if (inputs.isEmpty()) throw new AssertionError("No actual terrain fixtures");
        var summary = Class.forName(RadarVisibility.class.getName() + "$ColumnSummary")
                .getDeclaredConstructor(RocketTerrainCache.Snapshot.class, long.class); summary.setAccessible(true);
        int count = inputs.size() * 16;
        var bean = ManagementFactory.getThreadMXBean();
        for (int round = 0; round < 2; round++) {
            int valid = 0; long began = System.nanoTime(), cpu = bean.getCurrentThreadCpuTime();
            for (int i = 0; i < count; i++) {
                CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(inputs.get(i % inputs.size()))));
                var snapshot = RocketTerrainCache.decode(tag);
                if (snapshot == null) throw new AssertionError("Unusable real terrain fixture " + (i % inputs.size()));
                summary.newInstance(snapshot, 0L); valid++;
            }
            double cpuMs = (bean.getCurrentThreadCpuTime() - cpu) / 1e6;
            System.out.printf(Locale.ROOT, "PASS: terrain decode round=%d count=%d CPU=%.3fms perColumn=%.4fms wall=%.3fms%n",
                    round, valid, cpuMs, cpuMs / valid, (System.nanoTime() - began) / 1e6);
        }
    }
}
