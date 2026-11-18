package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.InfraredGeometry;
import com.hooya.stabilizedturret.content.controller.InfraredTargeting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Thin textured strips: no full-screen pass, supersampling, or per-frame texture allocation. */
final class InfraredHudMesh {
    static final int RANGE_SEGMENTS = 256, SEEKER_SEGMENTS = 96, VIEW_SEGMENTS = 80;
    private static final ResourceLocation PROFILE = ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "infrared_line_profile");
    private static final double[] COS = new double[RANGE_SEGMENTS + 1], SIN = COS.clone();
    private static final double[] SMALL_COS = new double[SEEKER_SEGMENTS + 1], SMALL_SIN = SMALL_COS.clone();
    private static final double[] VIEW_COS = new double[VIEW_SEGMENTS + 1], VIEW_SIN = VIEW_COS.clone();
    private static final double[] X = new double[RANGE_SEGMENTS + 1], Y = X.clone(), NX = X.clone(), NY = X.clone();
    private static final double[] DISTANCE = {0, .75, 1, 1.25, 1.5, 1.75, 2, 2.25, 2.5, 2.75, 3};
    private static final double[] GREEN_WEIGHT = {.95, .95, .894, .717, .486, .248, .058, 0, 0, 0, 0};
    private static final double[] COVERAGE = {.95, .95, .939, .902, .826, .653, .410, .199, .047, 0, 0};
    private static boolean textureReady;
    private static BufferBuilder vertices;
    private static Matrix4f pose;
    private static double left, right, top, bottom;
    private static int emittedSegments;

    static {
        circleTable(COS, SIN); circleTable(SMALL_COS, SMALL_SIN); circleTable(VIEW_COS, VIEW_SIN);
    }
    private static void circleTable(double[] cosine, double[] sine) {
        for (int i = 0; i < cosine.length; i++) {
            double angle = i * Math.PI * 2 / (cosine.length - 1);
            cosine[i] = Math.cos(angle); sine[i] = Math.sin(angle);
        }
    }
    private static double profile(double[] values, double distance) {
        for (int i = 1; i < DISTANCE.length; i++) if (distance <= DISTANCE[i]) {
            double fraction = (distance - DISTANCE[i - 1]) / (DISTANCE[i] - DISTANCE[i - 1]);
            return values[i - 1] + fraction * (values[i] - values[i - 1]);
        }
        return 0;
    }
    private static void texture() {
        if (textureReady) return;
        NativeImage pixels = new NativeImage(128, 2, false);
        for (int x = 0; x < 128; x++) {
            double unit = Math.abs((x + .5) / 128 * 2 - 1);
            double coverage = profile(COVERAGE, unit * 3);
            double green = profile(GREEN_WEIGHT, unit * 3);
            pixels.setPixelRGBA(x, 0, pixel(green, coverage));
            // The reference view marker is softer and more transparent than the seeker rings.
            double distance = unit * 4;
            double core = .61 * Math.clamp((1.9 - distance) / 1.15, 0, 1);
            double shadow = distance <= .75 ? .08 : distance <= 1.25 ? .08 + .48 * (distance - .75)
                    : .32 * Math.clamp((3.6 - distance) / 2.35, 0, 1);
            pixels.setPixelRGBA(x, 1, pixel(core, core + shadow * (1 - core)));
        }
        var texture = new DynamicTexture(pixels);
        Minecraft.getInstance().getTextureManager().register(PROFILE, texture);
        texture.setFilter(true, false);
        textureReady = true;
    }
    private static int pixel(double green, double coverage) {
        int alpha = (int) Math.round(Math.clamp(coverage, 0, 1) * 255);
        int light = alpha == 0 ? 0 : (int) Math.round(Math.clamp(green / coverage, 0, 1) * 255);
        return alpha << 24 | light << 16 | light << 8 | light;
    }
    private static void begin(GuiGraphics graphics, double aspect) {
        texture(); graphics.flush(); graphics.pose().pushPose();
        float scale = (float) (graphics.guiHeight() / InfraredGeometry.HEIGHT);
        graphics.pose().translate((graphics.guiWidth() - InfraredGeometry.WIDTH * scale) * .5, 0, 0);
        graphics.pose().scale(scale, scale, 1);
        pose = graphics.pose().last().pose();
        left = (InfraredGeometry.WIDTH - InfraredGeometry.HEIGHT * aspect) / 2 - 4;
        right = InfraredGeometry.WIDTH - left; top = -4; bottom = InfraredGeometry.HEIGHT + 4;
        vertices = null; emittedSegments = 0;
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableCull();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, PROFILE);
    }
    private static void finish(GuiGraphics graphics) {
        if (vertices != null) BufferUploader.drawWithShader(vertices.buildOrThrow());
        vertices = null; pose = null;
        RenderSystem.enableCull(); RenderSystem.disableBlend();
        graphics.pose().popPose();
    }
    static void render(GuiGraphics graphics, InfraredTargeting.Frame frame, int color,
                       double seekerX, double seekerY, boolean rings, boolean view) {
        if (!rings && !view) return;
        begin(graphics, frame.aspect());
        if (rings) {
            range(frame, color);
            Vec3 limited = frame.limitSeeker(seekerX, seekerY);
            if (limited != null) circle(limited.x, limited.y, InfraredGeometry.SEEKER_RADIUS, color, false);
        }
        if (view) circle(frame.seekerX(), frame.seekerY(), InfraredGeometry.VIEW_RADIUS, InfraredGeometry.VIEW_GREEN, true);
        finish(graphics);
    }
    static void view(GuiGraphics graphics, double x, double y) {
        begin(graphics, (double) graphics.guiWidth() / graphics.guiHeight());
        circle(x, y, InfraredGeometry.VIEW_RADIUS, InfraredGeometry.VIEW_GREEN, true);
        finish(graphics);
    }
    static int lastEmittedSegments() { return emittedSegments; }

    private static void range(InfraredTargeting.Frame frame, int color) {
        projectRange(frame);
        for (int i = 0; i < RANGE_SEGMENTS; i++)
            segment(X[i], Y[i], X[i + 1], Y[i + 1], NX[i], NY[i], NX[i + 1], NY[i + 1], 3, .25F, color);
    }
    static void projectRange(InfraredTargeting.Frame frame) {
        Vec3 circle = frame.rangeHudCircle();
        for (int i = 0; i <= RANGE_SEGMENTS; i++) {
            X[i] = circle == null ? Double.NaN : circle.x + circle.z * COS[i];
            Y[i] = circle == null ? Double.NaN : circle.y + circle.z * SIN[i];
            NX[i] = COS[i]; NY[i] = SIN[i];
        }
    }
    private static void circle(double x, double y, double radius, int color, boolean view) {
        double[] cosine = view ? VIEW_COS : SMALL_COS, sine = view ? VIEW_SIN : SMALL_SIN;
        for (int i = 0; i < cosine.length - 1; i++)
            segment(x + radius * cosine[i], y + radius * sine[i], x + radius * cosine[i + 1], y + radius * sine[i + 1],
                    cosine[i], sine[i], cosine[i + 1], sine[i + 1], view ? 4 : 3, view ? .75F : .25F, color);
    }
    private static void segment(double x0, double y0, double x1, double y1, double nx0, double ny0, double nx1, double ny1,
                                double halfWidth, float row, int color) {
        if (!Double.isFinite(x0) || !Double.isFinite(y0) || !Double.isFinite(x1) || !Double.isFinite(y1)) return;
        double dx = x1 - x0, dy = y1 - y0, start = 0, end = 1;
        if (Math.abs(dx) < 1e-12) { if (x0 < left || x0 > right) return; }
        else {
            double a = (left - x0) / dx, b = (right - x0) / dx;
            start = Math.max(start, Math.min(a, b)); end = Math.min(end, Math.max(a, b));
        }
        if (Math.abs(dy) < 1e-12) { if (y0 < top || y0 > bottom) return; }
        else {
            double a = (top - y0) / dy, b = (bottom - y0) / dy;
            start = Math.max(start, Math.min(a, b)); end = Math.min(end, Math.max(a, b));
        }
        if (start >= end) return;
        double sx = x0 + dx * start, sy = y0 + dy * start, ex = x0 + dx * end, ey = y0 + dy * end;
        double snx = nx0 + (nx1 - nx0) * start, sny = ny0 + (ny1 - ny0) * start;
        double enx = nx0 + (nx1 - nx0) * end, eny = ny0 + (ny1 - ny0) * end;
        if (vertices == null) vertices = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        vertex(sx - snx * halfWidth, sy - sny * halfWidth, 0, row, color);
        vertex(ex - enx * halfWidth, ey - eny * halfWidth, 0, row, color);
        vertex(ex + enx * halfWidth, ey + eny * halfWidth, 1, row, color);
        vertex(sx + snx * halfWidth, sy + sny * halfWidth, 1, row, color);
        emittedSegments++;
    }
    private static void vertex(double x, double y, float u, float v, int color) {
        vertices.addVertex(pose, (float) x, (float) y, 0).setUv(u, v).setColor(color);
    }
    private InfraredHudMesh() {}
}
