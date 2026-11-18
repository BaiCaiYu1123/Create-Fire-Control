package com.hooya.stabilizedturret.client.compat;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ThermalRenderMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Uses qModeling's own ordinary model renderer only for shader-pack thermal frames. */
public final class QModelingThermalRenderBridge {
    // Render-thread-only pool. Nested workspaces use separate builders, never
    // the world's shared entity batch or an outer workspace's unfinished mesh.
    private static final ArrayDeque<Buffers> available = new ArrayDeque<>();
    private static final ArrayDeque<Buffers> active = new ArrayDeque<>();
    private static boolean logged;

    public static boolean useNativeGeometry() {
        return ThermalRenderMode.isShaderPackThermal();
    }

    public static void render(MultiBufferSource originalBuffers, Consumer<MultiBufferSource> renderer) {
        int target = IrisThermalFramebufferBridge.getFramebufferId();
        if (!useNativeGeometry() || target == 0) {
            renderer.accept(originalBuffers);
            return;
        }
        Buffers buffers = available.pollFirst();
        if (buffers == null) buffers = new Buffers();
        var main = Minecraft.getInstance().getMainRenderTarget();
        var profiler = Minecraft.getInstance().getProfiler();
        boolean completed = false;
        try (var scope = new ThermalFramebufferScope(target, main.width, main.height)) {
            active.push(buffers);
            profiler.push("qmodeling_thermal_frame");
            try {
                renderer.accept(buffers.source);
                if (!buffers.meshes.isEmpty()) {
                    // Live block entities/items and cached blocks must all write
                    // opaque depth before glass is blended into the same scene.
                    buffers.source.endOpaqueBatch();
                    profiler.push("cached_opaque");
                    try { for (Queued mesh : buffers.meshes) mesh.mesh.draw(mesh.pose, false); }
                    finally { profiler.pop(); }
                    profiler.push("cached_transparent");
                    try { for (Queued mesh : buffers.meshes) mesh.mesh.draw(mesh.pose, true); }
                    finally { profiler.pop(); }
                }
                buffers.source.endBatch();
            } finally {
                profiler.pop();
                active.pop();
                buffers.meshes.forEach(mesh -> mesh.mesh.release());
                buffers.meshes.clear();
            }
            completed = true;
            if (!logged) {
                logged = true;
                StabilizedTurret.LOGGER.info("Enabled qModeling shader-pack thermal geometry route");
            }
        } finally {
            if (completed && available.size() < 2) {
                available.addFirst(buffers);
            } else {
                buffers.source.close();
            }
        }
    }

    public static boolean hasRenderContext() { return !active.isEmpty(); }

    public static void queue(ThermalModelMesh mesh, PoseStack pose) {
        var queued = new Queued(mesh, new Matrix4f(pose.last().pose()));
        mesh.retain();
        active.getFirst().meshes.add(queued);
    }

    public static void shutdown() {
        QModelingThermalGeometryCache.shutdown();
        Buffers buffers;
        while ((buffers = available.pollFirst()) != null) buffers.source.close();
    }

    private static final class Buffers {
        private final ThermalModelBufferSource source = new ThermalModelBufferSource();
        private final List<Queued> meshes = new ArrayList<>();
    }

    private record Queued(ThermalModelMesh mesh, Matrix4f pose) {}

    private QModelingThermalRenderBridge() {
    }
}
