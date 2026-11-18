package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.bearing.YawLimits;
import com.hooya.stabilizedturret.network.OpenControllerPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class VirtualScopeClientChecks {
    private static int age, wait, stage;
    private static boolean launched, finished;
    private static StabilizerControllerScreen screen;
    private static java.util.concurrent.CompletableFuture<UUID> frameBody;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.virtualScopeClientChecks") || finished) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (++age > 1800) throw new AssertionError("Virtual scope client check timed out");
            if (!launched && age > 60 && mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                mc.setScreen(new TitleScreen()); return;
            }
            if (!launched && age > 60 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                launched = true;
                mc.createWorldOpenFlows().createFreshLevel("VirtualScope-" + System.currentTimeMillis(),
                        new LevelSettings("Virtual scope QA", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                                true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234, false, false), WorldPresets::createNormalWorldDimensions, mc.screen);
                return;
            }
            if (mc.level == null || mc.player == null) return;
            if (frameBody == null) {
                var fixturePos = mc.player.blockPosition().above(6);
                frameBody = mc.getSingleplayerServer().submit(() -> {
                    var level = mc.getSingleplayerServer().overworld();
                    var pos = fixturePos;
                    level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
                    level.setBlockAndUpdate(pos, net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                            net.minecraft.resources.ResourceLocation.parse("simulated:swivel_bearing"))
                            .defaultBlockState().setValue(dev.simulated_team.simulated.content.blocks.swivel_bearing
                                    .SwivelBearingBlock.FACING, net.minecraft.core.Direction.UP));
                    level.setBlockAndUpdate(pos.above(), net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState());
                    var bearing = (dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity)
                            level.getBlockEntity(pos);
                    bearing.assemble();
                    require(bearing.isAssembled(), "ranged reticle fixture must have a real physical render pose");
                    return bearing.getSubLevelID();
                });
                return;
            }
            if (!frameBody.isDone() || dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level)
                    .getSubLevel(frameBody.join()) == null || ++wait < 30) return;
            wait = 0;
            if (stage == 0) {
                for (String type : List.of(
                        "dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher",
                        "dev.ryanhcode.sable.sublevel.render.dispatcher.FancySubLevelRenderDispatcher",
                        "dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment",
                        "com.simibubi.create.content.contraptions.render.ContraptionVisual")) {
                    require(Arrays.stream(Class.forName(type).getDeclaredMethods()).anyMatch(m ->
                            m.getName().contains("visibleTurrets") || m.getName().contains("hideTurretInstances")
                                    || m.getName().contains("hideAssembledBarrel")), "render mixin missing on " + type);
                }
                mc.options.guiScale().set(2);
                mc.resizeDisplay();
                screen = new StabilizerControllerScreen(payload(true, ""));
                mc.setScreen(screen);
                page("VIEW");
                checkOffsets(false);
            } else if (stage == 1) {
                screenshot(mc, "offsets-locked");
                page("FIRE_CONTROL");
                require(virtualButton().active, "valid topology toggle must be enabled");
            } else if (stage == 2) {
                screenshot(mc, "fire-control");
                virtualButton().onPress();
                page("VIEW");
                checkOffsets(true);
                set(ClientAimState.class, "active", true);
                set(ClientAimState.class, "scoped", true);
                VirtualScopeVisibility.update(true, List.of(), List.of(mc.player.getUUID()));
                require(VirtualScopeVisibility.hides(mc.player), "explicit assembled-entity UUID is hidden");
                checkVirtualScopeInfinity(mc);
                set(ClientAimState.class, "scoped", false);
                require(!VirtualScopeVisibility.hides(mc.player), "leaving scope immediately restores visibility");
                VirtualScopeVisibility.clear();
                set(ClientAimState.class, "active", false);
                screen = new StabilizerControllerScreen(payload(false, "virtual_scope_not_coaxial"));
                mc.setScreen(screen);
                page("FIRE_CONTROL");
                require(!virtualButton().active, "noncoaxial topology toggle must be disabled");
            } else if (stage == 3) {
                screenshot(mc, "noncoaxial-disabled");
                var reaction = StabilizerControllerScreen.class.getDeclaredField("stabilizerReactionEnabled");
                reaction.setAccessible(true);
                reaction.set(screen, true);
                page("FIRE_CONTROL");
                checkScrollableLayout();
            } else if (stage == 4) {
                screenshot(mc, "fire-control-scroll-scale-2");
                mc.options.guiScale().set(3);
                mc.resizeDisplay();
                page("FIRE_CONTROL");
                checkScrollableLayout();
            } else {
                screenshot(mc, "fire-control-scroll-scale-3");
                // Also cover the panel shrinking horizontally in a narrow window.
                screen.init(mc, 280, 240);
                page("FIRE_CONTROL");
                checkScrollableLayout();
                finish(mc, null);
            }
            stage++;
        } catch (Throwable error) { finish(mc, error); }
    }

    private static OpenControllerPayload payload(boolean enabled, String reason) throws Exception {
        var components = OpenControllerPayload.class.getRecordComponents();
        Object[] values = new Object[components.length];
        for (int i = 0; i < values.length; i++) {
            Class<?> type = components[i].getType();
            values[i] = type == double.class ? 0.0 : type == int.class ? 0 : type == boolean.class ? false
                    : type == BlockPos.class ? BlockPos.ZERO : type == Component.class ? Component.empty()
                    : type == List.class ? List.of() : type == CompoundTag.class ? new CompoundTag()
                    : type == YawLimits.class ? YawLimits.UNLIMITED : type == String.class ? reason : null;
            if (components[i].getName().equals("virtualScopeEnabled")) values[i] = enabled;
        }
        return (OpenControllerPayload) OpenControllerPayload.class.getDeclaredConstructors()[0].newInstance(values);
    }

    @SuppressWarnings({"rawtypes", "unchecked"}) private static void page(String name) throws Exception {
        var field = StabilizerControllerScreen.class.getDeclaredField("page");
        var method = StabilizerControllerScreen.class.getDeclaredMethod("openPage", field.getType());
        method.setAccessible(true);
        method.invoke(screen, Enum.valueOf((Class) field.getType(), name));
    }
    private static Button virtualButton() {
        String label = Component.translatable("screen.create_fire_control.virtual_scope", "").getString().trim();
        return screen.children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().contains(label))
                .map(w -> (Button) w).findFirst().orElseThrow();
    }
    private static void checkOffsets(boolean active) {
        var sliders = screen.children().stream().filter(w -> w.getClass().getSimpleName().equals("OffsetSlider")).toList();
        require(sliders.size() == 3 && sliders.stream().allMatch(w -> ((AbstractWidget) w).active == active), "all three scope sliders follow virtual-scope mode");
        var boxes = screen.children().stream().filter(w -> w instanceof EditBox).toList();
        require(boxes.size() == 4 && boxes.subList(1, 4).stream().allMatch(w -> ((AbstractWidget) w).active == active), "all three numeric offsets follow virtual-scope mode");
    }
    private static void checkVirtualScopeInfinity(Minecraft mc) throws Exception {
        var originPos = new BlockPos((mc.player.getBlockX() & ~15) + 6,
                mc.player.getBlockY() + 6, (mc.player.getBlockZ() & ~15) + 6);
        var obstacle = originPos.east(2);
        var oldBlock = mc.level.getBlockState(obstacle);
        var origin = net.minecraft.world.phys.Vec3.atCenterOf(originPos);
        var camera = origin.add(0, 1, 3);
        Field scope = ClientAimState.class.getDeclaredField("scopePosition"); scope.setAccessible(true);
        Field previous = ClientAimState.class.getDeclaredField("previousScopePosition"); previous.setAccessible(true);
        Object oldScope = scope.get(null), oldPrevious = previous.get(null);
        Field sweeps = ClientAimState.class.getDeclaredField("markerSweepCount"); sweeps.setAccessible(true);
        long before = sweeps.getLong(null);
        try {
            mc.level.setBlock(obstacle, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            set(ClientAimState.class, "scopePosition", camera);
            set(ClientAimState.class, "previousScopePosition", camera);
            set(ClientAimState.class, "scopePoseRelative", false);
            var ray = new net.minecraft.world.phys.Vec3(1, 0, 0);
            var hit = new com.hooya.stabilizedturret.network.AimGroupSessionPayload.BallisticAim(
                    12, 0, 0, .05, .99, false, 200, true, origin.x + 2, origin.y - 5, origin.z);
            var miss = new com.hooya.stabilizedturret.network.AimGroupSessionPayload.BallisticAim(
                    12, 0, 0, .05, .99, false, 200, false, 0, 0, 0);
            for (var ballistic : Arrays.asList(null, hit, miss)) {
                ClientAimState.acceptAimGroups(new com.hooya.stabilizedturret.network.AimGroupSessionPayload(List.of(
                        new com.hooya.stabilizedturret.network.AimGroupSessionPayload.GroupAim(
                                origin.x, origin.y, origin.z, 1, 0, 0, ballistic))));
                for (float alpha : new float[]{0, .5F, 1}) {
                    var point = ClientAimState.getCannonAimPoint(alpha);
                    var delta = point.subtract(camera);
                    require(delta.length() > 999_999 && delta.normalize().distanceToSqr(ray) < 1e-18,
                            "virtual scope must ignore walls and ballistic hits/misses without camera parallax");
                    require(ClientAimState.getCannonAimGroupPoints(alpha).getFirst().distanceToSqr(point) < 1e-12,
                            "group and primary reticles must use the same infinite direction");
                }
            }
            checkRangedFrame(mc, origin, camera);
            require(sweeps.getLong(null) == before, "virtual scope must not run terrain sweeps");
            // Enabled virtual sight must not alter third-person/non-scope impact markers.
            set(ClientAimState.class, "scoped", false);
            ClientAimState.acceptAimGroups(new com.hooya.stabilizedturret.network.AimGroupSessionPayload(List.of(
                    new com.hooya.stabilizedturret.network.AimGroupSessionPayload.GroupAim(origin.x, origin.y, origin.z, 1, 0, 0, hit))));
            var finite = ClientAimState.getCannonAimPoint(1);
            require(finite.distanceToSqr(new net.minecraft.world.phys.Vec3(hit.impactX(), hit.impactY(), hit.impactZ())) < 1e-12,
                    "outside scope the authoritative impact marker must remain finite");
            set(ClientAimState.class, "scoped", true);
            VirtualScopeVisibility.clear();
            ClientAimState.acceptAimGroups(new com.hooya.stabilizedturret.network.AimGroupSessionPayload(List.of(
                    new com.hooya.stabilizedturret.network.AimGroupSessionPayload.GroupAim(origin.x, origin.y, origin.z, 1, 0, 0))));
            require(ClientAimState.getCannonAimPoint(1).distanceTo(origin) < 3,
                    "normal scope must resume clipping against the nearby stone block immediately");
            System.out.println("PASS: virtual scope infinite reticles ignore real wall and ballistic hit/miss, preserve non-scope impacts and restore normal scope clipping");
        } finally {
            mc.level.setBlock(obstacle, oldBlock, 3);
            set(ClientAimState.class, "scopePosition", oldScope);
            set(ClientAimState.class, "previousScopePosition", oldPrevious);
            set(ClientAimState.class, "scoped", true);
            ClientAimState.acceptAimGroups(com.hooya.stabilizedturret.network.AimGroupSessionPayload.inactive());
            VirtualScopeVisibility.update(true, List.of(), List.of(mc.player.getUUID()));
        }
    }
    private static void checkRangedFrame(Minecraft mc, net.minecraft.world.phys.Vec3 pivot,
                                        net.minecraft.world.phys.Vec3 camera) throws Exception {
        var body = (dev.ryanhcode.sable.sublevel.ClientSubLevel) dev.ryanhcode.sable.api.sublevel.SubLevelContainer
                .getContainer(mc.level).getSubLevel(frameBody.join());
        var samplePose = body.renderPose(1);
        var sight = new net.minecraft.world.phys.Vec3(1, 0, 0);
        var bore = com.hooya.stabilizedturret.content.controller.LaserRangeCompensation.raise(
                sight, Math.toRadians(7.244054267964293));
        var muzzle = pivot.add(bore.scale(6.5));
        var localOrigin = samplePose.transformPositionInverse(muzzle);
        for (double error : new double[]{-.02, 0, .02}) {
            var measured = new org.joml.Quaterniond().rotationAxis(error, 0, 0, 1)
                    .transform(new org.joml.Vector3d(bore.x, bore.y, bore.z));
            var local = samplePose.transformNormalInverse(measured);
            var frame = new com.hooya.stabilizedturret.network.AimGroupSessionPayload.MuzzleFrame(
                    frameBody.join(), localOrigin, new net.minecraft.world.phys.Vec3(local.x, local.y, local.z), 9);
            var group = new com.hooya.stabilizedturret.network.AimGroupSessionPayload.GroupAim(
                    muzzle.x, muzzle.y, muzzle.z, measured.x, measured.y, measured.z, null, frame,
                    new com.hooya.stabilizedturret.network.AimGroupSessionPayload.SightAim(bore, sight));
            ClientAimState.acceptAimGroups(com.hooya.stabilizedturret.network.AimGroupSessionPayload.inactive());
            ClientAimState.acceptAimGroups(new com.hooya.stabilizedturret.network.AimGroupSessionPayload(List.of(group)));
            for (float alpha : new float[]{0, .5F, 1}) {
                var actualBore = CannonRenderFrame.from(frame, body.renderPose(alpha), net.minecraft.world.phys.Vec3.ZERO).direction();
                var expected = com.hooya.stabilizedturret.content.controller.CannonSightDirection.direction(actualBore, bore, sight);
                var delta = ClientAimState.getCannonAimPoint(alpha).subtract(camera);
                require(Math.abs(delta.length() - 1_000_000) < 1e-6
                                && delta.normalize().distanceTo(expected) < 1e-10,
                        "ranged physical render frame must remove lift at infinity and retain measured tracking error");
                require(ClientAimState.getCannonAimGroupPoints(alpha).getFirst()
                                .distanceTo(ClientAimState.getCannonAimPoint(alpha)) < 1e-8,
                        "ranged physical group and primary reticles share the same infinite direction");
            }
        }
        System.out.println("PASS: real Sable render frame + 7.244 degree laser lift + long muzzle stays at infinity and preserves physical tracking error");
    }
    private static void checkScrollableLayout() throws Exception {
        var field = StabilizerControllerScreen.class.getDeclaredField("scrollSlider");
        field.setAccessible(true);
        var scroll = (AbstractWidget) field.get(screen);
        require(scroll != null, "reaction-force page must exercise the scrollbar");
        int clipRight = scroll.getX() - 3;
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget != scroll) {
                require(widget.getX() + widget.getWidth() <= clipRight,
                        "control clipped beside scrollbar: " + widget.getMessage().getString()
                                + ", right=" + (widget.getX() + widget.getWidth()) + ", clip=" + clipRight);
            }
        }
        // Horizontal layout must remain intact at both ends of the vertical scroll range.
        var method = StabilizerControllerScreen.class.getDeclaredMethod("setScrollOffset", double.class, boolean.class);
        method.setAccessible(true);
        method.invoke(screen, Double.MAX_VALUE, true);
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget != scroll)
                require(widget.getX() + widget.getWidth() <= clipRight, "scrolled control overlaps scrollbar");
        }
        method.invoke(screen, 0.0, true);
    }
    private static void set(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    private static void screenshot(Minecraft mc, String name) throws Exception {
        Files.createDirectories(Path.of("virtual-scope-qa"));
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(Path.of("virtual-scope-qa", name + ".png"));
        }
    }
    private static void finish(Minecraft mc, Throwable failure) {
        finished = true;
        try { Files.writeString(Path.of("virtual-scope-client-result.txt"), failure == null ? "PASS" : "FAIL: " + failure); }
        catch (Exception error) { throw new RuntimeException(error); }
        if (failure != null) StabilizedTurret.LOGGER.error("VIRTUAL SCOPE CLIENT FAILED", failure);
        mc.stop();
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
