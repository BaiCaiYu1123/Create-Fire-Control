package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.MianbaoCruiseMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat;
import com.hooya.stabilizedturret.network.ConnectedDisplayConsoleActionPayload;
import com.hooya.stabilizedturret.network.OpenConnectedDisplayConsolePayload;
import com.hooya.stabilizedturret.network.RequestConnectedDisplayConsoleStatePayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Pannable, high-resolution form of the connected display's weapon canvas. */
public final class ConnectedDisplayConsoleScreen extends Screen {
    private static final float DESIGN_W = 420.0F;
    private static final float DESIGN_H = 600.0F;
    private static final float MIN_MAP_DESIGN_W = 190.0F;
    private static final int HEADER_COLOR = 0xFF0E1B2A;
    private static final int PANEL_COLOR = 0xFF0D1A29;
    private static final int SECTION_COLOR = 0xFF102131;
    private static final int CONTROL_COLOR = 0xFF162A3B;
    private static final int ACTIVE_COLOR = 0xFF0E6F8A;
    private static final int TEXT_COLOR = 0xFFEAF4F8;
    private static final int MUTED_COLOR = 0xFF8DA8B8;
    private static final int GREEN = 0xFF5DE1FF;
    private static final int AMBER = 0xFFFFBD59;
    private static final int RED = 0xFFFF6570;

    private OpenConnectedDisplayConsolePayload payload;
    private DisplayState state;
    private double viewCenterX;
    private double viewCenterZ;
    private double targetViewCenterX;
    private double targetViewCenterZ;
    private double mapHalfSpanX;
    private double targetMapHalfSpanX;
    private long lastMapAnimationNanos;
    private boolean panned;
    private boolean returningHome;
    private boolean mapPressed;
    private boolean mapDragging;
    private double mapPressX;
    private double mapPressY;
    private @Nullable Slider activeSlider;
    private int refreshTicks;

    public ConnectedDisplayConsoleScreen(OpenConnectedDisplayConsolePayload payload) {
        super(Component.translatable(
                "screen.create_fire_control.connected_display.console.title"));
        this.payload = payload;
        this.state = DisplayState.read(payload.displayState());
        this.viewCenterX = payload.displayX();
        this.viewCenterZ = payload.displayZ();
        this.targetViewCenterX = viewCenterX;
        this.targetViewCenterZ = viewCenterZ;
        this.mapHalfSpanX = Math.max(100.0, state.radarRange);
        this.targetMapHalfSpanX = mapHalfSpanX;
    }

    public boolean matches(OpenConnectedDisplayConsolePayload update) {
        return payload.consolePos().equals(update.consolePos())
                && java.util.Objects.equals(
                payload.consoleSubLevelId(), update.consoleSubLevelId());
    }

    public void acceptState(OpenConnectedDisplayConsolePayload update) {
        if (!matches(update)) return;
        payload = update;
        state = DisplayState.read(update.displayState());
        if (!panned) {
            targetViewCenterX = update.displayX();
            targetViewCenterZ = update.displayZ();
            if (!returningHome) {
                viewCenterX = targetViewCenterX;
                viewCenterZ = targetViewCenterZ;
            }
        }
    }

    @Override
    public void tick() {
        if (++refreshTicks >= 5) {
            refreshTicks = 0;
            PacketDistributor.sendToServer(
                    new RequestConnectedDisplayConsoleStatePayload(
                            payload.consolePos(), payload.consoleSubLevelId()));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        animateMapView();
        float scale = panelScale();
        float panelWidth = DESIGN_W * scale;
        float panelLeft = width - panelWidth;
        float headerHeight = 30.0F * scale;
        float mapWidth = Math.max(1.0F, panelLeft);
        float mapHeight = Math.max(1.0F, height - headerHeight);
        double halfSpanX = visibleHalfSpanX();
        double halfSpanZ = halfSpanX * mapHeight / mapWidth;

        graphics.fill(0, 0, width, height, 0xFF10171E);
        List<XaeroDisplayMapCompat.ConsoleMapTile> mapTiles =
                XaeroDisplayMapCompat.consoleMapTiles(this,
                        new Vec3(viewCenterX, payload.radarY(), viewCenterZ),
                        halfSpanX, halfSpanZ,
                        (int) Math.ceil(mapWidth), (int) Math.ceil(mapHeight));
        if (!mapTiles.isEmpty()) {
            graphics.enableScissor(0, (int) Math.ceil(headerHeight),
                    Math.max(1, (int) Math.floor(mapWidth)), height);
            graphics.flush();
            Matrix4f matrix = graphics.pose().last().pose();
            for (XaeroDisplayMapCompat.ConsoleMapTile tile : mapTiles) {
                float x1 = (float) (mapWidth * 0.5
                        + (tile.minX() - viewCenterX) * mapWidth
                        / (halfSpanX * 2.0));
                float x2 = (float) (mapWidth * 0.5
                        + (tile.minX() + tile.size() - viewCenterX) * mapWidth
                        / (halfSpanX * 2.0));
                float y1 = (float) (headerHeight + mapHeight * 0.5
                        + (tile.minZ() - viewCenterZ) * mapHeight
                        / (halfSpanZ * 2.0));
                float y2 = (float) (headerHeight + mapHeight * 0.5
                        + (tile.minZ() + tile.size() - viewCenterZ) * mapHeight
                        / (halfSpanZ * 2.0));
                if (tile.textureLocation() != null) {
                    int left = (int) Math.floor(x1);
                    int top = (int) Math.floor(y1);
                    int right = (int) Math.ceil(x2);
                    int bottom = (int) Math.ceil(y2);
                    graphics.blit(tile.textureLocation(), left, top,
                            Math.max(1, right - left), Math.max(1, bottom - top),
                            0.0F, 0.0F, 512, 512, 512, 512);
                } else {
                    graphics.flush();
                    texturedOpaque(matrix, x1, y1, x2, y2, tile.textureId());
                }
            }
            graphics.flush();
            graphics.disableScissor();
        }
        graphics.fill(0, (int) headerHeight, (int) mapWidth, height, 0x6608111B);
        renderMapOverlay(graphics, mapWidth, headerHeight, mapHeight,
                halfSpanX, halfSpanZ);

        graphics.fill(0, 0, (int) mapWidth, (int) headerHeight, HEADER_COLOR);
        graphics.drawString(font, Component.translatable(
                        "screen.create_fire_control.connected_display.title"),
                16, (int) (10.0F * scale), TEXT_COLOR, false);
        graphics.drawString(font, Component.translatable(
                        "screen.create_fire_control.connected_display.console.radar",
                        formatRange(halfSpanX)),
                Math.max(4, (int) mapWidth - 170),
                (int) (10.0F * scale), GREEN, false);
        graphics.drawString(font, Component.translatable(
                        "screen.create_fire_control.connected_display.console.zoom",
                        formatRange(halfSpanX)),
                Math.max(4, (int) mapWidth - 82),
                (int) (10.0F * scale), TEXT_COLOR, false);
        graphics.pose().pushPose();
        graphics.pose().translate(panelLeft, 0.0F, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        renderPanel(graphics, (float) ((mouseX - panelLeft) / scale),
                mouseY / scale);
        graphics.pose().popPose();
    }

    private void renderMapOverlay(GuiGraphics graphics, float mapWidth,
                                  float mapTop, float mapHeight,
                                  double halfSpanX, double halfSpanZ) {
        graphics.flush();
        MapGeometry geometry = new MapGeometry(graphics.pose().last().pose());
        for (Telemetry telemetry : state.telemetry) {
            Point missile = mapPoint(telemetry.x, telemetry.z,
                    mapWidth, mapTop, mapHeight, halfSpanX, halfSpanZ);
            Point target = mapPoint(telemetry.tx, telemetry.tz,
                    mapWidth, mapTop, mapHeight, halfSpanX, halfSpanZ);
            if (missile.visible && target.visible) {
                geometry.line(missile.x, missile.y, target.x, target.y,
                        0xCCFFD36A, 1.2F);
            }
        }
        geometry.flush();

        MapGeometry markers = new MapGeometry(graphics.pose().last().pose());
        for (Contact contact : state.contacts) {
            Point point = mapPoint(contact.x, contact.z,
                    mapWidth, mapTop, mapHeight, halfSpanX, halfSpanZ);
            if (!point.visible) continue;
            int color = contact.missile ? AMBER : 0xFF5DE1FF;
            if (contact.missile) {
                markers.line(point.x, point.y - 4.0F,
                        point.x - 3.5F, point.y + 3.0F, color, 1.2F);
                markers.line(point.x - 3.5F, point.y + 3.0F,
                        point.x + 3.5F, point.y + 3.0F, color, 1.2F);
                markers.line(point.x + 3.5F, point.y + 3.0F,
                        point.x, point.y - 4.0F, color, 1.2F);
            } else if (contact.living) {
                markers.circle(point.x, point.y, 3.0F, color, 1.2F);
            } else {
                markers.line(point.x, point.y - 3.8F,
                        point.x - 3.5F, point.y + 3.0F, color, 1.2F);
                markers.line(point.x - 3.5F, point.y + 3.0F,
                        point.x + 3.5F, point.y + 3.0F, color, 1.2F);
                markers.line(point.x + 3.5F, point.y + 3.0F,
                        point.x, point.y - 3.8F, color, 1.2F);
            }
            if (contact.selected || contact.strikeTarget) {
                markers.line(point.x - 6.0F, point.y - 5.0F,
                        point.x - 6.0F, point.y + 5.0F, GREEN, 1.3F);
                markers.line(point.x + 6.0F, point.y - 5.0F,
                        point.x + 6.0F, point.y + 5.0F, GREEN, 1.3F);
            }
        }
        markers.flush();
        for (Contact contact : state.contacts) {
            if (contact.strikeNumber <= 0) continue;
            Point point = mapPoint(contact.x, contact.z,
                    mapWidth, mapTop, mapHeight, halfSpanX, halfSpanZ);
            if (point.visible) graphics.drawString(font,
                    Integer.toString(contact.strikeNumber),
                    Math.round(point.x) + 6, Math.round(point.y) - 8,
                    0xFFFFE9A6, false);
        }

        if (state.manualAim != null && !state.cruiseMulti) {
            Point point = mapPoint(state.manualAim.x, state.manualAim.z,
                    mapWidth, mapTop, mapHeight, halfSpanX, halfSpanZ);
            if (point.visible) drawRedCross(graphics, point);
        }
        for (StrikeTarget target : state.strikeTargets) {
            if (target.targetId != null) continue;
            Point point = mapPoint(target.x, target.z,
                    mapWidth, mapTop, mapHeight, halfSpanX, halfSpanZ);
            if (!point.visible) continue;
            drawRedCross(graphics, point);
            graphics.drawString(font, Integer.toString(target.number),
                    Math.round(point.x) + 7, Math.round(point.y) - 10,
                    0xFFFFE9A6, false);
        }

        MapGeometry telemetryMarkers = new MapGeometry(
                graphics.pose().last().pose());
        for (Telemetry telemetry : state.telemetry) {
            Point missile = mapPoint(telemetry.x, telemetry.z,
                    mapWidth, mapTop, mapHeight, halfSpanX, halfSpanZ);
            if (missile.visible) {
                telemetryMarkers.circle(missile.x, missile.y,
                        2.8F, 0xFFFFF1A8, 1.2F);
            }
        }
        telemetryMarkers.flush();

        Point display = mapPoint(payload.displayX(), payload.displayZ(),
                mapWidth, mapTop, mapHeight, halfSpanX, halfSpanZ);
        if (display.visible) {
            int x = Math.round(display.x), y = Math.round(display.y);
            graphics.fill(x - 2, y - 2, x + 3, y + 3, 0xFF5B0909);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, RED);
        }

    }

    private static void drawRedCross(GuiGraphics graphics, Point point) {
        graphics.flush();
        MapGeometry geometry = new MapGeometry(graphics.pose().last().pose());
        geometry.line(point.x - 5, point.y - 5,
                point.x + 5, point.y + 5, RED, 2.0F);
        geometry.line(point.x - 5, point.y + 5,
                point.x + 5, point.y - 5, RED, 2.0F);
        geometry.flush();
    }

    private Point mapPoint(double worldX, double worldZ, float mapWidth,
                           float mapTop, float mapHeight,
                           double halfSpanX, double halfSpanZ) {
        float x = (float) (mapWidth * 0.5
                + (worldX - viewCenterX) * mapWidth / (halfSpanX * 2.0));
        float y = (float) (mapTop + mapHeight * 0.5
                + (worldZ - viewCenterZ) * mapHeight / (halfSpanZ * 2.0));
        return new Point(x, y, x >= 0 && x <= mapWidth
                && y >= mapTop && y <= mapTop + mapHeight);
    }

    private void renderPanel(GuiGraphics g, float mouseX, float mouseY) {
        g.fill(0, 0, (int) DESIGN_W, (int) DESIGN_H, PANEL_COLOR);
        g.fill(0, 0, (int) DESIGN_W, 30, HEADER_COLOR);
        g.drawString(font, Component.translatable(
                        "screen.create_fire_control.connected_display.console.target_and_fire_control"),
                16, 11, TEXT_COLOR, false);
        if (state.hasTarget()) {
            String summary = font.plainSubstrByWidth(targetSummary().getString(), 190);
            g.drawString(font, summary, 120, 11, AMBER, false);
        }
        g.drawString(font, Component.translatable(
                        "screen.create_fire_control.connected_display.weapon_selection"),
                16, 42, TEXT_COLOR, false);
        drawButton(g, 16, 58, 105, 89, Component.translatable(
                        "screen.create_fire_control.connected_display.weapon.cannon"),
                state.weapon.equals("CANNON"), mouseX, mouseY, false);
        drawButton(g, 115, 58, 204, 89, Component.translatable(
                        "screen.create_fire_control.connected_display.weapon.air_defense"),
                state.weapon.equals("AIR_DEFENSE"), mouseX, mouseY, false);
        drawButton(g, 214, 58, 303, 89, Component.translatable(
                        "screen.create_fire_control.connected_display.weapon.cruise"),
                state.weapon.equals("CRUISE"), mouseX, mouseY, false);
        drawButton(g, 313, 58, 404, 89, Component.translatable(
                        "screen.create_fire_control.connected_display.weapon.ballistic"),
                state.weapon.equals("BALLISTIC"), mouseX, mouseY, false);

        g.fill(16, 100, 404, 132, SECTION_COLOR);
        g.renderOutline(16, 100, 388, 32, 0xFF35546B);
        boolean ballistic = state.weapon.equals("BALLISTIC");
        Component selectedType = ballistic
                ? state.ballisticAvailable
                ? availableMissileName(ballisticName(state.ballisticType))
                : Component.translatable(
                "screen.create_fire_control.connected_display.ballistic.unavailable.short")
                : state.cruiseAvailable
                ? availableMissileName(cruiseName(state.cruiseType))
                : Component.translatable(
                "screen.create_fire_control.connected_display.cruise.unavailable.short");
        Component missileStatus = Component.translatable(ballistic
                        ? "screen.create_fire_control.connected_display.ballistic_model"
                        : "screen.create_fire_control.connected_display.missile_model",
                selectedType);
        boolean missileAvailable = ballistic ? state.ballisticAvailable : state.cruiseAvailable;
        g.drawString(font, missileStatus, 26, 112,
                missileAvailable ? GREEN : MUTED_COLOR, false);

        drawSlider(g, Slider.RADAR, Component.translatable(
                        "screen.create_fire_control.connected_display.radar_display_range"),
                formatRange(state.radarRange), state.radarProgress(),
                146, 169, 0xFF00C940, mouseX, mouseY);
        drawSlider(g, Slider.DEFENSE, Component.translatable(
                        "screen.create_fire_control.connected_display.defense_range"),
                formatDefenseRange(state.defenseRange), state.defenseProgress(),
                196, 219, AMBER, mouseX, mouseY);
        drawSlider(g, Slider.PROXIMITY, Component.translatable(
                        "screen.create_fire_control.connected_display.proximity_range"),
                Component.translatable(
                        "screen.create_fire_control.connected_display.range.meters",
                        decimal("%.1f", state.proximityRange)),
                state.proximityProgress(), 246, 269, 0xFFFF6570, mouseX, mouseY);

        drawButton(g, 16, 291, 202, 325,
                Component.translatable(
                        "screen.create_fire_control.connected_display.automatic_fire",
                        toggle(state.automaticAttack)),
                state.automaticAttack, mouseX, mouseY, false);
        drawButton(g, 218, 291, 404, 325,
                Component.translatable(
                        "screen.create_fire_control.connected_display.automatic_defense",
                        toggle(state.automaticDefense)),
                state.automaticDefense, mouseX, mouseY, false);

        g.fill(16, 336, 404, 384, SECTION_COLOR);
        g.drawString(font, Component.translatable(
                        "screen.create_fire_control.connected_display.current_strike_target"),
                26, 347, TEXT_COLOR, false);
        if (state.weapon.equals("CRUISE")) {
            drawButton(g, 277, 342, 394, 369,
                    Component.translatable(
                            "screen.create_fire_control.connected_display.multi_select",
                            toggle(state.cruiseMulti)),
                    state.cruiseMulti, mouseX, mouseY, false);
        }
        g.drawString(font, targetSummary(), 26, 367,
                state.hasTarget() ? 0xFFFFD36A : MUTED_COLOR, false);

        drawButton(g, 16, 395, 404, 435, fireText(), false,
                mouseX, mouseY, true);
        drawButton(g, 16, 443, 404, 477, Component.translatable(
                        "screen.create_fire_control.connected_display.cancel_target"), false,
                mouseX, mouseY, false);

        g.fill(16, 488, 404, 596, SECTION_COLOR);
        g.drawString(font, Component.translatable(state.weapon.equals("BALLISTIC")
                        ? "screen.create_fire_control.connected_display.telemetry.ballistic"
                        : "screen.create_fire_control.connected_display.telemetry.cruise"),
                26, 499, TEXT_COLOR, false);
        if (state.telemetry.isEmpty()) {
            g.drawString(font, Component.translatable(
                            "screen.create_fire_control.connected_display.telemetry.empty"),
                    26, 520,
                    MUTED_COLOR, false);
        } else {
            Telemetry telemetry = state.telemetry.get(0);
            double speed = Math.sqrt(telemetry.vx * telemetry.vx
                    + telemetry.vy * telemetry.vy + telemetry.vz * telemetry.vz) * 20.0;
            double bearing = Math.floorMod((int) Math.round(Math.toDegrees(
                    Math.atan2(telemetry.vx, telemetry.vz))), 360);
            Component telemetryType = state.weapon.equals("BALLISTIC")
                    ? ballisticName(state.ballisticType) : cruiseName(telemetry.type);
            g.drawString(font, Component.translatable(
                            "screen.create_fire_control.connected_display.telemetry.entry",
                            telemetryType, state.telemetry.size()),
                    26, 518, 0xFFFFD36A, false);
            g.drawString(font, Component.translatable(
                            "screen.create_fire_control.connected_display.telemetry.position",
                            decimal("%.0f", telemetry.x), decimal("%.0f", telemetry.y),
                            decimal("%.0f", telemetry.z)),
                    26, 537, TEXT_COLOR, false);
            g.drawString(font, Component.translatable(
                            "screen.create_fire_control.connected_display.telemetry.flight",
                            decimal("%.0f", bearing), decimal("%.1f", speed),
                            decimal("%.1f", telemetry.age / 20.0)),
                    26, 556, TEXT_COLOR, false);
            g.drawString(font, Component.translatable(
                            "screen.create_fire_control.connected_display.telemetry.target",
                            decimal("%.0f", telemetry.tx), decimal("%.0f", telemetry.ty),
                            decimal("%.0f", telemetry.tz)),
                    26, 575, GREEN, false);
        }
    }

    private void drawSlider(GuiGraphics g, Slider slider, Component label, Component value,
                            double progress, int labelY, int trackY, int color,
                            float mouseX, float mouseY) {
        g.drawString(font, label, 24, labelY, TEXT_COLOR, false);
        g.drawString(font, value, 350, labelY, color, false);
        g.fill(24, trackY, 396, trackY + 6, 0xFF294255);
        int knob = 24 + (int) Math.round(372.0 * Math.max(0.0, Math.min(1.0, progress)));
        g.fill(24, trackY, knob, trackY + 6, color);
        g.fill(knob - 4, trackY - 5, knob + 5, trackY + 11,
                slider == activeSlider ? 0xFFFFFFFF : 0xFF8FEAFF);
    }

    private void drawButton(GuiGraphics g, int x1, int y1, int x2, int y2,
                             Component text, boolean active, float mouseX, float mouseY,
                            boolean primary) {
        boolean hovered = inside(mouseX, mouseY, x1, y1, x2, y2);
        int fill = active ? ACTIVE_COLOR : primary ? 0xFFB86A1A : CONTROL_COLOR;
        if (hovered) fill = active ? 0xFF168BA8 : primary ? 0xFFD88B28 : 0xFF1F3B51;
        int outline = active ? GREEN : primary ? AMBER : 0xFF35546B;
        roundedFill(g, x1, y1, x2, y2, 5, fill);
        roundedOutline(g, x1, y1, x2, y2, 5, outline);
        g.drawCenteredString(font, text, (x1 + x2) / 2,
                (y1 + y2) / 2 - 4, primary ? 0xFFFFD69D : TEXT_COLOR);
    }

    private static void roundedFill(GuiGraphics g, int x1, int y1, int x2, int y2,
                                    int radius, int color) {
        int r = Math.max(1, Math.min(radius, Math.min((x2 - x1) / 2, (y2 - y1) / 2)));
        int steps = Math.max(4, r);
        g.fill(x1 + r, y1, x2 - r, y2, color);
        for (int i = 0; i < steps; i++) {
            int top = y1 + r * i / steps;
            int bottom = y1 + r * (i + 1) / steps;
            double dy = (top + bottom) * 0.5 - (y1 + r);
            int inset = Math.max(0,
                    r - (int) Math.round(Math.sqrt(Math.max(0.0, r * r - dy * dy))));
            g.fill(x1 + inset, top, x2 - inset, bottom, color);
            int bottomTop = y2 - r + r * i / steps;
            int bottomBottom = y2 - r + r * (i + 1) / steps;
            double bottomDy = (bottomTop + bottomBottom) * 0.5 - (y2 - r);
            int bottomInset = Math.max(0,
                    r - (int) Math.round(Math.sqrt(Math.max(0.0, r * r - bottomDy * bottomDy))));
            g.fill(x1 + bottomInset, bottomTop,
                    x2 - bottomInset, bottomBottom, color);
        }
    }

    private static void roundedOutline(GuiGraphics g, int x1, int y1, int x2, int y2,
                                       int radius, int color) {
        int r = Math.max(1, Math.min(radius, Math.min((x2 - x1) / 2, (y2 - y1) / 2)));
        g.fill(x1 + r, y1, x2 - r, y1 + 1, color);
        g.fill(x1 + r, y2 - 1, x2 - r, y2, color);
        g.fill(x1, y1 + r, x1 + 1, y2 - r, color);
        g.fill(x2 - 1, y1 + r, x2, y2 - r, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float scale = panelScale();
        float panelLeft = width - DESIGN_W * scale;
        if (mouseX < panelLeft) {
            float mapTop = 30.0F * scale;
            if (mouseY < mapTop) return false;
            if (button == 2) {
                panned = false;
                returningHome = true;
                targetViewCenterX = payload.displayX();
                targetViewCenterZ = payload.displayZ();
                return true;
            }
            if (button == 1) {
                cancelMapPoint(mouseX, mouseY);
                return true;
            }
            if (button == 0) {
                returningHome = false;
                mapPressed = true;
                mapDragging = false;
                mapPressX = mouseX;
                mapPressY = mouseY;
                return true;
            }
            return false;
        }
        if (button != 0) return false;
        double x = (mouseX - panelLeft) / scale;
        double y = mouseY / scale;
        if (inside(x, y, 16, 58, 105, 89)) sendSimple(
                ConnectedDisplayConsoleActionPayload.Action.SELECT_CANNON);
        else if (inside(x, y, 115, 58, 204, 89)) sendSimple(
                ConnectedDisplayConsoleActionPayload.Action.SELECT_AIR_DEFENSE);
        else if (inside(x, y, 214, 58, 303, 89)) sendSimple(
                ConnectedDisplayConsoleActionPayload.Action.SELECT_CRUISE);
        else if (inside(x, y, 313, 58, 404, 89)) sendSimple(
                ConnectedDisplayConsoleActionPayload.Action.SELECT_BALLISTIC);
        else if (state.weapon.equals("CRUISE")
                && inside(x, y, 16, 100, 404, 132)) sendSimple(
                ConnectedDisplayConsoleActionPayload.Action.CYCLE_CRUISE_TYPE);
        else if (state.weapon.equals("BALLISTIC")
                && inside(x, y, 16, 100, 404, 132)) sendSimple(
                ConnectedDisplayConsoleActionPayload.Action.CYCLE_BALLISTIC_TYPE);
        else if (inside(x, y, 24, 160, 396, 186)) setSlider(Slider.RADAR, x);
        else if (inside(x, y, 24, 210, 396, 236)) setSlider(Slider.DEFENSE, x);
        else if (inside(x, y, 24, 260, 396, 286)) setSlider(Slider.PROXIMITY, x);
        else if (inside(x, y, 16, 291, 202, 325)) sendSimple(
                ConnectedDisplayConsoleActionPayload.Action.TOGGLE_AUTOMATIC_ATTACK);
        else if (inside(x, y, 218, 291, 404, 325)) sendSimple(
                ConnectedDisplayConsoleActionPayload.Action.TOGGLE_AUTOMATIC_DEFENSE);
        else if (inside(x, y, 277, 342, 394, 369)) sendSimple(
                ConnectedDisplayConsoleActionPayload.Action.TOGGLE_CRUISE_MULTI);
        else if (inside(x, y, 16, 395, 404, 435)) sendSimple(
                ConnectedDisplayConsoleActionPayload.Action.FIRE);
        else if (inside(x, y, 16, 443, 404, 477)) sendSimple(
                ConnectedDisplayConsoleActionPayload.Action.CLEAR_TARGET);
        else return false;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        float scale = panelScale();
        float panelLeft = width - DESIGN_W * scale;
        if (activeSlider != null && button == 0) {
            setSlider(activeSlider, (mouseX - panelLeft) / scale);
            return true;
        }
        if (mapPressed && button == 0) {
            if (!mapDragging && Math.hypot(mouseX - mapPressX,
                    mouseY - mapPressY) >= 3.0) mapDragging = true;
            if (mapDragging) {
                float mapWidth = Math.max(1.0F, panelLeft);
                float mapTop = 30.0F * scale;
                float mapHeight = Math.max(1.0F, height - mapTop);
                double halfX = visibleHalfSpanX();
                double halfZ = halfX * mapHeight / mapWidth;
                viewCenterX -= dragX * halfX * 2.0 / mapWidth;
                viewCenterZ -= dragY * halfZ * 2.0 / mapHeight;
                targetViewCenterX = viewCenterX;
                targetViewCenterZ = viewCenterZ;
                panned = true;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (activeSlider != null && button == 0) {
            activeSlider = null;
            return true;
        }
        if (mapPressed && button == 0) {
            boolean click = !mapDragging;
            mapPressed = false;
            mapDragging = false;
            if (click) selectMapPoint(mouseX, mouseY);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        float scale = panelScale();
        float mapWidth = Math.max(1.0F, width - DESIGN_W * scale);
        float mapTop = 30.0F * scale;
        float mapHeight = Math.max(1.0F, height - mapTop);
        if (mouseX < 0.0 || mouseX > mapWidth
                || mouseY < mapTop || mouseY > height || scrollY == 0.0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        double oldHalfX = targetMapHalfSpanX;
        double oldHalfZ = oldHalfX * mapHeight / mapWidth;
        double newHalfX = Math.max(25.0, Math.min(50_000.0,
                oldHalfX * Math.pow(0.8, scrollY)));
        if (Math.abs(newHalfX - oldHalfX) < 0.001) return true;
        double newHalfZ = newHalfX * mapHeight / mapWidth;

        // Keep the block beneath the cursor fixed while zooming, matching a
        // normal map application's wheel behaviour.
        double relativeX = mouseX / mapWidth - 0.5;
        double relativeZ = (mouseY - mapTop) / mapHeight - 0.5;
        double anchorX = targetViewCenterX + relativeX * oldHalfX * 2.0;
        double anchorZ = targetViewCenterZ + relativeZ * oldHalfZ * 2.0;
        targetViewCenterX = anchorX - relativeX * newHalfX * 2.0;
        targetViewCenterZ = anchorZ - relativeZ * newHalfZ * 2.0;
        targetMapHalfSpanX = newHalfX;
        returningHome = false;
        panned = true;
        return true;
    }

    private void selectMapPoint(double mouseX, double mouseY) {
        float scale = panelScale();
        float mapWidth = Math.max(1.0F, width - DESIGN_W * scale);
        float mapTop = 30.0F * scale;
        float mapHeight = Math.max(1.0F, height - mapTop);
        if (mouseX < 0 || mouseX > mapWidth || mouseY < mapTop || mouseY > height) return;
        double halfX = visibleHalfSpanX();
        double halfZ = halfX * mapHeight / mapWidth;
        Contact nearest = null;
        double nearestDistance = 11.0 * 11.0;
        for (Contact contact : state.contacts) {
            Point point = mapPoint(contact.x, contact.z,
                    mapWidth, mapTop, mapHeight, halfX, halfZ);
            double distance = (point.x - mouseX) * (point.x - mouseX)
                    + (point.y - mouseY) * (point.y - mouseY);
            if (point.visible && distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = contact;
            }
        }
        double worldX = viewCenterX + (mouseX / mapWidth - 0.5) * halfX * 2.0;
        double worldZ = viewCenterZ
                + ((mouseY - mapTop) / mapHeight - 0.5) * halfZ * 2.0;
        PacketDistributor.sendToServer(new ConnectedDisplayConsoleActionPayload(
                payload.consolePos(), payload.consoleSubLevelId(),
                ConnectedDisplayConsoleActionPayload.Action.MAP_POINT,
                worldX, worldZ, nearest == null ? null : nearest.id,
                nearest != null && nearest.living));
    }

    private void cancelMapPoint(double mouseX, double mouseY) {
        float scale = panelScale();
        float mapWidth = Math.max(1.0F, width - DESIGN_W * scale);
        float mapTop = 30.0F * scale;
        float mapHeight = Math.max(1.0F, height - mapTop);
        if (mouseX < 0 || mouseX > mapWidth || mouseY < mapTop || mouseY > height) return;
        double halfX = visibleHalfSpanX();
        double halfZ = halfX * mapHeight / mapWidth;
        double nearestDistance = 11.0 * 11.0;
        UUID targetId = null;
        boolean targetLiving = false;
        double targetX = 0.0;
        double targetZ = 0.0;
        boolean found = false;

        if (state.cruiseMulti) {
            for (StrikeTarget strike : state.strikeTargets) {
                double worldX = strike.x;
                double worldZ = strike.z;
                if (strike.targetId != null) {
                    Contact contact = state.contacts.stream()
                            .filter(candidate -> strike.targetId.equals(candidate.id)
                                    && strike.living == candidate.living)
                            .findFirst().orElse(null);
                    if (contact != null) {
                        worldX = contact.x;
                        worldZ = contact.z;
                    }
                }
                Point point = mapPoint(worldX, worldZ,
                        mapWidth, mapTop, mapHeight, halfX, halfZ);
                double distance = squaredDistance(point, mouseX, mouseY);
                if (point.visible && distance <= nearestDistance) {
                    nearestDistance = distance;
                    targetId = strike.targetId;
                    targetLiving = strike.living;
                    targetX = strike.x;
                    targetZ = strike.z;
                    found = true;
                }
            }
        } else {
            if (state.manualAim != null) {
                Point point = mapPoint(state.manualAim.x, state.manualAim.z,
                        mapWidth, mapTop, mapHeight, halfX, halfZ);
                double distance = squaredDistance(point, mouseX, mouseY);
                if (point.visible && distance <= nearestDistance) {
                    nearestDistance = distance;
                    targetX = state.manualAim.x;
                    targetZ = state.manualAim.z;
                    found = true;
                }
            }
            for (Contact contact : state.contacts) {
                if (!contact.selected) continue;
                Point point = mapPoint(contact.x, contact.z,
                        mapWidth, mapTop, mapHeight, halfX, halfZ);
                double distance = squaredDistance(point, mouseX, mouseY);
                if (point.visible && distance <= nearestDistance) {
                    nearestDistance = distance;
                    targetId = contact.id;
                    targetLiving = contact.living;
                    targetX = contact.x;
                    targetZ = contact.z;
                    found = true;
                }
            }
        }
        if (!found) return;
        PacketDistributor.sendToServer(new ConnectedDisplayConsoleActionPayload(
                payload.consolePos(), payload.consoleSubLevelId(),
                ConnectedDisplayConsoleActionPayload.Action.CANCEL_MAP_POINT,
                targetX, targetZ, targetId, targetLiving));
    }

    private static double squaredDistance(Point point, double x, double y) {
        double dx = point.x - x;
        double dy = point.y - y;
        return dx * dx + dy * dy;
    }

    private void setSlider(Slider slider, double designX) {
        activeSlider = slider;
        double progress = Math.max(0.0, Math.min(1.0, (designX - 24.0) / 372.0));
        double value;
        ConnectedDisplayConsoleActionPayload.Action action;
        switch (slider) {
            case RADAR -> {
                value = Math.round((100.0 + progress * 9900.0) / 10.0) * 10.0;
                state.radarRange = value;
                action = ConnectedDisplayConsoleActionPayload.Action.SET_RADAR_RANGE;
            }
            case DEFENSE -> {
                value = progress >= 0.999 ? 0.0
                        : 50.0 + Math.round(progress * 9950.0 / 10.0) * 10.0;
                state.defenseRange = value;
                action = ConnectedDisplayConsoleActionPayload.Action.SET_DEFENSE_RANGE;
            }
            case PROXIMITY -> {
                value = Math.round((1.0 + progress * 29.0) * 2.0) / 2.0;
                state.proximityRange = value;
                action = ConnectedDisplayConsoleActionPayload.Action.SET_PROXIMITY_RANGE;
            }
            default -> throw new IllegalStateException();
        }
        PacketDistributor.sendToServer(new ConnectedDisplayConsoleActionPayload(
                payload.consolePos(), payload.consoleSubLevelId(), action,
                value, 0.0, null, false));
    }

    private void sendSimple(ConnectedDisplayConsoleActionPayload.Action action) {
        PacketDistributor.sendToServer(ConnectedDisplayConsoleActionPayload.simple(
                payload.consolePos(), payload.consoleSubLevelId(), action));
    }

    private double visibleHalfSpanX() {
        return Math.max(25.0, Math.min(50_000.0, mapHalfSpanX));
    }

    private void animateMapView() {
        long now = System.nanoTime();
        if (lastMapAnimationNanos == 0L) {
            lastMapAnimationNanos = now;
            return;
        }
        double seconds = Math.min(0.1,
                (now - lastMapAnimationNanos) / 1_000_000_000.0);
        lastMapAnimationNanos = now;
        double blend = 1.0 - Math.exp(-12.0 * seconds);
        viewCenterX += (targetViewCenterX - viewCenterX) * blend;
        viewCenterZ += (targetViewCenterZ - viewCenterZ) * blend;
        mapHalfSpanX += (targetMapHalfSpanX - mapHalfSpanX) * blend;

        if (Math.abs(targetMapHalfSpanX - mapHalfSpanX) < 0.01) {
            mapHalfSpanX = targetMapHalfSpanX;
        }
        if (returningHome && Math.hypot(targetViewCenterX - viewCenterX,
                targetViewCenterZ - viewCenterZ) < 0.05) {
            viewCenterX = targetViewCenterX;
            viewCenterZ = targetViewCenterZ;
            returningHome = false;
        }
    }

    private float panelScale() {
        float byHeight = height / DESIGN_H;
        float byWidth = width / (DESIGN_W + MIN_MAP_DESIGN_W);
        return Math.max(0.25F, Math.min(byHeight, byWidth));
    }

    private Component fireText() {
        return switch (state.weapon) {
            case "AIR_DEFENSE" -> Component.translatable(
                    "screen.create_fire_control.connected_display.fire.air_defense");
            case "CRUISE" -> state.cruiseMulti
                    ? Component.translatable(
                    "screen.create_fire_control.connected_display.fire.cruise_multiple")
                    : Component.translatable(
                    "screen.create_fire_control.connected_display.fire.cruise_single");
            case "BALLISTIC" -> Component.translatable(
                    "screen.create_fire_control.connected_display.fire.ballistic");
            default -> Component.translatable(
                    "screen.create_fire_control.connected_display.fire.cannon");
        };
    }

    private Component targetSummary() {
        if (state.cruiseMulti) return Component.translatable(
                "screen.create_fire_control.connected_display.targets_selected",
                state.strikeTargets.size(), state.cruiseCapacity);
        if (state.manualAim != null) return Component.translatable(
                "screen.create_fire_control.connected_display.map_point_summary",
                decimal("%.0f", state.manualAim.x), decimal("%.0f", state.manualAim.z));
        Contact selected = state.contacts.stream().filter(contact -> contact.selected)
                .findFirst().orElse(null);
        if (selected == null) return Component.translatable(
                "screen.create_fire_control.connected_display.not_selected");
        return Component.translatable(
                "screen.create_fire_control.connected_display.target_summary",
                targetName(selected.missile, selected.living),
                decimal("%.0f", selected.distance), decimal("%.1f", selected.closing));
    }

    private static Component cruiseName(String key) {
        MianbaoCruiseMissileCompat.Type type =
                MianbaoCruiseMissileCompat.Type.byKey(key);
        return type == null ? Component.literal(key) : Component.translatable(
                "screen.create_fire_control.connected_display.missile_type." + type.key());
    }

    private static Component ballisticName(String key) {
        MianbaoBallisticMissileCompat.Type type =
                MianbaoBallisticMissileCompat.Type.byKey(key);
        return type == null ? Component.literal(key) : Component.translatable(
                "screen.create_fire_control.connected_display.missile_type." + type.key());
    }

    private static Component availableMissileName(Component name) {
        return Component.translatable(
                "screen.create_fire_control.connected_display.missile_type.available", name);
    }

    private static Component targetName(boolean missile, boolean living) {
        String suffix = missile ? "cruise_missile" : living ? "living" : "sable";
        return Component.translatable(
                "screen.create_fire_control.connected_display.target_name." + suffix);
    }

    private static Component toggle(boolean enabled) {
        return Component.translatable(enabled
                ? "screen.create_fire_control.connected_display.state.enabled"
                : "screen.create_fire_control.connected_display.state.disabled");
    }

    private static Component formatRange(double range) {
        return range >= 1000.0
                ? Component.translatable(
                "screen.create_fire_control.connected_display.range.kilometers",
                decimal("%.1f", range / 1000.0))
                : Component.translatable(
                "screen.create_fire_control.connected_display.range.meters",
                decimal("%.0f", range));
    }

    private static Component formatDefenseRange(double range) {
        return range <= 0.0 ? Component.translatable(
                "screen.create_fire_control.connected_display.range.unlimited")
                : formatRange(range);
    }

    private static String decimal(String format, double value) {
        return String.format(Locale.ROOT, format, value);
    }

    private static boolean inside(double x, double y,
                                  double x1, double y1, double x2, double y2) {
        return x >= x1 && x <= x2 && y >= y1 && y <= y2;
    }

    private static void texturedOpaque(Matrix4f matrix, float x1, float y1,
                                       float x2, float y2, int texture) {
        // Xaero stores its light value in the texture's alpha channel. Draw
        // opaquely so zero light is not mistaken for transparent terrain.
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        BufferBuilder builder = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(matrix, x1, y1, 0.0F).setUv(0.0F, 0.0F);
        builder.addVertex(matrix, x2, y1, 0.0F).setUv(1.0F, 0.0F);
        builder.addVertex(matrix, x2, y2, 0.0F).setUv(1.0F, 1.0F);
        builder.addVertex(matrix, x1, y2, 0.0F).setUv(0.0F, 1.0F);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY,
                                 float partialTick) {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 || minecraft != null
                && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        if (minecraft != null && minecraft.getConnection() != null) {
            PacketDistributor.sendToServer(ConnectedDisplayConsoleActionPayload.simple(
                    payload.consolePos(), payload.consoleSubLevelId(),
                    ConnectedDisplayConsoleActionPayload.Action.RELEASE_CONTROL));
        }
        XaeroDisplayMapCompat.releaseConsoleTexture(this);
        super.removed();
    }

    private enum Slider { RADAR, DEFENSE, PROXIMITY }

    private record Point(float x, float y, boolean visible) {}
    private record AimPoint(double x, double y, double z) {}
    private record StrikeTarget(@Nullable UUID targetId, boolean living,
                                double x, double y, double z, int number) {}
    private record Telemetry(String type, double x, double y, double z,
                             double vx, double vy, double vz,
                             double tx, double ty, double tz, int age) {}
    private record Contact(UUID id, boolean living, boolean missile,
                           double x, double y, double z,
                           double distance, double closing,
                           boolean selected, boolean strikeTarget,
                           int strikeNumber) {}

    private static final class DisplayState {
        private boolean automaticAttack;
        private boolean automaticDefense;
        private double radarRange;
        private double defenseRange;
        private double proximityRange;
        private int mapUpdateIntervalSeconds;
        private String weapon;
        private String cruiseType;
        private String ballisticType;
        private boolean cruiseAvailable;
        private boolean ballisticAvailable;
        private boolean cruiseMulti;
        private int cruiseCapacity;
        private @Nullable AimPoint manualAim;
        private List<Contact> contacts = List.of();
        private List<StrikeTarget> strikeTargets = List.of();
        private List<Telemetry> telemetry = List.of();

        static DisplayState read(CompoundTag tag) {
            DisplayState result = new DisplayState();
            result.automaticAttack = tag.getBoolean("AutomaticAttack");
            result.automaticDefense = tag.getBoolean("AutomaticMissileDefense");
            result.radarRange = tag.contains("RadarRange")
                    ? tag.getDouble("RadarRange") : 2000.0;
            result.defenseRange = tag.getDouble("AutomaticMissileDefenseRange");
            result.proximityRange = tag.contains("MissileProximityRange")
                    ? tag.getDouble("MissileProximityRange") : 5.0;
            result.mapUpdateIntervalSeconds = Math.max(1,
                    tag.getInt("MapUpdateIntervalSeconds"));
            result.weapon = tag.getString("SelectedWeapon");
            if (result.weapon.isEmpty()) result.weapon = "CANNON";
            result.cruiseType = tag.getString("SelectedCruiseType");
            result.ballisticType = tag.getString("SelectedBallisticType");
            if (result.ballisticType.isEmpty()) result.ballisticType = "native";
            result.cruiseAvailable = !tag.getString("AvailableCruiseTypes").isEmpty();
            result.ballisticAvailable = tag.getBoolean("BallisticAvailable");
            result.cruiseMulti = tag.getBoolean("CruiseMultiSelect")
                    && result.weapon.equals("CRUISE");
            result.cruiseCapacity = Math.max(0, tag.getInt("CruiseSalvoCapacity"));
            if (tag.contains("ManualAimX")) {
                result.manualAim = new AimPoint(tag.getDouble("ManualAimX"),
                        tag.getDouble("ManualAimY"), tag.getDouble("ManualAimZ"));
            }
            UUID selectedId = tag.hasUUID("SelectedTarget")
                    ? tag.getUUID("SelectedTarget") : null;
            boolean selectedLiving = tag.getBoolean("SelectedTargetLiving");
            List<StrikeTarget> strikes = new ArrayList<>();
            int strikeNumber = 1;
            for (Tag value : tag.getList("CruiseStrikeTargets", Tag.TAG_COMPOUND)) {
                CompoundTag target = (CompoundTag) value;
                strikes.add(new StrikeTarget(target.hasUUID("Target")
                        ? target.getUUID("Target") : null,
                        target.getBoolean("Living"), target.getDouble("X"),
                        target.getDouble("Y"), target.getDouble("Z"), strikeNumber++));
            }
            result.strikeTargets = List.copyOf(strikes);
            List<Contact> contacts = new ArrayList<>();
            for (Tag value : tag.getList("Contacts", Tag.TAG_COMPOUND)) {
                CompoundTag contact = (CompoundTag) value;
                UUID id = contact.getUUID("Id");
                boolean living = contact.getBoolean("Living");
                int number = 0;
                for (StrikeTarget strike : strikes) {
                    if (id.equals(strike.targetId) && living == strike.living) {
                        number = strike.number;
                        break;
                    }
                }
                contacts.add(new Contact(id, living, contact.getBoolean("Missile"),
                        contact.getDouble("X"), contact.getDouble("Y"),
                        contact.getDouble("Z"), contact.getDouble("Distance"),
                        contact.getDouble("Closing"), id.equals(selectedId)
                        && living == selectedLiving, number > 0, number));
            }
            result.contacts = List.copyOf(contacts);
            List<Telemetry> telemetry = new ArrayList<>();
            String telemetryKey = result.weapon.equals("BALLISTIC")
                    ? "BallisticTelemetry" : "CruiseTelemetry";
            for (Tag value : tag.getList(telemetryKey, Tag.TAG_COMPOUND)) {
                CompoundTag data = (CompoundTag) value;
                telemetry.add(new Telemetry(result.weapon.equals("BALLISTIC")
                        ? "ballistic" : data.getString("Type"),
                        data.getDouble("X"), data.getDouble("Y"), data.getDouble("Z"),
                        data.getDouble("VX"), data.getDouble("VY"), data.getDouble("VZ"),
                        data.getDouble("TX"), data.getDouble("TY"), data.getDouble("TZ"),
                        data.getInt("Age")));
            }
            result.telemetry = List.copyOf(telemetry);
            return result;
        }

        boolean hasTarget() {
            return manualAim != null || contacts.stream().anyMatch(Contact::selected)
                    || !strikeTargets.isEmpty();
        }

        double radarProgress() {
            return (radarRange - 100.0) / 9900.0;
        }

        double defenseProgress() {
            return defenseRange <= 0.0 ? 1.0 : (defenseRange - 50.0) / 9950.0;
        }

        double proximityProgress() {
            return (proximityRange - 1.0) / 29.0;
        }
    }

    private static final class MapGeometry {
        private final Matrix4f matrix;
        private final List<Quad> quads = new ArrayList<>();

        private MapGeometry(Matrix4f matrix) {
            this.matrix = matrix;
        }

        void line(float x1, float y1, float x2, float y2, int color, float width) {
            float dx = x2 - x1, dy = y2 - y1;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length < 0.001F) return;
            float nx = -dy / length * width * 0.5F;
            float ny = dx / length * width * 0.5F;
            quads.add(new Quad(x1 + nx, y1 + ny, x2 + nx, y2 + ny,
                    x2 - nx, y2 - ny, x1 - nx, y1 - ny, color));
        }

        void circle(float centerX, float centerY, float radius,
                    int color, float width) {
            int segments = 12;
            float previousX = centerX + radius;
            float previousY = centerY;
            for (int segment = 1; segment <= segments; segment++) {
                double angle = Math.PI * 2.0 * segment / segments;
                float nextX = centerX + (float) Math.cos(angle) * radius;
                float nextY = centerY + (float) Math.sin(angle) * radius;
                line(previousX, previousY, nextX, nextY, color, width);
                previousX = nextX;
                previousY = nextY;
            }
        }

        void flush() {
            if (quads.isEmpty()) return;
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder builder = Tesselator.getInstance().begin(
                    VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (Quad quad : quads) {
                builder.addVertex(matrix, quad.x1, quad.y1, 0).setColor(quad.color);
                builder.addVertex(matrix, quad.x2, quad.y2, 0).setColor(quad.color);
                builder.addVertex(matrix, quad.x3, quad.y3, 0).setColor(quad.color);
                builder.addVertex(matrix, quad.x4, quad.y4, 0).setColor(quad.color);
            }
            BufferUploader.drawWithShader(builder.buildOrThrow());
            RenderSystem.enableDepthTest();
        }
    }

    private record Quad(float x1, float y1, float x2, float y2,
                        float x3, float y3, float x4, float y4, int color) {}
}
