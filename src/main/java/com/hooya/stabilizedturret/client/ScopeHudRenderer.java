package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.ScopePreset;
import com.hooya.stabilizedturret.mixin.client.GuiOverlayMessageAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.Locale;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ScopeHudRenderer {
    private static final int RETICLE = 0xE8FFFFFF;
    private static final int ACTIVE = 0xFFE7B94B;
    private static final int DIM = 0xFF777777;
    private static final int PRESET_REFERENCE_WIDTH = 1424;
    private static final int PRESET_REFERENCE_HEIGHT = 800;
    private static final int PRESET_FRAME_TEXTURE_WIDTH = 2848;
    private static final int PRESET_FRAME_TEXTURE_HEIGHT = 1600;

    /** Resolve each draw so thermal toggles recolor even cached sight geometry. */
    static int sightLineColor(int normalColor) {
        return ClientAimState.isThermal()
                ? (normalColor & 0xFF000000) | 0x00FF0000
                : normalColor;
    }

    @SubscribeEvent
    public static void suppressUnrelatedHud(RenderGuiLayerEvent.Pre event) {
        if (ClientWeaponViewState.isActive()) {
            if (VanillaGuiLayers.CROSSHAIR.equals(event.getName())) event.setCanceled(true);
            return;
        }
        boolean podAllowed = VanillaGuiLayers.OVERLAY_MESSAGE.equals(event.getName());
        if (((ClientAimState.isPodView()
                || ClientTelevisionGuidanceState.isActive()) && !podAllowed)
                || (ClientAimState.isScoped()
                && !VanillaGuiLayers.HOTBAR.equals(event.getName()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void renderScope(RenderGuiEvent.Post event) {
        if (ClientWeaponViewState.isActive()) return;
        if (!ClientAimState.isOpticalView()) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int cx = width / 2;
        int cy = height / 2;

        if (ClientAimState.isPodView()) {
            drawPodHud(graphics, width, height, event.getPartialTick().getGameTimeDeltaPartialTick(true));
            return;
        }

        ScopePreset preset = ClientAimState.getScopePreset();
        if (preset == ScopePreset.DEFAULT) {
            drawOpticalVignette(graphics, width, height);
        } else {
            drawPresetFrame(graphics, width, height, preset);
        }

        Minecraft minecraft = Minecraft.getInstance();
        Component range = Component.translatable("hud.create_fire_control.scope.range",
                String.format(Locale.ROOT, "%.0f", ClientAimState.getLastAimDistance()));
        Component azimuth = Component.translatable("hud.create_fire_control.scope.azimuth",
                String.format(Locale.ROOT, "%.1f", ClientAimState.getStableWorldYaw()));
        Component elevation = Component.translatable("hud.create_fire_control.scope.elevation",
                String.format(Locale.ROOT, "%+.1f", -ClientAimState.getStableWorldPitch()));
        graphics.drawString(
                minecraft.font,
                Component.translatable("hud.create_fire_control.scope.zoom",
                        String.format(Locale.ROOT, "%.1f", ClientAimState.getScopeZoom())),
                12,
                height - 20,
                RETICLE,
                true
        );
        graphics.drawCenteredString(minecraft.font, range, cx, height - 20, RETICLE);
        graphics.drawString(minecraft.font, azimuth, width - minecraft.font.width(azimuth) - 12, height - 31, RETICLE, true);
        graphics.drawString(minecraft.font, elevation, width - minecraft.font.width(elevation) - 12, height - 20, RETICLE, true);
        ScopeOrientationHud.draw(graphics, event.getPartialTick().getGameTimeDeltaPartialTick(true));
        ScopeAmmoHud.draw(graphics);
        graphics.drawString(minecraft.font, Component.translatable("hud.create_fire_control.scope.stabilizer"), 12, 12, ACTIVE, true);
        if (ClientAimState.isHeadAimEnabled()) {
            graphics.drawString(minecraft.font, Component.translatable("hud.create_fire_control.scope.head_aim"), 12, 24, 0xFF8BE46A, true);
        }
        Component thermalLabel = Component.translatable("hud.create_fire_control.scope.white_hot");
        graphics.drawString(
                minecraft.font,
                thermalLabel,
                width - minecraft.font.width(thermalLabel) - 12,
                12,
                ClientAimState.isThermal() ? 0xFFFFFFFF : DIM,
                true
        );
        ThirdPersonAimHudRenderer.renderAimMarkers(
                graphics,
                minecraft,
                width,
                height,
                event.getPartialTick().getGameTimeDeltaPartialTick(true),
                true
        );
        drawLaserRangeMessage(graphics, minecraft, width, height,
                event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }

    /** The scoped HUD suppresses vanilla action-bar layers; draw ranging feedback above its frame. */
    private static void drawLaserRangeMessage(GuiGraphics graphics, Minecraft minecraft,
                                             int width, int height, float partialTick) {
        var overlay = (GuiOverlayMessageAccessor) minecraft.gui;
        Component message = overlay.stabilizedTurret$getOverlayMessage();
        int remaining = overlay.stabilizedTurret$getOverlayMessageTime();
        if (remaining <= 0 || message == null
                || !(message.getContents() instanceof TranslatableContents translated)
                || !translated.getKey().startsWith("message.create_fire_control.laser_range.")) return;
        int alpha = (int) Math.clamp((remaining - partialTick) * 255 / 20, 0, 255);
        if (alpha <= 8) return;
        int textWidth = minecraft.font.width(message);
        graphics.fill((width - textWidth) / 2 - 4, height - 48,
                (width + textWidth) / 2 + 4, height - 35, 0xA0000000);
        graphics.drawCenteredString(minecraft.font, message, width / 2, height - 45,
                (alpha << 24) | 0xFFFFFF);
    }

    private static void drawPodHud(
            GuiGraphics graphics,
            int width,
            int height,
            float partialTick
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        int cx = width / 2;
        int cy = height / 2;
        int green = 0xFF55FF62;
        int black = 0xEE050505;

        // Fine sensor boresight.
        graphics.fill(cx - 7, cy, cx + 8, cy + 1, black);
        graphics.fill(cx, cy - 7, cx + 1, cy + 8, black);
        graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, black);

        // Four inward-facing diagonal acquisition marks.
        drawDiagonal(graphics, cx - 18, cy - 18, 1, 1, 8, green);
        drawDiagonal(graphics, cx + 18, cy - 18, -1, 1, 8, green);
        drawDiagonal(graphics, cx - 18, cy + 18, 1, -1, 8, green);
        drawDiagonal(graphics, cx + 18, cy + 18, -1, -1, 8, green);

        Component range = Component.translatable("hud.create_fire_control.scope.pod_range",
                String.format(Locale.ROOT, "%.0f", ClientAimState.getLastAimDistance()));
        graphics.drawCenteredString(minecraft.font, range, cx, cy + 28, green);
        graphics.drawString(
                minecraft.font,
                Component.translatable("hud.create_fire_control.scope.zoom",
                        String.format(Locale.ROOT, "%.1f", ClientAimState.getScopeZoom())),
                12,
                height - 20,
                green,
                true
        );
        Component thermalLabel = Component.translatable("hud.create_fire_control.scope.white_hot");
        graphics.drawString(
                minecraft.font,
                thermalLabel,
                width - minecraft.font.width(thermalLabel) - 12,
                12,
                ClientAimState.isThermal() ? 0xFFFFFFFF : DIM,
                true
        );
        ThirdPersonAimHudRenderer.renderAirToGroundMarkers(
                graphics,
                minecraft,
                width,
                height,
                partialTick
        );
    }

    private static void drawDiagonal(
            GuiGraphics graphics,
            int startX,
            int startY,
            int stepX,
            int stepY,
            int length,
            int color
    ) {
        for (int index = 0; index < length; index++) {
            int x = startX + stepX * index;
            int y = startY + stepY * index;
            graphics.fill(x, y, x + 2, y + 2, color);
        }
    }

    private static void drawOpticalVignette(GuiGraphics graphics, int width, int height) {
        int steps = Math.min(96, Math.max(32, height / 3));
        double radiusX = width * 0.49;
        double radiusY = height * 0.49;
        for (int i = 0; i < steps; i++) {
            int y0 = i * height / steps;
            int y1 = (i + 1) * height / steps + 1;
            double normalizedY = ((y0 + y1) * 0.5 - height * 0.5) / radiusY;
            double half = radiusX * Math.sqrt(Math.max(0.0, 1.0 - normalizedY * normalizedY));
            int left = (int) Math.max(0, width * 0.5 - half);
            int right = (int) Math.min(width, width * 0.5 + half);
            graphics.fill(0, y0, left, y1, 0xE9000000);
            graphics.fill(right, y0, width, y1, 0xE9000000);
        }
    }

    private static void drawPresetFrame(
            GuiGraphics graphics, int width, int height, ScopePreset preset
    ) {
        PresetTextures textures = presetTextures(preset);
        if (textures == null) return;
        graphics.pose().pushPose();
        graphics.pose().translate(width * 0.5F, height * 0.5F, 0.0F);
        graphics.pose().scale(
                width / (float) PRESET_REFERENCE_WIDTH,
                height / (float) PRESET_REFERENCE_HEIGHT,
                1.0F
        );
        graphics.blit(textures.border(),
                -PRESET_REFERENCE_WIDTH / 2, -PRESET_REFERENCE_HEIGHT / 2,
                PRESET_REFERENCE_WIDTH, PRESET_REFERENCE_HEIGHT,
                0.0F, 0.0F,
                PRESET_FRAME_TEXTURE_WIDTH, PRESET_FRAME_TEXTURE_HEIGHT,
                PRESET_FRAME_TEXTURE_WIDTH, PRESET_FRAME_TEXTURE_HEIGHT);
        graphics.pose().popPose();
        ScopePresetGeometry.drawFrame(graphics, width, height, preset);
    }

    /** Draws the moving part of an alternate sight at the projected cannon aim point. */
    static void drawPresetReticle(
            GuiGraphics graphics,
            int width,
            int height,
            ScopePreset preset,
            int aimX,
            int aimY
    ) {
        if (preset == null || preset == ScopePreset.DEFAULT) return;
        PresetTextures textures = presetTextures(preset);
        if (textures == null) return;
        ScopePresetGeometry.drawReticle(
                graphics, width, height, preset, aimX, aimY, textures.reticleScale()
        );
    }

    private static PresetTextures presetTextures(ScopePreset preset) {
        return switch (preset) {
            case AMERICAN -> textures("american", 1.0F);
            case CHINESE -> textures("chinese", 1.0F);
            case ITALIAN -> textures("italian", 1.0F);
            case FRENCH -> textures("french", 0.7F);
            case SQUID -> textures("squid", 1.0F);
            case ELB -> textures("elb", 1.0F);
            case GERMAN -> textures("german", 1.0F);
            default -> null;
        };
    }

    private static PresetTextures textures(
            String name, float reticleScale
    ) {
        String base = "textures/gui/scope/" + name;
        return new PresetTextures(
                ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, base + "_border.png"),
                reticleScale
        );
    }

    private record PresetTextures(
            ResourceLocation border,
            float reticleScale
    ) {
    }

    /**
     * Fills only the area outside a normalized optic aperture. The boundary is
     * recalculated from the current GUI width and height, so ultrawide, 4:3,
     * and resized windows never inherit a 16:9 bitmap's fixed black bars.
     */
    private static void drawAdaptiveAperture(
            GuiGraphics graphics, int width, int height, ScopePreset preset
    ) {
        int steps = Math.min(192, Math.max(48, height / 2));
        for (int index = 0; index < steps; index++) {
            int y0 = index * height / steps;
            int y1 = (index + 1) * height / steps + 1;
            double normalizedY = Math.abs((y0 + y1) * 0.5 - height * 0.5) / (height * 0.5);
            double halfWidthFraction = apertureHalfWidth(preset, normalizedY);
            int left = (int) Math.max(0, Math.floor(width * (0.5 - halfWidthFraction)));
            int right = (int) Math.min(width, Math.ceil(width * (0.5 + halfWidthFraction)));
            graphics.fill(0, y0, left, y1, 0xFF000000);
            graphics.fill(right, y0, width, y1, 0xFF000000);
        }
    }

    private static double apertureHalfWidth(ScopePreset preset, double normalizedY) {
        double y = normalizedY / 0.985;
        if (y >= 1.0) return 0.0;
        return switch (preset) {
            case AMERICAN -> y <= 0.86
                    ? 0.485
                    : 0.485 - (y - 0.86) / 0.14 * 0.225;
            case GERMAN -> y <= 0.86
                    ? 0.465
                    : 0.465 - (y - 0.86) / 0.14 * 0.165;
            case ELB -> y <= 0.78
                    ? 0.462
                    : 0.462 - (y - 0.78) / 0.22 * 0.155;
            case SQUID -> 0.455 * Math.pow(1.0 - Math.pow(y, 2.5), 0.42);
            case CHINESE -> 0.435 * Math.sqrt(1.0 - y * y);
            case ITALIAN, FRENCH -> 0.470 * Math.sqrt(1.0 - y * y);
            default -> 0.490 * Math.sqrt(1.0 - y * y);
        };
    }

    private static void drawFixedCornerFrame(
            GuiGraphics graphics, int width, int height, int color
    ) {
        int cx = width / 2;
        int cy = height / 2;
        int halfWidth = Math.max(24, Math.round(width * 0.272F));
        int halfHeight = Math.max(18, Math.round(height * 0.272F));
        int arm = Math.max(5, Math.round(height * 0.038F));
        corner(graphics, cx - halfWidth, cy - halfHeight, arm, 1, 1, color);
        corner(graphics, cx + halfWidth, cy - halfHeight, arm, -1, 1, color);
        corner(graphics, cx - halfWidth, cy + halfHeight, arm, 1, -1, color);
        corner(graphics, cx + halfWidth, cy + halfHeight, arm, -1, -1, color);
    }

    private static void drawAmericanReticle(GuiGraphics g, int x, int y, int u, int color) {
        ring(g, x, y, 6 * u, color);
        hLine(g, x - 23 * u, x - 7 * u, y, u, color);
        hLine(g, x + 7 * u, x + 24 * u, y, u, color);
        vLine(g, x, y - 24 * u, y - 8 * u, u, color);
        vLine(g, x, y + 8 * u, y + 24 * u, u, color);
        hLine(g, x - 58 * u, x - 35 * u, y, u, color);
        hLine(g, x + 35 * u, x + 58 * u, y, u, color);
        hLine(g, x - 92 * u, x - 70 * u, y, u, color);
        hLine(g, x + 70 * u, x + 92 * u, y, u, color);
        hLine(g, x - 46 * u, x - 25 * u, y - 10 * u, u, color);
        hLine(g, x + 25 * u, x + 46 * u, y - 10 * u, u, color);
        hLine(g, x - 46 * u, x - 25 * u, y + 10 * u, u, color);
        hLine(g, x + 25 * u, x + 46 * u, y + 10 * u, u, color);
        g.fill(x - u, y - u, x + u + 1, y + u + 1, color);
    }

    private static void drawChineseReticle(GuiGraphics g, int x, int y, int u, int color) {
        ring(g, x, y, 5 * u, color);
        hLine(g, x - 24 * u, x - 7 * u, y, u, color);
        hLine(g, x + 7 * u, x + 24 * u, y, u, color);
        vLine(g, x, y - 24 * u, y - 7 * u, u, color);
        vLine(g, x, y + 7 * u, y + 24 * u, u, color);
        g.fill(x - u, y - u, x + u + 1, y + u + 1, color);
    }

    private static void drawItalianReticle(
            GuiGraphics g, int x, int y, int width, int height, int u, int color
    ) {
        squareOutline(g, x - 4 * u, y - 4 * u, x + 4 * u, y + 4 * u, u, color);
        hLine(g, x - 28 * u, x - 6 * u, y, u, color);
        hLine(g, x + 6 * u, x + 28 * u, y, u, color);
        vLine(g, x, y - 17 * u, y - 6 * u, u, color);
        vLine(g, x, y + 6 * u, y + 17 * u, u, color);
        for (int row : new int[]{-10, 10}) {
            hLine(g, x - 55 * u, x - 33 * u, y + row * u, u, color);
            hLine(g, x + 33 * u, x + 55 * u, y + row * u, u, color);
        }
        hLine(g, x - 95 * u, x - 72 * u, y, u, color);
        hLine(g, x + 72 * u, x + 95 * u, y, u, color);
        int baselineY = y + Math.round(height * 0.205F);
        hLine(g, x - Math.round(width * 0.093F), x + Math.round(width * 0.093F), baselineY, u, color);
        vLine(g, x, baselineY - 10 * u, baselineY, u, color);
    }

    private static void drawFrenchReticle(
            GuiGraphics g, int x, int y, int width, int height, int u, int color
    ) {
        hLine(g, x - 25 * u, x - 10 * u, y, u, color);
        hLine(g, x + 10 * u, x + 25 * u, y, u, color);
        vLine(g, x, y - 26 * u, y - 10 * u, u, color);
        vLine(g, x, y + 10 * u, y + 26 * u, u, color);
        for (int offset = 38 * u; offset <= Math.round(width * 0.09F); offset += 10 * u) {
            g.fill(x - offset, y, x - offset + u, y + u, color);
            g.fill(x + offset, y, x + offset + u, y + u, color);
        }
        for (int offset = 38 * u; offset <= Math.round(height * 0.18F); offset += 10 * u) {
            g.fill(x, y - offset, x + u, y - offset + u, color);
            g.fill(x, y + offset, x + u, y + offset + u, color);
        }
        chevron(g, x - Math.round(width * 0.17F), y, 1, 7 * u, color);
        chevron(g, x - Math.round(width * 0.27F), y, 1, 7 * u, color);
        chevron(g, x + Math.round(width * 0.17F), y, -1, 7 * u, color);
        chevron(g, x + Math.round(width * 0.27F), y, -1, 7 * u, color);
        g.fill(x - u, y - u, x + u + 1, y + u + 1, color);
    }

    private static void drawSquidReticle(
            GuiGraphics g, int x, int y, int height, int u, int color
    ) {
        ring(g, x, y, 8 * u, color);
        g.fill(x - u, y - u, x + u + 1, y + u + 1, color);
        hLine(g, x - 20 * u, x - 9 * u, y, u, color);
        hLine(g, x + 9 * u, x + 20 * u, y, u, color);
        vLine(g, x, y - 19 * u, y - 9 * u, u, color);
        vLine(g, x, y + 9 * u, y + 19 * u, u, color);
        vLine(g, x, y - Math.round(height * 0.17F), y - 45 * u, u, color);
        vLine(g, x, y + 45 * u, y + Math.round(height * 0.17F), u, color);
        for (int offset : new int[]{45, 90, 135}) {
            vLine(g, x - offset * u, y - 10 * u, y + 10 * u, u, color);
            vLine(g, x + offset * u, y - 10 * u, y + 10 * u, u, color);
        }
    }

    private static void drawElbReticle(
            GuiGraphics g, int x, int y, int width, int height, int u, int color
    ) {
        int scaleHalf = Math.round(width * 0.075F);
        hLine(g, x - scaleHalf, x + scaleHalf, y, u, color);
        for (int offset = -scaleHalf; offset <= scaleHalf; offset += Math.max(3 * u, scaleHalf / 20)) {
            int tick = (Math.abs(offset) / Math.max(1, 3 * u)) % 5 == 0 ? 6 * u : 3 * u;
            vLine(g, x + offset, y, y + tick, u, color);
        }
        int ladderX = x - Math.round(width * 0.052F);
        for (int row = 1; row <= 9; row++) {
            int yy = y + row * 6 * u;
            int length = (row % 2 == 0 ? 10 : 6) * u;
            hLine(g, ladderX - length, ladderX, yy, u, color);
        }
        // The upward pointer's tip is the true centre aiming point.
        vLine(g, x, y + 10 * u, y + Math.round(height * 0.13F), u, color);
        diagonal(g, x, y, x - 6 * u, y + 10 * u, color);
        diagonal(g, x, y, x + 6 * u, y + 10 * u, color);
        g.fill(x - u, y - u, x + u + 1, y + u + 1, color);
    }

    private static void drawElbApertureTicks(
            GuiGraphics g, int width, int height, int color
    ) {
        int x = width / 2;
        int y = height / 2;
        int u = Math.max(1, Math.round(Math.min(width, height) / 540.0F));
        int apertureSide = Math.round(width * 0.462F);
        hLine(g, x - apertureSide, x - apertureSide + 16 * u, y, u, color);
        hLine(g, x + apertureSide - 16 * u, x + apertureSide, y, u, color);
        vLine(g, x, 0, Math.round(height * 0.025F), u, color);
        vLine(g, x, height - Math.round(height * 0.025F), height, u, color);
    }

    private static void drawGermanReticle(GuiGraphics g, int x, int y, int u, int color) {
        hLine(g, x - 28 * u, x - 7 * u, y, u, color);
        hLine(g, x + 7 * u, x + 28 * u, y, u, color);
        vLine(g, x, y - 35 * u, y - 23 * u, u, color);
        vLine(g, x, y - 15 * u, y - 6 * u, u, color);
        vLine(g, x, y + 6 * u, y + 17 * u, u, color);
        vLine(g, x, y + 25 * u, y + 37 * u, u, color);
        for (int row : new int[]{-10, 10}) {
            hLine(g, x - 55 * u, x - 32 * u, y + row * u, u, color);
            hLine(g, x + 32 * u, x + 55 * u, y + row * u, u, color);
        }
        hLine(g, x - 90 * u, x - 67 * u, y, u, color);
        hLine(g, x + 67 * u, x + 90 * u, y, u, color);
        g.fill(x - u, y - u, x + u + 1, y + u + 1, color);
    }

    private static void corner(
            GuiGraphics g, int x, int y, int arm, int dx, int dy, int color
    ) {
        hLine(g, x, x + dx * arm, y, 1, color);
        vLine(g, x, y, y + dy * arm, 1, color);
    }

    private static void hLine(GuiGraphics g, int x0, int x1, int y, int thickness, int color) {
        g.fill(Math.min(x0, x1), y, Math.max(x0, x1) + 1, y + Math.max(1, thickness), color);
    }

    private static void vLine(GuiGraphics g, int x, int y0, int y1, int thickness, int color) {
        g.fill(x, Math.min(y0, y1), x + Math.max(1, thickness), Math.max(y0, y1) + 1, color);
    }

    private static void squareOutline(
            GuiGraphics g, int left, int top, int right, int bottom, int thickness, int color
    ) {
        hLine(g, left, right, top, thickness, color);
        hLine(g, left, right, bottom, thickness, color);
        vLine(g, left, top, bottom, thickness, color);
        vLine(g, right, top, bottom, thickness, color);
    }

    private static void ring(GuiGraphics g, int cx, int cy, int radius, int color) {
        int previousX = radius;
        int previousY = 0;
        int samples = Math.max(20, radius * 8);
        for (int sample = 1; sample <= samples; sample++) {
            double angle = Math.PI * 2.0 * sample / samples;
            int x = (int) Math.round(Math.cos(angle) * radius);
            int y = (int) Math.round(Math.sin(angle) * radius);
            diagonal(g, cx + previousX, cy + previousY, cx + x, cy + y, color);
            previousX = x;
            previousY = y;
        }
    }

    private static void chevron(
            GuiGraphics g, int tipX, int centreY, int direction, int halfHeight, int color
    ) {
        diagonal(g, tipX, centreY, tipX - direction * halfHeight, centreY - halfHeight / 2, color);
        diagonal(g, tipX, centreY, tipX - direction * halfHeight, centreY + halfHeight / 2, color);
    }

    private static void diagonal(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) return;
            int twice = error * 2;
            if (twice >= dy) { error += dy; x0 += sx; }
            if (twice <= dx) { error += dx; y0 += sy; }
        }
    }

    private ScopeHudRenderer() {
    }
}
