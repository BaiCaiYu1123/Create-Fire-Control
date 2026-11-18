package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.bearing.YawLimits;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.ScopeOrientationMath;
import com.hooya.stabilizedturret.content.controller.ScopeOrientationResolver;
import com.hooya.stabilizedturret.content.controller.ScopePreset;
import com.hooya.stabilizedturret.network.ScopeOrientationPayload;
import com.hooya.stabilizedturret.network.ScopeAmmoPayload;
import com.hooya.stabilizedturret.content.loader.ScopeAmmoIntegration;
import com.hooya.stabilizedturret.network.ScopeOrientationPayload.Heading;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ScopeOrientationClientChecks {
    private static int age, wait, stage;
    private static boolean launched, finished;
    private static CompletableFuture<ScopeOrientationPayload> fixture;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.scopeOrientationChecks") || finished) return;
        var mc = Minecraft.getInstance();
        try {
            if (++age > 1400) throw new AssertionError("scope orientation client timed out");
            if (!launched && age > 60 && mc.screen != null
                    && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                mc.setScreen(new TitleScreen()); return;
            }
            if (!launched && age > 60 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                launched = true;
                mc.createWorldOpenFlows().createFreshLevel("ScopeOrientation-" + System.currentTimeMillis(),
                        new LevelSettings("Scope orientation QA", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                                true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234, false, false), WorldPresets::createNormalWorldDimensions, mc.screen);
                return;
            }
            if (mc.level == null || mc.player == null) return;
            if (fixture == null) {
                fixture = mc.getSingleplayerServer().submit(() -> physicalFixture(mc.getSingleplayerServer().overworld()));
                return;
            }
            if (!fixture.isDone() || ++wait < 30) return;
            wait = 0;
            set("active", true); set("scoped", true); set("podView", false);
            set("scopePosition", mc.player.getEyePosition().add(0, 80, 0));
            set("previousScopePosition", mc.player.getEyePosition().add(0, 80, 0));
            set("scopePoseRelative", false);
            set("stableWorldYaw", 0.0F); set("stableWorldPitch", 0.0F);
            if (stage > 0) screenshot(mc, "stage-" + stage);
            if (stage == 0) {
                mc.options.guiScale().set(2); mc.resizeDisplay(); mc.setScreen(null);
                ClientScopeOrientation.accept(fixture.join());
                require(ClientScopeOrientation.angles(1) != null, "live Sable heading references unavailable");
                if (Boolean.getBoolean("create_fire_control.scopeAmmoChecks")) {
                    ScopeAmmoHud.accept(new ScopeAmmoPayload(22));
                    require(ScopeAmmoHud.visible(), "APFSDS scope badge missing");
                }
            } else if (stage <= 4) {
                double[][] cases = {{0, 0}, {0, 60}, {45, 0}, {0, 180}};
                double[] pose = cases[stage - 1];
                ClientScopeOrientation.clear();
                ClientScopeOrientation.accept(new ScopeOrientationPayload(heading(pose[0]), heading(pose[1])));
                var angles = ClientScopeOrientation.angles(1);
                close(0, ScopeOrientationMath.wrap(angles.hull() - pose[0]), "hull angle");
                close(0, ScopeOrientationMath.wrap(angles.turret() - pose[1]), "turret angle");
                if (stage == 4) set("thermal", true);
                if (Boolean.getBoolean("create_fire_control.scopeAmmoChecks"))
                    ScopeAmmoHud.accept(new ScopeAmmoPayload(new int[]{17, 0, -1, 9}[stage - 1]));
            } else if (stage == 5) {
                mc.options.guiScale().set(3); mc.resizeDisplay();
                set("scopePreset", ScopePreset.CHINESE);
                if (Boolean.getBoolean("create_fire_control.scopeAmmoChecks")) ScopeAmmoHud.accept(new ScopeAmmoPayload(8));
            } else {
                set("podView", true);
                require(!ScopeAmmoHud.visible(), "pod view displays ammunition badge");
                require(ClientScopeOrientation.angles(1) == null, "pod view displays tank HUD");
                set("podView", false); set("scoped", false);
                require(!ScopeAmmoHud.visible(), "unscoped view displays ammunition badge");
                require(ClientScopeOrientation.angles(1) == null, "unscoped view displays tank HUD");
                ClientScopeOrientation.clear();
                ScopeAmmoHud.clear();
                finish(mc, null);
            }
            stage++;
        } catch (Throwable error) { finish(mc, error); }
    }

    private static ScopeOrientationPayload physicalFixture(ServerLevel level) {
        if (Boolean.getBoolean("create_fire_control.scopeAmmoChecks")) {
            try { ScopeAmmoIntegration.check(level); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }
        var root = new BlockPos(8, 200, 8);
        level.setChunkForced(0, 0, true);
        var hullJoint = assemble(level, root);
        var yaw = assemble(level, hullJoint.getPlatePos().above(2));
        var body = SubLevelContainer.getContainer(level).getSubLevel(yaw.getSubLevelID());
        var barrel = ScopeOrientationMath.rotate(body.logicalPose().orientation(), new Vec3(0, 0, 1));
        var sample = ScopeOrientationResolver.measure(level, BindingRef.capture(level, yaw.getBlockPos()),
                barrel, body.getUniqueId(), YawLimits.UNLIMITED);
        require(sample.hull() != null && sample.turret() != null, "assembled nested bearings have no headings");
        require(hullJoint.getSubLevelID().equals(sample.hull().bodyId()), "hull heading follows the wrong physical body");
        require(yaw.getSubLevelID().equals(sample.turret().bodyId()), "turret heading follows the wrong physical body");
        return sample;
    }

    private static SwivelBearingBlockEntity assemble(ServerLevel level, BlockPos pos) {
        level.getChunk(pos);
        level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing"))
                .defaultBlockState().setValue(SwivelBearingBlock.FACING, Direction.UP));
        level.setBlockAndUpdate(pos.above(), Blocks.IRON_BLOCK.defaultBlockState());
        var bearing = (SwivelBearingBlockEntity) level.getBlockEntity(pos);
        bearing.assemble();
        require(bearing.isAssembled(), "fixture bearing failed to assemble");
        return bearing;
    }

    private static Heading heading(double degrees) {
        double radians = Math.toRadians(degrees);
        Vec3 direction = new Vec3(-Math.sin(radians), 0, Math.cos(radians));
        return new Heading(null, direction, direction);
    }
    private static void set(String name, Object value) throws Exception {
        var field = ClientAimState.class.getDeclaredField(name);
        field.setAccessible(true); field.set(null, value);
    }
    private static void screenshot(Minecraft mc, String name) throws Exception {
        var folder = Path.of("scope-orientation-qa"); Files.createDirectories(folder);
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(folder.resolve(name + ".png"));
        }
    }
    private static void finish(Minecraft mc, Throwable failure) {
        finished = true;
        try { Files.writeString(Path.of("scope-orientation-client-result.txt"), failure == null ? "PASS" : "FAIL: " + failure); }
        catch (Exception error) { throw new RuntimeException(error); }
        if (failure != null) StabilizedTurret.LOGGER.error("SCOPE ORIENTATION CLIENT FAILED", failure);
        mc.stop();
    }
    private static void close(double expected, double actual, String message) {
        require(Math.abs(expected - actual) < 1e-7, message + ": " + actual);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
