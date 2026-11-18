package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoRocketFlight;
import com.hooya.stabilizedturret.content.controller.MianbaoRocketPodCompat;
import com.hooya.stabilizedturret.network.RocketSalvoPayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Native models and bounded smoke history, independent of client chunks/entities. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class VirtualRocketVisuals {
    private static final int TRAIL_POINTS = 64;
    private static final Map<UUID, Visual> ACTIVE = new HashMap<>();
    private static final ArrayDeque<FadingTrail> FADING = new ArrayDeque<>();
    private static ClientLevel trackedLevel;
    private static int nextId = -2_000_000;

    public static void accept(RocketSalvoPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(payload.dimension())) return;
        ensureLevel(level);
        for (UUID id : payload.removed()) remove(id, level.getGameTime());
        for (RocketSalvoPayload.State state : payload.rockets()) {
            if (!finite(state.position()) || !finite(state.velocity()) || state.age() < 0
                    || state.age() >= MianbaoRocketFlight.LIFETIME_TICKS
                    || !Double.isFinite(state.firstDrag()) || !Double.isFinite(state.drop())) continue;
            Visual visual = ACTIVE.get(state.id());
            if (visual == null) {
                if (ACTIVE.size() >= RocketSalvoPayload.MAX_ROCKETS) continue;
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(state.entityType()).orElse(null);
                Entity proxy = type == null ? null : type.create(level);
                if (proxy == null) continue;
                proxy.setId(nextId--);
                proxy.setUUID(state.id());
                proxy.getPersistentData().putBoolean("CreateFireControlRemoteVisual", true);
                proxy.getPersistentData().putBoolean("CreateFireControlVirtualRocketVisual", true);
                proxy.noPhysics = true;
                proxy.setNoGravity(true);
                proxy.setSilent(true);
                visual = new Visual(proxy);
                ACTIVE.put(state.id(), visual);
            }
            visual.state = state;
            visual.receivedAt = level.getGameTime();
            visual.delay = Mth.clamp(level.getGameTime() - payload.serverTick(), 0L, 5L);
            visual.update(level.getGameTime());
        }
    }

    private static void ensureLevel(ClientLevel level) {
        if (trackedLevel == level) return;
        VirtualRocketParticles.clear();
        ACTIVE.clear(); FADING.clear(); trackedLevel = level;
    }

    private static void remove(UUID id, long now) {
        Visual visual = ACTIVE.remove(id);
        if (visual == null) return;
        visual.proxy.discard();
        if (FADING.size() >= RocketSalvoPayload.MAX_ROCKETS) FADING.removeFirst();
        FADING.addLast(new FadingTrail(List.copyOf(visual.trail), now));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            ACTIVE.clear(); FADING.clear(); trackedLevel = null;
            VirtualRocketParticles.clear();
            return;
        }
        ensureLevel(level);
        VirtualRocketParticles.tick(level);
        long now = level.getGameTime();
        List<UUID> expired = new ArrayList<>();
        for (var entry : ACTIVE.entrySet()) {
            Visual visual = entry.getValue();
            if (visual.state.age() + visual.elapsed(now) >= MianbaoRocketFlight.LIFETIME_TICKS) {
                expired.add(entry.getKey());
            } else {
                visual.update(now);
                visual.trail.addLast(visual.proxy.position());
                while (visual.trail.size() > TRAIL_POINTS) visual.trail.removeFirst();
            }
        }
        expired.forEach(id -> remove(id, now));
        FADING.removeIf(trail -> now - trail.stoppedAt >= 40);
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || ACTIVE.isEmpty() && FADING.isEmpty()) return;
        RenderSystem.getModelViewStack().pushMatrix().identity();
        RenderSystem.applyModelViewMatrix();
        try {
            PoseStack pose = new PoseStack();
            pose.mulPose(event.getModelViewMatrix());
            var buffers = minecraft.renderBuffers().bufferSource();
            Vec3 camera = event.getCamera().getPosition();
            float partialTick = Mth.clamp(event.getPartialTick().getGameTimeDeltaTicks(), 0, 1);
            List<Visual> visible = new ArrayList<>(ACTIVE.values());
            // Group native model materials to avoid one draw flush per warhead
            // when ordinary, HE and incendiary rockets share the same salvo.
            visible.sort(Comparator.comparing(visual -> visual.state.entityType()));
            // Rotate draw order so the shared particle budget is fair to
            // every rocket in a large salvo. Smoke history remains visible for all.
            int offset = visible.isEmpty() ? 0 : Math.floorMod(minecraft.level.getGameTime(), visible.size());
            for (int i = 0; i < visible.size(); i++) {
                Visual visual = visible.get((i + offset) % visible.size());
                Vec3 world = visual.position(minecraft.level.getGameTime(), partialTick);
                if (world.distanceToSqr(camera) > 20000.0 * 20000.0) continue;
                Entity proxy = visual.proxy;
                long now = minecraft.level.getGameTime();
                if (visual.lastParticleTick != now && !ClientWeaponViewState.observes(proxy.getUUID())) {
                    visual.lastParticleTick = now;
                    VirtualRocketParticles.trail(minecraft.level, world,
                            proxy.getDeltaMovement(), camera);
                }
                Vec3 relative = world.subtract(camera);
                minecraft.getEntityRenderDispatcher().render(proxy, relative.x, relative.y, relative.z,
                        proxy.getYRot(), partialTick, pose, buffers, LightTexture.FULL_BRIGHT);
            }
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            for (Visual visual : visible) if (!ClientWeaponViewState.observes(visual.proxy.getUUID()))
                drawTrail(lines, pose, camera, visual.trail, 1.0);
            for (FadingTrail trail : FADING) drawTrail(lines, pose, camera, trail.points,
                    1.0 - (minecraft.level.getGameTime() - trail.stoppedAt) / 40.0);
            buffers.endBatch(RenderType.lines());
        } finally {
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void drawTrail(VertexConsumer lines, PoseStack pose, Vec3 camera,
                                  Iterable<Vec3> points, double fade) {
        Vec3 previous = null;
        int index = 0;
        for (Vec3 point : points) {
            if (previous != null && point.distanceToSqr(camera) < 20000.0 * 20000.0) {
                Vec3 normal = point.subtract(previous).normalize();
                int alpha = Mth.clamp((int) (fade * (12 + index * 1.6)), 0, 120);
                Vec3 a = previous.subtract(camera), b = point.subtract(camera);
                lines.addVertex(pose.last().pose(), (float) a.x, (float) a.y, (float) a.z)
                        .setColor(210, 210, 205, alpha).setNormal(pose.last(), (float) normal.x, (float) normal.y, (float) normal.z);
                lines.addVertex(pose.last().pose(), (float) b.x, (float) b.y, (float) b.z)
                        .setColor(210, 210, 205, alpha).setNormal(pose.last(), (float) normal.x, (float) normal.y, (float) normal.z);
            }
            previous = point;
            index++;
        }
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private record FadingTrail(List<Vec3> points, long stoppedAt) {}

    private static final class Visual {
        private long lastParticleTick = Long.MIN_VALUE;
        final Entity proxy;
        final ArrayDeque<Vec3> trail = new ArrayDeque<>();
        RocketSalvoPayload.State state;
        long receivedAt, delay;

        Visual(Entity proxy) { this.proxy = proxy; }
        long elapsed(long now) { return Math.max(0, now - receivedAt + delay); }
        Vec3 position(long now, float partialTick) {
            if (MianbaoRocketPodCompat.isVisualGuidedRocket(state.entityType())) {
                // Guidance is authoritative on the server; interpolate frequent corrections.
                return state.position().add(state.velocity().scale(elapsed(now) + partialTick));
            }
            return MianbaoRocketFlight.positionAt(state.position(), state.velocity(), state.age(),
                    elapsed(now) + partialTick, state.firstDrag(), state.drop(), firstGravity());
        }
        double firstGravity() {
            return state.entityType().getPath().equals("rocket_he_tanshe") ? 0.0 : MianbaoRocketFlight.ARROW_GRAVITY;
        }
        void update(long now) {
            Vec3 world = position(now, 0);
            Vec3 velocity = MianbaoRocketPodCompat.isVisualGuidedRocket(state.entityType()) ? state.velocity()
                    : MianbaoRocketFlight.velocityAt(state.velocity(), state.age(),
                    (int) elapsed(now), state.firstDrag(), state.drop(), firstGravity());
            float yaw = (float) Math.toDegrees(Math.atan2(velocity.x, velocity.z));
            float pitch = (float) Math.toDegrees(Math.atan2(velocity.y, velocity.horizontalDistance()));
            proxy.setOldPosAndRot();
            proxy.setPos(world.x, world.y, world.z);
            proxy.setDeltaMovement(velocity);
            proxy.setYRot(yaw); proxy.setXRot(pitch);
            proxy.yRotO = yaw; proxy.xRotO = pitch;
        }
    }

    private VirtualRocketVisuals() {}
}
