package com.hooya.stabilizedturret.client.compat;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.preview.PreviewCaptureScope;
import com.hooya.stabilizedturret.mixin.client.QModelingThermalPartAccess;
import com.hooya.stabilizedturret.mixin.client.QModelingThermalWorkspaceAccess;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.function.BiConsumer;

/** Reuses stable qModeling geometry while applying the live camera/sub-level pose on every draw. */
public final class QModelingThermalGeometryCache {
    private static final ThermalModelMeshCache cache = new ThermalModelMeshCache(128, 64L * 1024 * 1024);
    private static long hits, builds, live;
    private static long lastReport = System.nanoTime();
    private record State(Object blockState, int animationSlot) {}

    public static void render(Object workspace, PoseStack pose, MultiBufferSource buffers, int light, int overlay,
                              BiConsumer<PoseStack, MultiBufferSource> original) {
        if (!QModelingThermalRenderBridge.useNativeGeometry() || PreviewCaptureScope.active()
                || !QModelingThermalRenderBridge.hasRenderContext()
                || !(workspace instanceof QModelingThermalWorkspaceAccess access)
                || !(workspace instanceof BlockEntity blockEntity)) {
            original.accept(pose, buffers);
            return;
        }
        var profiler = Minecraft.getInstance().getProfiler();
        profiler.push("qmodeling_thermal_geometry");
        try {
            int animationSlot = access.createFireControl$thermalAnimationSlot();
            if (animationSlot >= 0 && access.createFireControl$thermalAnimationPlaying()) {
                live++;
                cache.remove(workspace);
                profiler.push("animated_live");
                try { original.accept(pose, buffers); } finally { profiler.pop(); }
                return;
            }
            var stamp = new ThermalModelMeshCache.Stamp(access.createFireControl$thermalRevision(),
                    light, overlay, new State(blockEntity.getBlockState(), animationSlot));
            ThermalModelMesh mesh = cache.get(workspace, stamp, () -> eligible(access), () -> {
                builds++;
                profiler.push("mesh_build");
                try {
                    return ThermalModelMesh.capture(source -> original.accept(new PoseStack(), source));
                } finally { profiler.pop(); }
            });
            if (mesh != null) {
                hits++;
                QModelingThermalRenderBridge.queue(mesh, pose);
            } else {
                live++;
                profiler.push("dynamic_live");
                try { original.accept(pose, buffers); } finally { profiler.pop(); }
            }
        } finally {
            profiler.pop();
            long now = System.nanoTime();
            if (now - lastReport >= 10_000_000_000L) {
                StabilizedTurret.LOGGER.info("qModeling thermal mesh cache: reused={} built={} live={}", hits, builds, live);
                hits = builds = live = 0;
                lastReport = now;
            }
        }
    }

    private static boolean eligible(QModelingThermalWorkspaceAccess workspace) {
        for (Object part : workspace.createFireControl$thermalParts()) {
            if (!(part instanceof QModelingThermalPartAccess access)) return false;
            // These parts are drawn separately by qModeling's auxiliary path.
            if (access.createFireControl$thermalBone() || access.createFireControl$thermalText()
                    || access.createFireControl$thermalImage() || access.createFireControl$thermalItem()
                    || access.createFireControl$thermalParticle() || access.createFireControl$thermalSound()) continue;
            if (access.createFireControl$thermalForceDynamic() || access.createFireControl$thermalBillboard()) return false;
            var tag = access.createFireControl$thermalBlockEntityData();
            if (tag != null && tag.getBoolean("QModelingForceDynamicRender")) return false;
        }
        return true;
    }

    public static void shutdown() {
        cache.close();
        hits = builds = live = 0;
        lastReport = System.nanoTime();
    }

    private QModelingThermalGeometryCache() {}
}
