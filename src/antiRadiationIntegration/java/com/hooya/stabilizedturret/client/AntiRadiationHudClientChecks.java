package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.AntiRadiationGeometry;
import com.hooya.stabilizedturret.content.controller.AntiRadiationTargeting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
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

/** Actual GPU frames and the installed flight mod's native target-circle drawing/Mixin. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class AntiRadiationHudClientChecks {
    private static int age, settle, stage, frames;
    private static boolean launched, ready, done;
    private static final String[] NAMES = {"unarmed-hidden", "search-on", "search-off", "locked-phase-on",
            "locked-phase-off", "flight-circle-offset", "flight-circle-gui4-wide-fov", "flight-inactive-fallback",
            "flight-stale-fallback", "search-gui4", "aircraft-free-look", "aircraft-pitch-roll",
            "rear-view-hidden", "viewport-clipped"};

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.antiRadiationClientChecks") || done) return;
        var mc = Minecraft.getInstance();
        try {
            if (++age > 2400) throw new AssertionError("Client QA timed out at stage " + stage);
            mc.options.pauseOnLostFocus = false;
            if (!launched && age > 60 && mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                mc.setScreen(new TitleScreen()); return;
            }
            if (!launched && age > 60 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                launched = true;
                mc.options.renderDistance().set(3); mc.options.simulationDistance().set(3);
                mc.createWorldOpenFlows().createFreshLevel("AntiRadiationHud-" + System.currentTimeMillis(),
                        new LevelSettings("Anti-radiation HUD QA", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                                new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(1234, false, false),
                        WorldPresets::createNormalWorldDimensions, mc.screen);
                return;
            }
            if (mc.level == null || mc.player == null || ready || ++settle < 40) return;
            mc.setScreen(null); mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            mc.getToasts().clear(); mc.options.hideGui = false;
            mc.options.fov().set(70); mc.options.bobView().set(false); mc.options.guiScale().set(1); mc.resizeDisplay();
            Files.createDirectories(Path.of("anti-radiation-hud-qa"));
            ready = true;
        } catch (Throwable error) { finish(error); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(RenderGuiEvent.Post event) {
        if (!ready || done) return;
        var mc = Minecraft.getInstance();
        try {
            var method = AntiRadiationHudRenderer.class.getDeclaredMethod("cameraFrame", Minecraft.class, float.class);
            method.setAccessible(true);
            var camera = (AntiRadiationTargeting.Frame) method.invoke(null, mc, 1F);
            if (!camera.valid()) throw new AssertionError("Actual camera basis/FOV rejected");
            var g = event.getGuiGraphics();
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xFFD8E4E8);
            int x = (int) Math.round(g.guiWidth() * (stage == 6 ? .54 : .43));
            int y = (int) Math.round(g.guiHeight() * (stage == 6 ? .45 : .38));
            if (stage == 8) {
                MouseFlightSeekerCompat.begin(new RenderGuiEvent.Post(g, event.getPartialTick()));
                MouseFlightSeekerCompat.capture(g, x, y);
            } else MouseFlightSeekerCompat.begin(event);
            if (stage == 5 || stage == 6) {
                Class<?> nativeHud = Class.forName("ace.flight.neoforge.client.MouseFlightControllerClient");
                var draw = nativeHud.getDeclaredMethod("drawTargetCircle", net.minecraft.client.gui.GuiGraphics.class, int.class, int.class);
                draw.setAccessible(true); draw.invoke(null, g, x, y);
            }
            var frame = MouseFlightSeekerCompat.frame(event, camera);
            var aperture = AntiRadiationGeometry.Basis.of(camera.forward(), camera.up());
            if (stage == 10) {
                Vec3 f = camera.forward().scale(Math.cos(.4)).add(camera.right().scale(Math.sin(.4)));
                Vec3 r = f.cross(camera.up()).normalize();
                frame = new AntiRadiationTargeting.Frame(camera.origin(), f, r, r.cross(f), camera.halfTanY(), camera.aspect());
                Vec3 center = frame.projectDirection(aperture.forward());
                if (center == null || Math.abs(center.x - .5) < .1)
                    throw new AssertionError("Search frame followed player free look");
            }
            if (stage == 11) aperture = AntiRadiationGeometry.Basis.of(
                    camera.forward().scale(Math.cos(.12)).add(camera.up().scale(Math.sin(.12))),
                    camera.up().scale(Math.cos(.5)).add(camera.right().scale(Math.sin(.5))));
            if (stage == 12) aperture = AntiRadiationGeometry.Basis.of(camera.forward().scale(-1), camera.up());
            if (stage == 13) aperture = AntiRadiationGeometry.Basis.of(
                    camera.forward().scale(Math.cos(.95)).add(camera.right().scale(Math.sin(.95))), camera.up());
            if (stage == 5 || stage == 6) {
                double actualX = g.guiWidth() * .5 + (frame.aimX() - 1280) * g.guiHeight() / 1440;
                double actualY = frame.aimY() * g.guiHeight() / 1440;
                if (Math.abs(actualX - x) > 1e-7 || Math.abs(actualY - y) > 1e-7)
                    throw new AssertionError("Acquisition reference does not match the native flight target circle");
                Vec3 circle = new Vec3((double) x / g.guiWidth(), (double) y / g.guiHeight(), 100);
                if (frame.aimDistanceSquared(circle) >= frame.aimDistanceSquared(new Vec3(.5, .5, 100)))
                    throw new AssertionError("Off-centre native flight circle did not override the central reticle");
            } else if (frame.aimX() != 1280 || frame.aimY() != 720)
                throw new AssertionError("Inactive/stale flight marker leaked into the next camera frame");

            boolean locked = stage >= 3 && stage <= 6;
            double px = 1210, py = 650;
            Vec3 target = !locked ? null : frame.origin().add(frame.forward().scale(1000))
                    .add(frame.right().scale((px - 1280) * 2 * frame.halfTanY() * 1000 / 1440))
                    .add(frame.up().scale((720 - py) * 2 * frame.halfTanY() * 1000 / 1440));
            double elapsed = stage == 2 || stage == 4 ? 12 : 2;
            AntiRadiationHudRenderer.renderFrames(g, frame, aperture, target, stage != 0, elapsed);
            g.flush();
            if (++frames < 4) return;
            frames = 0;
            try (var screenshot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                if (screenshot.getWidth() != 2560 || screenshot.getHeight() != 1440)
                    throw new AssertionError("Expected 2560x1440 framebuffer");
                var sample = frame.projectDirection(aperture.ray(AntiRadiationGeometry.LEFT + 8, AntiRadiationGeometry.TOP));
                boolean rangeVisible = false;
                if (sample != null && sample.x >= 0 && sample.x <= 1 && sample.y >= 0 && sample.y <= 1) {
                    int sx = (int) Math.round(sample.x * 2560), sy = (int) Math.round(sample.y * 1440);
                    for (int ix = Math.max(0, sx - 3); ix <= Math.min(2559, sx + 3); ix++)
                        for (int iy = Math.max(0, sy - 3); iy <= Math.min(1439, sy + 3); iy++)
                            rangeVisible |= (screenshot.getPixelRGBA(ix, iy) & 255) < 180;
                }
                if (rangeVisible != (stage != 0 && stage != 2 && stage != 12))
                    throw new AssertionError("Outer search frame has wrong armed/blink/lock phase at stage " + stage);
                if (stage == 1 && (Math.abs(sample.x * 2560 - 1049) > .01 || Math.abs(sample.y * 1440 - 470) > .01))
                    throw new AssertionError("Search frame is not centered at the reference 478x500 size");
                if (locked && (screenshot.getPixelRGBA(1200, 632) & 255) >= 180)
                    throw new AssertionError("Locked target frame missing or blinking");
                screenshot.writeToFile(Path.of("anti-radiation-hud-qa", NAMES[stage] + ".png"));
            }
            if (++stage == 6) { mc.options.guiScale().set(4); mc.options.fov().set(110); mc.resizeDisplay(); }
            if (stage == 10) { mc.options.guiScale().set(1); mc.options.fov().set(70); mc.resizeDisplay(); }
            if (stage == NAMES.length) finish(null);
        } catch (Throwable error) { finish(error); }
    }

    private static void finish(Throwable error) {
        done = true;
        if (error != null) StabilizedTurret.LOGGER.error("ANTI-RADIATION CLIENT CHECK FAILED", error);
        try { Files.writeString(Path.of("anti-radiation-client-result.txt"), error == null
                ? "PASS: actual GPU centered 478x500 at 2K/FOV70; aircraft-fixed free look and pitch/roll projection; rear-view suppression/viewport clipping; search blink and steady lock; native flight circle Mixin; GUI1/4 FOV70/110; inactive/stale fallback; pixel assertions"
                : "FAIL: " + error); }
        catch (Exception failure) { throw new RuntimeException(failure); }
        Minecraft.getInstance().stop();
    }
}
