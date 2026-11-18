package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.WeaponViewPayload;
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

/** Free orbit of a render-only proxy. Minecraft's camera entity always stays with the player. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ClientWeaponViewState {
    private static Entity visual;
    private static ClientLevel level;
    private static final WeaponViewMotion MOTION = new WeaponViewMotion();
    private static Vec3 framePosition = Vec3.ZERO;
    private static Vec3 frameVelocity = Vec3.ZERO;
    private static boolean frameReady;
    private static ClientLevel clockLevel;
    private static long clientTick;
    private static long receivedTick;
    private static float yaw;
    private static float pitch;

    public static void accept(WeaponViewPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().equals(payload.dimension())) return;
        ensureClockLevel();
        if (payload.id() == null) { clear(); return; }
        if (!finite(payload.position()) || !finite(payload.velocity())) return;
        if (visual == null || level != mc.level || !visual.getUUID().equals(payload.id())) {
            clear();
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(payload.entityType()).orElse(null);
            visual = type == null ? null : type.create(mc.level);
            if (visual == null) return;
            level = mc.level;
            visual.setId(-4_000_000);
            visual.setUUID(payload.id());
            WeaponViewExhaust.beginObservation(payload.id());
            MathematicalMissileVisuals.beginObservation(payload.id());
            visual.noPhysics = true;
            visual.setNoGravity(true);
            visual.setSilent(true);
            Entity source = MathematicalMissileVisuals.find(payload.id());
            if (source == null) source = MissileLongRangeEffects.getRemoteVisual(payload.id());
            if (source != null && com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight.managed(source))
                com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight.acceptAge(visual,
                        com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight.poweredAge(source));
            visual.getPersistentData().putBoolean("CreateFireControlRemoteVisual", true);
            yaw = payload.velocity().lengthSqr() > 1.0e-8
                    ? (float) Math.toDegrees(Math.atan2(-payload.velocity().x, payload.velocity().z))
                    : mc.gameRenderer.getMainCamera().getYRot();
            pitch = 15.0F;
        }
        if (MOTION.accept(payload.serverTick(), payload.position(), payload.velocity(), clientTime(
                mc.getTimer().getGameTimeDeltaPartialTick(true)))) receivedTick = clientTick;
    }

    public static boolean isActive() { return visual != null && level == Minecraft.getInstance().level; }
    public static void acceptAirAge(java.util.UUID id, int age) {
        if (visual != null && visual.getUUID().equals(id))
            com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight.acceptAge(visual, age);
    }
    public static void acceptLandAge(java.util.UUID id, int age) {
        if (visual != null && visual.getUUID().equals(id))
            com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight.acceptAge(visual, age);
    }
    public static float yaw() { return yaw; }
    public static float pitch() { return pitch; }
    static Vec3 weaponVelocity() { return frameReady ? frameVelocity : MOTION.velocity(); }

    public static Vec3 weaponPosition(float partialTick) {
        if (!frameReady) prepareFrame(partialTick);
        return framePosition;
    }

    private static void ensureClockLevel() {
        ClientLevel current = Minecraft.getInstance().level;
        if (clockLevel != current) { clockLevel = current; clientTick = 0; }
    }

    private static double clientTime(float partial) { return clientTick + Math.clamp(partial, 0, 1); }
    static long animationTick() { ensureClockLevel(); return clientTick; }

    @SubscribeEvent public static void clock(ClientTickEvent.Pre event) {
        ensureClockLevel();
        if (clockLevel != null && !Minecraft.getInstance().isPaused()) clientTick++;
    }

    private static void prepareFrame(float partial) {
        framePosition = MOTION.sample(clientTime(partial));
        frameVelocity = MOTION.velocity();
        frameReady = true;
    }

    public static Vec3 cameraPosition(float partialTick) {
        double distance = 1.5 * Math.max(7.0, visual.getBbWidth() * 3.0 + visual.getBbHeight());
        return weaponPosition(partialTick).subtract(Vec3.directionFromRotation(pitch, yaw).scale(distance));
    }

    public static boolean turn(double yawInput, double pitchInput) {
        if (!isActive()) return false;
        yaw = Mth.wrapDegrees(yaw + (float) yawInput * 0.15F);
        pitch = Mth.clamp(pitch + (float) pitchInput * 0.15F, -89.9F, 89.9F);
        return true;
    }

    /** Avoid drawing both the ordinary long-range proxy and this observer's authoritative proxy. */
    public static boolean suppressDuplicate(Entity entity) {
        return isActive() && entity != visual && visual.getUUID().equals(entity.getUUID());
    }

    public static boolean observes(java.util.UUID id) { return isActive() && visual.getUUID().equals(id); }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        WeaponViewExhaust.tick();
        if (visual != null && (mc.level != level || mc.player == null || !mc.player.isAlive()
                || clientTick - receivedTick > 20)) clear();
        WeaponViewSound.tick(visual);
    }

    @SubscribeEvent public static void prepareExhaust(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        if (!isActive()) return;
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        prepareFrame(partial);
        prepareModel(weaponPosition(partial));
        WeaponViewExhaust.renderFrame(visual, weaponPosition(partial), frameVelocity);
    }

    private static void prepareModel(Vec3 world) {
        Vec3 velocity = weaponVelocity();
        visual.setPos(world); visual.setDeltaMovement(velocity);
        visual.setYRot((float) Math.toDegrees(Math.atan2(velocity.x, velocity.z)));
        visual.setXRot((float) Math.toDegrees(Math.atan2(velocity.y, velocity.horizontalDistance())));
        visual.yRotO = visual.getYRot(); visual.xRotO = visual.getXRot();
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (!isActive()) {
            WeaponViewExhaust.renderParticles(event);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 world = weaponPosition(partial);
        prepareModel(world);
        Vec3 relative = world.subtract(event.getCamera().getPosition());
        RenderSystem.getModelViewStack().pushMatrix().identity(); RenderSystem.applyModelViewMatrix();
        try {
            PoseStack pose = new PoseStack(); pose.mulPose(event.getModelViewMatrix());
            var buffers = mc.renderBuffers().bufferSource();
            mc.getEntityRenderDispatcher().render(visual, relative.x, relative.y, relative.z,
                    visual.getYRot(), partial, pose, buffers, LightTexture.FULL_BRIGHT);
            buffers.endBatch();
            WeaponViewExhaust.renderParticles(event);
        } finally { RenderSystem.getModelViewStack().popMatrix(); RenderSystem.applyModelViewMatrix(); }
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
    public static void clear() {
        WeaponViewSound.clear();
        WeaponViewExhaust.endObservation();
        if (visual != null) visual.discard();
        visual = null; level = null;
        frameReady = false; MOTION.clear();
    }
    public static float requiredTrailFarDistance() { return WeaponViewExhaust.requiredFarDistance(); }
    private ClientWeaponViewState() {}
}
