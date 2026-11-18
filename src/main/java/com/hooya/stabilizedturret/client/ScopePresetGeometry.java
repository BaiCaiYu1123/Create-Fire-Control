package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.ScopePreset;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Draws extracted sight shapes with the same one-GUI-pixel stroke as default. */
final class ScopePresetGeometry {
    private static final int REFERENCE_WIDTH = 1424;
    private static final int REFERENCE_HEIGHT = 800;
    private static final int BLACK = 0xFF000000;
    private static final EnumMap<ScopePreset, Layers> CACHE = new EnumMap<>(ScopePreset.class);

    private ScopePresetGeometry() {
    }

    static void drawFrame(
            GuiGraphics graphics, int screenWidth, int screenHeight, ScopePreset preset
    ) {
        layers(preset).frame().draw(
                graphics,
                screenWidth * 0.5,
                screenHeight * 0.5,
                screenWidth / (double) REFERENCE_WIDTH,
                screenHeight / (double) REFERENCE_HEIGHT,
                isHorizontallySymmetric(preset)
        );
    }

    static void drawReticle(
            GuiGraphics graphics,
            int screenWidth,
            int screenHeight,
            ScopePreset preset,
            int aimX,
            int aimY,
            float sightScale
    ) {
        layers(preset).reticle().draw(
                graphics,
                aimX,
                aimY,
                screenWidth / (double) REFERENCE_WIDTH * sightScale,
                screenHeight / (double) REFERENCE_HEIGHT * sightScale,
                isHorizontallySymmetric(preset)
        );
    }

    private static boolean isHorizontallySymmetric(ScopePreset preset) {
        return preset != null && preset != ScopePreset.DEFAULT && preset != ScopePreset.ELB;
    }

    private static Layers layers(ScopePreset preset) {
        if (preset == null || preset == ScopePreset.DEFAULT) return Layers.EMPTY;
        return CACHE.computeIfAbsent(preset, ScopePresetGeometry::loadLayers);
    }

    private static Layers loadLayers(ScopePreset preset) {
        String base = "textures/gui/scope/" + preset.serializedName();
        return new Layers(
                load(base + "_frame_geometry.png"),
                load(base + "_reticle_geometry.png")
        );
    }

    private static ShapeLayer load(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, path);
        try {
            Resource resource = Minecraft.getInstance().getResourceManager()
                    .getResource(id)
                    .orElseThrow(() -> new IOException("Missing scope geometry " + id));
            try (InputStream stream = resource.open(); NativeImage image = NativeImage.read(stream)) {
                List<Run> runs = new ArrayList<>();
                for (int y = 0; y < image.getHeight(); y++) {
                    int x = 0;
                    while (x < image.getWidth()) {
                        while (x < image.getWidth() && !set(image, x, y)) x++;
                        int start = x;
                        while (x < image.getWidth() && set(image, x, y)) x++;
                        if (start < x) runs.add(new Run(start, x, y));
                    }
                }
                return new ShapeLayer(List.copyOf(runs));
            }
        } catch (IOException exception) {
            StabilizedTurret.LOGGER.error("Unable to load gun-sight geometry {}", id, exception);
            return ShapeLayer.EMPTY;
        }
    }

    private static boolean set(NativeImage image, int x, int y) {
        return (image.getLuminanceOrAlpha(x, y) & 0xFF) != 0;
    }

    private record Layers(ShapeLayer frame, ShapeLayer reticle) {
        private static final Layers EMPTY = new Layers(ShapeLayer.EMPTY, ShapeLayer.EMPTY);
    }

    private record Run(int startX, int endX, int y) {
    }

    private record ShapeLayer(List<Run> runs) {
        private static final ShapeLayer EMPTY = new ShapeLayer(List.of());

        private void draw(
                GuiGraphics graphics,
                double anchorX,
                double anchorY,
                double scaleX,
                double scaleY,
                boolean horizontallySymmetric
        ) {
            int color = ScopeHudRenderer.sightLineColor(BLACK);
            for (Run run : runs) {
                if (horizontallySymmetric && run.startX() > REFERENCE_WIDTH / 2) {
                    continue;
                }
                int sourceEnd = horizontallySymmetric
                        ? Math.min(run.endX(), REFERENCE_WIDTH / 2 + 1)
                        : run.endX();
                int x0 = mapped(anchorX, run.startX() - REFERENCE_WIDTH * 0.5, scaleX);
                int x1 = mapped(anchorX, sourceEnd - REFERENCE_WIDTH * 0.5, scaleX);
                int y0 = mapped(anchorY, run.y() - REFERENCE_HEIGHT * 0.5, scaleY);
                int y1 = mapped(anchorY, run.y() + 1.0 - REFERENCE_HEIGHT * 0.5, scaleY);

                // A horizontal centre-line keeps one GUI pixel vertically;
                // a vertical/diagonal centre-line keeps one horizontally.
                if (run.endX() - run.startX() > 1) {
                    y1 = y0 + 1;
                    if (x1 <= x0) x1 = x0 + 1;
                } else {
                    x1 = x0 + 1;
                    if (y1 <= y0) y1 = y0 + 1;
                }
                graphics.fill(x0, y0, x1, y1, color);
                if (horizontallySymmetric && x0 < Math.round(anchorX)) {
                    // Reflect rendered pixel centres, not scaled source
                    // boundaries.  Both halves therefore remain identical
                    // even when a resolution maps a source coordinate onto a
                    // half GUI pixel and would otherwise round differently.
                    int axisX = (int) Math.round(anchorX);
                    int mirroredX0 = 2 * axisX - x1 + 1;
                    int mirroredX1 = 2 * axisX - x0 + 1;
                    graphics.fill(mirroredX0, y0, mirroredX1, y1, color);
                }
            }
        }

        private static int mapped(double anchor, double local, double scale) {
            return (int) Math.round(anchor + local * scale);
        }
    }
}
