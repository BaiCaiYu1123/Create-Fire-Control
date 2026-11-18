package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import com.hooya.stabilizedturret.mixin.client.NativeMissileParticleFactory;
import com.hooya.stabilizedturret.mixin.client.ParticleAccessor;
import com.hooya.stabilizedturret.network.BombSightPayload;
import net.minecraft.client.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Real providers, production HUD draw calls, marker geometry, camera following and world-ring visibility. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ClientVisualSettingsClientChecks {
    private static int age, settle, stage;
    private static boolean launched, finished;
    private static Vec3 impact;
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void frame(RenderFrameEvent.Pre event) {
        if (!Boolean.getBoolean("create_fire_control.visualSettingsChecks") || finished || stage == 0) return;
        try { prepareFrame(); } catch (Throwable failure) { finish(failure); }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.visualSettingsChecks") || finished) return;
        var mc = Minecraft.getInstance();
        try {
            if (++age > 3600) throw new AssertionError("Visual settings client check timed out");
            if (!launched) {
                if (age < 80 || mc.getOverlay() != null) return;
                if (mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) { mc.setScreen(new TitleScreen()); return; }
                if (!(mc.screen instanceof TitleScreen)) return;
                launched = true;
                mc.options.pauseOnLostFocus = false;
                mc.createWorldOpenFlows().createFreshLevel("VisualSettings-" + System.currentTimeMillis(),
                        new LevelSettings("Visual settings QA", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                                new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(4321, false, false),
                        WorldPresets::createNormalWorldDimensions, mc.screen);
                return;
            }
            if (mc.level == null || mc.player == null || ++settle < 30) return;
            settle = 0;
            switch (stage++) {
                case 0 -> {
                    Files.createDirectories(Path.of("visual-settings-qa"));
                    mc.options.guiScale().set(2); mc.resizeDisplay(); mc.setScreen(null);
                    mc.options.hideGui = false; mc.options.bobView().set(false);
                    mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE); mc.getToasts().clear();
                    mc.player.getAbilities().flying = true;
                    mc.player.setPos(mc.player.getX(), 230, mc.player.getZ());
                    impact = mc.player.position().add(0, -30, 45);
                    var target = BlockPos.containing(impact).below();
                    mc.getSingleplayerServer().execute(() -> {
                        var level = mc.getSingleplayerServer().overworld(); level.getChunk(target);
                        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++)
                            level.setBlock(target.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                    });
                    verifyParticles(mc); verifyHud(mc); verifyRocketMarker(mc); verifyCamera(mc);
                }
                case 1 -> {
                    require(get(ClientBombSight.class, "meshAnchor") != null, "Enabled bomb circle did not generate world geometry");
                    snapshot("01-default-hud-and-bomb-circle");
                    ClientVisualConfig.HUD_TEXT_COLOR.set("#00FF00");
                }
                case 2 -> {
                    snapshot("02-custom-green-hud");
                    ClientVisualConfig.SHOW_AIRCRAFT_HUD.set(false);
                    ClientVisualConfig.SHOW_BOMB_INDICATOR.set(false);
                    set(ClientBombSight.class, "meshAnchor", null);
                }
                case 3 -> {
                    require(get(ClientBombSight.class, "meshAnchor") == null, "Disabled bomb circle still generated world geometry");
                    snapshot("03-hidden-text-and-bomb-circle");
                    ClientVisualConfig.HUD_TEXT_COLOR.set(ClientVisualConfig.DEFAULT_HUD_COLOR);
                    ClientVisualConfig.SHOW_AIRCRAFT_HUD.set(true); ClientVisualConfig.SHOW_BOMB_INDICATOR.set(true);
                    ClientVisualConfig.SPEC.save();
                    var menu = new FireControlConfigMenu(new TitleScreen()); mc.setScreen(menu);
                    menu.openSpec("visuals", ClientVisualConfig.SPEC, false);
                }
                case 4 -> { snapshot("04-visual-categories"); finish(null); }
                default -> throw new AssertionError("Unexpected visual stage");
            }
        } catch (Throwable failure) { finish(failure); }
    }
    private static void prepareFrame() throws Exception {
        var mc = Minecraft.getInstance(); if (mc.player == null || mc.level == null || stage >= 4) return;
        set(ClientAimState.class, "active", true); set(ClientAimState.class, "remoteControl", true);
        set(ClientAimState.class, "seatedAtBoundController", false); set(ClientAimState.class, "aircraftEquipment", true);
        set(ClientAimState.class, "headAim", false); set(ClientAimState.class, "scoped", false); set(ClientAimState.class, "podView", false);
        set(ClientRadarState.class, "active", true); set(ClientRadarState.class, "aircraftMode", true);
        set(ClientRadarState.class, "origin", mc.player.position()); set(ClientRadarState.class, "ownVelocity", new Vec3(2, 0, 0));
        set(ClientRadarState.class, "selectedAircraftWeapon", WeaponType.AIM_120D.ordinal()); set(ClientRadarState.class, "aim120dAmmo", 8);
        mc.options.setCameraType(CameraType.FIRST_PERSON); mc.player.setYRot(0); mc.player.setXRot(32);
        mc.player.setDeltaMovement(Vec3.ZERO);
        ClientBombSight.accept(new BombSightPayload(WeaponType.BOMB.ordinal(), 4, 4, null, impact));
    }
    private static void verifyParticles(Minecraft mc) throws Exception {
        for (double scale : new double[]{1, .5, 2}) {
            ClientVisualConfig.SMOKE_LIFETIME_MULTIPLIER.set(scale);
            for (String type : List.of("newsmoke_2", "newsmoke_3", "ballistic_flight_smoke")) {
                Particle p = particle(mc, type); int life = lifetime(p);
                if (type.equals("ballistic_flight_smoke")) require(life >= Math.round(420 * scale) && life <= Math.round(539 * scale), "Ballistic random lifetime not scaled");
                else require(life == Math.round((type.equals("newsmoke_2") ? 124 : 134) * scale), "Native smoke lifetime not scaled: " + type);
                int ticks = 0; while (p.isAlive() && ticks < life + 3) { p.tick(); ticks++; }
                require(!p.isAlive() && ticks >= life && ticks <= life + 1, "Particle did not actually live for its configured duration: " + type);
            }
            Particle launch = particle(mc, "ballistic_launch_smoke");
            require(lifetime(launch) >= 280 && lifetime(launch) <= 379, "Ground launch cloud was altered by the tail-smoke setting"); launch.remove();
            VirtualRocketParticles.clear();
            VirtualRocketParticles.trail(mc.level, mc.player.position().add(0, 15, 0), new Vec3(1, 0, 0), mc.player.position().add(0, 15, 0));
            var particles = (List<?>) get(VirtualRocketParticles.class, "PARTICLES");
            require(particles.size() == 2 && lifetime((Particle) particles.getLast()) == Math.round(32 * scale), "Optimized rocket smoke ignored multiplier");
            VirtualRocketParticles.clear();
        }
        for (String type : List.of("newsmoke_2", "newsmoke_3")) {
            ClientVisualConfig.SMOKE_LIFETIME_MULTIPLIER.set(1.0); Particle original = particle(mc, type);
            ClientVisualConfig.SMOKE_LIFETIME_MULTIPLIER.set(2.0); Particle stretched = particle(mc, type);
            for (int i = 0; i < 60; i++) original.tick();
            for (int i = 0; i < 120; i++) stretched.tick();
            var sprite = TextureSheetParticle.class.getDeclaredField("sprite"); sprite.setAccessible(true);
            require(sprite.get(original) == sprite.get(stretched), "Native smoke animation did not stretch with lifetime");
            original.remove(); stretched.remove();
        }
        ClientVisualConfig.SMOKE_LIFETIME_MULTIPLIER.set(1.0);
        StabilizedTurret.LOGGER.info("VISUAL SMOKE PASS: installed providers at 1x/0.5x/2x, actual tick expiry, random ballistic lifetime, animation stretching, optimized rocket smoke and unchanged launch clouds");
    }
    private static Particle particle(Minecraft mc, String name) {
        var options = (ParticleOptions) BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", name));
        return ((NativeMissileParticleFactory) mc.particleEngine).createFireControl$makeParticle(options,
                mc.player.getX(), 260, mc.player.getZ(), 0, 0, 0);
    }
    private static int lifetime(Particle p) { require(p != null, "Native particle provider missing"); return ((ParticleAccessor) p).createFireControl$getLifetime(); }
    private static void verifyHud(Minecraft mc) throws Exception {
        var draw = RadarHudRenderer.class.getDeclaredMethod("renderAircraftFlightInfo", GuiGraphics.class, Minecraft.class, int.class, int.class); draw.setAccessible(true);
        var g = new RecordingGraphics(mc);
        ClientVisualConfig.SHOW_AIRCRAFT_HUD.set(true); draw.invoke(null, g, mc, 8, 8);
        require(g.colors.size() == 7 && g.colors.stream().allMatch(c -> c == 0xEEDDE5E8), "Default HUD text changed");
        ClientVisualConfig.SHOW_AIRCRAFT_HUD.set(false); g.colors.clear(); draw.invoke(null, g, mc, 8, 8);
        require(g.colors.isEmpty(), "HUD-off still draws flight or weapon text");
        ClientVisualConfig.SHOW_AIRCRAFT_HUD.set(true); ClientVisualConfig.HUD_TEXT_COLOR.set("#00FF00"); draw.invoke(null, g, mc, 8, 8);
        require(g.colors.size() == 7 && g.colors.stream().allMatch(c -> c == 0xEE00FF00), "HUD text colour did not reach all text draws");
        ClientVisualConfig.HUD_TEXT_COLOR.set(ClientVisualConfig.DEFAULT_HUD_COLOR);
    }
    private static void verifyRocketMarker(Minecraft mc) throws Exception {
        set(ClientAimState.class, "active", true); set(ClientAimState.class, "seatedAtBoundController", true);
        set(ClientAimState.class, "rocketImpactAvailable", true);
        Vec3 point = mc.gameRenderer.getMainCamera().getPosition().add(new Vec3(mc.gameRenderer.getMainCamera().getLookVector()).scale(50));
        set(ClientAimState.class, "rocketImpactPoint", point); set(ClientAimState.class, "previousRocketImpactPoint", point);
        var draw = ThirdPersonAimHudRenderer.class.getDeclaredMethod("renderRocketImpactMarker", GuiGraphics.class, Minecraft.class, int.class, int.class, float.class); draw.setAccessible(true);
        var g = new RecordingGraphics(mc);
        ClientVisualConfig.SHOW_ROCKET_IMPACT.set(false); draw.invoke(null, g, mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), 1F);
        require(g.fills == 0, "Disabled rocket impact marker still draws");
        ClientVisualConfig.SHOW_ROCKET_IMPACT.set(true); draw.invoke(null, g, mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), 1F);
        require(g.fills > 0, "Enabled rocket impact marker did not draw");
        set(ClientAimState.class, "seatedAtBoundController", false); set(ClientAimState.class, "rocketImpactAvailable", false);
    }
    private static void verifyCamera(Minecraft mc) throws Exception {
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        set(ClientAimState.class, "active", true); set(ClientAimState.class, "seatedAtBoundController", true); set(ClientAimState.class, "headAim", true);
        set(ClientAimState.class, "scoped", false); set(ClientAimState.class, "podView", false);
        set(ClientAimState.class, "viewAimOffsetX", 0.0); set(ClientAimState.class, "viewAimOffsetY", 0.0);
        set(ClientAimState.class, "stableWorldYaw", 40F); set(ClientAimState.class, "stableWorldPitch", -10F);
        var solve = ClientAimState.class.getDeclaredMethod("solveCameraAnglesForReticle", Minecraft.class, float.class, float.class); solve.setAccessible(true);
        Object desired = solve.invoke(null, mc, 40F, -10F);
        var yaw = desired.getClass().getDeclaredMethod("yaw"); yaw.setAccessible(true);
        var pitch = desired.getClass().getDeclaredMethod("pitch"); pitch.setAccessible(true);
        for (double delay : new double[]{0, .125}) {
            ClientVisualConfig.THIRD_PERSON_CAMERA_DELAY.set(delay);
            set(ClientAimState.class, "thirdPersonCameraFollowInitialized", true);
            set(ClientAimState.class, "thirdPersonCameraYaw", 0F); set(ClientAimState.class, "thirdPersonCameraPitch", 0F);
            set(ClientAimState.class, "lastThirdPersonCameraFollowNanos", System.nanoTime() - 50_000_000);
            ClientAimState.updateHeadAimThirdPersonCamera(mc.player, 0, 0);
            float current = ClientAimState.getHeadAimThirdPersonCameraYaw(), target = (float) yaw.invoke(desired);
            if (delay == 0) require(Math.abs(Mth.wrapDegrees(current - target)) < .001
                    && Math.abs(ClientAimState.getHeadAimThirdPersonCameraPitch() - (float) pitch.invoke(desired)) < .001, "Zero-lag camera did not reach its reticle target in one update");
            else require(Math.abs(current) > .01 && Math.abs(current) < Math.abs(target), "Default camera lost its original lag");
        }
        set(ClientAimState.class, "seatedAtBoundController", false); set(ClientAimState.class, "headAim", false);
    }
    private static final class RecordingGraphics extends GuiGraphics {
        final List<Integer> colors = new ArrayList<>(); int fills;
        RecordingGraphics(Minecraft mc) { super(mc, mc.renderBuffers().bufferSource()); }
        @Override public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) { colors.add(color); return 0; }
        @Override public int drawString(Font font, String text, int x, int y, int color, boolean shadow) { colors.add(color); return 0; }
        @Override public void fill(int x1, int y1, int x2, int y2, int color) { fills++; }
    }
    private static Object get(Class<?> owner, String name) throws Exception { var f = owner.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
    private static void set(Class<?> owner, String name, Object value) throws Exception { var f = owner.getDeclaredField(name); f.setAccessible(true); f.set(null, value); }
    private static void snapshot(String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) { image.writeToFile(Path.of("visual-settings-qa", name + ".png")); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void finish(Throwable failure) {
        finished = true;
        if (failure != null) StabilizedTurret.LOGGER.error("VISUAL SETTINGS CHECK FAILED", failure);
        try { Files.writeString(Path.of("visual-settings-client-result.txt"), failure == null
                ? "PASS: actual particle providers/expiry/animation, optimized smoke, unchanged native defaults, HUD text visibility/colours, rocket marker visibility, bomb world circle visibility, zero-lag and default camera following"
                : "FAIL: " + failure); } catch (Exception io) { io.printStackTrace(); }
        Minecraft.getInstance().stop();
    }
}
