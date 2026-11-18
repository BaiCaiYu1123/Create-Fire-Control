package com.hooya.stabilizedturret.client.compat;

import com.hooya.stabilizedturret.StabilizedTurret;
import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Render-thread-only, bounded GPU cache; rejected revisions retain the live fallback. */
public final class ThermalModelMeshCache implements AutoCloseable {
    public record Stamp(long revision, int light, int overlay, Object state) {}
    private record Entry(Stamp stamp, ThermalModelMesh mesh) {}
    private final LinkedHashMap<Object, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final long maxBytes;
    private final int maxEntries;
    private long bytes;
    private boolean failureLogged;

    public ThermalModelMeshCache(int maxEntries, long maxBytes) {
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    public ThermalModelMesh get(Object owner, Stamp stamp, BooleanSupplier eligible,
                                Supplier<ThermalModelMesh> build) {
        Entry entry = entries.get(owner);
        if (entry != null && entry.stamp.equals(stamp)) return entry.mesh;
        remove(owner);
        ThermalModelMesh mesh = null;
        if (eligible.getAsBoolean()) {
            try {
                mesh = build.get();
                if (mesh.bytes() > maxBytes) {
                    mesh.close();
                    mesh = null;
                }
            } catch (UnsupportedOperationException ignored) {
                // Some modded models depend on dynamic primitives; keep the live renderer.
            } catch (RuntimeException failure) {
                if (!failureLogged) {
                    failureLogged = true;
                    StabilizedTurret.LOGGER.warn("Unable to cache optional thermal model geometry; using live renderer", failure);
                }
            }
        }
        entries.put(owner, new Entry(stamp, mesh));
        if (mesh != null) bytes += mesh.bytes();
        while (entries.size() > maxEntries || bytes > maxBytes) remove(entries.firstEntry().getKey());
        return mesh;
    }

    public void remove(Object owner) {
        Entry removed = entries.remove(owner);
        if (removed != null && removed.mesh != null) {
            bytes -= removed.mesh.bytes();
            removed.mesh.close();
        }
    }

    @Override public void close() {
        while (!entries.isEmpty()) remove(entries.firstEntry().getKey());
    }
}
