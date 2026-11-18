package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.InfraredGeometry;
import com.hooya.stabilizedturret.content.controller.InfraredTargeting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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

/** Actual GPU HUD render at 2K; only the test background and seeker position are synthetic. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class InfraredHudClientChecks {
    private static int age, settle, stage, frames;
    private static boolean launched, ready, done;
    private static boolean aircraftRequested;
    private static volatile java.util.UUID aircraftId;
    private static volatile BlockPos aircraftPos;
    private static volatile Throwable aircraftError;
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.infraredClientChecks") || done) return;
        var mc = Minecraft.getInstance();
        try {
            age++;
            if (!launched && age > 60 && mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                mc.setScreen(new TitleScreen()); return;
            }
            if (!launched && age > 60 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                launched = true;
                mc.createWorldOpenFlows().createFreshLevel("InfraredHud-" + System.currentTimeMillis(),
                        new LevelSettings("Infrared HUD QA", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                                new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(1234, false, false),
                        WorldPresets::createNormalWorldDimensions, mc.screen);
                return;
            }
            if (mc.level == null || mc.player == null || ready) return;
            if (++settle < 40) return;
            if (Boolean.getBoolean("create_fire_control.infraredMouseFlightChecks") || Boolean.getBoolean("create_fire_control.infraredHemisphereChecks")) {
                if (!aircraftRequested) {
                    aircraftRequested = true;
                    BlockPos anchor = mc.player.blockPosition().above(80);
                    mc.getSingleplayerServer().execute(() -> {
                        try {
                            var level = mc.getSingleplayerServer().overworld();
                            level.setBlockAndUpdate(anchor, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                            var assembly = dev.simulated_team.simulated.util.SimAssemblyHelper.assembleFromSingleBlock(
                                    level, anchor.below(), anchor, false, false);
                            if (assembly == null) throw new AssertionError("Client aircraft fixture assembly failed");
                            aircraftPos = anchor.offset(assembly.offset());
                            aircraftId = assembly.subLevel().getUniqueId();
                        } catch (Throwable error) { aircraftError = error; }
                    });
                    return;
                }
                if (aircraftError != null) throw new AssertionError("Aircraft fixture failed", aircraftError);
                if (aircraftId == null || dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level).getSubLevel(aircraftId) == null) {
                    if (settle > 300) throw new AssertionError("Aircraft fixture did not synchronize to the actual client");
                    return;
                }
            }
            mc.setScreen(null); mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            mc.getToasts().clear(); mc.options.hideGui = false; mc.options.pauseOnLostFocus = false;
            mc.options.fov().set(70); mc.options.bobView().set(false); mc.options.guiScale().set(1); mc.resizeDisplay();
            mc.player.setYRot(180); mc.player.setXRot(0);
            Files.createDirectories(Path.of("infrared-hud-qa"));
            ready = true;
        } catch (Throwable error) { finish(error); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(RenderGuiEvent.Post event) {
        if (!ready || done) return;
        var mc = Minecraft.getInstance();
        try {
            var method = InfraredHudRenderer.class.getDeclaredMethod("cameraFrame", Minecraft.class, float.class);
            method.setAccessible(true);
            var frame = (InfraredTargeting.Frame) method.invoke(null, mc, 1F);
            if (!frame.valid()) throw new AssertionError("Actual Minecraft camera basis/FOV rejected by server validation: " + frame);
            if (Boolean.getBoolean("create_fire_control.infraredHemisphereChecks")) {
                if (InfraredHemisphereClientChecks.render(event, mc, aircraftId, aircraftPos)) finish(null);
                return;
            }
            if (Boolean.getBoolean("create_fire_control.infraredStyleChecks")) {
                if (InfraredStyleClientChecks.render(event, mc)) finish(null);
                return;
            }
            if (Boolean.getBoolean("create_fire_control.infraredMouseFlightChecks")) {
                mouseFlightRender(event, mc, frame);
                return;
            }
            var g = event.getGuiGraphics();
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xFF000000);
            if (stage != 3 && stage != 4) InfraredHudRenderer.renderRings(g,
                    stage == 0 || stage == 5 ? InfraredGeometry.GREEN : InfraredGeometry.RED,
                    stage == 0 || stage == 5 ? InfraredGeometry.SEEKER_X : 1560,
                    stage == 0 || stage == 5 ? InfraredGeometry.SEEKER_Y : 430, frame);
            g.flush();
            if (++frames < 4) return;
            frames = 0;
            String[] names = {"search-gui1", "locked", "loss-on", "loss-off", "other-weapon-hidden", "search-gui4"};
            try (var screenshot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                if (screenshot.getWidth() != 2560 || screenshot.getHeight() != 1440)
                    throw new AssertionError("Expected 2560x1440 framebuffer, got " + screenshot.getWidth() + "x" + screenshot.getHeight());
                screenshot.writeToFile(Path.of("infrared-hud-qa", names[stage] + ".png"));
            }
            if (++stage == 5) { mc.options.guiScale().set(4); mc.resizeDisplay(); }
            if (stage == 6) finish(null);
        } catch (Throwable error) { finish(error); }
    }
    private static void mouseFlightRender(RenderGuiEvent.Post event, Minecraft mc, InfraredTargeting.Frame camera) throws Exception {
        var g = event.getGuiGraphics();
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xFF000000);
        // Turn only the view. The installed terminal still points north.
        if (stage == 5) camera = new InfraredTargeting.Frame(camera.origin(),
                turnView(camera.forward()), turnView(camera.right()), turnView(camera.up()), camera.halfTanY(), camera.aspect());
        int x = (int) Math.round(g.guiWidth() * (stage == 1 ? .65 : stage == 2 ? .35 : .5));
        int y = (int) Math.round(g.guiHeight() * (stage == 1 ? .25 : stage == 2 ? .45 : .35));
        boolean nativeActive = stage <= 2 || stage >= 5;
        Direction actualNose = stage == 6 ? Direction.SOUTH : Direction.NORTH;
        BlockPos terminalPos = aircraftPos;
        var body = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level).getSubLevel(aircraftId);
        Class<?> nativeHud = Class.forName("ace.flight.neoforge.client.MouseFlightControllerClient");
        if (stage != 4) MouseFlightSeekerCompat.begin(event);
        else {
            MouseFlightSeekerCompat.begin(new RenderGuiEvent.Post(g, event.getPartialTick()));
            MouseFlightSeekerCompat.capture(g, x, y);
        }
        if (nativeActive) {
            resolveNativeAircraft(nativeHud, mc, terminalPos, actualNose, stage == 1 || stage == 2 || stage == 6);
            // Invoke the installed flight mod's real draw method, so the
            // shipped Mixin, native icon and capture bridge are all exercised.
            var draw = nativeHud.getDeclaredMethod("drawTargetCircle", net.minecraft.client.gui.GuiGraphics.class, int.class, int.class);
            draw.setAccessible(true); draw.invoke(null, g, x, y);
            var nose = nativeHud.getDeclaredMethod("drawAircraftCrosshair", net.minecraft.client.gui.GuiGraphics.class, int.class, int.class);
            nose.setAccessible(true); nose.invoke(null, g, x - 80, y + 60);
        }
        MouseFlightSeekerCompat.end(event);
        if (stage == 7) resolveNativeAircraft(nativeHud, mc, terminalPos, Direction.EAST, false);
        var captured = MouseFlightSeekerCompat.frame(event, camera);
        double sx = captured.seekerX(), sy = captured.seekerY();
        if (nativeActive) {
            double actualX = g.guiWidth() / 2.0 + (sx - InfraredGeometry.WIDTH / 2) * g.guiHeight() / InfraredGeometry.HEIGHT;
            double actualY = sy * g.guiHeight() / InfraredGeometry.HEIGHT;
            if (Math.abs(actualX - x) > 1e-8 || Math.abs(actualY - y) > 1e-8)
                throw new AssertionError("IR seeker did not follow the native flight-control circle: " + actualX + "," + actualY + " expected " + x + "," + y);
            Vec3 screen = captured.project(captured.direction(sx, sy).scale(1000).add(captured.origin()));
            if (Math.abs(screen.x * g.guiWidth() - x) > 1e-7 || Math.abs(screen.y * g.guiHeight() - y) > 1e-7)
                throw new AssertionError("Server acquisition ray and captured native icon disagree");
            Vec3 expectedNose = new Vec3(actualNose.getStepX(), actualNose.getStepY(), actualNose.getStepZ());
            var nativeAxis = body.logicalPose().transformNormal(new org.joml.Vector3d(expectedNose.x, expectedNose.y, expectedNose.z));
            expectedNose = new Vec3(nativeAxis.x, nativeAxis.y, nativeAxis.z).normalize();
            var pose = body instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientBody ? clientBody.renderPose() : body.logicalPose();
            var nativePosition = pose.transformPosition(new org.joml.Vector3d(terminalPos.getX() + .5, terminalPos.getY() + .5, terminalPos.getZ() + .5));
            if (captured.aircraftForward().dot(expectedNose) < .999999
                    || captured.aircraftOrigin().distanceTo(new Vec3(nativePosition.x, nativePosition.y, nativePosition.z)) > 1e-7)
                throw new AssertionError("IR cone did not capture the installed flight terminal's actual aircraft frame: " + captured);
            Vec3 northTarget = captured.aircraftOrigin().add(expectedNose.scale(actualNose == Direction.NORTH ? 1000 : -1000));
            if (captured.inRange(northTarget) != (actualNose == Direction.NORTH))
                throw new AssertionError("Turning the view/nose changed the wrong acquisition axis");
            if (!captured.valid()) throw new AssertionError("Native aircraft frame rejected by server validation");
            if (stage == 5) {
                Vec3 projectedNose = captured.project(northTarget);
                if (projectedNose == null || Math.abs(projectedNose.x - .5) < .02)
                    throw new AssertionError("Free-look failed to move the aircraft cone's projected axis");
            }
        } else if (sx != InfraredGeometry.SEEKER_X || sy != InfraredGeometry.SEEKER_Y)
            throw new AssertionError("Inactive or stale flight-control marker was not cleared");
        InfraredHudRenderer.renderRings(g, InfraredGeometry.GREEN, sx, sy, captured);
        g.flush();
        if (++frames < 4) return;
        frames = 0;
        String[] names = {"flight-target-above", "flight-creative-target-right", "flight-creative-gui4-wide-fov", "flight-inactive-fallback",
                "flight-stale-fallback", "flight-free-look-nose-fixed", "flight-nose-turned-away", "flight-render-interval-guard"};
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(Path.of("infrared-hud-qa", names[stage] + ".png"));
        }
        if (++stage == 2) { mc.options.guiScale().set(4); mc.options.fov().set(110); mc.resizeDisplay(); }
        if (stage == 8) finish(null);
    }
    private static Vec3 turnView(Vec3 vector) {
        double cosine = Math.cos(.45), sine = Math.sin(.45);
        return new Vec3(vector.x * cosine + vector.z * sine, vector.y, vector.z * cosine - vector.x * sine);
    }
    private static void resolveNativeAircraft(Class<?> nativeHud, Minecraft mc, BlockPos pos, Direction facing, boolean creative) throws Exception {
        Class<?> terminalType = Class.forName("ace.flight.block.MouseFlightControllerBlockEntity");
        String className = creative ? "ace.flight.block.CreativeFlightControlComputerBlockEntity" : terminalType.getName();
        String blockId = creative ? "creative_flight_control_computer" : "mouse_flight_controller";
        var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_flight_control:" + blockId)).defaultBlockState();
        var entity = (net.minecraft.world.level.block.entity.BlockEntity) Class.forName(className)
                .getConstructor(BlockPos.class, net.minecraft.world.level.block.state.BlockState.class).newInstance(pos, state);
        entity.setLevel(mc.level);
        Object terminal = creative ? entity.getClass().getMethod("getFlightTerminal").invoke(entity) : entity;
        ((net.minecraft.world.level.block.entity.BlockEntity) terminal).setLevel(mc.level);
        var noseField = terminalType.getField("bodyForwardFacing");
        noseField.set(terminal, noseField.getType().getMethod("fromDirection", Direction.class).invoke(null, facing));
        var resolve = nativeHud.getDeclaredMethod("resolveAircraftFrame", terminalType);
        resolve.setAccessible(true);
        if (resolve.invoke(null, terminal) == null) throw new AssertionError("Installed flight terminal did not resolve an aircraft frame");
    }
    private static void finish(Throwable error) {
        done = true;
        if (error != null) StabilizedTurret.LOGGER.error("INFRARED CLIENT CHECK FAILED", error);
        try { Files.writeString(Path.of("infrared-hud-client-result.txt"), error == null
                ? Boolean.getBoolean("create_fire_control.infraredMouseFlightChecks")
                ? "PASS: installed Create Flight Control native target-circle and aircraft-frame Mixins, ordinary/creative terminals, IR/HUD/acquisition alignment, GUI scales 1/4, wide FOV, fixed nose during free-look, actual nose turn, stale/inactive and render-interval guards"
                : "PASS: native GPU 2560x1440 circles, actual camera basis, GUI scales 1/4, search/lock/loss/hide screenshots" : "FAIL: " + error); }
        catch (Exception failure) { throw new RuntimeException(failure); }
        Minecraft.getInstance().stop();
    }
}
