package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.GuidedMissileFlight;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileFlightProfile;
import com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight;
import com.hooya.stabilizedturret.mixin.client.NativeMissileParticleFactory;
import com.hooya.stabilizedturret.network.MathematicalMissilePayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import java.util.*;

/** Original entity renderers and original particle providers, with a one-tick prepared timeline. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class MathematicalMissileVisuals {
    public static final String VISUAL = "CreateFireControlMathematicalMissileVisual";
    private static final Map<UUID, Visual> ACTIVE = new HashMap<>();
    private static ClientLevel trackedLevel;
    private static long animationTick;
    private static int nextId = -3_000_000;
    private static final class Visual {
        final Entity model;
        MathematicalMissilePayload.State state;
        long received, serverTick = Long.MIN_VALUE;
        final List<ScheduledParticle> prepared = new ArrayList<>();
        final List<ScheduledSound> sounds = new ArrayList<>();
        Visual(Entity model) { this.model = model; }
        void cancelFuture() { prepared.forEach(ScheduledParticle::cancelFuture); prepared.clear(); sounds.clear(); }
    }

    public static Entity find(UUID id) { Visual visual = ACTIVE.get(id); return visual == null ? null : visual.model; }

    static void beginObservation(UUID id) {
        Visual visual = ACTIVE.get(id);
        if (visual != null) visual.cancelFuture();
    }

    /** Model, observer camera and native trail must use the same prepared timeline. */
    public static Vec3 renderPosition(UUID id, float partial) {
        Visual visual = ACTIVE.get(id);
        return visual == null || visual.state == null || trackedLevel != Minecraft.getInstance().level
                ? null : renderPosition(visual, partial);
    }

    private static Vec3 renderPosition(Visual visual, float partial) {
        double elapsed = Mth.clamp(animationTick - visual.received + partial, 0, 2);
        var s = visual.state;
        return elapsed <= 1 ? s.previous().lerp(s.position(), elapsed)
                : s.position().add(s.velocity().scale(elapsed - 1));
    }

    static Boolean emitting(UUID id) {
        Visual visual = ACTIVE.get(id);
        if (visual == null || visual.state == null) return null;
        var s = visual.state;
        var profile = MianbaoMissileFlightProfile.of(s.type().getPath());
        double timer = switch (profile.timer()) {
            case "time" -> s.cruiseEngineAge();
            case "fadongji" -> s.groundEngineAge();
            default -> s.airEngineAge();
        };
        return profile.smoke(LandAttackMissileFlight.isTypePath(s.type().getPath()) ? s.age() + 1 : timer + 1);
    }

    /** Keep an ARM and its original particles inside the projection/fog range while it flies away. */
    public static float antiRadiationFarDistance() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || trackedLevel != mc.level || ACTIVE.isEmpty()) return 0;
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition(), forward = new Vec3(camera.getLookVector());
        double far = 0;
        for (Visual visual : ACTIVE.values()) {
            var state = visual.state;
            if (state == null || animationTick - visual.received > 40 || visual.model.isRemoved()
                    || !state.type().toString().equals("mianbaos_modernwarfare:antiradiationmissiletanshe")) continue;
            Vec3 relative = state.position().subtract(origin);
            double distance = relative.length();
            if (relative.dot(forward) > 0 && distance <= 20000)
                far = Math.max(far, distance + 1024);
        }
        return (float) far;
    }

    public static void accept(MathematicalMissilePayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(payload.dimension())) return;
        ensureLevel(level);
        for (UUID id : payload.removed()) {
            Visual visual = ACTIVE.remove(id);
            if (visual != null) { visual.cancelFuture(); visual.model.discard(); }
        }
        for (var state : payload.states()) {
            if (!finite(state.position()) || !finite(state.previous()) || !finite(state.velocity())) continue;
            Visual visual = ACTIVE.get(state.id());
            if (visual == null) {
                var type = BuiltInRegistries.ENTITY_TYPE.getOptional(state.type()).orElse(null);
                Entity model = type == null ? null : type.create(level);
                if (model == null) continue;
                model.setId(nextId--); model.setUUID(state.id()); model.noPhysics = true;
                model.setNoGravity(true); model.setSilent(true);
                model.getPersistentData().putBoolean(VISUAL, true);
                model.getPersistentData().putBoolean("CreateFireControlRemoteVisual", true);
                visual = new Visual(model); ACTIVE.put(state.id(), visual);
            }
            if (payload.tick() <= visual.serverTick) continue;
            if (visual.state != null && visual.state.position().add(visual.state.velocity())
                    .distanceToSqr(state.position()) > 1.0e-10) visual.cancelFuture();
            visual.prepared.removeIf(particle -> particle.started || !particle.isAlive());
            visual.state = state; visual.received = animationTick; visual.serverTick = payload.tick();
            visual.model.setYRot(state.yaw()); visual.model.yRotO = state.previousYaw();
            visual.model.setXRot(state.pitch()); visual.model.xRotO = state.previousPitch();
            visual.model.setDeltaMovement(state.velocity()); visual.model.tickCount = state.age();
            visual.model.setPos(state.position().x, state.position().y, state.position().z);
            if (com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.isAirToAir(
                    com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.typeOf(visual.model))) {
                com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight.acceptAge(visual.model, (int) state.airEngineAge());
                ClientWeaponViewState.acceptAirAge(state.id(), (int) state.airEngineAge());
                AirToAirMissileSounds.update(state.id(), visual.model);
            }
            prepare(level, visual);
        }
    }

    private static void ensureLevel(ClientLevel level) {
        if (trackedLevel == level) return;
        ACTIVE.values().forEach(visual -> { visual.cancelFuture(); visual.model.discard(); });
        ACTIVE.clear(); trackedLevel = level; animationTick = 0;
    }

    @SubscribeEvent public static void clock(ClientTickEvent.Pre event) {
        ClientLevel level = Minecraft.getInstance().level;
        ensureLevel(level);
        if (level != null && !Minecraft.getInstance().isPaused()) animationTick++;
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        ensureLevel(level);
        if (level == null) return;
        ACTIVE.values().removeIf(visual -> {
            if (animationTick - visual.received <= 40) return false;
            visual.cancelFuture(); visual.model.discard(); return true;
        });
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || ACTIVE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        RenderSystem.getModelViewStack().pushMatrix().identity(); RenderSystem.applyModelViewMatrix();
        try {
            PoseStack pose = new PoseStack(); pose.mulPose(event.getModelViewMatrix());
            var buffers = mc.renderBuffers().bufferSource();
            Vec3 camera = event.getCamera().getPosition();
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
            for (Visual visual : ACTIVE.values()) {
                double soundTime = animationTick + partial;
                visual.sounds.removeIf(sound -> sound.play(mc.level, soundTime));
                var s = visual.state;
                Vec3 position = renderPosition(visual, partial);
                if (position.distanceToSqr(camera) > 20000.0 * 20000.0) continue;
                Entity model = visual.model;
                Vec3 relative = position.subtract(camera);
                // The original renderer chooses model, texture, scale and animated UVs.
                int light = mc.getEntityRenderDispatcher().getPackedLightCoords(model, partial);
                mc.getEntityRenderDispatcher().render(model, relative.x, relative.y, relative.z,
                        model.getYRot(), partial, pose, buffers, light);
            }
            buffers.endBatch();
        } finally {
            RenderSystem.getModelViewStack().popMatrix(); RenderSystem.applyModelViewMatrix();
        }
    }

    private static void prepare(ClientLevel level, Visual visual) {
        var s = visual.state;
        String path = s.type().getPath();
        var profile = MianbaoMissileFlightProfile.of(path);
        boolean large = profile.large();
        double timer = switch (profile.timer()) {
            case "time" -> s.cruiseEngineAge();
            case "fadongji" -> s.groundEngineAge();
            default -> s.airEngineAge();
        };
        double next = LandAttackMissileFlight.isTypePath(path) ? s.age() + 1 : timer + 1;
        boolean observed = ClientWeaponViewState.observes(s.id());
        Vec3 end = s.position().add(s.velocity());
        if (!observed && profile.laserSpark() && next <= 200) {
            schedule(level, visual, "countermeasuretrail_1", end, Vec3.ZERO, visual.received + 2);
        }
        if (!observed && large && next >= 10 && next <= 20) {
            for (int radius = 0; radius < 8; radius++) {
                Vec3 spread = new Vec3(level.random.nextDouble() * 1.6 - .8,
                        level.random.nextDouble() * 1.6 - .8, level.random.nextDouble() * 1.6 - .8);
                schedule(level, visual, "newsmoke_4", end,
                        spread.subtract(s.velocity().scale(.99F)).scale(8 - radius), visual.received + 2);
            }
        }
        if (!profile.smoke(next) || observed) return; // Observer owns both nozzle particles and flight audio.
        float volume = large ? 10 : 1;
        if (!com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight.managed(visual.model))
            visual.sounds.add(new ScheduledSound("missile_engine_far", end, volume,
                    1 + level.random.nextFloat() * .5F, visual.received + 2));
        // Same post-AbstractArrow velocity used by the native smoke procedure.
        Vec3 smokeVelocity = s.velocity().scale(0.99F);
        double speed = smokeVelocity.length();
        int count = GuidedMissileFlight.smokeCount(smokeVelocity);
        for (int i = 0; i < count; i++) {
            Vec3 position = GuidedMissileFlight.smokePosition(end, smokeVelocity, i);
            double fraction = GuidedMissileFlight.smokeTimeFraction(smokeVelocity, i);
            schedule(level, visual, large ? "newsmoke_3" : "newsmoke_2", position,
                    new Vec3((level.random.nextDouble() * 2 - 1) * .75,
                            (level.random.nextDouble() * 2 - 1) * .75,
                            (level.random.nextDouble() * 2 - 1) * .75), visual.received + 1 + fraction);
        }
        schedule(level, visual, path.equals("antiairmissiletanshe")
                ? "newrocketmissiletrailsmall" : "newrocketmissiletrail", end, Vec3.ZERO, visual.received + 2);
    }

    private record ScheduledSound(String name, Vec3 position, float volume, float pitch, double at) {
        boolean play(ClientLevel level, double now) {
            if (now < at) return false;
            var sound = BuiltInRegistries.SOUND_EVENT.getOptional(ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", name));
            sound.ifPresent(event -> level.playLocalSound(position.x, position.y, position.z, event,
                    net.minecraft.sounds.SoundSource.NEUTRAL, volume, pitch, false));
            return true;
        }
    }

    private static void schedule(ClientLevel level, Visual visual, String name, Vec3 position, Vec3 velocity, double at) {
        var type = BuiltInRegistries.PARTICLE_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath(
                "mianbaos_modernwarfare", name)).orElse(null);
        if (!(type instanceof ParticleOptions options)) return;
        var engine = Minecraft.getInstance().particleEngine;
        Particle original = ((NativeMissileParticleFactory) engine).createFireControl$makeParticle(options,
                position.x, position.y, position.z, velocity.x, velocity.y, velocity.z);
        if (original == null) return;
        ScheduledParticle particle = new ScheduledParticle(level, position, original, at);
        visual.prepared.add(particle); engine.add(particle);
    }

    /** Future particles neither tick nor render. Age/physics/UVs belong to the native provider. */
    private static final class ScheduledParticle extends Particle {
        final Particle original;
        final double at;
        boolean started;
        ScheduledParticle(ClientLevel level, Vec3 position, Particle original, double at) {
            super(level, position.x, position.y, position.z);
            this.original = original; this.at = at;
        }
        void cancelFuture() { if (!started) { original.remove(); remove(); } }
        @Override public ParticleRenderType getRenderType() { return original.getRenderType(); }
        @Override public AABB getBoundingBox() { return original == null ? super.getBoundingBox() : original.getBoundingBox(); }
        @Override public void tick() {
            if (animationTick < at) return;
            started = true; original.tick(); if (!original.isAlive()) remove();
        }
        @Override public void render(VertexConsumer vertices, Camera camera, float partial) {
            double now = animationTick + partial;
            if (now < at || !original.isAlive()) return;
            started = true;
            original.render(vertices, camera, partial);
        }
    }
    private static boolean finite(Vec3 value) { return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z); }
    private MathematicalMissileVisuals() {}
}
