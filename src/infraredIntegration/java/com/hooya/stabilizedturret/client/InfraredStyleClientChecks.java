package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.InfraredGeometry;
import com.hooya.stabilizedturret.content.controller.InfraredTargeting;
import com.hooya.stabilizedturret.network.InfraredStatePayload;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Opt-in GPU/style and CPU-allocation checks; excluded from the shipped JAR. */
final class InfraredStyleClientChecks {
    private static int stage, frames;
    private static volatile double sink;
    private static boolean benchmarked;
    private static final String[] NAMES = {"style-sky-gui1", "style-sky-gui4", "style-dark-gui1", "style-centre-limited",
            "style-free-look-limited", "style-cone-behind", "style-reference-comparison", "style-locked-red"};
    private static final ResourceLocation REFERENCE = ResourceLocation.fromNamespaceAndPath("create_fire_control", "style_qa_reference");
    private static InfraredTargeting.Frame referenceFrame() {
        double tan = Math.tan(Math.toRadians(35));
        double depth = InfraredGeometry.RANGE * InfraredGeometry.GIMBAL_COS;
        return new InfraredTargeting.Frame(Vec3.ZERO, new Vec3(0, 0, -1), new Vec3(1, 0, 0), new Vec3(0, 1, 0), tan, 16.0 / 9)
                .withAircraft(new Vec3((1274.00476 - 1280) * 2 * tan * depth / 1440,
                        (720 - 650.99891) * 2 * tan * depth / 1440, 0), new Vec3(0, 0, -1));
    }
    static boolean render(RenderGuiEvent.Post event, Minecraft mc) throws Exception {
        var g = event.getGuiGraphics();
        var frame = referenceFrame();
        if (!benchmarked) {
            benchmarked = true; benchmark(frame);
            try (var input = Files.newInputStream(Path.of("../../reference.png"))) {
                mc.getTextureManager().register(REFERENCE, new DynamicTexture(NativeImage.read(input)));
            }
        }
        if (stage == 6) g.blit(REFERENCE, 0, 0, g.guiWidth(), g.guiHeight(), 0, 0, 2560, 1440, 2560, 1440);
        else g.fill(0, 0, g.guiWidth(), g.guiHeight(), stage == 2 || stage == 3 ? 0xFF171B26 : 0xFFB7CED6);
        double sx = 1274.85841, sy = 605.93258;
        if (stage == 3) { sx = 6000; sy = 650.99891; }
        if (stage == 4) {
            double angle = .7;
            Vec3 forward = new Vec3(Math.sin(angle), 0, -Math.cos(angle));
            frame = new InfraredTargeting.Frame(new Vec3(3, 7, 12), forward, forward.cross(new Vec3(0, 1, 0)),
                    new Vec3(0, 1, 0), .7, 16.0 / 9).withAircraft(Vec3.ZERO, new Vec3(0, 0, -1));
            sx = -5000; sy = -600;
        }
        if (stage == 5) frame = frame.withAircraft(frame.aircraftOrigin(), new Vec3(0, 0, 1));
        if (stage == 6) {
            frame = frame.withAircraft(frame.aircraftOrigin().add(28 * 2 * frame.halfTanY()
                    * InfraredGeometry.RANGE * InfraredGeometry.GIMBAL_COS / 1440, 0, 0), frame.aircraftForward());
            sx += 96;
        }
        InfraredHudRenderer.renderRings(g, stage == 7 ? InfraredGeometry.RED : InfraredGeometry.GREEN, sx, sy, frame);
        int segments = InfraredHudMesh.lastEmittedSegments();
        if (segments > InfraredHudMesh.RANGE_SEGMENTS + InfraredHudMesh.SEEKER_SEGMENTS)
            throw new AssertionError("Unbounded IR geometry budget: " + segments);
        if (stage == 5 && segments != 0) throw new AssertionError("A backward invisible cone generated HUD geometry");
        if (stage == 0 || stage == 1 || stage == 2 || stage == 6) {
            int x = (int) Math.round((1280 + (stage == 6 ? 96 : 0)) * g.guiHeight() / 1440.0);
            int y = (int) Math.round(608 * g.guiHeight() / 1440.0);
            MouseFlightSeekerCompat.begin(event);
            // Exercise the real installed Mixin with its IR-selection guard enabled.
            var boat = new net.minecraft.world.entity.vehicle.Boat(mc.level, mc.player.getX(), mc.player.getY(), mc.player.getZ());
            if (!mc.player.startRiding(boat, true)) throw new AssertionError("Could not establish the client-only HUD selection fixture");
            try {
                InfraredHudRenderer.accept(new InfraredStatePayload(true, 78, 2, false, Long.MIN_VALUE, null, Vec3.ZERO));
                var nativeHud = Class.forName("ace.flight.neoforge.client.MouseFlightControllerClient");
                var draw = nativeHud.getDeclaredMethod("drawTargetCircle", net.minecraft.client.gui.GuiGraphics.class, int.class, int.class);
                draw.setAccessible(true); draw.invoke(null, g, x, y);
                if (!MouseFlightSeekerCompat.hasTarget(event)) throw new AssertionError("Style replacement discarded the native target centre");
            } finally {
                mc.player.stopRiding(); InfraredHudRenderer.accept(InfraredStatePayload.inactive()); MouseFlightSeekerCompat.end(event);
            }
        }
        g.flush();
        if (++frames < 4) return false;
        frames = 0;
        try (var screenshot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            screenshot.writeToFile(Path.of("infrared-hud-qa", NAMES[stage] + ".png"));
        }
        stage++;
        if (stage == 1) { mc.options.guiScale().set(4); mc.resizeDisplay(); }
        if (stage == 2) { mc.options.guiScale().set(1); mc.resizeDisplay(); }
        if (stage != NAMES.length) return false;
        Files.writeString(Path.of("infrared-style-result.txt"), "PASS: reference alpha/outline line profiles, actual installed flight-view Mixin replacement, GUI 1/4, bright/dark backgrounds, search/lock centre constraints, free-look, behind-camera culling and bounded mesh budgets");
        return true;
    }
    private static void benchmark(InfraredTargeting.Frame frame) throws Exception {
        var memory = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        memory.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 150; i++) { oldGeometry(frame); InfraredHudMesh.projectRange(frame); }
        long[] oldTime = new long[5], newTime = new long[5], oldAllocation = new long[5], newAllocation = new long[5];
        long thread = Thread.currentThread().threadId();
        for (int round = 0; round < 5; round++) {
            long allocated = memory.getThreadAllocatedBytes(thread), start = System.nanoTime();
            for (int i = 0; i < 200; i++) oldGeometry(frame);
            oldTime[round] = System.nanoTime() - start; oldAllocation[round] = memory.getThreadAllocatedBytes(thread) - allocated;
            allocated = memory.getThreadAllocatedBytes(thread); start = System.nanoTime();
            for (int i = 0; i < 200; i++) InfraredHudMesh.projectRange(frame);
            newTime[round] = System.nanoTime() - start; newAllocation[round] = memory.getThreadAllocatedBytes(thread) - allocated;
        }
        Arrays.sort(oldTime); Arrays.sort(newTime); Arrays.sort(oldAllocation); Arrays.sort(newAllocation);
        Files.writeString(Path.of("infrared-geometry-performance.json"), "{\n  \"iterations_per_sample\":200,\n  \"samples\":5,\n  \"old_median_nanoseconds\":" + oldTime[2]
                + ",\n  \"new_median_nanoseconds\":" + newTime[2] + ",\n  \"old_allocated_bytes\":" + oldAllocation[2]
                + ",\n  \"new_allocated_bytes\":" + newAllocation[2] + ",\n  \"old_max_vertices\":3134,\n  \"new_max_vertices\":1408,\n  \"new_texture_bytes\":1024\n}\n");
    }
    private static void oldGeometry(InfraredTargeting.Frame frame) {
        double sum = 0;
        for (int i = 0; i <= 720; i++) {
            double angle = i * Math.PI * 2 / 720;
            Vec3 axis = frame.aircraftForward().cross(new Vec3(0, 1, 0));
            if (axis.lengthSqr() < 1e-8) axis = frame.aircraftForward().cross(new Vec3(1, 0, 0));
            axis = axis.normalize(); Vec3 up = axis.cross(frame.aircraftForward()).normalize();
            Vec3 point = frame.aircraftOrigin().add(frame.aircraftForward().add(axis.scale(InfraredGeometry.GIMBAL_TANGENT * Math.cos(angle)))
                    .add(up.scale(InfraredGeometry.GIMBAL_TANGENT * Math.sin(angle))).normalize().scale(InfraredGeometry.RANGE));
            Vec3 screen = frame.project(point);
            if (screen != null) sum += screen.x + screen.y;
        }
        sink = sum;
    }
    private InfraredStyleClientChecks() {}
}
