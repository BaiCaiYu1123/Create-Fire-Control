package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
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
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Pixel checks against a real Sable turret with Iris/Photon active, without thermal imaging. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class VirtualScopeShaderClientChecks {
    private record Fixture(UUID body, Vec3 camera) {}
    private static int age, wait, stage;
    private static int[] visiblePixels, hiddenPixels;
    private static boolean launched, finished;
    private static CompletableFuture<Fixture> pending;
    private static Fixture fixture;
    private static Object shaderEmbedding;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.virtualScopeShaderChecks") || finished) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (++age > 2400) throw new AssertionError("Shader visibility check timed out");
            if (!launched && age > 60 && mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                mc.setScreen(new TitleScreen()); return;
            }
            if (!launched && age > 60 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                launched = true;
                mc.createWorldOpenFlows().createFreshLevel("VirtualScopeShader-" + System.currentTimeMillis(),
                        new LevelSettings("Virtual scope shader QA", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                                true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234, false, false), WorldPresets::createNormalWorldDimensions, mc.screen);
                return;
            }
            if (mc.level == null || mc.player == null) return;
            if (pending == null) {
                var server = mc.getSingleplayerServer();
                pending = server.submit(() -> {
                    var level = server.overworld();
                    level.setDayTime(6000);
                    var pos = new BlockPos(0, 220, 8);
                    level.setChunkForced(0, 0, true);
                    level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing"))
                            .defaultBlockState().setValue(SwivelBearingBlock.FACING, Direction.UP));
                    level.setBlockAndUpdate(pos.above(), Blocks.IRON_BLOCK.defaultBlockState());
                    var bearing = (SwivelBearingBlockEntity) level.getBlockEntity(pos);
                    bearing.assemble();
                    require(bearing.isAssembled(), "fixture bearing must assemble");
                    boolean colorwheel = Boolean.getBoolean("create_fire_control.scopeColorwheelChecks");
                    var fixtureBlock = colorwheel
                            ? BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:cogwheel")).defaultBlockState()
                            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.Z)
                            : Blocks.RED_CONCRETE.defaultBlockState();
                    for (int x = -2; x <= 2; x++) for (int y = 1; y <= 4; y++)
                        level.setBlockAndUpdate(bearing.getPlatePos().offset(x, y, 0), fixtureBlock);
                    var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    player.teleportTo(.5, 221.9, -6);
                    player.setYRot(0);
                    player.setXRot(0);
                    return new Fixture(bearing.getSubLevelID(), new Vec3(.5, 223.5, -6));
                });
                return;
            }
            if (!pending.isDone()) return;
            if (fixture == null) fixture = pending.join();
            var body = SubLevelContainer.getContainer(mc.level).getSubLevel(fixture.body());
            if (body == null) return;
            mc.setScreen(null);
            mc.options.hideGui = true;
            set("active", true);
            set("scoped", stage < 3);
            set("headAim", false);
            set("thermal", false);
            set("scopePosition", fixture.camera());
            set("previousScopePosition", fixture.camera());
            set("scopePoseRelative", false);
            set("stableWorldYaw", 0.0F);
            set("stableWorldPitch", 0.0F);
            set("trackedRenderInterpolation", false);
            if (++wait < (stage == 0 ? 160 : 50)) return;
            wait = 0;
            require(ThermalRenderMode.isShaderPackInUseNow(), "Iris shader pack must stay active");
            require(!ClientAimState.isThermal(), "thermal must be off for this regression");
            if (stage == 0) {
                if (Boolean.getBoolean("create_fire_control.scopeColorwheelChecks")) {
                    var manager = dev.engine_room.flywheel.api.visualization.VisualizationManager.get(mc.level).blockEntities();
                    Object storage = manager.getClass().getMethod("getStorage").invoke(manager);
                    var info = ((dev.ryanhcode.sable.neoforge.mixinterface.compatibility.flywheel.BlockEntityStorageExtension) storage)
                            .sable$getEmbeddingInfo(body);
                    require(info != null, "real cogwheel instances must have a Sable embedding");
                    shaderEmbedding = info.embedding();
                    require(shaderEmbedding.getClass().getName().contains("colorwheel"), "Colorwheel backend must own the instances");
                    require(embeddingScale() > 0.1, "visible Colorwheel instances must have a nonzero pose");
                }
                visiblePixels = capture(mc, "shader-visible");
                VirtualScopeVisibility.update(true, List.of(fixture.body()), List.of());
            } else if (stage == 1) {
                hiddenPixels = capture(mc, "shader-hidden-without-thermal");
                require(VirtualScopeVisibility.hides(body), "turret must be in the virtual sight visibility set");
                if (shaderEmbedding != null) require(embeddingScale() < 1.0e-6,
                        "Colorwheel still submits turret instances while ordinary scope is active");
                int changed = changedPixels(visiblePixels, hiddenPixels);
                require(changed > visiblePixels.length / 20, "shader turret was not hidden without thermal: changed pixels=" + changed);
                VirtualScopeVisibility.clear();
            } else if (stage == 2) {
                int[] restored = capture(mc, "shader-restored");
                if (shaderEmbedding != null) require(embeddingScale() > 0.1, "Colorwheel pose must restore on scope release");
                require(changedPixels(restored, hiddenPixels) > visiblePixels.length / 20,
                        "clearing visibility must restore shader turret");
                VirtualScopeVisibility.update(true, List.of(fixture.body()), List.of());
            } else {
                require(!VirtualScopeVisibility.hides(body), "leaving scope must restore visibility immediately");
                finish(mc, null);
            }
            stage++;
        } catch (Throwable failure) { finish(mc, failure); }
    }

    private static int[] capture(Minecraft mc, String name) throws Exception {
        Files.createDirectories(Path.of("virtual-scope-shader-qa"));
        int red = 0;
        int[] pixels;
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(Path.of("virtual-scope-shader-qa", name + ".png"));
            pixels = new int[(image.getHeight() / 2) * (image.getWidth() / 2)];
            int index = 0;
            for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++)
                for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                    int color = image.getPixelRGBA(x, y);
                    pixels[index++] = color;
                    int r = color & 255, g = color >> 8 & 255, b = color >> 16 & 255;
                    if (r > 50 && r > g * 1.5 && r > b * 1.3) red++;
                }
        }
        System.out.println("VIRTUAL SCOPE SHADER PIXELS " + name + "=" + red);
        return pixels;
    }
    private static int changedPixels(int[] first, int[] other) {
        int changed = 0;
        for (int i = 0; i < first.length; i++) {
            int difference = 0;
            for (int channel = 0; channel < 3; channel++)
                difference += Math.abs((first[i] >> (channel * 8) & 255) - (other[i] >> (channel * 8) & 255));
            if (difference > 24) changed++;
        }
        System.out.println("VIRTUAL SCOPE SHADER CHANGED PIXELS=" + changed + "/" + first.length);
        return changed;
    }
    private static double embeddingScale() throws Exception {
        var pose = (org.joml.Matrix4f) shaderEmbedding.getClass().getField("pose").get(shaderEmbedding);
        return pose.getScale(new org.joml.Vector3f()).length();
    }
    private static void set(String name, Object value) throws Exception {
        Field field = ClientAimState.class.getDeclaredField(name);
        field.setAccessible(true); field.set(null, value);
    }
    private static void finish(Minecraft mc, Throwable failure) {
        finished = true;
        VirtualScopeVisibility.clear();
        try { Files.writeString(Path.of("virtual-scope-client-result.txt"), failure == null ? "PASS" : "FAIL: " + failure); }
        catch (Exception error) { throw new RuntimeException(error); }
        if (failure != null) StabilizedTurret.LOGGER.error("VIRTUAL SCOPE SHADER CLIENT FAILED", failure);
        mc.stop();
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
