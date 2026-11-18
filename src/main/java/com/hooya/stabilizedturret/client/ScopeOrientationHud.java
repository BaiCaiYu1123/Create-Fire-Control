package com.hooya.stabilizedturret.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.joml.Quaternionf;

import java.util.Locale;

final class ScopeOrientationHud {
    private static final int HULL = 0xF0FFFFFF;
    private static final int TURRET = 0xFFFFCF64;

    static void draw(GuiGraphics graphics, float partialTick) {
        var angles = ClientScopeOrientation.angles(partialTick);
        if (angles == null) return;
        var layout = ScopeOrientationGeometry.layout(graphics.guiWidth(), graphics.guiHeight());
        double cameraYaw = Minecraft.getInstance().gameRenderer.getMainCamera().getYRot();
        // Submit dark outlines first, then fine colored strokes for contrast on terrain and thermal.
        for (boolean shadow : new boolean[]{true, false}) {
            ScopeOrientationGeometry.dial(lines(graphics, layout, 0x907F8A91, shadow));
            ScopeOrientationGeometry.hull(angles.hull() - cameraYaw, lines(graphics, layout, HULL, shadow));
            ScopeOrientationGeometry.turret(angles.turret() - cameraYaw, lines(graphics, layout, TURRET, shadow));
        }
        var font = Minecraft.getInstance().font;
        int textY = (int) Math.round(layout.y() + 59 * layout.scale());
        Component label = Component.translatable("hud.create_fire_control.scope.turret_relative",
                String.format(Locale.ROOT, "%+.0f", Math.abs(angles.relativeTurret()) < 0.5 ? 0 : angles.relativeTurret()));
        float labelScale = (float) Math.min(1.0, 2 * ScopeOrientationGeometry.RADIUS * layout.scale()
                / Math.max(1, font.width(label)));
        graphics.pose().pushPose();
        graphics.pose().translate(layout.x(), textY, 0);
        graphics.pose().scale(labelScale, labelScale, 1);
        graphics.drawCenteredString(font, label, 0, 0, TURRET);
        graphics.pose().popPose();
    }

    private static ScopeOrientationGeometry.Lines lines(GuiGraphics graphics,
            ScopeOrientationGeometry.Layout layout, int color, boolean shadow) {
        return (x0, y0, x1, y1) -> {
            float dx = (float) ((x1 - x0) * layout.scale());
            float dy = (float) ((y1 - y0) * layout.scale());
            float length = (float) Math.hypot(dx, dy);
            float stroke = shadow ? 2.5F : 1.0F;
            graphics.pose().pushPose();
            graphics.pose().translate(layout.x() + x0 * layout.scale(), layout.y() + y0 * layout.scale(), 0);
            graphics.pose().mulPose(new Quaternionf().rotationZ((float) Math.atan2(dy, dx)));
            graphics.pose().translate(-stroke / 2, -stroke / 2, 0);
            graphics.pose().scale(length + stroke, stroke, 1);
            graphics.fill(0, 0, 1, 1, shadow ? 0xB8000000 : color);
            graphics.pose().popPose();
        };
    }

    private ScopeOrientationHud() {}
}
