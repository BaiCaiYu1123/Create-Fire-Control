package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Actual client rendering and real server-to-client state/removal packets beyond loaded chunks. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class OrdinaryAaClientIntegration {
    private static final UUID ID = UUID.fromString("d78e7b82-d24d-4f5b-a561-532045b36ad4");
    private static int age, wait, stage;
    private static boolean launched;
    private static volatile boolean done, removalDispatched;
    private static net.minecraft.world.entity.Entity serverVisual;
    private static int renderedStage, renderedFrames;
    private static double renderedFov, renderedFar;
    private static int probedStage;
    private static boolean captureRequested;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void capture(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.ordinaryAaClientChecks") || done || !captureRequested) return;
        var mc = Minecraft.getInstance();
        var visual = MissileLongRangeEffects.getRemoteVisual(ID);
        double distance = stage == 1 ? 96 : 512;
        if (visual == null || Math.abs(visual.getZ() - distance) >= 1) return;
        captureRequested = false;
        try {
            Files.createDirectories(Path.of("ordinary-aa-qa"));
            try (var screenshot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                int modelPixels = 0;
                for (int y = 0; y < screenshot.getHeight(); y++) for (int x = 0; x < screenshot.getWidth(); x++) {
                    int pixel = screenshot.getPixelRGBA(x, y);
                    if ((pixel & 255) < 90 && ((pixel >>> 8) & 255) < 90 && ((pixel >>> 16) & 255) < 90) modelPixels++;
                }
                screenshot.writeToFile(Path.of("ordinary-aa-qa/ordinary-aa-" + (int) distance + "m.png"));
                require(modelPixels > 0, "Native ordinary AA model produced no visible pixels at " + distance + "m beyond view/far plane");
            }
            stage++; wait = 0;
        } catch (Throwable failure) { finish(mc, failure); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void rendered(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (!Boolean.getBoolean("create_fire_control.ordinaryAaClientChecks")
                || event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (renderedStage != stage) { renderedStage = stage; renderedFrames = 0; }
        var visual = MissileLongRangeEffects.getRemoteVisual(ID);
        double distance = stage == 1 ? 96 : stage == 2 ? 512 : 8192;
        if (visual != null && Math.abs(visual.getZ() - distance) < 1) renderedFrames++;
        var projection = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix();
        renderedFov = Math.toDegrees(2 * Math.atan(1 / projection.m11()));
        renderedFar = projection.m32() / (projection.m22() + 1.0);
        if (visual != null && renderedFrames > 8 && probedStage != stage) {
            probedStage = stage;
            var mc = Minecraft.getInstance();
            var relative = visual.position().subtract(event.getCamera().getPosition());
            var probeProjection = new org.joml.Matrix4f(projection);
            if (relative.length() + 32 > renderedFar) {
                double near = projection.m32() / (projection.m22() - 1.0), far = relative.length() + 64;
                probeProjection.m22((float) (-(far + near) / (far - near)))
                        .m32((float) (-2 * far * near / (far - near)));
            }
            var probe = new VertexProbe(probeProjection);
            var pose = new com.mojang.blaze3d.vertex.PoseStack();
            pose.mulPose(event.getModelViewMatrix());
            pose.translate(relative.x, relative.y, relative.z);
            mc.getEntityRenderDispatcher().getRenderer(visual).render(visual, visual.getYRot(), 1, pose,
                    type -> probe, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
            StabilizedTurret.LOGGER.info("ORDINARY AA VERTICES stage={} count={} ndcMin={} ndcMax={} yaw={} pitch={}",
                    stage, probe.count, probe.min, probe.max, visual.getYRot(), visual.getXRot());
            var shader = net.minecraft.client.renderer.GameRenderer.getRendertypeEntityCutoutShader();
            for (var name : new String[]{"ModelViewMat", "ProjMat", "FogStart", "FogEnd", "ColorModulator"}) {
                var uniform = shader.getUniform(name);
                if (uniform == null || uniform.getFloatBuffer() == null) continue;
                var values = new float[uniform.getFloatBuffer().limit()];
                for (int i = 0; i < values.length; i++) values[i] = uniform.getFloatBuffer().get(i);
                StabilizedTurret.LOGGER.info("ORDINARY AA SHADER stage={} {}={}", stage, name, java.util.Arrays.toString(values));
            }
        }
    }

    @SubscribeEvent
    public static void scopedFov(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) {
        if (Boolean.getBoolean("create_fire_control.ordinaryAaClientChecks") && stage >= 2) event.setFOV(2.0);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.ordinaryAaClientChecks") || done) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            age++;
            if (!launched && age > 80 && mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                mc.setScreen(new TitleScreen()); return;
            }
            if (!launched && age > 80 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                launched = true;
                mc.options.renderDistance().set(2);
                mc.createWorldOpenFlows().createFreshLevel("Ordinary-AA-QA-" + System.currentTimeMillis(),
                        new LevelSettings("Ordinary AA QA", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                                new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(1234, false, false),
                        WorldPresets::createNormalWorldDimensions, mc.screen);
                return;
            }
            if (mc.level == null || mc.player == null) return;
            if (stage == 0) {
                if (++wait < 60) return;
                mc.setScreen(null);
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                mc.options.hideGui = true; mc.options.pauseOnLostFocus = false; mc.options.fov().set(35);
                mc.options.bobView().set(false);
                mc.player.getAbilities().flying = true;
                mc.player.setPos(0, 230, 0);
                mc.getSingleplayerServer().execute(() -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    player.getAbilities().flying = true; player.teleportTo(0, 230, 0);
                });
                stage = 1; wait = 0;
            }
            mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            mc.player.setYRot(0); mc.player.setXRot(0);
            if (stage <= 3) {
                double distance = stage == 1 ? 96 : stage == 2 ? 512 : 8192;
                var integratedServer = mc.getSingleplayerServer();
                if (integratedServer == null) return;
                integratedServer.execute(() -> {
                    if (done) return;
                    if (serverVisual == null) {
                        serverVisual = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(
                                ResourceLocation.parse("mianbaos_modernwarfare:portableantiairmissiletanshe"))
                                .create(integratedServer.overworld());
                        serverVisual.setUUID(ID);
                        serverVisual.getPersistentData().putBoolean("CreateFireControlGuided", true);
                    }
                    serverVisual.setPos(0, 231.62, distance);
                    // Keep this synthetic rendering fixture within the narrow
                    // scope between packets; real Mach-1 flight has its own server check.
                    serverVisual.setDeltaMovement(.1, 0, 0);
                    com.hooya.stabilizedturret.content.controller.MianbaoMissileRemoteSync.broadcast(serverVisual);
                });
                wait++;
                var visual = MissileLongRangeEffects.getRemoteVisual(ID);
                require(wait < 400, "Server state packet did not create ordinary AA visual at " + distance + "m");
                if (wait >= 30 && renderedStage == stage && renderedFrames >= 10
                        && visual != null && Math.abs(visual.getZ() - distance) < 1) {
                    require(MissileLongRangeEffects.detachedBallisticVisuals().contains(visual), "Visual depends on client chunk sections");
                    require(mc.level.getEntity(visual.getId()) == null, "Visual was inserted into terrain chunks");
                    require(mc.getEntityRenderDispatcher().getRenderer(visual) != null, "Missing native ordinary missile renderer");
                    require(!mc.level.getChunkSource().hasChunk(0, ((int) distance) >> 4), "Visual fixture is within loaded terrain");
                    if (stage <= 2) {
                        StabilizedTurret.LOGGER.info("ORDINARY AA FRAME distance={} fov={} far={} frames={} camera={} visual={}",
                                distance, renderedFov, renderedFar, renderedFrames,
                                mc.gameRenderer.getMainCamera().getPosition(), visual.position());
                        captureRequested = true;
                    } else {
                        integratedServer.execute(() -> {
                            if (done) return;
                            // A fresh state immediately before removal proves
                            // the result was not the six-tick visual timeout.
                            com.hooya.stabilizedturret.content.controller.MianbaoMissileRemoteSync.broadcast(serverVisual);
                            com.hooya.stabilizedturret.content.controller.MianbaoMissileRemoteSync.remove(serverVisual);
                            removalDispatched = true;
                        });
                        stage = 4; wait = 0;
                    }
                }
            } else {
                if (removalDispatched && ++wait >= 4) {
                    require(MissileLongRangeEffects.getRemoteVisual(ID) == null, "Authoritative removal left a distant visual alive");
                    finish(mc, null);
                }
            }
            require(age < 5000, "Ordinary AA client check timed out");
        } catch (Throwable failure) { finish(mc, failure); }
    }

    private static void finish(Minecraft mc, Throwable failure) {
        done = true;
        try {
            Files.writeString(Path.of("ordinary-aa-client-result.txt"), failure == null
                    ? "PASS: native visible pixels at 96/512m beyond view/far plane, server packets at 8192m without terrain, explicit removal" : "FAIL: " + failure);
            if (failure != null) StabilizedTurret.LOGGER.error("ORDINARY AA CLIENT CHECK FAILED", failure);
        } catch (Exception error) { throw new RuntimeException(error); }
        finally { mc.stop(); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class VertexProbe implements com.mojang.blaze3d.vertex.VertexConsumer {
        final org.joml.Matrix4f projection;
        final org.joml.Vector3f min = new org.joml.Vector3f(Float.POSITIVE_INFINITY);
        final org.joml.Vector3f max = new org.joml.Vector3f(Float.NEGATIVE_INFINITY);
        int count;
        VertexProbe(org.joml.Matrix4f projection) { this.projection = projection; }
        public VertexProbe addVertex(float x, float y, float z) {
            var ndc = projection.transformProject(new org.joml.Vector3f(x, y, z));
            min.min(ndc); max.max(ndc); count++; return this;
        }
        public VertexProbe setColor(int r, int g, int b, int a) { return this; }
        public VertexProbe setUv(float u, float v) { return this; }
        public VertexProbe setUv1(int u, int v) { return this; }
        public VertexProbe setUv2(int u, int v) { return this; }
        public VertexProbe setNormal(float x, float y, float z) { return this; }
    }
}
