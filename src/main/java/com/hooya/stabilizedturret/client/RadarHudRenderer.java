package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Vector3f;

import java.util.Locale;
import java.util.UUID;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class RadarHudRenderer {
    private static final int GREEN = 0xEE00FF22;
    private static final int VIEW_GREEN = 0xFF72FF88;
    private static final ResourceLocation RADAR_GRID = ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "textures/gui/radar_grid.png");
    private static final ResourceLocation RADAR_CONTACT = ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "textures/gui/radar_contact.png");

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientRadarState.isActive() || ClientAimState.isPodView()
                || minecraft.options.hideGui || minecraft.screen != null) return;
        GuiGraphics graphics = event.getGuiGraphics();
        float partialTick = event.getPartialTick().getGameTimeDeltaTicks();
        if (ClientRadarState.isAircraftMode()) renderAircraftScope(graphics, minecraft, partialTick);
        else renderRadarScope(graphics, minecraft);
        if (ClientRadarState.isAircraftMode()
                || ClientAimState.isScoped()
                || minecraft.options.getCameraType() != CameraType.FIRST_PERSON) {
            renderWorldFrames(graphics, minecraft, partialTick);
        }
    }

    private static void renderAircraftScope(GuiGraphics graphics, Minecraft minecraft, float partialTick) {
        int screenW = graphics.guiWidth(), screenH = graphics.guiHeight();
        double scale = Mth.clamp(ClientRadarState.getAircraftHudScale(), 40.0, 180.0) / 100.0;
        int width = Mth.clamp((int)Math.round(237.0 * scale), 95, Math.max(95, screenW - 8));
        int height = Math.max(90, (int)Math.round(width * 450.0 / 474.0));
        height = Math.min(height, Math.max(90, screenH - 8));
        int maxX = Math.max(0, screenW - width);
        int maxY = Math.max(0, screenH - height);
        int left = Mth.clamp((int)Math.round(maxX * ClientRadarState.getAircraftHudX() / 100.0), 0, maxX);
        int top = Mth.clamp((int)Math.round(maxY * ClientRadarState.getAircraftHudY() / 100.0), 0, maxY);
        drawTwsPanel(graphics, minecraft, left, top, width, height, partialTick, false, 0.0, 0.0, 0.0);
        // Flight data is intentionally independent from the movable radar panel.
        renderAircraftFlightInfo(graphics, minecraft, 8, 8);
    }

    /** One renderer is shared by the live HUD and the controller preview. */
    private static void drawTwsPanel(GuiGraphics g, Minecraft mc, int x, int y,
                                     int width, int height, float partialTick, boolean preview,
                                     double previewRange, double previewHalfAngle, double previewVerticalAngle) {
        int white = 0xEEDCE4E7, grid = 0xA0AAB6BB, dim = 0x88445158;
        int plotLeft = x + Math.round(width * 57F / 474F);
        int plotRight = x + Math.round(width * 420F / 474F);
        int plotTop = y + Math.round(height * 71F / 450F);
        int plotBottom = y + Math.round(height * 426F / 450F);
        int centerX = (plotLeft + plotRight) / 2;
        g.fill(plotLeft, plotTop, plotRight, plotBottom, 0xAA26343D);

        g.drawCenteredString(mc.font, "TWS", x + width / 2, y + Math.round(height * 8F / 450F), white);
        double halfAngle = preview ? previewHalfAngle : ClientRadarState.getAircraftHorizontalHalfAngle();
        double vertical = preview ? previewVerticalAngle : ClientRadarState.getAircraftVerticalAngle();
        double radarRange = preview ? previewRange : ClientRadarState.getRange();
        String scan = String.format(Locale.ROOT, "%.0f°×%.0f° *", halfAngle, vertical);
        String range = radarRange >= 1000.0
                ? String.format(Locale.ROOT, radarRange < 10000.0 ? "%.1f km" : "%.0f km", radarRange / 1000.0)
                : String.format(Locale.ROOT, "%.0f m", radarRange);
        g.drawString(mc.font, scan, plotLeft, y + Math.round(height * 31F / 450F), white, false);
        g.drawString(mc.font, range, plotRight - mc.font.width(range),
                y + Math.round(height * 31F / 450F), white, false);

        String upper = "+" + Math.round(halfAngle) + "°";
        String middle = "0°";
        String lower = "-" + Math.round(halfAngle) + "°";
        g.drawString(mc.font, upper, x + 1, plotTop - 4, white, false);
        g.drawString(mc.font, middle, x + 1, (plotTop + plotBottom) / 2 - 4, white, false);
        g.drawString(mc.font, lower, x + 1, plotBottom - 8, white, false);
        String zero = "0 m";
        g.drawString(mc.font, zero, plotRight - mc.font.width(zero), plotBottom + 4, white, false);

        // Grid proportions are copied from the supplied 474x450 reference.
        for (int column = 0; column <= 5; column++) {
            int gx = plotLeft + (plotRight - plotLeft) * column / 5;
            g.fill(gx, plotTop, gx + 1, plotBottom, grid);
        }
        for (int row = 0; row <= 4; row++) {
            int gy = plotTop + (plotBottom - plotTop) * row / 4;
            g.fill(plotLeft, gy, plotRight, gy + 1, grid);
        }
        g.fill(centerX - 1, plotTop, centerX + 2, plotBottom, 0xC9DCE4E7);

        // Triangle-wave sweep: left -> right -> left, with no wrap/jump.
        long time = mc.level == null ? 0L : mc.level.getGameTime();
        double phase = (time % 160L) / 80.0;
        double sweep = phase <= 1.0 ? phase : 2.0 - phase;
        int sweepX = plotLeft + (int)Math.round((plotRight - plotLeft) * sweep);
        g.fill(sweepX - 1, plotTop, sweepX + 2, plotBottom, 0xB8EDF2F3);

        // Reference-style right toolbar: seven dim square selectors.
        int toolX = plotRight + Math.max(2, width / 80);
        int toolSize = Math.max(7, Math.round(width * 28F / 474F));
        int toolGap = Math.max(1, Math.round(height * 4F / 450F));
        for (int i = 0; i < 7; i++) {
            int ty = plotTop + i * (toolSize + toolGap);
            if (ty + toolSize > plotBottom) break;
            g.fill(toolX, ty, toolX + toolSize, ty + toolSize, dim);
            int cx = toolX + toolSize / 2, cy = ty + toolSize / 2;
            if (i == 0) { g.fill(cx - 3, cy - 2, cx + 4, cy - 1, white); g.fill(cx - 2, cy + 2, cx + 3, cy + 3, white); }
            else if (i == 1) drawWhiteAircraftSymbol(g, cx, cy, Math.max(2, toolSize / 4));
            else { g.fill(cx - 1, cy - 3, cx + 1, cy + 4, white); g.fill(cx - 3, cy, cx + 4, cy + 1, white); }
        }

        if (preview) {
            int px = plotLeft + (plotRight - plotLeft) * 2 / 5;
            int py = plotBottom - (plotBottom - plotTop) / 5;
            drawWhiteAircraftSymbol(g, px, py, Math.max(3, width / 55));
            drawWhiteSelectionBars(g, px, py, Math.max(3, width / 55));
            drawWhiteAircraftSymbol(g, centerX + (plotRight - plotLeft) / 5,
                    plotBottom - (plotBottom - plotTop) / 3, Math.max(3, width / 55));
            return;
        }

        Vec3 origin = ClientRadarState.getOrigin();
        Vec3 forward = ClientRadarState.getForward().normalize();
        Vec3 up = ClientRadarState.getUp().normalize();
        Vec3 right = forward.cross(up).normalize();
        ClientRadarState.Contact selected = selectedAircraftContact(mc, partialTick);
        for (ClientRadarState.Contact contact : ClientRadarState.getContacts()) {
            Vec3 delta = contact.center().subtract(origin);
            double longitudinal = delta.dot(forward);
            double azimuth = Math.toDegrees(Math.atan2(delta.dot(right), Math.max(1.0e-6, longitudinal)));
            double distance = delta.length();
            if (longitudinal <= 0.0 || distance > radarRange || Math.abs(azimuth) > halfAngle) continue;
            int px = centerX + (int)Math.round(azimuth / halfAngle * (plotRight - plotLeft) / 2.0);
            int py = plotBottom - (int)Math.round(distance / radarRange * (plotBottom - plotTop));
            int icon = Math.max(3, width / 55);
            drawWhiteAircraftSymbol(g, px, py, icon);
            if (selected != null && selected.id().equals(contact.id())) drawWhiteSelectionBars(g, px, py, icon);
        }
    }

    private static void renderAircraftFlightInfo(GuiGraphics graphics, Minecraft minecraft, int x, int y) {
        int color = 0xEEDDE5E8;
        Vec3 velocity = ClientRadarState.getOwnVelocity();
        double metresPerSecond = velocity.length() * 20.0;
        graphics.drawString(minecraft.font, String.format(Locale.ROOT, "速度      %.0f km/h", metresPerSecond * 3.6), x, y, color, false);
        graphics.drawString(minecraft.font, String.format(Locale.ROOT, "马赫数    %.2f", metresPerSecond / 340.3), x, y + 11, color, false);
        graphics.drawString(minecraft.font, String.format(Locale.ROOT, "高度      %.0f m", ClientRadarState.getOrigin().y), x, y + 22, color, false);
        String[] names = {"远距离空空弹", "中距离空空弹", "近距离空空弹",
                "普通空地弹", "攻顶空地弹", "激光制导弹"};
        int[] ammo = {ClientRadarState.getFarAmmo(), ClientRadarState.getMediumAmmo(),
                ClientRadarState.getCloseAmmo(), ClientRadarState.getAirToGroundDirectAmmo(),
                ClientRadarState.getAirToGroundTopAttackAmmo(), ClientRadarState.getLaserGuidedAmmo()};
        for (int i = 0; i < names.length; i++) {
            graphics.drawString(minecraft.font,
                    (i == ClientRadarState.getSelectedAircraftWeapon() ? "> " : "  ") + names[i] + "  " + ammo[i],
                    x, y + 42 + i * 11, color, false);
        }
    }

    private static void drawWhiteAircraftSymbol(GuiGraphics g,int x,int y,int size){
        int c=0xFFF2F5F6;g.fill(x,y-size,x+1,y+size+1,c);g.fill(x-size,y-1,x+size+1,y+1,c);
        g.fill(x-size/2,y+2,x+size/2+1,y+3,c);
    }
    private static void drawWhiteSelectionBars(GuiGraphics g,int x,int y,int size){
        int c=0xFFF2F5F6,gap=size+3;g.fill(x-gap,y-size,x-gap+2,y+size+1,c);g.fill(x+gap-1,y-size,x+gap+1,y+size+1,c);
    }

    private static ClientRadarState.Contact selectedAircraftContact(Minecraft minecraft,float partialTick){
        UUID selectedId = ClientRadarState.getSelectedTarget();
        if (selectedId != null) for (var contact : ClientRadarState.getContacts())
            if (contact.id().equals(selectedId)) return contact;
        UUID locked = ClientRadarState.getLockedTarget();
        if (locked != null) for (var contact : ClientRadarState.getContacts())
            if (contact.id().equals(locked)) return contact;
        int w=minecraft.getWindow().getGuiScaledWidth(),h=minecraft.getWindow().getGuiScaledHeight();
        int aimX=w/2+(int)Math.round(w*ClientAimState.getViewAimOffsetX()/100.0);
        int aimY=h/2+(int)Math.round(h*ClientAimState.getViewAimOffsetY()/100.0);
        ClientRadarState.Contact best=null;double bestD=Double.POSITIVE_INFINITY;
        for(var c:ClientRadarState.getContacts()){ScreenPoint p=project(minecraft,c.center(),w,h,partialTick);if(p==null)continue;
            double d=(p.x-aimX)*(double)(p.x-aimX)+(p.y-aimY)*(double)(p.y-aimY);if(d<bestD){bestD=d;best=c;}}
        return best;
    }

    private static void renderRadarScope(GuiGraphics graphics, Minecraft minecraft) {
        int diameter = Mth.clamp((int) Math.round(212.0 * ClientRadarState.getHudScale() / 100.0), 72, 318);
        int extraWidth = 34;
        int extraHeight = 37;
        int maxX = Math.max(0, graphics.guiWidth() - diameter - extraWidth);
        int maxY = Math.max(0, graphics.guiHeight() - diameter - extraHeight);
        int left = Mth.clamp((int) Math.round(maxX * ClientRadarState.getHudX() / 100.0), 0, maxX);
        int top = Mth.clamp((int) Math.round(maxY * ClientRadarState.getHudY() / 100.0), 0, maxY);
        drawRadarTexture(graphics, left, top, diameter);

        int cx = left + diameter / 2;
        int cy = top + diameter / 2;
        int radius = Math.max(1, (int) Math.round(diameter * 0.459));

        float sweep = ClientRadarState.getSweepDegrees();
        for (int trail = 3; trail >= 0; trail--) {
            int alpha = trail == 0 ? 0xE8 : 0x22 + (3 - trail) * 0x24;
            drawRadialLine(graphics, cx, cy, radius, sweep - trail * 2.2F,
                    (alpha << 24) | 0x00FF22, trail == 0 ? 2 : 1);
        }

        Vector3f look = minecraft.gameRenderer.getMainCamera().getLookVector();
        float viewDegrees = (float) Math.toDegrees(Math.atan2(look.x, -look.z));
        drawRadialLine(graphics, cx, cy, (int) Math.round(radius * 0.88),
                viewDegrees, VIEW_GREEN, 2);
        drawDirectionTip(graphics, cx, cy, radius, viewDegrees);

        Vec3 origin = ClientRadarState.getOrigin();
        double range = Math.max(1.0, ClientRadarState.getRange());
        UUID locked = ClientRadarState.getLockedTarget();
        int iconSize = Mth.clamp((int) Math.round(diameter / 30.0), 4, 9);
        for (ClientRadarState.Contact contact : ClientRadarState.getContacts()) {
            Vec3 delta = contact.center().subtract(origin);
            int x = cx + (int) Math.round(delta.x / range * radius);
            int y = cy + (int) Math.round(delta.z / range * radius);
            if ((x - cx) * (long) (x - cx) + (y - cy) * (long) (y - cy) > radius * (long) radius) continue;
            drawAircraft(graphics, x, y, iconSize, contact.velocity());
            if (contact.id().equals(locked)) drawRadarLockBars(graphics, x, y, iconSize);
        }

        drawCompassLabels(graphics, minecraft, left, top, diameter, cx, cy, radius);
    }

    private static void drawRadarTexture(GuiGraphics graphics, int x, int y, int size) {
        graphics.blit(RADAR_GRID, x, y, size, size, 0.0F, 0.0F,
                1024, 1024, 1024, 1024);
    }

    private static void drawCompassLabels(
            GuiGraphics graphics, Minecraft minecraft, int left, int top, int diameter,
            int cx, int cy, int radius
    ) {
        int fontHeight = minecraft.font.lineHeight;
        graphics.drawCenteredString(minecraft.font, "0°", cx, top - fontHeight + 3, GREEN);
        graphics.drawString(minecraft.font, "90°", cx + radius + 3, cy - 4, GREEN, false);
        graphics.drawCenteredString(minecraft.font, "180°", cx, top + diameter - 2, GREEN);
        String west = "270°";
        graphics.drawString(minecraft.font, west, cx - radius - minecraft.font.width(west) - 3,
                cy - 4, GREEN, false);

        int textY = top + diameter + 10;
        graphics.drawString(minecraft.font, "SRC", left, textY, GREEN, false);
        graphics.drawString(minecraft.font,
                "360°  ↑" + Math.round(ClientRadarState.getElevation())
                        + "°  ↓" + Math.round(ClientRadarState.getDepression()) + "°",
                left, textY + 10, GREEN, false);
        double displayRange = ClientRadarState.getRange();
        String distance = displayRange >= 1000.0
                ? String.format(Locale.ROOT, "%.1f km*", displayRange / 1000.0)
                : String.format(Locale.ROOT, "%.0f m*", displayRange);
        graphics.drawString(minecraft.font, distance, left, textY + 20, GREEN, false);
    }

    private static void drawRadialLine(
            GuiGraphics graphics, int cx, int cy, int length, float degrees, int color, int width
    ) {
        graphics.pose().pushPose();
        graphics.pose().translate(cx, cy, 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(degrees));
        int half = width / 2;
        graphics.fill(-half, -length, width - half, 0, color);
        graphics.pose().popPose();
    }

    private static void drawDirectionTip(GuiGraphics graphics, int cx, int cy, int radius, float degrees) {
        graphics.pose().pushPose();
        graphics.pose().translate(cx, cy, 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(degrees));
        int y = -(int) Math.round(radius * 0.88);
        graphics.fill(-3, y, 4, y + 1, VIEW_GREEN);
        graphics.fill(-2, y - 1, 3, y, VIEW_GREEN);
        graphics.pose().popPose();
    }

    private static void drawAircraft(GuiGraphics graphics, int x, int y, int size, Vec3 velocity) {
        float heading = velocity.horizontalDistanceSqr() > 1.0e-6
                ? (float) Math.toDegrees(Math.atan2(velocity.x, -velocity.z)) : 0.0F;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(heading));
        int half = size / 2;
        graphics.blit(RADAR_CONTACT, -half, -half, size, size, 0.0F, 0.0F,
                256, 256, 256, 256);
        graphics.pose().popPose();
    }

    public static void renderPreview(GuiGraphics graphics, int x, int y, int size) {
        drawRadarTexture(graphics, x, y, size);
        int cx = x + size / 2;
        int cy = y + size / 2;
        int radius = Math.max(1, (int) Math.round(size * 0.459));
        drawRadialLine(graphics, cx, cy, radius, 32.0F, GREEN, 1);
        drawRadialLine(graphics, cx, cy, (int) Math.round(radius * 0.88), -18.0F, VIEW_GREEN, 1);
        int icon = Mth.clamp(size / 22, 3, 6);
        drawAircraft(graphics, cx + radius / 3, cy - radius / 4, icon, new Vec3(0.4, 0.0, -0.8));
        drawAircraft(graphics, cx - radius / 2, cy + radius / 3, icon, new Vec3(-0.4, 0.0, 0.6));
    }

    public static void renderAircraftPreview(GuiGraphics graphics, int x, int y, int width, int height,
                                             double range, double halfAngle, double verticalAngle) {
        Minecraft minecraft = Minecraft.getInstance();
        int exactHeight = Math.min(height, Math.max(1, (int)Math.round(width * 450.0 / 474.0)));
        drawTwsPanel(graphics, minecraft, x, y, width, exactHeight, 1.0F, true,
                Math.max(200.0, range), Math.max(5.0, halfAngle), Math.max(2.0, verticalAngle));
    }

    private static void drawRadarLockBars(GuiGraphics graphics, int x, int y, int iconSize) {
        int half = Math.max(2, iconSize / 2);
        int gap = half + 2;
        graphics.fill(x - gap - 1, y - half, x - gap, y + half + 1, GREEN);
        graphics.fill(x + gap, y - half, x + gap + 1, y + half + 1, GREEN);
    }

    private static void renderWorldFrames(GuiGraphics graphics, Minecraft minecraft, float partialTick) {
        int width = graphics.guiWidth();
        if (ClientRadarState.isAircraftMode()) {
            ClientRadarState.Contact contact = selectedAircraftContact(minecraft, partialTick);
            if (contact == null) return;
            ScreenPoint center = project(minecraft, contact.center(), width, graphics.guiHeight(), partialTick);
            ScreenSize size = projectBounds(minecraft, contact.bounds(), graphics.guiHeight(), partialTick);
            if (center == null || size == null) return;
            int half = Mth.clamp((Math.max(size.width,size.height)+1)/2+2,7,220);
            drawCornerSquare(graphics,center.x,center.y,half);
            double distance=contact.center().distanceTo(ClientRadarState.getOrigin());
            Vec3 los=contact.center().subtract(ClientRadarState.getOrigin()).normalize();
            double closing=-contact.velocity().subtract(ClientRadarState.getOwnVelocity()).dot(los)*20.0;
            String text=String.format(Locale.ROOT,"%.1f km   %.0f m/s",distance/1000.0,closing);
            graphics.drawCenteredString(minecraft.font,text,center.x,center.y-half-12,GREEN);
            return;
        }
        int height = graphics.guiHeight();
        UUID locked = ClientRadarState.getLockedTarget();
        for (ClientRadarState.Contact contact : ClientRadarState.getContacts()) {
            ScreenPoint center = project(minecraft, contact.center(), width, height, partialTick);
            ScreenSize size = projectBounds(minecraft, contact.bounds(), height, partialTick);
            if (center == null || size == null) continue;
            int half = Mth.clamp((Math.max(size.width, size.height) + 1) / 2 + 2, 7, 220);
            if (contact.id().equals(locked)) {
                drawConnectedSquare(graphics, center.x, center.y, half);
            } else {
                drawCornerSquare(graphics, center.x, center.y, half);
            }
        }
    }

    public static UUID findNearestContactToReticle() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientRadarState.isActive() || minecraft.level == null) return null;
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        int aimX = ClientAimState.isScoped() ? width / 2
                : width / 2 + (int) Math.round(width * ClientAimState.getViewAimOffsetX() / 100.0);
        int aimY = ClientAimState.isScoped() ? height / 2
                : height / 2 + (int) Math.round(height * ClientAimState.getViewAimOffsetY() / 100.0);
        UUID best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (ClientRadarState.Contact contact : ClientRadarState.getContacts()) {
            ScreenPoint point = project(minecraft, contact.center(), width, height, 1.0F);
            if (point == null || point.x < 0 || point.x >= width || point.y < 0 || point.y >= height) continue;
            double distance = (point.x - aimX) * (double) (point.x - aimX)
                    + (point.y - aimY) * (double) (point.y - aimY);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = contact.id();
            }
        }
        return best;
    }

    private static ScreenPoint project(Minecraft minecraft, Vec3 world, int width, int height, float partialTick) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 relative = world.subtract(camera.getPosition());
        Vector3f forwardVector = camera.getLookVector();
        Vector3f leftVector = camera.getLeftVector();
        Vector3f upVector = camera.getUpVector();
        Vec3 forward = new Vec3(forwardVector.x, forwardVector.y, forwardVector.z);
        Vec3 left = new Vec3(leftVector.x, leftVector.y, leftVector.z);
        Vec3 up = new Vec3(upVector.x, upVector.y, upVector.z);
        double depth = relative.dot(forward);
        if (depth <= 0.05) return null;
        double fov = ((GameRendererAccessor) (Object) minecraft.gameRenderer)
                .stabilizedTurret$getFov(camera, partialTick, true);
        double focal = height * 0.5 / Math.tan(Mth.DEG_TO_RAD * fov * 0.5);
        int x = width / 2 + (int) Math.round(-relative.dot(left) * focal / depth);
        int y = height / 2 + (int) Math.round(-relative.dot(up) * focal / depth);
        return new ScreenPoint(x, y);
    }

    private static ScreenSize projectBounds(Minecraft minecraft, AABB bounds, int height, float partialTick) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vector3f forwardVector = camera.getLookVector();
        Vector3f leftVector = camera.getLeftVector();
        Vector3f upVector = camera.getUpVector();
        Vec3 forward = new Vec3(forwardVector.x, forwardVector.y, forwardVector.z);
        Vec3 left = new Vec3(leftVector.x, leftVector.y, leftVector.z);
        Vec3 up = new Vec3(upVector.x, upVector.y, upVector.z);
        double fov = ((GameRendererAccessor) (Object) minecraft.gameRenderer)
                .stabilizedTurret$getFov(camera, partialTick, true);
        double focal = height * 0.5 / Math.tan(Mth.DEG_TO_RAD * fov * 0.5);
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        int visible = 0;
        for (int ix = 0; ix < 2; ix++) for (int iy = 0; iy < 2; iy++) for (int iz = 0; iz < 2; iz++) {
            Vec3 corner = new Vec3(ix == 0 ? bounds.minX : bounds.maxX,
                    iy == 0 ? bounds.minY : bounds.maxY,
                    iz == 0 ? bounds.minZ : bounds.maxZ);
            Vec3 relative = corner.subtract(camera.getPosition());
            double depth = relative.dot(forward);
            if (depth <= 0.05) continue;
            double x = -relative.dot(left) * focal / depth;
            double y = -relative.dot(up) * focal / depth;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            visible++;
        }
        return visible == 0 ? null : new ScreenSize(
                (int) Math.ceil(maxX - minX), (int) Math.ceil(maxY - minY));
    }

    private static void drawCornerSquare(GuiGraphics graphics, int x, int y, int half) {
        int arm = Math.max(4, half / 3);
        int l = x - half, r = x + half, t = y - half, b = y + half;
        graphics.fill(l, t, l + arm, t + 1, GREEN); graphics.fill(l, t, l + 1, t + arm, GREEN);
        graphics.fill(r - arm, t, r, t + 1, GREEN); graphics.fill(r - 1, t, r, t + arm, GREEN);
        graphics.fill(l, b - 1, l + arm, b, GREEN); graphics.fill(l, b - arm, l + 1, b, GREEN);
        graphics.fill(r - arm, b - 1, r, b, GREEN); graphics.fill(r - 1, b - arm, r, b, GREEN);
    }

    private static void drawConnectedSquare(GuiGraphics graphics, int x, int y, int half) {
        int l=x-half, r=x+half, t=y-half, b=y+half;
        graphics.fill(l,t,r,t+1,GREEN); graphics.fill(l,b-1,r,b,GREEN);
        graphics.fill(l,t+1,l+1,b-1,GREEN); graphics.fill(r-1,t+1,r,b-1,GREEN);
    }

    private record ScreenPoint(int x, int y) {}
    private record ScreenSize(int width, int height) {}
    private RadarHudRenderer() {}
}
