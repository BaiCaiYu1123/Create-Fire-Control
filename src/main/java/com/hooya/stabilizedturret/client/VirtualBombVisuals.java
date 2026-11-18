package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoBombBallistics;
import com.hooya.stabilizedturret.content.controller.VirtualBombFlight;
import com.hooya.stabilizedturret.network.BombFlightPayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import java.util.*;

/** Render-only native models. No native tick, particle trail, smoke history or world entity registration. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class VirtualBombVisuals {
    private static final Map<UUID, Visual> ACTIVE = new HashMap<>();
    private static ClientLevel trackedLevel;
    private static int nextId = -3_000_000;

    public static void accept(BombFlightPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(payload.dimension())) return;
        ensureLevel(level);
        for (UUID id : payload.removed()) { Visual removed = ACTIVE.remove(id); if (removed != null) removed.proxy.discard(); }
        for (var state : payload.bombs()) {
            if (state.weapon() == null || !MianbaoBombBallistics.finite(state.position()) || !MianbaoBombBallistics.finite(state.velocity())
                    || !MianbaoBombBallistics.finite(state.target()) || state.lasers().stream().anyMatch(v -> !MianbaoBombBallistics.finite(v))
                    || state.age() < 0 || state.age() >= MianbaoBombBallistics.MAX_TICKS) continue;
            Visual visual = ACTIVE.get(state.id());
            if (visual == null) {
                if (ACTIVE.size() >= BombFlightPayload.MAX_BOMBS) continue;
                var type = BuiltInRegistries.ENTITY_TYPE.getOptional(state.entityType()).orElse(null);
                Entity proxy = type == null ? null : type.create(level);
                if (proxy == null) continue;
                proxy.setId(nextId--); proxy.setUUID(state.id()); proxy.noPhysics = true; proxy.setNoGravity(true); proxy.setSilent(true);
                proxy.getPersistentData().putBoolean("CreateFireControlRemoteVisual", true);
                visual = new Visual(proxy); ACTIVE.put(state.id(), visual);
            }
            if (payload.serverTick() <= visual.snapshotTick) continue;
            visual.snapshotTick = payload.serverTick();
            visual.state = state;
            visual.flight = new VirtualBombFlight(state.weapon(), state.position(), state.velocity(), state.age());
            visual.updated = level.getGameTime();
            int delay = (int) Mth.clamp(level.getGameTime() - payload.serverTick(), 0, 5);
            for (int i = 0; i < delay; i++) visual.advance();
            if (!visual.render.initialized()) {
                visual.render.initialize(visual.flight.position(), visual.flight.velocity());
                visual.displayedTick = level.getGameTime();
            }
        }
    }
    private static void ensureLevel(ClientLevel level) {
        if (trackedLevel != level) { ACTIVE.values().forEach(v -> v.proxy.discard()); ACTIVE.clear(); trackedLevel = level; }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        ensureLevel(level); if (level == null) return;
        for (Iterator<Visual> iterator = ACTIVE.values().iterator(); iterator.hasNext();) {
            Visual v = iterator.next();
            while (v.updated < level.getGameTime() && v.flight.age() < MianbaoBombBallistics.MAX_TICKS) { v.advance(); v.updated++; }
            if (v.displayedTick < level.getGameTime()) {
                v.render.advance(v.flight.position(), v.flight.velocity());
                v.displayedTick = level.getGameTime();
            }
            if (v.flight.age() >= MianbaoBombBallistics.MAX_TICKS) { v.proxy.discard(); iterator.remove(); }
        }
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ACTIVE.isEmpty()) return;
        RenderSystem.getModelViewStack().pushMatrix().identity(); RenderSystem.applyModelViewMatrix();
        try {
            PoseStack pose = new PoseStack(); pose.mulPose(event.getModelViewMatrix());
            var buffers = mc.renderBuffers().bufferSource(); Vec3 camera = event.getCamera().getPosition();
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
            List<Visual> visible = new ArrayList<>(ACTIVE.values());
            visible.sort(Comparator.comparing(v -> v.state.entityType().toString()));
            for (Visual v : visible) {
                Vec3 velocity = v.render.velocity(partial), world = v.render.position(partial);
                if (world.distanceToSqr(camera) > 20000.0 * 20000.0) continue;
                float yaw = (float) Math.toDegrees(Math.atan2(velocity.x, velocity.z));
                float pitch = (float) Math.toDegrees(Math.atan2(velocity.y, velocity.horizontalDistance()));
                v.proxy.setPos(world); v.proxy.setDeltaMovement(velocity); v.proxy.tickCount = v.flight.age();
                v.proxy.setYRot(yaw); v.proxy.yRotO = yaw; v.proxy.setXRot(pitch); v.proxy.xRotO = pitch;
                Vec3 relative = world.subtract(camera);
                mc.getEntityRenderDispatcher().render(v.proxy, relative.x, relative.y, relative.z, yaw, partial, pose, buffers, LightTexture.FULL_BRIGHT);
            }
            buffers.endBatch();
        } finally { RenderSystem.getModelViewStack().popMatrix(); RenderSystem.applyModelViewMatrix(); }
    }
    private static final class Visual {
        final Entity proxy;
        BombFlightPayload.State state;
        VirtualBombFlight flight;
        long updated;
        long snapshotTick = Long.MIN_VALUE, displayedTick = Long.MIN_VALUE;
        final BombRenderInterpolation render = new BombRenderInterpolation();
        Visual(Entity proxy) { this.proxy = proxy; }
        void advance() { flight.advance(state.coordinates(), state.target(), state.lasers(), state.water()); }
    }
    private VirtualBombVisuals() {}
}
