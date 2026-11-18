package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight;

import com.hooya.stabilizedturret.content.controller.GuidedMissileFlight;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoRocketPodCompat;
import com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseGuidance;
import com.hooya.stabilizedturret.mixin.client.NativeMissileParticleFactory;
import com.hooya.stabilizedturret.mixin.client.ParticleAccessor;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;

/** Original native particles and emission density; only nozzle placement follows the render clock. */
final class WeaponViewExhaust {
    private static final ArrayDeque<NativeParticle> SMOKE = new ArrayDeque<>();
    private static final ArrayDeque<NativeParticle> ACTIVE = new ArrayDeque<>();
    private static NativeParticle flame;
    private static long emissionTick = Long.MIN_VALUE;
    private static Vec3 nozzleOffset = new Vec3(0, 0, -0.65);
    private static ClientLevel trackedLevel;
    private static java.util.UUID observedId;
    private static long session;

    static void beginObservation(java.util.UUID id) {
        ensureLevel();
        endObservation();
        observedId = id;
    }

    /** A camera handoff detaches the fresh cohort; existing native particles keep their own clocks. */
    static void endObservation() {
        for (NativeParticle particle : ACTIVE) particle.detach();
        observedId = null;
        session++;
        emissionTick = Long.MIN_VALUE;
        flame = null;
    }

    private static void ensureLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == trackedLevel) return;
        clear();
        trackedLevel = level;
    }

    static void renderFrame(Entity model, Vec3 position, Vec3 velocity) {
        ensureLevel();
        boolean rocket = MianbaoRocketPodCompat.isRocket(model);
        if (!MianbaoAirDefenseCompat.isAnyFireControlMissile(model) && !rocket) return;
        var level = Minecraft.getInstance().level;
        if (level == null || velocity.lengthSqr() < 1.0e-8) return;
        nozzleOffset = sampleNozzle(model);
        ACTIVE.removeIf(p -> !p.isAlive());
        SMOKE.removeIf(p -> !p.isAlive());
        if (Boolean.FALSE.equals(MathematicalMissileVisuals.emitting(model.getUUID()))) return;
        if (com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.isAirToAir(
                com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.typeOf(model))
                && !com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight.managed(model)) return;
        if (!LandAttackMissileFlight.emitting(model)) return;
        if (!com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight.emitting(model)) return;
        if (emissionTick == ClientWeaponViewState.animationTick()) return;
        emissionTick = ClientWeaponViewState.animationTick();
        Vec3 nozzle = nozzle(position);
        // Same post-arrow drag, one-block spacing, count, random drift, and providers as the native procedure.
        Vec3 smokeVelocity = velocity.scale(0.99F);
        int count = GuidedMissileFlight.smokeCount(smokeVelocity);
        for (int i = 0; i < count; i++) {
            Vec3 point = GuidedMissileFlight.smokePosition(nozzle, smokeVelocity, i);
            Vec3 drift = new Vec3((level.random.nextDouble() * 2 - 1) * .75,
                    (level.random.nextDouble() * 2 - 1) * .75,
                    (level.random.nextDouble() * 2 - 1) * .75);
            NativeParticle smoke = spawn(level, "newsmoke_2", point, drift, point.subtract(nozzle));
            if (smoke != null) SMOKE.addLast(smoke);
        }
        // Native order: smoke first, then the original flame particle. No added cone or flame segments.
        String flameType = OrdinaryAirDefenseGuidance.isOrdinaryType(model.getType())
                ? "newrocketmissiletrailsmall" : rocket ? "rocketmissiletrail_3" : "newrocketmissiletrail";
        flame = spawn(level, flameType, nozzle, Vec3.ZERO, Vec3.ZERO);
        // Bounds retain several seconds of the original trail, including its native particle lifetime.
        while (ACTIVE.size() > 8192) ACTIVE.removeFirst().remove();
    }

    static Vec3 nozzle(Vec3 position) { return position.add(nozzleOffset); }

    @SuppressWarnings("unchecked")
    static Vec3 sampleNozzle(Entity model) {
        Vec3 axis = model.getDeltaMovement().normalize();
        NozzleProbe probe = new NozzleProbe(axis);
        var renderer = (net.minecraft.client.renderer.entity.EntityRenderer<Entity>) Minecraft.getInstance()
                .getEntityRenderDispatcher().getRenderer(model);
        // CPU-only native geometry includes the model pivot, legacy frame, scale, pitch and yaw.
        renderer.render(model, model.getYRot(), Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true),
                new com.mojang.blaze3d.vertex.PoseStack(), type -> probe, 15728880);
        return probe.count == 0 ? axis.scale(-0.65)
                : probe.sum.scale(1.0 / probe.count).subtract(axis.scale(0.04));
    }

    private static final class NozzleProbe implements VertexConsumer {
        final Vec3 axis;
        double rear = Double.POSITIVE_INFINITY;
        Vec3 sum = Vec3.ZERO;
        int count;
        NozzleProbe(Vec3 axis) { this.axis = axis; }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            Vec3 point = new Vec3(x, y, z);
            double depth = point.dot(axis);
            if (depth < rear - 1.0e-3) { rear = depth; sum = point; count = 1; }
            else if (Math.abs(depth - rear) <= 1.0e-3) { sum = sum.add(point); count++; }
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    private static NativeParticle spawn(ClientLevel level, String path, Vec3 point, Vec3 motion, Vec3 offset) {
        var type = BuiltInRegistries.PARTICLE_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath(
                "mianbaos_modernwarfare", path)).orElse(null);
        if (!(type instanceof ParticleOptions options)) return null;
        Particle original = ((NativeMissileParticleFactory) Minecraft.getInstance().particleEngine)
                .createFireControl$makeParticle(options, point.x, point.y, point.z, motion.x, motion.y, motion.z);
        if (original == null) return null;
        NativeParticle particle = new NativeParticle(level, original, point, offset);
        ACTIVE.addLast(particle);
        return particle;
    }

    static void tick() {
        ensureLevel();
        if (Minecraft.getInstance().isPaused()) return;
        for (NativeParticle particle : ACTIVE) if (particle.isAlive()) particle.tick();
        ACTIVE.removeIf(p -> !p.isAlive());
        SMOKE.removeIf(p -> !p.isAlive());
    }

    /** Draw native opaque/translucent batches with the same completed camera matrix as the remote model. */
    static void renderParticles(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        ensureLevel();
        if (ACTIVE.isEmpty()) return;
        var mc = Minecraft.getInstance();
        var matrix = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        matrix.pushMatrix().set(event.getModelViewMatrix());
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        try {
            var batches = new java.util.LinkedHashMap<ParticleRenderType, java.util.List<NativeParticle>>();
            for (NativeParticle particle : ACTIVE) if (particle.isAlive())
                batches.computeIfAbsent(particle.getRenderType(), ignored -> new java.util.ArrayList<>()).add(particle);
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
            for (var entry : batches.entrySet()) {
                com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getParticleShader);
                var vertices = entry.getKey().begin(com.mojang.blaze3d.vertex.Tesselator.getInstance(), mc.getTextureManager());
                if (vertices == null) continue;
                for (NativeParticle particle : entry.getValue()) particle.render(vertices, event.getCamera(), partial);
                var mesh = vertices.build();
                if (mesh != null) com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(mesh);
            }
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
            mc.gameRenderer.lightTexture().turnOffLightLayer();
            matrix.popMatrix(); com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        }
    }

    static void clear() {
        ACTIVE.forEach(Particle::remove); ACTIVE.clear(); SMOKE.clear();
        flame = null; emissionTick = Long.MIN_VALUE; observedId = null; session++;
    }

    /** Preserve native size, alpha, lifetime, shader, animation and motion; retime only the fresh cohort. */
    private static final class NativeParticle extends Particle {
        final Particle original;
        final long bornAt;
        final Vec3 offset;
        final java.util.UUID missileId;
        final long observationSession;
        boolean detached;
        NativeParticle(ClientLevel level, Particle original, Vec3 point, Vec3 offset) {
            super(level, point.x, point.y, point.z);
            this.original = original; this.offset = offset; bornAt = ClientWeaponViewState.animationTick();
            missileId = observedId; observationSession = session;
        }
        private boolean follows() {
            return !detached && observationSession == session && java.util.Objects.equals(missileId, observedId)
                    && ClientWeaponViewState.observes(missileId)
                    && bornAt == emissionTick && ClientWeaponViewState.animationTick() <= bornAt + 1;
        }
        void detach() {
            if (follows()) place(anchor(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true)));
            detached = true;
        }
        private void place(Vec3 point) {
            original.setPos(point.x, point.y, point.z);
            ParticleAccessor state = (ParticleAccessor) original;
            state.createFireControl$setXo(point.x); state.createFireControl$setYo(point.y); state.createFireControl$setZo(point.z);
        }
        private Vec3 anchor(float partial) { return nozzle(ClientWeaponViewState.weaponPosition(partial)).add(offset); }
        @Override public ParticleRenderType getRenderType() { return original.getRenderType(); }
        @Override public AABB getBoundingBox() {
            if (original == null) return super.getBoundingBox();
            return follows() ? new AABB(anchor(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true)),
                    anchor(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true))).inflate(4)
                    : original.getBoundingBox();
        }
        @Override public void tick() { original.tick(); if (!original.isAlive()) remove(); }
        @Override public void render(VertexConsumer vertices, Camera camera, float partial) {
            if (follows()) {
                place(anchor(partial));
            }
            original.render(vertices, camera, partial);
        }
        @Override public void remove() { super.remove(); if (original != null) original.remove(); }
    }

    static float requiredFarDistance() {
        if (trackedLevel == null || trackedLevel != Minecraft.getInstance().level || ACTIVE.isEmpty()) return 0;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition(), forward = new Vec3(camera.getLookVector());
        double far = 0;
        for (NativeParticle particle : ACTIVE) if (particle.isAlive()) {
            Vec3 relative = particle.original.getPos().subtract(origin);
            double distance = relative.length();
            if (relative.dot(forward) > 0 && distance <= 20000) far = Math.max(far, distance + 256);
        }
        return (float) far;
    }
    private WeaponViewExhaust() {}
}
