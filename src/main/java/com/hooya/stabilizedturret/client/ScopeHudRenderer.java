package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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

    @SubscribeEvent
    public static void suppressUnrelatedHud(RenderGuiLayerEvent.Pre event) {
        boolean podAllowed = VanillaGuiLayers.OVERLAY_MESSAGE.equals(event.getName());
        if ((ClientAimState.isPodView() && !podAllowed)
                || (ClientAimState.isScoped()
                && !VanillaGuiLayers.HOTBAR.equals(event.getName()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void renderScope(RenderGuiEvent.Post event) {
        if (!ClientAimState.isOpticalView()) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int cx = width / 2;
        int cy = height / 2;

        if (ClientAimState.isPodView()) {
            drawPodHud(graphics, width, height, event.getPartialTick().getGameTimeDeltaTicks());
            return;
        }

        drawOpticalVignette(graphics, width, height);

        Minecraft minecraft = Minecraft.getInstance();
        String range = String.format(Locale.ROOT, "%.0f m", ClientAimState.getLastAimDistance());
        String azimuth = String.format(Locale.ROOT, "AZ %.1f°", ClientAimState.getStableWorldYaw());
        String elevation = String.format(Locale.ROOT, "EL %+.1f°", -ClientAimState.getStableWorldPitch());
        graphics.drawString(
                minecraft.font,
                String.format(Locale.ROOT, "%.1fx", ClientAimState.getScopeZoom()),
                12,
                height - 20,
                RETICLE,
                true
        );
        graphics.drawCenteredString(minecraft.font, range, cx, height - 20, RETICLE);
        graphics.drawString(minecraft.font, azimuth, width - 92, height - 31, RETICLE, true);
        graphics.drawString(minecraft.font, elevation, width - 92, height - 20, RETICLE, true);
        graphics.drawString(minecraft.font, "STAB", 12, 12, ACTIVE, true);
        if (ClientAimState.isHeadAimEnabled()) {
            graphics.drawString(minecraft.font, "HEAD-AIM", 12, 24, 0xFF8BE46A, true);
        }
        graphics.drawString(
                minecraft.font,
                "WHOT",
                width - 40,
                12,
                ClientAimState.isThermal() ? 0xFFFFFFFF : DIM,
                true
        );
        ThirdPersonAimHudRenderer.renderAimMarkers(
                graphics,
                minecraft,
                width,
                height,
                event.getPartialTick().getGameTimeDeltaTicks(),
                true
        );
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

        String range = String.format(Locale.ROOT, "%.0f 米", ClientAimState.getLastAimDistance());
        graphics.drawCenteredString(minecraft.font, range, cx, cy + 28, green);
        graphics.drawString(
                minecraft.font,
                String.format(Locale.ROOT, "%.1fx", ClientAimState.getScopeZoom()),
                12,
                height - 20,
                green,
                true
        );
        graphics.drawString(
                minecraft.font,
                "WHOT",
                width - 40,
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

    private ScopeHudRenderer() {
    }
}
