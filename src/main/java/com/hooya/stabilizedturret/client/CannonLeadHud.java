package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import com.hooya.stabilizedturret.network.CannonLockLeadPayload.LeadStatus;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** The reference's small open lead circle, linked from the edge of the existing target frame. */
final class CannonLeadHud {
    static void render(GuiGraphics graphics, Minecraft minecraft, int targetX, int targetY,
                       @Nullable Vec3 lead, LeadStatus status, float partialTick, int color) {
        if ((color >>> 24) < 4) return;
        if (lead == null) {
            if (status == LeadStatus.NO_AMMUNITION || status == LeadStatus.UNREACHABLE) {
                var label = Component.translatable(status == LeadStatus.NO_AMMUNITION
                        ? "hud.create_fire_control.cannon_lead.no_ammunition"
                        : "hud.create_fire_control.cannon_lead.unreachable");
                int y = targetY + Math.round(graphics.guiHeight() * 110 / TargetLockFrame.REFERENCE_HEIGHT);
                graphics.drawCenteredString(minecraft.font, label, targetX, y, (color & 0xFF000000) | 0xFF6655);
            }
            return;
        }
        var camera = minecraft.gameRenderer.getMainCamera();
        Vec3 relative = lead.subtract(camera.getPosition());
        double depth = relative.dot(new Vec3(camera.getLookVector()));
        if (depth <= .05 || !Double.isFinite(depth)) return;
        double fov = ((GameRendererAccessor) (Object) minecraft.gameRenderer)
                .stabilizedTurret$getFov(camera, partialTick, true);
        double focal = graphics.guiHeight() * .5 / Math.tan(Math.toRadians(fov) * .5);
        double x = graphics.guiWidth() * .5 - relative.dot(new Vec3(camera.getLeftVector())) * focal / depth;
        double y = graphics.guiHeight() * .5 - relative.dot(new Vec3(camera.getUpVector())) * focal / depth;
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || x < 0 || x >= graphics.guiWidth() || y < 0 || y >= graphics.guiHeight()) return;
        float scale = graphics.guiHeight() / TargetLockFrame.REFERENCE_HEIGHT;
        double dx = (x - targetX) / scale, dy = (y - targetY) / scale;
        double distance = Math.hypot(dx, dy), radius = 20;
        graphics.pose().pushPose();
        graphics.pose().translate(targetX, targetY, 0);
        graphics.pose().scale(scale, scale, 1);
        if (distance > 1e-6) {
            double start = TargetLockFrame.REFERENCE_SIDE * .5 * distance / Math.max(Math.abs(dx), Math.abs(dy));
            double end = distance - radius;
            if (end > start) line(graphics, dx * start / distance, dy * start / distance,
                    dx * end / distance, dy * end / distance, 2, color);
        }
        for (int segment = 0; segment < 64; segment++) {
            double a = segment * Math.PI / 32, b = (segment + 1) * Math.PI / 32;
            line(graphics, dx + Math.cos(a) * radius, dy + Math.sin(a) * radius,
                    dx + Math.cos(b) * radius, dy + Math.sin(b) * radius, 3, color);
        }
        graphics.pose().popPose();
    }

    private static void line(GuiGraphics graphics, double x0, double y0, double x1, double y1,
                             int width, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(x0, y0, 0);
        graphics.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(y1 - y0, x1 - x0)));
        graphics.fill(0, -width / 2, (int) Math.ceil(Math.hypot(x1 - x0, y1 - y0)), (width + 1) / 2, color);
        graphics.pose().popPose();
    }

    private CannonLeadHud() {}
}
