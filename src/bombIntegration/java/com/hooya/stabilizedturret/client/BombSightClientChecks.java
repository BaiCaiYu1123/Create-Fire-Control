package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import com.hooya.stabilizedturret.network.BombSightPayload;
import com.hooya.stabilizedturret.network.BombFlightPayload;
import com.hooya.stabilizedturret.content.controller.MianbaoBombCompat;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.client.CameraType;
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
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Captures the actual HUD in both camera modes and after selecting a missile. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class BombSightClientChecks {
    private static int age, stage, wait;
    private static boolean launched, finished;
    private static volatile boolean platformReady;
    private static Vec3 impact;
    private static ServerSubLevel obstruction;
    private static int obstructionAge;
    private static final java.util.List<java.util.UUID> MODEL_IDS = java.util.stream.IntStream.range(0, 13)
            .mapToObj(i -> java.util.UUID.nameUUIDFromBytes(("bomb-model-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList();

    @SubscribeEvent
    public static void serverTick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.bombClientChecks") || obstruction == null || platformReady || finished) return;
        try {
            // Wait for the native body to register before locking it above the terrain probes.
            if (++obstructionAge < 5) return;
            var pose = obstruction.logicalPose();
            var handle = SubLevelPhysicsSystem.require(event.getServer().overworld()).getPipeline().addConstraint(null, obstruction,
                    new FixedConstraintConfiguration(pose.position(), pose.rotationPoint(), pose.orientation()));
            require(handle != null && handle.isValid(), "Could not pin physical obstruction above ground ring");
            platformReady = true;
        } catch (Throwable failure) { finish(Minecraft.getInstance(), failure); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void frame(RenderFrameEvent.Pre event) {
        if (!Boolean.getBoolean("create_fire_control.bombClientChecks") || stage == 0 || finished) return;
        try { prepareFrame(Minecraft.getInstance()); }
        catch (Throwable failure) { finish(Minecraft.getInstance(), failure); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.bombClientChecks") || finished) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            age++;
            if (!launched && age > 80 && mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                mc.setScreen(new TitleScreen()); return;
            }
            if (!launched && age > 80 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                launched = true;
                mc.createWorldOpenFlows().createFreshLevel("BombSight-" + System.currentTimeMillis(),
                        new LevelSettings("Bomb sight", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                                new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(1234, false, false),
                        WorldPresets::createNormalWorldDimensions, mc.screen);
                return;
            }
            if (mc.player == null || mc.level == null) return;
            if (stage == 0) {
                if (++wait < 70) return;
                mc.setScreen(null);
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                mc.getToasts().clear();
                mc.getWindow().setWindowed(1280, 720); mc.options.guiScale().set(2); mc.resizeDisplay();
                mc.options.hideGui = false; mc.options.bobView().set(false); mc.options.pauseOnLostFocus = false;
                mc.player.getAbilities().flying = true;
                mc.player.setPos(mc.player.getX(), 180, mc.player.getZ());
                mc.player.setYRot(0); mc.player.setXRot(36);
                impact = new Vec3(Math.floor(mc.player.getX()) + .5, 121, Math.floor(mc.player.getZ()) + 80.5);
                Vec3 target = impact;
                mc.getSingleplayerServer().execute(() -> {
                    try {
                        var level = mc.getSingleplayerServer().overworld();
                        var base = net.minecraft.core.BlockPos.containing(target).below();
                        level.getChunk(base);
                        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
                            level.setBlock(base.offset(x, 0, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                        }
                        var original = base.offset(4, 4, 0);
                        level.setBlockAndUpdate(original, net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState());
                        var assembly = SimAssemblyHelper.assembleFromSingleBlock(level, original.below(), original, false, false);
                        require(assembly != null, "Could not assemble physical obstruction for marker regression");
                        obstruction = (ServerSubLevel) assembly.subLevel();
                        var local = original.offset(assembly.offset());
                        for (int x = -1; x <= 1; x++) for (int z = -3; z <= 3; z++) {
                            obstruction.getLevel().setBlock(local.offset(x, 0, z), net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState(), 3);
                        }
                        ((ServerSubLevelContainer) SubLevelContainer.getContainer(level)).addForceLoadTicket(
                                obstruction, SubLevelLoadingTicketType.COMMAND_FORCED, net.minecraft.util.Unit.INSTANCE);
                    } catch (Throwable failure) { mc.execute(() -> finish(mc, failure)); }
                });
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                stage = 1; wait = 0;
            }
            prepareFrame(mc);
            if (!platformReady || stage < 6 && !mc.level.getBlockState(net.minecraft.core.BlockPos.containing(impact).below()).is(net.minecraft.world.level.block.Blocks.STONE)) return;
            if (++wait < 25) return;
            if (stage == 1) {
                verifyObstructedGround(mc);
                require(ClientBombSight.selectedBomb() == WeaponType.BOMB, "First-person bomb selection missing");
                require(mc.options.getCameraType() == CameraType.FIRST_PERSON, "First-person fixture camera changed");
                capture(mc, "first-person-3d");
                // Keep the world-space ring beside the avatar, so depth occlusion does not hide it in the QA image.
                mc.player.setYRot(-15); mc.player.setXRot(30);
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK); stage = 2; wait = 0;
            } else if (stage == 2) {
                require(ClientBombSight.selectedBomb() == WeaponType.BOMB, "Third-person bomb selection missing");
                require(mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK, "Third-person fixture camera changed");
                capture(mc, "third-person-3d"); stage = 3; wait = 0;
            } else if (stage == 3) {
                require(ClientBombSight.selectedBomb() == null, "Missile selection kept bomb marker");
                capture(mc, "missile-selected-3d");
                mc.player.setYRot(0); mc.player.setXRot(10); stage = 4; wait = 0;
            } else if (stage == 4) {
                var field = VirtualBombVisuals.class.getDeclaredField("ACTIVE"); field.setAccessible(true);
                var models = (java.util.Map<?, ?>) field.get(null);
                require(models.size() == 13, "Native mathematical bomb models missing");
                for (Object model : models.values()) {
                    var proxyField = model.getClass().getDeclaredField("proxy"); proxyField.setAccessible(true);
                    var proxy = (net.minecraft.world.entity.Entity) proxyField.get(model);
                    require(mc.level.getEntity(proxy.getId()) == null, "Render-only bomb entered the client entity manager");
                }
                capture(mc, "mathematical-bomb-models-no-smoke"); stage = 5; wait = 0;
            } else if (stage == 5) {
                var field = VirtualBombVisuals.class.getDeclaredField("ACTIVE"); field.setAccessible(true);
                require(((java.util.Map<?, ?>) field.get(null)).isEmpty(), "Removed mathematical bomb models remained visible");
                capture(mc, "mathematical-bomb-models-removed");
                mc.getWindow().setWindowed(2560, 1440); mc.resizeDisplay(); stage = 6; wait = 0;
            } else {
                String name = switch (stage) {
                    case 6 -> "fixed-size-2k-first-near";
                    case 7 -> "fixed-size-2k-first-far";
                    case 8 -> "fixed-size-2k-first-far-zoom";
                    case 9 -> "fixed-size-2k-third-near";
                    default -> "fixed-size-2k-third-far";
                };
                require(ClientBombSight.selectedBomb() == WeaponType.BOMB, "Fixed-size marker lost bomb selection");
                capture(mc, name);
                if (stage == 10) finish(mc, null); else { stage++; wait = 0; }
            }
        } catch (Throwable failure) { finish(mc, failure); }
    }
    private static void verifyObstructedGround(Minecraft mc) {
        int[] projectedHits = {0};
        var ring = BombSightGeometry.create(impact, point -> {
            var raw = mc.level.clip(new net.minecraft.world.level.ClipContext(point.add(0, 8, 0), point.add(0, -8, 0),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()));
            if (raw.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                    && (Math.abs(raw.getLocation().x - point.x) > 1 || Math.abs(raw.getLocation().z - point.z) > 1)) projectedHits[0]++;
            Vec3 ground = ClientBombSight.groundPoint(mc.level, point);
            require(ground.distanceTo(point.add(0, BombSightGeometry.SURFACE_LIFT, 0)) < 1.0e-6,
                    "Aircraft plot coordinates or height contaminated the ground marker: " + ground);
            return ground;
        });
        require(projectedHits[0] > 0, "Regression fixture did not reproduce projected physical-body ray hits");
        require(ring.size() == 72, "Physical body damaged the ground ring");
        StabilizedTurret.LOGGER.info("BOMB HUD CHECK PASS: {} projected body hits excluded from ground ring", projectedHits[0]);
    }
    private static void prepareFrame(Minecraft mc) throws ReflectiveOperationException {
        if (mc.player == null || mc.level == null) return;
        setFlag("active", true); setFlag("seatedAtBoundController", false); setFlag("aircraftEquipment", true);
        // This visual fixture has no seat; retain vanilla third person while testing the shared HUD.
        setFlag("remoteControl", true);
        mc.options.setCameraType(stage == 1 || stage >= 4 && stage < 9 ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
        boolean bomb = stage < 3 || stage >= 6;
        ClientBombSight.accept(new BombSightPayload(bomb ? WeaponType.BOMB.ordinal() : WeaponType.FAR.ordinal(), 3, 8, null, bomb ? impact : null));
        if (stage >= 6) {
            double distance = stage == 6 || stage == 9 ? 50 : 500;
            mc.player.setPos(impact.x, impact.y + distance * .6 - mc.player.getEyeHeight(), impact.z - distance * .8);
            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.setYRot(stage >= 9 ? -25 : 0); mc.player.setXRot((float) Math.toDegrees(Math.atan2(.6, .8)));
            mc.options.fov().set(stage == 8 ? 30 : 70);
        }
        if (stage == 4) {
            java.util.List<BombFlightPayload.State> models = new java.util.ArrayList<>();
            int i = 0;
            for (WeaponType type : WeaponType.values()) if (MianbaoBombCompat.isBomb(type)) {
                Vec3 point = mc.player.position().add((i % 5 - 2) * 3, 1 - i / 5 * 3, 16);
                models.add(new BombFlightPayload.State(MODEL_IDS.get(i++), MianbaoBombCompat.entityType(type), point,
                        new Vec3(.15, -.15, .4), 40, false, Vec3.ZERO, java.util.List.of(), false));
            }
            VirtualBombVisuals.accept(new BombFlightPayload(mc.level.dimension().location(), mc.level.getGameTime(), models, java.util.List.of()));
        } else if (stage == 5) {
            VirtualBombVisuals.accept(new BombFlightPayload(mc.level.dimension().location(), mc.level.getGameTime(), java.util.List.of(), MODEL_IDS));
        }
    }
    private static void setFlag(String field, boolean value) throws ReflectiveOperationException {
        var flag = ClientAimState.class.getDeclaredField(field); flag.setAccessible(true); flag.setBoolean(null, value);
    }
    private static void capture(Minecraft mc, String name) throws Exception {
        Files.createDirectories(Path.of("bomb-sight-qa"));
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(Path.of("bomb-sight-qa", name + ".png")); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void finish(Minecraft mc, Throwable failure) {
        finished = true;
        if (failure != null) StabilizedTurret.LOGGER.error("BOMB HUD CHECK FAILED", failure);
        try { Files.writeString(Path.of("bomb-client-result.txt"), failure == null ? "PASS" : "FAIL: " + failure); }
        catch (Exception error) { throw new RuntimeException(error); }
        finally { mc.stop(); }
    }
}
