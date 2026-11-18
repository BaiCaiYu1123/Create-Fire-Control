package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.network.MathematicalMissilePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Real native particle providers and GPU frames throughout a synthetic 20-second Mach-3 flight. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class AntiRadiationExhaustClientChecks {
    private static final UUID ID = UUID.fromString("40e4bdee-015d-4154-a409-a5b88a232115");
    private static final ResourceLocation TYPE = ResourceLocation.parse("mianbaos_modernwarfare:antiradiationmissiletanshe");
    private static final int[] SHOTS = {70, 110, 250, 390, 417};
    private static int age, settle, flightAge, shot;
    private static boolean launched, ready, done;
    private static Entity camera, removedModel;
    private static Vec3 position = new Vec3(0, 240, 0), velocity = new Vec3(2, 0, 0);

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.antiRadiationExhaustChecks") || done) return;
        var mc = Minecraft.getInstance();
        try {
            require(++age < 3000, "Client exhaust check timed out");
            mc.options.pauseOnLostFocus = false;
            if (!launched && age > 60 && mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                mc.setScreen(new TitleScreen()); return;
            }
            if (!launched && age > 60 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                launched = true;
                mc.options.renderDistance().set(8); mc.options.simulationDistance().set(5);
                mc.createWorldOpenFlows().createFreshLevel("AntiRadiationExhaust-" + System.currentTimeMillis(),
                        new LevelSettings("ARM exhaust QA", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                                new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(1234, false, false),
                        WorldPresets::createNormalWorldDimensions, mc.screen);
                return;
            }
            if (mc.level == null || mc.player == null) return;
            if (!ready) {
                if (++settle < 40) return;
                mc.setScreen(null); mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                mc.getToasts().clear(); mc.options.hideGui = false;
                mc.options.fov().set(70); mc.options.bobView().set(false);
                mc.player.getAbilities().flying = true;
                mc.player.setPos(0, 238, 0);
                mc.getSingleplayerServer().execute(() -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    player.getAbilities().flying = true; player.teleportTo(0, 238, 0);
                    mc.getSingleplayerServer().overworld().setDayTime(18000);
                });
                camera = EntityType.ARMOR_STAND.create(mc.level);
                require(camera != null, "Cannot create chase camera");
                mc.setCameraEntity(camera);
                Files.createDirectories(Path.of("anti-radiation-exhaust-qa"));
                ready = true;
            }
            mc.player.setDeltaMovement(Vec3.ZERO);
            flightAge++;
            if (flightAge < 400) {
                Vec3 previous = position;
                position = position.add(velocity);
                velocity = new Vec3(MianbaoAircraftCompat.speedProfile(
                        MianbaoAircraftCompat.WeaponType.ANTI_RADIATION, 2).speedAt(flightAge), 0, 0);
                camera.setPos(position.x, position.y - 1.6, position.z - 50);
                camera.setYRot(0); camera.setXRot(0); camera.setOldPosAndRot();
                var state = new MathematicalMissilePayload.State(ID, TYPE, previous, position, velocity,
                        90, 0, 90, 0, flightAge, 0, flightAge, 0);
                MathematicalMissileVisuals.accept(new MathematicalMissilePayload(mc.level.dimension().location(),
                        flightAge, List.of(state), List.of()));
                require(MathematicalMissileVisuals.find(ID) != null, "Native ARM model disappeared in flight");
                if (flightAge == 50 || flightAge == 51 || flightAge == 70 || flightAge == 100
                        || flightAge == 200 || flightAge == 399) verifyFreshEffects();
            } else if (flightAge == 400) {
                removedModel = MathematicalMissileVisuals.find(ID);
                MathematicalMissileVisuals.accept(new MathematicalMissilePayload(mc.level.dimension().location(),
                        flightAge, List.of(), List.of(ID)));
                require(MathematicalMissileVisuals.find(ID) == null && removedModel.isRemoved(),
                        "Expiry did not remove native model and future effects");
            } else if (flightAge == 410) {
                require(shot == 4, "Missing GPU flight screenshots");
                require(aliveFlames(mc) == 0, "Tail flame kept emitting after expiry");
                require(MathematicalMissileVisuals.antiRadiationFarDistance() == 0,
                        "Expired missile kept the long-range render view active");
            } else if (flightAge >= 411 && flightAge < 426) {
                // Fixed observer: the missile and flame are beyond the vanilla projection and fog distance.
                mc.options.fov().set(30); // Resolve the native metre-scale flame beyond 1 km without changing its size.
                camera.setPos(0, 238.2, 0); camera.setOldPosAndRot();
                Vec3 farVelocity = new Vec3(0, 0, 51.045);
                Vec3 farPosition = new Vec3(0, 240, 1024 + (flightAge - 411) * 51.045);
                var state = new MathematicalMissilePayload.State(ID, TYPE, farPosition.subtract(farVelocity),
                        farPosition, farVelocity, 0, 0, 0, 0, 200 + flightAge - 411, 0, 200 + flightAge - 411, 0);
                MathematicalMissileVisuals.accept(new MathematicalMissilePayload(mc.level.dimension().location(),
                        flightAge, List.of(state), List.of()));
            } else if (flightAge == 426) {
                MathematicalMissileVisuals.accept(new MathematicalMissilePayload(mc.level.dimension().location(),
                        flightAge, List.of(), List.of(ID)));
            } else if (flightAge == 436) {
                require(shot == SHOTS.length && aliveFlames(mc) == 0, "Remote exhaust did not render/expire correctly");
                require(MathematicalMissileVisuals.antiRadiationFarDistance() == 0, "Remote removal left extended fog active");
                finish(null);
            }
        } catch (Throwable failure) { finish(failure); }
    }

    private static void verifyFreshEffects() throws Exception {
        var active = MathematicalMissileVisuals.class.getDeclaredField("ACTIVE"); active.setAccessible(true);
        Object visual = ((Map<?, ?>) active.get(null)).get(ID);
        var prepared = visual.getClass().getDeclaredField("prepared"); prepared.setAccessible(true);
        int flames = 0, smoke = 0;
        for (Object particle : (List<?>) prepared.get(visual)) {
            var original = particle.getClass().getDeclaredField("original"); original.setAccessible(true);
            Object nativeParticle = original.get(particle);
            if (nativeParticle.getClass().getSimpleName().equals("NewrocketmissiletrailParticle")) flames++;
            if (nativeParticle.getClass().getSimpleName().equals("Newsmoke2Particle")) smoke++;
        }
        require(flames > 0 && smoke > 0, "No fresh native flame/smoke at flight tick " + flightAge);
        StabilizedTurret.LOGGER.info("ARM EXHAUST: tick={} distance={} freshFlames={} freshSmoke={}",
                flightAge, position.x, flames, smoke);
    }

    private static int aliveFlames(Minecraft mc) throws Exception {
        var queuesField = net.minecraft.client.particle.ParticleEngine.class.getDeclaredField("particles");
        queuesField.setAccessible(true);
        int result = 0;
        for (Object queue : ((Map<?, ?>) queuesField.get(mc.particleEngine)).values())
            for (Object particle : (Iterable<?>) queue) {
                if (!particle.getClass().getName().contains("MathematicalMissileVisuals$ScheduledParticle")) continue;
                var original = particle.getClass().getDeclaredField("original"); original.setAccessible(true);
                var nativeParticle = (net.minecraft.client.particle.Particle) original.get(particle);
                if (nativeParticle.isAlive() && nativeParticle.getClass().getSimpleName().equals("NewrocketmissiletrailParticle")) result++;
            }
        return result;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(RenderGuiEvent.Post event) {
        if (!ready || done || shot >= SHOTS.length || flightAge < SHOTS[shot]) return;
        try {
            var mc = Minecraft.getInstance();
            require(aliveFlames(mc) > 0, "No live native tail flames in GPU frame at tick " + flightAge);
            if (flightAge >= 411) require(mc.gameRenderer.getDepthFar() > 1300,
                    "Fixed observer's far plane still clipped the remote missile/particles");
            mc.renderBuffers().bufferSource().endBatch();
            try (var screenshot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                int yellow = 0;
                for (int y = screenshot.getHeight() / 3; y < screenshot.getHeight() * 2 / 3; y++)
                    for (int x = 0; x < screenshot.getWidth(); x++) {
                        int rgba = screenshot.getPixelRGBA(x, y);
                        int red = rgba & 255, green = rgba >>> 8 & 255, blue = rgba >>> 16 & 255;
                        if (red > 100 && green > 80 && blue < green * .65) yellow++;
                    }
                screenshot.writeToFile(Path.of("anti-radiation-exhaust-qa", "flight-" + flightAge + ".png"));
                StabilizedTurret.LOGGER.info("ARM EXHAUST GPU: tick={} yellowFlamePixels={} farPlane={}",
                        flightAge, yellow, mc.gameRenderer.getDepthFar());
                require(yellow > (flightAge >= 411 ? 0 : 8),
                        "Native flame pixels missing from completed GPU frame at tick " + flightAge);
            }
            shot++;
        } catch (Throwable failure) { finish(failure); }
    }

    private static void finish(Throwable failure) {
        done = true;
        if (failure != null) StabilizedTurret.LOGGER.error("ARM EXHAUST CLIENT CHECK FAILED", failure);
        try { Files.writeString(Path.of("anti-radiation-exhaust-result.txt"), failure == null
                ? "PASS: native ARM model, fresh original flame/smoke at ticks 50/51/70/100/200/399; flame pixels in four full-flight GPU frames and a fixed observer beyond 1 km; far plane/fog expand and reset on removal; expiry stops effects"
                : "FAIL: " + failure); }
        catch (Exception error) { throw new RuntimeException(error); }
        Minecraft.getInstance().stop();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
