package com.hooya.stabilizedturret.client.compat;

import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Set;

/** Consolidates alternating model parts into one upload per render layer. */
public final class ThermalModelBufferSource extends MultiBufferSource.BufferSource implements AutoCloseable {
    private final Set<RenderType> used = Collections.newSetFromMap(new IdentityHashMap<>());

    public ThermalModelBufferSource() {
        super(new ByteBufferBuilder(256), new LinkedHashMap<>());
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        used.add(type);
        fixedBuffers.computeIfAbsent(type, layer -> new ByteBufferBuilder(Math.min(layer.bufferSize(), 64 * 1024)));
        // Connected primitives may cause super.getBuffer() to draw immediately.
        // Flywheel/Voxy use raw VAO bindings that don't update this vanilla cache.
        BufferUploader.invalidate();
        return super.getBuffer(type);
    }

    @Override
    public void endBatch() {
        endLastBatch();
        var layers = new ArrayList<>(fixedBuffers.keySet());
        // Keep alpha-sorted layers after the solid depth-writing layers.
        layers.sort(Comparator.comparing(RenderType::sortOnUpload));
        for (RenderType layer : layers) endBatch(layer);
        var iterator = fixedBuffers.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!used.contains(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
        used.clear();
    }

    public void endOpaqueBatch() {
        for (RenderType layer : new ArrayList<>(fixedBuffers.keySet())) {
            if (!layer.sortOnUpload()) endBatch(layer);
        }
    }

    @Override
    public void endBatch(RenderType type) {
        BufferUploader.invalidate();
        super.endBatch(type);
    }

    @Override
    public void close() {
        startedBuilders.clear();
        fixedBuffers.values().forEach(ByteBufferBuilder::close);
        fixedBuffers.clear();
        sharedBuffer.close();
        used.clear();
    }
}
