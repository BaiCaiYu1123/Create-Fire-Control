package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
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
    // Circular radar palette follows the aircraft scope: low-luminance slate
    // glass, desaturated cyan symbology, and amber target/lock accents.
    // Keeping alpha in the colours is intentional so the world remains
    // readable through the HUD instead of being covered by an opaque panel.
    private static final int GREEN = 0xD06ED8C7;
    private static final int VIEW_GREEN = 0xE5B5F3E3;
    private static final int RADAR_CONTACT_CYAN = 0xE7B8F4E4;
    private static final int RADAR_CONTACT_AMBER = 0xF2E4B56A;
    private static final int RADAR_LOCK_AMBER = 0xF6F4B866;
    // The world-space lock frame keeps the original bright radar green.  The
    // circular HUD palette above is intentionally left unchanged.
    private static final int LOCK_FRAME_GREEN = 0xFF00FF00;
    private static final int RADAR_DISC = 0x70222E36;
    private static final ResourceLocation RADAR_GRID = ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "textures/gui/radar_grid.png");
    private static final ResourceLocation RWR_DIAL = ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "textures/gui/rwr_dial.png");

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientRadarState.isActive() || ClientAimState.isPodView()
                || ClientTelevisionGuidanceState.isActive()
                || minecraft.options.hideGui || minecraft.screen != null) return;
        GuiGraphics graphics = event.getGuiGraphics();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
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
        renderRwr(graphics, minecraft);
        // Flight data is intentionally independent from the movable radar panel.
        renderAircraftFlightInfo(graphics, minecraft, 8, 8);
    }

    private static void renderRwr(GuiGraphics graphics, Minecraft minecraft) {
        if (ClientRadarState.getThreats().isEmpty()) return;
        int screenW = graphics.guiWidth();
        int screenH = graphics.guiHeight();
        double scale = Mth.clamp(ClientRadarState.getRwrScale(), 40.0, 180.0) / 100.0;
        // Measured from the supplied 2048x864 reference: diameter 11.8% W,
        // centre 8.2% W / 52.5% H. Width-based sizing preserves the same
        // circular HUD proportions on every aspect ratio.
        int diameter = Math.max(48, (int) Math.round(screenW * 0.118 * scale));
        int radius = diameter / 2;
        int cx = Mth.clamp((int) Math.round(screenW * 0.082), radius + 2,
                Math.max(radius + 2, screenW - radius - 2));
        int cy = Mth.clamp((int) Math.round(screenH * 0.525), radius + 2,
                Math.max(radius + 2, screenH - radius - 2));
        int innerRadius = Math.max(12, (int) Math.round(radius * 0.64));
        int centreRadius = Math.max(5, (int) Math.round(radius * 0.115));
        int detailScale = Mth.clamp(
                (int) Math.ceil(minecraft.getWindow().getGuiScale()), 1, 6);
        // Use the same ARGB fill path as the radar panel for the translucent
        // background. Some GUI texture pipelines treat partial texture alpha
        // as cutout, which made the previous baked background look opaque.
        fillRwrBackground(graphics, cx, cy, radius, 0x304D5359);
        // The rings and ticks remain a single filtered texture draw.
        graphics.blit(RWR_DIAL, cx - radius, cy - radius,
                diameter, diameter, 0.0F, 0.0F,
                1024, 1024, 1024, 1024);

        // The reference labels are about 6.7% of the dial diameter. Scaling
        // from the dial instead of using the fixed GUI font size keeps that
        // ratio at every Minecraft GUI scale and screen resolution.
        float labelScale = Math.max(0.1F,
                (float) (diameter * 0.067 / minecraft.font.lineHeight));

        Vec3 up = ClientRadarState.getUp();
        if (up.lengthSqr() < 1.0e-8) up = new Vec3(0.0, 1.0, 0.0);
        else up = up.normalize();
        Vec3 forward = ClientRadarState.getForward();
        forward = forward.subtract(up.scale(forward.dot(up)));
        if (forward.lengthSqr() < 1.0e-8) forward = new Vec3(0.0, 0.0, -1.0);
        else forward = forward.normalize();
        Vec3 right = forward.cross(up);
        if (right.lengthSqr() < 1.0e-8) right = new Vec3(1.0, 0.0, 0.0);
        else right = right.normalize();

        Vec3 origin = ClientRadarState.getOrigin();
        double warningRange = Math.max(1.0, ClientRadarState.getRwrRange());
        boolean flash = minecraft.level == null
                || ((minecraft.level.getGameTime() / 5L) & 1L) == 0L;
        for (ClientRadarState.Threat threat : ClientRadarState.getThreats()) {
            if (!threat.incoming() || !flash) continue;
            ThreatPoint point = rwrThreatPoint(
                    minecraft, threat, origin, forward, right,
                    cx, cy, innerRadius, centreRadius, warningRange, labelScale);
            drawHighResolutionDashedLine(graphics, cx, cy,
                    point.x(), point.y(), 0xE8F2F2F2, detailScale);
        }
        for (ClientRadarState.Threat threat : ClientRadarState.getThreats()) {
            ThreatPoint point = rwrThreatPoint(
                    minecraft, threat, origin, forward, right,
                    cx, cy, innerRadius, centreRadius, warningRange, labelScale);
            String label = threat.type().name();
            drawScaledCenteredString(graphics, minecraft, label,
                    point.x(), point.y(), labelScale, 0xFFF4F4F4);
        }
    }

    private static void drawHighResolutionDashedLine(
            GuiGraphics graphics,
            int x0,
            int y0,
            int x1,
            int y1,
            int color,
            int detailScale
    ) {
        int dx = (x1 - x0) * detailScale;
        int dy = (y1 - y0) * detailScale;
        int length = (int) Math.round(Math.hypot(dx, dy));
        if (length <= 0) return;
        graphics.pose().pushPose();
        graphics.pose().scale(1.0F / detailScale, 1.0F / detailScale, 1.0F);
        graphics.pose().translate(x0 * detailScale, y0 * detailScale, 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        int dashLength = 4 * detailScale;
        int period = 7 * detailScale;
        for (int start = 0; start < length; start += period) {
            graphics.fill(start, 0, Math.min(start + dashLength, length), 1, color);
        }
        graphics.pose().popPose();
    }

    private static void drawScaledCenteredString(
            GuiGraphics graphics,
            Minecraft minecraft,
            String text,
            int x,
            int y,
            float scale,
            int color
    ) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(minecraft.font, text,
                -minecraft.font.width(text) / 2,
                -minecraft.font.lineHeight / 2,
                color, false);
        graphics.pose().popPose();
    }

    private static ThreatPoint rwrThreatPoint(
            Minecraft minecraft,
            ClientRadarState.Threat threat,
            Vec3 origin,
            Vec3 forward,
            Vec3 right,
            int cx,
            int cy,
            int innerRadius,
            int centreRadius,
            double warningRange,
            float labelScale
    ) {
        Vec3 delta = threat.position().subtract(origin);
        double bearing = Math.atan2(delta.dot(right), delta.dot(forward));
        String label = threat.type().name();
        double halfDiagonal = 0.5 * Math.hypot(
                minecraft.font.width(label) * labelScale,
                minecraft.font.lineHeight * labelScale);
        double maximumRadius = Math.max(0.0, innerRadius - halfDiagonal - 2.0);
        double minimumRadius = Math.min(maximumRadius,
                centreRadius + minecraft.font.lineHeight * labelScale * 0.75);
        double markerRadius = Mth.clamp(
                delta.length() / warningRange * maximumRadius,
                minimumRadius,
                maximumRadius);
        return new ThreatPoint(
                cx + (int) Math.round(Math.sin(bearing) * markerRadius),
                cy - (int) Math.round(Math.cos(bearing) * markerRadius));
    }

    private static void fillRwrBackground(
            GuiGraphics graphics, int cx, int cy, int radius, int color
    ) {
        long radiusSquared = radius * (long) radius;
        for (int y = -radius; y <= radius; y++) {
            int halfWidth = (int) Math.floor(Math.sqrt(Math.max(
                    0L, radiusSquared - y * (long) y)));
            graphics.fill(cx - halfWidth, cy + y,
                    cx + halfWidth + 1, cy + y + 1, color);
        }
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

        g.drawCenteredString(mc.font, Component.translatable("hud.create_fire_control.radar.tws"), x + width / 2, y + Math.round(height * 8F / 450F), white);
        double halfAngle = preview ? previewHalfAngle : ClientRadarState.getAircraftHorizontalHalfAngle();
        double vertical = preview ? previewVerticalAngle : ClientRadarState.getAircraftVerticalAngle();
        double radarRange = preview ? previewRange : ClientRadarState.getRange();
        String scan = String.format(Locale.ROOT, "%.0f°×%.0f° *", halfAngle, vertical);
        Component range = radarRangeText(radarRange, radarRange >= 10000.0);
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
            drawWhiteContactCircle(g, px, py, Math.max(3, width / 55));
            drawWhiteSelectionBars(g, px, py, Math.max(3, width / 55));
            drawWhiteContactCircle(g, centerX + (plotRight - plotLeft) / 5,
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
            int contactColor = ClientRadarMemory.controllerColor(contact.id(), 0xFFF2F5F6);
            drawWhiteContactCircle(g, px, py, icon, contactColor);
            if (selected != null && selected.id().equals(contact.id())) drawWhiteSelectionBars(g, px, py, icon, contactColor);
        }
    }

    private static void renderAircraftFlightInfo(GuiGraphics graphics, Minecraft minecraft, int x, int y) {
        if (!ClientVisualConfig.showAircraftHud()) return;
        int color = ClientVisualConfig.hudTextColor(0xEEDDE5E8);
        Vec3 velocity = ClientRadarState.getOwnVelocity();
        double metresPerSecond = velocity.length() * 20.0;
        graphics.drawString(minecraft.font, Component.translatable("hud.create_fire_control.aircraft.speed",
                String.format(Locale.ROOT, "%.0f", metresPerSecond * 3.6)), x, y, color, false);
        graphics.drawString(minecraft.font, Component.translatable("hud.create_fire_control.aircraft.mach",
                String.format(Locale.ROOT, "%.2f", metresPerSecond / 340.3)), x, y + 11, color, false);
        graphics.drawString(minecraft.font, Component.translatable("hud.create_fire_control.aircraft.altitude",
                String.format(Locale.ROOT, "%.0f", ClientRadarState.getOrigin().y)), x, y + 22, color, false);
        int[] legacyAmmo = {ClientRadarState.getFarAmmo(), ClientRadarState.getMediumAmmo(),
                ClientRadarState.getCloseAmmo(), ClientRadarState.getAirToGroundDirectAmmo(),
                ClientRadarState.getAirToGroundTopAttackAmmo(), ClientRadarState.getLaserGuidedAmmo(),
                ClientRadarState.getRocketAmmo(), ClientRadarState.getOpticalGuidedAmmo(),
                ClientRadarState.getMicaEmAmmo(), ClientRadarState.getAim120dAmmo()};
        int[] ammo = java.util.Arrays.copyOf(legacyAmmo, MianbaoAircraftCompat.WeaponType.values().length);
        ammo[MianbaoAircraftCompat.WeaponType.ANTI_RADIATION.ordinal()] = AntiRadiationHudRenderer.ammo();
        String[] weaponLines = aircraftWeaponHudLines(
                ClientRadarState.getSelectedAircraftWeapon(), ammo);
        for (int i = 0; i < weaponLines.length; i++) {
            graphics.drawString(minecraft.font, weaponLines[i],
                    x, y + 42 + i * 11, color, false);
        }
        graphics.drawString(minecraft.font, ClientBombSight.weaponLine(),
                x, y + 42 + weaponLines.length * 11, color, false);
    }

    static String[] aircraftWeaponHudLines(int selectedWeapon, int[] ammo) {
        String[] categories = {
                Component.translatable("hud.create_fire_control.aircraft.air_to_air").getString(),
                Component.translatable("hud.create_fire_control.aircraft.air_to_ground").getString(),
                Component.translatable("hud.create_fire_control.aircraft.rockets").getString()
        };
        String[] lines = {"  " + categories[0], "  " + categories[1], "  " + categories[2]};
        if (ammo == null || ammo.length < 10 || selectedWeapon < 0 || selectedWeapon >= ammo.length
                || selectedWeapon >= MianbaoAircraftCompat.WeaponType.values().length
                || com.hooya.stabilizedturret.content.controller.MianbaoBombCompat
                .isBomb(MianbaoAircraftCompat.WeaponType.values()[selectedWeapon])) {
            return lines;
        }

        int category;
        int categoryTotal;
        if (selectedWeapon <= 2 || selectedWeapon == 8 || selectedWeapon == 9) {
            category = 0;
            categoryTotal = ammo[0] + ammo[1] + ammo[2] + ammo[8] + ammo[9];
        } else if (selectedWeapon == 6) {
            category = 2;
            categoryTotal = ammo[6];
        } else {
            category = 1;
            categoryTotal = ammo[3] + ammo[4] + ammo[5] + ammo[7];
            int anti = MianbaoAircraftCompat.WeaponType.ANTI_RADIATION.ordinal();
            if (ammo.length > anti) categoryTotal += ammo[anti];
        }

        Component model = MianbaoAircraftCompat.WeaponType.values()[selectedWeapon].displayName();
        if (category == 2) {
            lines[category] = Component.translatable("hud.create_fire_control.aircraft.rockets_selected",
                    categories[category], categoryTotal).getString();
        } else {
            lines[category] = Component.translatable("hud.create_fire_control.aircraft.weapon_selected",
                    categories[category], model, ammo[selectedWeapon], categoryTotal).getString();
        }
        return lines;
    }

    private static void drawWhiteAircraftSymbol(GuiGraphics g,int x,int y,int size){
        int c=0xFFF2F5F6;g.fill(x,y-size,x+1,y+size+1,c);g.fill(x-size,y-1,x+size+1,y+1,c);
        g.fill(x-size/2,y+2,x+size/2+1,y+3,c);
    }
    private static void drawWhiteContactCircle(GuiGraphics g,int centerX,int centerY,int radius){
        drawWhiteContactCircle(g, centerX, centerY, radius, 0xFFF2F5F6);
    }
    private static void drawWhiteContactCircle(GuiGraphics g,int centerX,int centerY,int radius,int c){
        int x=Math.max(2,radius),y=0,error=1-x;
        while(y<=x){
            drawCircleOctants(g,centerX,centerY,x,y,c);
            y++;
            if(error<=0) error+=2*y+1;
            else { x--; error+=2*(y-x)+1; }
        }
    }
    private static void drawCircleOctants(GuiGraphics g,int centerX,int centerY,int x,int y,int color){
        g.fill(centerX+x,centerY+y,centerX+x+1,centerY+y+1,color);
        g.fill(centerX+y,centerY+x,centerX+y+1,centerY+x+1,color);
        g.fill(centerX-y,centerY+x,centerX-y+1,centerY+x+1,color);
        g.fill(centerX-x,centerY+y,centerX-x+1,centerY+y+1,color);
        g.fill(centerX-x,centerY-y,centerX-x+1,centerY-y+1,color);
        g.fill(centerX-y,centerY-x,centerX-y+1,centerY-x+1,color);
        g.fill(centerX+y,centerY-x,centerX+y+1,centerY-x+1,color);
        g.fill(centerX+x,centerY-y,centerX+x+1,centerY-y+1,color);
    }
    private static void drawWhiteSelectionBars(GuiGraphics g,int x,int y,int size){
        drawWhiteSelectionBars(g, x, y, size, 0xFFF2F5F6);
    }
    private static void drawWhiteSelectionBars(GuiGraphics g,int x,int y,int size,int c){
        int gap=size+3;g.fill(x-gap,y-size,x-gap+2,y+size+1,c);g.fill(x+gap-1,y-size,x+gap+1,y+size+1,c);
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
        int radarRgb = GREEN & 0x00FFFFFF;
        for (int trail = 3; trail >= 0; trail--) {
            int alpha = trail == 0 ? 0xE8 : 0x22 + (3 - trail) * 0x24;
            drawRadialLine(graphics, cx, cy, radius, sweep - trail * 2.2F,
                    (alpha << 24) | radarRgb, trail == 0 ? 2 : 1);
        }

        Vector3f look = minecraft.gameRenderer.getMainCamera().getLookVector();
        float viewDegrees = (float) Math.toDegrees(Math.atan2(look.x, -look.z));
        // Player heading is deliberately a single, flat line. It reaches the
        // circular grid edge directly; there is no arrow head or protrusion.
        drawRadialLine(graphics, cx, cy, radius,
                viewDegrees, VIEW_GREEN, 1);

        Vec3 origin = ClientRadarState.getOrigin();
        double range = Math.max(1.0, ClientRadarState.getRange());
        UUID locked = ClientRadarState.getLockedTarget();
        int iconSize = Mth.clamp((int) Math.round(diameter / 30.0), 4, 9);
        for (ClientRadarState.Contact contact : ClientRadarState.getContacts()) {
            Vec3 delta = contact.center().subtract(origin);
            int x = cx + (int) Math.round(delta.x / range * radius);
            int y = cy + (int) Math.round(delta.z / range * radius);
            if ((x - cx) * (long) (x - cx) + (y - cy) * (long) (y - cy) > radius * (long) radius) continue;
            drawSmoothContactCircle(graphics, x, y, Math.max(2.5F, iconSize * 0.56F),
                    ClientRadarMemory.controllerColor(contact.id(), contact.missile() ? RADAR_CONTACT_AMBER : RADAR_CONTACT_CYAN));
            if (contact.id().equals(locked)) drawRadarLockBars(graphics, x, y, iconSize,
                    ClientRadarMemory.controllerColor(contact.id(), RADAR_LOCK_AMBER));
        }

        drawCompassLabels(graphics, minecraft, left, top, diameter, cx, cy, radius);
    }

    private static void drawRadarTexture(GuiGraphics graphics, int x, int y, int size) {
        // The transparent grid texture's outer green ring is inset from its
        // square bounds. Match the glass disc to that ring instead of filling
        // the whole texture; otherwise a dark stepped halo appears outside
        // the radar, especially against bright terrain or sky.
        int radius = Math.max(1, (int) Math.round(size * 0.459));
        fillCircularBackground(graphics, x + size / 2, y + size / 2, radius, RADAR_DISC);

        // The source grid is deliberately kept as a transparent circular
        // texture so existing resource-pack overrides still work. Tinting it
        // at draw time gives the circular scope the aircraft-style cyan glass
        // treatment without changing the texture contract.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(0.56F, 0.90F, 0.78F, 0.74F);
        graphics.blit(RADAR_GRID, x, y, size, size, 0.0F, 0.0F,
                1024, 1024, 1024, 1024);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void fillCircularBackground(
            GuiGraphics graphics, int cx, int cy, int radius, int color
    ) {
        long radiusSquared = radius * (long) radius;
        int baseAlpha = color >>> 24;
        for (int y = -radius; y <= radius; y++) {
            double halfWidthExact = Math.sqrt(Math.max(
                    0.0, radiusSquared - y * (long) y));
            int halfWidth = (int) Math.floor(halfWidthExact);
            graphics.fill(cx - halfWidth, cy + y,
                    cx + halfWidth + 1, cy + y + 1, color);
            // Add fractional edge coverage so the dark glass disc does not
            // produce the stepped grey halo visible around the old HUD.
            float edgeCoverage = (float) (halfWidthExact - halfWidth);
            if (edgeCoverage > 0.01F) {
                int edgeColor = withAlpha(color,
                        Math.round(baseAlpha * edgeCoverage));
                graphics.fill(cx - halfWidth - 1, cy + y,
                        cx - halfWidth, cy + y + 1, edgeColor);
                graphics.fill(cx + halfWidth + 1, cy + y,
                        cx + halfWidth + 2, cy + y + 1, edgeColor);
            }
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
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
        graphics.drawString(minecraft.font, Component.translatable("hud.create_fire_control.radar.search"), left, textY, GREEN, false);
        graphics.drawString(minecraft.font,
                "360°  ↑" + Math.round(ClientRadarState.getElevation())
                        + "°  ↓" + Math.round(ClientRadarState.getDepression()) + "°",
                left, textY + 10, GREEN, false);
        double displayRange = ClientRadarState.getRange();
        Component distance = radarRangeText(displayRange, false).copy().append("*");
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

    private static void drawAircraft(GuiGraphics graphics, int x, int y, int size, Vec3 velocity) {
        drawSmoothContactCircle(graphics, x, y,
                Math.max(2.5F, size * 0.56F), RADAR_CONTACT_CYAN);
    }

    /**
     * Draws a small anti-aliased ring using pixel coverage rather than a
     * blocky octant approximation. This keeps contacts circular at the small
     * sizes used by the radar scope.
     */
    private static void drawSmoothContactCircle(
            GuiGraphics graphics, int centerX, int centerY,
            float radius, int color
    ) {
        float halfThickness = Math.max(0.75F, radius * 0.20F);
        float ringRadius = Math.max(0.75F, radius - halfThickness);
        int extent = (int) Math.ceil(radius + 1.0F);
        for (int y = -extent; y <= extent; y++) {
            for (int x = -extent; x <= extent; x++) {
                float distance = (float) Math.hypot(x + 0.5F, y + 0.5F);
                float coverage = 1.0F - Math.abs(distance - ringRadius)
                        / (halfThickness + 0.75F);
                if (coverage <= 0.01F) continue;
                coverage = Mth.clamp(coverage, 0.0F, 1.0F);
                graphics.fill(centerX + x, centerY + y,
                        centerX + x + 1, centerY + y + 1,
                        withAlpha(color, Math.round((color >>> 24) * coverage)));
            }
        }
    }

    /** A narrow arrow-and-fins glyph, deliberately distinct from the aircraft silhouette. */
    private static void drawMissile(GuiGraphics graphics, int x, int y, int size, Vec3 velocity) {
        drawSmoothContactCircle(graphics, x, y,
                Math.max(2.5F, size * 0.56F), RADAR_CONTACT_AMBER);
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
        drawMissile(graphics, cx - radius / 2, cy + radius / 3, icon, new Vec3(-0.4, 0.0, 0.6));
    }

    public static void renderAircraftPreview(GuiGraphics graphics, int x, int y, int width, int height,
                                             double range, double halfAngle, double verticalAngle) {
        Minecraft minecraft = Minecraft.getInstance();
        int exactHeight = Math.min(height, Math.max(1, (int)Math.round(width * 450.0 / 474.0)));
        drawTwsPanel(graphics, minecraft, x, y, width, exactHeight, 1.0F, true,
                Math.max(200.0, range), Math.max(5.0, halfAngle), Math.max(2.0, verticalAngle));
    }

    private static void drawRadarLockBars(GuiGraphics graphics, int x, int y, int iconSize) {
        drawRadarLockBars(graphics, x, y, iconSize, RADAR_LOCK_AMBER);
    }
    private static void drawRadarLockBars(GuiGraphics graphics, int x, int y, int iconSize, int color) {
        int half = Math.max(2, iconSize / 2);
        int gap = half + 2;
        graphics.fill(x - gap - 1, y - half, x - gap, y + half + 1, color);
        graphics.fill(x + gap, y - half, x + gap + 1, y + half + 1, color);
    }

    private static void renderWorldFrames(GuiGraphics graphics, Minecraft minecraft, float partialTick) {
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        if (ClientRadarState.isAircraftMode()) {
            ClientRadarState.Contact contact = selectedAircraftContact(minecraft, partialTick);
            if (contact == null) return;
            ScreenPoint center = project(minecraft, contact.center(), width, height, partialTick);
            if (center == null) return;
            TargetLockFrame.render(graphics, minecraft, center.x, center.y, height,
                    TargetLockFrame.Style.AIRCRAFT_RADAR, ClientRadarMemory.controllerColor(contact.id(), LOCK_FRAME_GREEN),
                    contact.center(), contact.telemetry());
            return;
        }
        UUID locked = ClientRadarState.getLockedTarget();
        for (ClientRadarState.Contact contact : ClientRadarState.getContacts()) {
            Vec3 target = contact.center();
            boolean remembered = ClientRadarMemory.remembered(contact.id());
            if (contact.id().equals(locked) && !remembered) {
                Vec3 smooth = ClientCannonLockLead.radarTarget();
                if (smooth != null) target = smooth;
            }
            ScreenPoint center = project(minecraft, target, width, height, partialTick);
            if (center == null) continue;
            if (contact.id().equals(locked)) {
                if (!remembered) CannonLeadHud.render(graphics, minecraft, center.x, center.y,
                        ClientCannonLockLead.radarAimPoint(target, partialTick),
                        ClientCannonLockLead.radarStatus(), partialTick, LOCK_FRAME_GREEN);
                TargetLockFrame.render(graphics, minecraft, center.x, center.y, height,
                        TargetLockFrame.Style.GROUND_RADAR, ClientRadarMemory.controllerColor(contact.id(), LOCK_FRAME_GREEN),
                        target, contact.telemetry());
            } else {
                TargetLockFrame.renderRadarContact(graphics, center.x, center.y, height, ClientRadarMemory.controllerColor(contact.id(), GREEN));
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

    private static Component radarRangeText(double metres, boolean wholeKilometres) {
        return metres >= 1000.0
                ? Component.translatable("screen.create_fire_control.connected_display.range.kilometers",
                        String.format(Locale.ROOT, wholeKilometres ? "%.0f" : "%.1f", metres / 1000.0))
                : Component.translatable("screen.create_fire_control.connected_display.range.meters",
                        String.format(Locale.ROOT, "%.0f", metres));
    }

    private record ScreenPoint(int x, int y) {}
    private record ThreatPoint(int x, int y) {}
    private RadarHudRenderer() {}
}
