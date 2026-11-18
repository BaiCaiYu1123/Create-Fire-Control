package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.TargetLockTelemetry;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/** Fixed screen proportions measured from the three 2560x1440 references. */
final class TargetLockFrame {
    static final float REFERENCE_SIDE = 72.0F;
    static final float OPTICAL_REFERENCE_SIDE = 73.0F;
    static final float REFERENCE_HEIGHT = 1440.0F;
    static final int FONT_ADVANCE = 15;
    static final int DISTANCE_Y = -34;
    static final int CLOSING_Y = 13;
    static final int ALTITUDE_Y = 41;
    static final int HEADING_Y = 84;
    private static final String GLYPHS = "0123456789.-kms/";
    private static final ResourceLocation NUMERALS = texture("numerals");
    private static final ResourceLocation OPTICAL = texture("optical");
    private static final ResourceLocation AIRCRAFT = texture("aircraft_radar");
    private static final ResourceLocation GROUND = texture("ground_radar");
    private static final ResourceLocation CONTACT = texture("radar_contact");
    private static final ResourceLocation HEADING_GREEN = texture("heading_green");
    private static final ResourceLocation HEADING_BLACK = texture("heading_black");
    private static final ResourceLocation CAP_GREEN = texture("cap_green");
    private static final ResourceLocation CAP_BLACK = texture("cap_black");

    enum Style { OPTICAL, AIRCRAFT_RADAR, GROUND_RADAR, AIR_TO_GROUND }

    static int halfSide(int guiHeight) {
        return Math.max(2, Math.round(guiHeight * REFERENCE_SIDE / REFERENCE_HEIGHT * 0.5F));
    }

    /** Unselected contacts share the locked frame's screen size and silhouette. */
    static void renderRadarContact(GuiGraphics graphics, int x, int y, int guiHeight, int color) {
        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        float scale = guiHeight / REFERENCE_HEIGHT;
        graphics.pose().scale(scale, scale, 1.0F);
        RenderSystem.setShaderColor(((color >>> 16) & 255) / 255.0F,
                ((color >>> 8) & 255) / 255.0F, (color & 255) / 255.0F, (color >>> 24) / 255.0F);
        graphics.blit(CONTACT, -38, -38, 76, 76, 0.0F, 0.0F, 76, 76, 76, 76);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.pose().popPose();
    }

    static void render(
            GuiGraphics graphics, Minecraft minecraft, int x, int y, int guiHeight,
            Style style, int color, Vec3 target, TargetLockTelemetry telemetry
    ) {
        if ((color >>> 24) < 4) return;
        // The extracted silhouettes and glyphs preserve the supplied image's
        // actual round caps and font outlines. Draw at
        // native reference pixels; Minecraft's font and GUI scale are bypassed.
        float scale = guiHeight / REFERENCE_HEIGHT;
        float alpha = (color >>> 24) / 255.0F;
        boolean black = style == Style.AIRCRAFT_RADAR;
        int ink = (color & 0xFF000000) | (black ? 0 : 0x0000FF00);
        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        if (style == Style.AIR_TO_GROUND) {
            graphics.fill(-36, -36, 36, -32, ink);
            graphics.fill(-36, 32, 36, 36, ink);
            graphics.fill(-36, -32, -32, 32, ink);
            graphics.fill(32, -32, 36, 32, ink);
            graphics.fill(-2, -18, 2, 18, ink);
            graphics.fill(-18, -2, 18, 2, ink);
        } else {
            boolean optical = style == Style.OPTICAL;
            int size = optical ? 77 : 76; // includes two AA pixels on each edge
            if (optical) opticalOutline(graphics, alpha);
            else sprite(graphics, black ? AIRCRAFT : GROUND,
                    -size * 0.5F, -size * 0.5F, size, size, alpha);
        }

        text(graphics, kilometres(telemetry.distance()) + " km", 43, DISTANCE_Y, false, black, alpha);
        text(graphics, (Double.isFinite(telemetry.closingRate())
                        ? Long.toString(Math.round(telemetry.closingRate())) : "--") + " m/s",
                43, CLOSING_Y, false, black, alpha);
        text(graphics, kilometres(telemetry.heightAboveGround()) + " km",
                0, ALTITUDE_Y, true, black, alpha);

        Camera camera = minecraft.gameRenderer.getMainCamera();
        MotionIndicator indicator = motionIndicator(telemetry.velocity(), target.subtract(camera.getPosition()));
        int circleY = HEADING_Y;
        if (indicator.extension() > 0.0) {
            line(graphics, indicator.x() * 10.0, circleY + indicator.y() * 10.0,
                    indicator.x() * (12.5 + indicator.extension()),
                    circleY + indicator.y() * (12.5 + indicator.extension()), ink, black, alpha);
        }
        sprite(graphics, black ? HEADING_BLACK : HEADING_GREEN, -14.5F, circleY - 14.5F, 29, 29, alpha);
        graphics.pose().popPose();
    }

    /** Heading dial seen from above: up is away from the observer, down is toward it. */
    static MotionIndicator motionIndicator(Vec3 velocity, Vec3 lineOfSight) {
        Vec3 forward = new Vec3(lineOfSight.x, 0.0, lineOfSight.z).normalize();
        if (forward.lengthSqr() < 1.0e-8) forward = new Vec3(0.0, 0.0, 1.0);
        Vec3 right = new Vec3(forward.z, 0.0, -forward.x);
        double x = velocity.dot(right);
        double y = -velocity.dot(forward);
        double length = Math.hypot(x, y);
        if (length < 1.0e-6) {
            // Pure vertical flight still has a useful up/down direction.
            if (Math.abs(velocity.y) < 1.0e-6) return new MotionIndicator(0.0, 0.0, 0.0);
            y = -Math.signum(velocity.y);
            length = 1.0;
        }
        // 0.08 reference pixels per m/s: faster targets always have longer ticks.
        return new MotionIndicator(x / length, y / length, velocity.length() * 20.0 * 0.08);
    }

    private static String kilometres(double metres) {
        return Double.isFinite(metres) ? String.format(Locale.ROOT, "%.1f", metres / 1000.0) : "--";
    }

    private static void text(GuiGraphics graphics, String text, int x, int y,
                             boolean centered, boolean black, float alpha) {
        float cursor = centered ? -(text.length() * FONT_ADVANCE - (black ? 4 : 3)) * 0.5F : x;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int glyph = GLYPHS.indexOf(ch);
            if (glyph >= 0) {
                graphics.pose().pushPose();
                graphics.pose().translate(cursor - 4, y - 6, 0.0F);
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
                graphics.blit(NUMERALS, 0, 0, 24, 40,
                        glyph * 24.0F, black ? 40.0F : 0.0F, 24, 40, 384, 80);
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                graphics.pose().popPose();
            }
            cursor += FONT_ADVANCE;
        }
    }

    private static void line(GuiGraphics graphics, double x0, double y0, double x1, double y1,
                             int color, boolean black, float alpha) {
        graphics.pose().pushPose();
        graphics.pose().translate(x0, y0, 0.0);
        graphics.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(y1 - y0, x1 - x0)));
        int length = (int) Math.round(Math.hypot(x1 - x0, y1 - y0));
        graphics.fill(0, -2, length, 3, color);
        graphics.flush();
        sprite(graphics, black ? CAP_BLACK : CAP_GREEN, -4, -4, 8, 8, alpha);
        sprite(graphics, black ? CAP_BLACK : CAP_GREEN, length - 4, -4, 8, 8, alpha);
        graphics.pose().popPose();
    }

    private static void sprite(GuiGraphics graphics, ResourceLocation texture,
                               float x, float y, int width, int height, float alpha) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        graphics.blit(texture, 0, 0, width, height, 0.0F, 0.0F, width, height, width, height);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.pose().popPose();
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,
                "textures/gui/target_lock/" + name + ".png");
    }

    // The reference screenshot also contains a circle inside the optical frame.
    // It is baked into optical.png, so drawing the full sprite creates a false
    // stationary lead cue. Preserve its exact outer frame, never its interior.
    static final java.util.List<FrameRegion> OPTICAL_OUTLINE = java.util.List.of(
            new FrameRegion(0, 0, 77, 7), new FrameRegion(0, 70, 77, 7),
            new FrameRegion(0, 7, 7, 63), new FrameRegion(70, 7, 7, 63));

    record FrameRegion(int x, int y, int width, int height) {
        boolean contains(int px, int py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    private static void opticalOutline(GuiGraphics graphics, float alpha) {
        graphics.pose().pushPose();
        graphics.pose().translate(-38.5F, -38.5F, 0);
        RenderSystem.setShaderColor(1, 1, 1, alpha);
        for (FrameRegion region : OPTICAL_OUTLINE)
            graphics.blit(OPTICAL, region.x(), region.y(), region.width(), region.height(),
                    (float) region.x(), (float) region.y(), region.width(), region.height(), 77, 77);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        graphics.pose().popPose();
    }

    record MotionIndicator(double x, double y, double extension) {}

    private TargetLockFrame() {
    }
}
