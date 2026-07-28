package com.hooya.stabilizedturret.client.compat;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientAimState;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL45C;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Samples the already composited world depth at the optical screen centre.
 *
 * <p>NHblock714/Voxy's normal pipeline transforms its LOD depth into
 * Minecraft's main target. Reading that final depth detects exactly the
 * surface the player can see, even when no vanilla chunk or CPU WorldSection
 * exists there. A small PBO ring keeps the read asynchronous and avoids a
 * per-frame GPU flush.</p>
 */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class RenderedDepthAimSampler {
    private static final int SLOT_COUNT = 3;
    private static final double MAX_DISTANCE = 65_536.0;
    private static final AtomicLong REQUESTED_GENERATION = new AtomicLong();
    private static final AtomicReference<Sample> READY = new AtomicReference<>();
    private static final AtomicLong MIN_VALID_GENERATION = new AtomicLong();
    private static final AtomicLong DIAGNOSTIC_COUNT = new AtomicLong();
    private static final Slot[] SLOTS = new Slot[SLOT_COUNT];

    private static long issuedGeneration;
    private static int nextSlot;
    private static boolean initialized;
    private static boolean failureLogged;

    /** Requests one centre-depth sample after the next completed level render. */
    public static long request() {
        return REQUESTED_GENERATION.incrementAndGet();
    }

    /** Invalidates in-flight samples without making OpenGL calls off-thread. */
    public static void reset() {
        long generation = REQUESTED_GENERATION.incrementAndGet();
        MIN_VALID_GENERATION.set(generation);
        READY.set(null);
    }

    /**
     * Returns the newest completed sample, even when a newer request is still
     * in flight. Continuous mouse input used to invalidate every older PBO
     * result, so no finite terrain range became available until the mouse
     * stopped moving.
     */
    public static Sample poll() {
        Sample result = READY.getAndSet(null);
        return result == null || result.generation() < MIN_VALID_GENERATION.get()
                ? null : result;
    }

    public static boolean isLatest(Sample sample) {
        return sample != null
                && sample.generation() == REQUESTED_GENERATION.get();
    }

    @SubscribeEvent
    public static void afterLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientAimState.isPodView() || minecraft.level == null) return;

        try {
            ensureInitialized();
            resolveCompletedSamples();

            long requested = REQUESTED_GENERATION.get();
            if (requested <= issuedGeneration) return;
            Slot slot = findFreeSlot();
            if (slot == null) return;

            RenderTarget target = minecraft.getMainRenderTarget();
            int width = Math.max(1, target.viewWidth);
            int height = Math.max(1, target.viewHeight);
            int pixelX = Math.min(target.width - 1, width / 2);
            int pixelY = Math.min(target.height - 1, height / 2);
            float ndcX = ((pixelX + 0.5F) / width) * 2.0F - 1.0F;
            float ndcY = ((pixelY + 0.5F) / height) * 2.0F - 1.0F;

            slot.generation = requested;
            slot.inverseViewProjection.set(event.getProjectionMatrix())
                    .mul(event.getModelViewMatrix())
                    .invert();
            slot.cameraPosition = event.getCamera().getPosition();
            slot.ndcX = ndcX;
            slot.ndcY = ndcY;
            slot.projectileDepthIntervals = captureProjectileDepthIntervals(
                    minecraft,
                    slot.cameraPosition,
                    event.getCamera().getLookVector()
            );

            int previousReadFramebuffer = GL11C.glGetInteger(
                    GL30C.GL_READ_FRAMEBUFFER_BINDING
            );
            int previousPackBuffer = GL11C.glGetInteger(
                    GL21C.GL_PIXEL_PACK_BUFFER_BINDING
            );
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, slot.pixelBuffer);
            GL11C.nglReadPixels(
                    pixelX,
                    pixelY,
                    1,
                    1,
                    GL11C.GL_DEPTH_COMPONENT,
                    GL11C.GL_FLOAT,
                    0L
            );
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            GL30C.glBindFramebuffer(
                    GL30C.GL_READ_FRAMEBUFFER,
                    previousReadFramebuffer
            );

            slot.fence = GL32C.glFenceSync(
                    GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE,
                    0
            );
            issuedGeneration = requested;
        } catch (RuntimeException | LinkageError failure) {
            if (!failureLogged) {
                failureLogged = true;
                StabilizedTurret.LOGGER.warn(
                        "Rendered-depth optical aim sampling is unavailable",
                        failure
                );
            }
        }
    }

    private static void ensureInitialized() {
        if (initialized) return;
        for (int i = 0; i < SLOT_COUNT; i++) {
            int buffer = GL45C.glCreateBuffers();
            GL45C.glNamedBufferData(buffer, Float.BYTES, GL15C.GL_STREAM_READ);
            SLOTS[i] = new Slot(buffer);
        }
        initialized = true;
        StabilizedTurret.LOGGER.info(
                "Enabled asynchronous rendered-depth optical aim sampling"
        );
    }

    private static Slot findFreeSlot() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            int index = (nextSlot + i) % SLOT_COUNT;
            Slot slot = SLOTS[index];
            if (slot.fence == 0L) {
                nextSlot = (index + 1) % SLOT_COUNT;
                return slot;
            }
        }
        return null;
    }

    private static void resolveCompletedSamples() {
        for (Slot slot : SLOTS) {
            if (slot == null || slot.fence == 0L) continue;
            int status = GL32C.glClientWaitSync(slot.fence, 0, 0L);
            if (status == GL32C.GL_TIMEOUT_EXPIRED) continue;

            GL32C.glDeleteSync(slot.fence);
            slot.fence = 0L;
            if (status == GL32C.GL_WAIT_FAILED) continue;

            ByteBuffer mapped = GL45C.glMapNamedBufferRange(
                    slot.pixelBuffer,
                    0L,
                    Float.BYTES,
                    GL30C.GL_MAP_READ_BIT
            );
            if (mapped == null) continue;
            float depth = mapped.getFloat(0);
            GL45C.glUnmapNamedBuffer(slot.pixelBuffer);

            if (slot.generation < MIN_VALID_GENERATION.get()) {
                continue;
            }
            if (depth <= 0.0F || depth >= 0.999999F) {
                logDiagnostic("miss", -1.0);
                continue;
            }
            Vec3 point = unproject(slot, depth);
            if (point != null) {
                double distance = Math.sqrt(
                        point.distanceToSqr(slot.cameraPosition));
                // PBO readback completes a few frames after rendering. A fast
                // autocannon round may have moved dozens of blocks by then,
                // so querying its *current* AABB cannot identify the sampled
                // surface. Intervals captured with the exact rendered frame
                // reliably reject projectile depth without rejecting terrain
                // that appears after the round has passed.
                if (slot.isProjectileDepth(distance)) {
                    logDiagnostic("projectile ignored", distance);
                    continue;
                }
                Sample sample = new Sample(
                        slot.generation,
                        point,
                        slot.cameraPosition,
                        distance
                );
                READY.accumulateAndGet(sample, (previous, next) ->
                        previous == null || next.generation() > previous.generation()
                                ? next : previous);
                logDiagnostic(
                        "hit",
                        distance
                );
            } else {
                logDiagnostic("invalid unprojection", -1.0);
            }
        }
    }

    private static void logDiagnostic(String result, double distance) {
        if (DIAGNOSTIC_COUNT.getAndIncrement() >= 8L) return;
        if (distance >= 0.0) {
            StabilizedTurret.LOGGER.info(
                    "Rendered-depth optical aim {} at {} blocks",
                    result,
                    String.format(java.util.Locale.ROOT, "%.1f", distance)
            );
        } else {
            StabilizedTurret.LOGGER.info(
                    "Rendered-depth optical aim {}",
                    result
            );
        }
    }

    private static Vec3 unproject(Slot slot, float depth) {
        Vector4f position = new Vector4f(
                slot.ndcX,
                slot.ndcY,
                depth * 2.0F - 1.0F,
                1.0F
        );
        slot.inverseViewProjection.transform(position);
        if (!Float.isFinite(position.w) || Math.abs(position.w) < 1.0e-7F) {
            return null;
        }
        double inverseW = 1.0 / position.w;
        Vec3 point = slot.cameraPosition.add(
                position.x * inverseW,
                position.y * inverseW,
                position.z * inverseW
        );
        if (!Double.isFinite(point.x)
                || !Double.isFinite(point.y)
                || !Double.isFinite(point.z)) {
            return null;
        }
        double distanceSqr = point.distanceToSqr(slot.cameraPosition);
        if (distanceSqr < 0.01 || distanceSqr > MAX_DISTANCE * MAX_DISTANCE) {
            return null;
        }
        return point;
    }

    private static List<DepthInterval> captureProjectileDepthIntervals(
            Minecraft minecraft,
            Vec3 cameraPosition,
            org.joml.Vector3f cameraLook
    ) {
        Vec3 direction = new Vec3(
                cameraLook.x, cameraLook.y, cameraLook.z);
        if (direction.lengthSqr() < 1.0e-10) return List.of();
        direction = direction.normalize();
        Vec3 rayEnd = cameraPosition.add(direction.scale(MAX_DISTANCE));
        List<DepthInterval> intervals = new ArrayList<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!isProjectileLike(entity) || entity.isRemoved()) continue;
            // Expand by one full tick of movement because entity logical AABBs
            // and render interpolation do not necessarily use the same pose.
            double motionMargin = Math.min(
                    32.0, entity.getDeltaMovement().length());
            AABB renderedEnvelope = entity.getBoundingBox()
                    .inflate(0.75 + motionMargin);
            Optional<Vec3> intersection = renderedEnvelope.clip(
                    cameraPosition, rayEnd);
            if (intersection.isEmpty()) continue;
            double entry = cameraPosition.distanceTo(intersection.get());
            double visualRadius = Math.max(
                    1.0,
                    Math.sqrt(
                            renderedEnvelope.getXsize()
                                    * renderedEnvelope.getXsize()
                                    + renderedEnvelope.getYsize()
                                    * renderedEnvelope.getYsize()
                                    + renderedEnvelope.getZsize()
                                    * renderedEnvelope.getZsize()
                    ) * 0.5
            );
            intervals.add(new DepthInterval(
                    Math.max(0.0, entry - 1.0),
                    entry + visualRadius * 2.0 + 1.0
            ));
        }
        return intervals.isEmpty() ? List.of() : List.copyOf(intervals);
    }

    private static boolean isProjectileLike(Entity entity) {
        if (entity instanceof Projectile) return true;
        String name = entity.getClass().getName().toLowerCase(Locale.ROOT);
        return name.contains("projectile")
                || name.contains("missile")
                || name.contains("munition")
                || name.contains("shell")
                || name.contains("rocket")
                || name.contains("bullet");
    }

    private static final class Slot {
        private final int pixelBuffer;
        private final Matrix4f inverseViewProjection = new Matrix4f();
        private long fence;
        private long generation;
        private Vec3 cameraPosition = Vec3.ZERO;
        private float ndcX;
        private float ndcY;
        private List<DepthInterval> projectileDepthIntervals = List.of();

        private Slot(int pixelBuffer) {
            this.pixelBuffer = pixelBuffer;
        }

        private boolean isProjectileDepth(double distance) {
            for (DepthInterval interval : projectileDepthIntervals) {
                if (distance >= interval.minimum()
                        && distance <= interval.maximum()) {
                    return true;
                }
            }
            return false;
        }
    }

    private record DepthInterval(double minimum, double maximum) {
    }

    public record Sample(
            long generation,
            Vec3 worldPoint,
            Vec3 cameraPosition,
            double distance
    ) {
    }

    private RenderedDepthAimSampler() {
    }
}
