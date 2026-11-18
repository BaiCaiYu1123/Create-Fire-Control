package com.hooya.stabilizedturret.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Immutable local model vertices; only transparent index ordering changes with the camera. */
public final class ThermalModelMesh implements AutoCloseable {
    final List<Layer> layers;
    private final long bytes;
    private int references;
    private boolean closeRequested;
    private boolean released;

    private ThermalModelMesh(List<Layer> layers, long bytes) {
        this.layers = List.copyOf(layers);
        this.bytes = bytes;
    }

    public static ThermalModelMesh capture(Consumer<MultiBufferSource> renderer) {
        try (Capture capture = new Capture()) {
            renderer.accept(capture);
            if (capture.unsupported) throw new UnsupportedOperationException("Non-quad qModeling geometry");
            List<Layer> layers = new ArrayList<>();
            long bytes = 0;
            try {
                var types = new ArrayList<>(capture.sinks.keySet());
                types.sort(Comparator.comparing(RenderType::sortOnUpload));
                for (RenderType type : types) {
                    MeshData data = capture.sinks.get(type).builder.build();
                    if (data == null) continue;
                    VertexBuffer buffer = null;
                    ByteBufferBuilder indices = null;
                    boolean consumed = false;
                    try {
                        bytes += data.vertexBuffer().remaining();
                        MeshData.SortState sorting = null;
                        if (type.sortOnUpload()) {
                            indices = new ByteBufferBuilder(Math.max(256,
                                    data.drawState().indexCount() * data.drawState().indexType().bytes));
                            sorting = data.sortQuads(indices, VertexSorting.DISTANCE_TO_ORIGIN);
                            bytes += data.drawState().indexCount() * data.drawState().indexType().bytes;
                        }
                        buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                        buffer.bind();
                        consumed = true; // VertexBuffer.upload owns and closes MeshData, including on failure.
                        buffer.upload(data);
                        layers.add(new Layer(type, buffer, sorting, indices));
                        buffer = null;
                        indices = null;
                    } finally {
                        if (!consumed) data.close();
                        if (buffer != null) buffer.close();
                        if (indices != null) indices.close();
                        VertexBuffer.unbind();
                    }
                }
                return new ThermalModelMesh(layers, bytes);
            } catch (RuntimeException | Error failure) {
                layers.forEach(Layer::close);
                throw failure;
            }
        }
    }

    public long bytes() {
        return bytes;
    }

    public void draw(PoseStack pose) {
        draw(pose.last().pose(), null);
    }

    public void draw(Matrix4f pose, Boolean transparent) {
        Matrix4f model = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose);
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        for (Layer layer : layers) {
            if (transparent != null && layer.type.sortOnUpload() != transparent) continue;
            layer.type.setupRenderState();
            try {
                layer.buffer.bind();
                layer.sort(model);
                layer.buffer.drawWithShader(model, projection, RenderSystem.getShader());
            } finally {
                VertexBuffer.unbind();
                layer.type.clearRenderState();
            }
        }
    }

    @Override
    public void close() {
        closeRequested = true;
        releaseIfUnused();
    }

    public void retain() {
        if (released) throw new IllegalStateException("Thermal model mesh is already released");
        references++;
    }

    public void release() {
        if (references <= 0) throw new IllegalStateException("Unbalanced thermal model mesh reference");
        references--;
        releaseIfUnused();
    }

    private void releaseIfUnused() {
        if (closeRequested && references == 0 && !released) {
            released = true;
            layers.forEach(Layer::close);
        }
    }

    static final class Layer implements AutoCloseable {
        final RenderType type;
        final VertexBuffer buffer;
        private final MeshData.SortState sorting;
        private final ByteBufferBuilder indices;
        private final Matrix4f lastSort = new Matrix4f();
        private boolean sorted;

        Layer(RenderType type, VertexBuffer buffer, MeshData.SortState sorting, ByteBufferBuilder indices) {
            this.type = type;
            this.buffer = buffer;
            this.sorting = sorting;
            this.indices = indices;
        }

        void sort(Matrix4f model) {
            if (sorting == null || sorted && lastSort.equals(model, 0.00001f)) return;
            // Sorting in transformed space also handles scaled or articulated sub-levels.
            buffer.uploadIndexBuffer(sorting.buildSortedIndexBuffer(indices, VertexSorting.byDistance(point -> {
                float x = model.m00() * point.x + model.m10() * point.y + model.m20() * point.z + model.m30();
                float y = model.m01() * point.x + model.m11() * point.y + model.m21() * point.z + model.m31();
                float z = model.m02() * point.x + model.m12() * point.y + model.m22() * point.z + model.m32();
                return x * x + y * y + z * z;
            })));
            lastSort.set(model);
            sorted = true;
        }

        @Override public void close() {
            buffer.close();
            if (indices != null) indices.close();
        }
    }

    private static final class Capture implements MultiBufferSource, AutoCloseable {
        private final Map<RenderType, Sink> sinks = new LinkedHashMap<>();
        private boolean unsupported;

        @Override public VertexConsumer getBuffer(RenderType type) {
            if (type.mode() != VertexFormat.Mode.QUADS) {
                unsupported = true;
                throw new UnsupportedOperationException("Dynamic primitive topology");
            }
            return sinks.computeIfAbsent(type, Sink::new).builder;
        }

        @Override public void close() {
            sinks.values().forEach(sink -> sink.bytes.close());
        }
    }

    private static final class Sink {
        private final ByteBufferBuilder bytes = new ByteBufferBuilder(65536);
        private final BufferBuilder builder;
        Sink(RenderType type) { builder = new BufferBuilder(bytes, type.mode(), type.format()); }
    }
}
