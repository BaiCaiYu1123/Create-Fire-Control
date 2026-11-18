package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.ScopePreset;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import dev.ryanhcode.sable.mixinhelpers.camera.new_camera_types.SableCameraTypes;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Vector3f;

/** Independent third-person and gun-sight aiming marks. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ThirdPersonAimHudRenderer {
    /** Reference outer ring: pale white with the dark inner anti-alias edge. */
    private static final int VIEW_RETICLE = 0xE8E8ECEF;
    /** Reference inner ring: the barrel's pale yellow actual aim point. */
    private static final int CANNON_RETICLE = 0xEEF2DF9E;
    private static final int RETICLE_SHADOW_OUTER = 0x5C111820;
    private static final int RETICLE_SHADOW_INNER = 0x82111820;
    private static final float HEAD_AIM_REFERENCE_HEIGHT = 1440.0F;
    private static final int SCOPE_RETICLE_BLACK = 0xEE000000;
    private static final int SCOPE_RETICLE_RING = 0xEED8D8D8;
    private static final int OPTICAL_LOCK = 0xEE5DFF78;
    private static final int LOCK_FRAME_GREEN = 0xFF00FF00;
    private static final int ROCKET_IMPACT = 0xFF58FF72;
    private static final float ROCKET_REFERENCE_HEIGHT = 1440.0F;

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        if (ClientWeaponViewState.isActive()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (InfraredHudRenderer.selected() || !ClientAimState.isActive()
                || ClientAimState.isOpticalView()
                || ClientTelevisionGuidanceState.isActive()
                || minecraft.options.hideGui
                || minecraft.screen != null) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        // Use the same pose fraction as Camera.setup and the rendered cannon.
        // Frame duration is almost constant at steady FPS and freezes pose interpolation.
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        if (minecraft.options.getCameraType() == CameraType.FIRST_PERSON) {
            // First person keeps optical-lock feedback only. The large
            // War-Thunder sight belongs exclusively to the gun sight.
            renderOpticalMarkers(
                    graphics, minecraft, width, height, partialTick,
                    width / 2, height / 2
            );
        } else {
            renderAimMarkers(graphics, minecraft, width, height, partialTick, false);
            if (minecraft.options.getCameraType() == SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED) {
                renderRocketImpactMarker(
                        graphics, minecraft, width, height, partialTick);
            }
        }
    }

    static void renderAimMarkers(
            GuiGraphics graphics,
            Minecraft minecraft,
            int width,
            int height,
            float partialTick,
            boolean scoped
    ) {
        int viewX = scoped
                ? width / 2
                : width / 2 + (int) Math.round(width * ClientAimState.getViewAimOffsetX() / 100.0);
        int viewY = scoped
                ? height / 2
                : height / 2 + (int) Math.round(height * ClientAimState.getViewAimOffsetY() / 100.0);
        if (!scoped && ClientAimState.shouldUseHeadAimThirdPersonFollow()) {
            Camera camera = minecraft.gameRenderer.getMainCamera();
            ScreenPoint mouseTarget = projectToScreen(
                    minecraft,
                    camera.getPosition().add(
                            ClientAimState.getHeadAimTargetDirection().scale(4096.0)),
                    width,
                    height,
                    partialTick
            );
            if (mouseTarget != null) {
                viewX = mouseTarget.x;
                viewY = mouseTarget.y;
            }
        }

        java.util.List<ScreenPoint> cannons = new java.util.ArrayList<>();
        java.util.List<Vec3> groupPoints = ClientAimState.getCannonAimGroupPoints(partialTick);
        if (!groupPoints.isEmpty()) {
            for (Vec3 point : groupPoints) {
                ScreenPoint projected = projectToScreen(minecraft, point, width, height, partialTick);
                if (projected != null && projected.x >= 4 && projected.x < width - 4
                        && projected.y >= 4 && projected.y < height - 4) {
                    cannons.add(projected);
                }
            }
        } else if (!ClientAimState.hasAimGroups() && ClientAimState.hasCannonAimPoint()) {
            ScreenPoint projected = projectToScreen(
                    minecraft, ClientAimState.getCannonAimPoint(partialTick), width, height, partialTick);
            if (projected != null && projected.x >= 4 && projected.x < width - 4
                    && projected.y >= 4 && projected.y < height - 4) {
                cannons.add(projected);
            }
        }

        if (scoped) {
            if (ClientAimState.getScopePreset() == ScopePreset.DEFAULT) {
                // The original preset keeps its independent cannon convergence
                // scale, fixed corner frame, and view-centre ring.
                for (ScreenPoint cannon : cannons) {
                    drawScopeCannonReticle(graphics, cannon.x, cannon.y, width, height);
                }
                drawScopePlayerReticle(graphics, viewX, viewY, width, height);
            } else {
                for (ScreenPoint cannon : cannons) {
                    ScopeHudRenderer.drawPresetReticle(
                            graphics, width, height, ClientAimState.getScopePreset(),
                            cannon.x, cannon.y
                    );
                }
                drawScopePlayerRing(graphics, viewX, viewY, height);
            }
        } else {
            // Aircraft installations use radar symbology and do not show the tank head-aim ring.
            if (!ClientAimState.hasAircraftEquipment()
                    && ClientAimState.isHeadAimEnabled()) {
                drawViewReticle(graphics, viewX, viewY);
            }
            for (ScreenPoint cannon : cannons) {
                drawCannonReticle(graphics, cannon.x, cannon.y);
            }
        }
        renderOpticalMarkers(graphics, minecraft, width, height, partialTick, viewX, viewY);
        if (scoped && ClientAimState.isPodView()) {
            renderAirToGroundMarkers(graphics, minecraft, width, height, partialTick);
        }
    }

    public static void renderAirToGroundMarkers(
            GuiGraphics graphics,
            Minecraft minecraft,
            int width,
            int height,
            float partialTick
    ) {
        if (ClientAimState.isAirToGroundLockSearching()) {
            int cx = width / 2;
            int cy = height / 2;
            int half = TargetLockFrame.halfSide(height);
            drawCornerFrame(graphics, cx - half, cy - half, cx + half, cy + half);
        }
        if (!ClientAimState.isAirToGroundLockActive()) return;
        AABB bounds = ClientAimState.getAirToGroundLockBounds(partialTick);
        if (bounds == null) return;
        ScreenPoint center = projectToScreen(
                minecraft, bounds.getCenter(), width, height, partialTick);
        if (center == null) return;
        TargetLockFrame.render(graphics, minecraft, center.x, center.y, height,
                TargetLockFrame.Style.AIR_TO_GROUND, LOCK_FRAME_GREEN, bounds.getCenter(),
                ClientAimState.getAirToGroundLockTelemetry());
    }

    private static void renderRocketImpactMarker(
            GuiGraphics graphics,
            Minecraft minecraft,
            int width,
            int height,
            float partialTick
    ) {
        if (!ClientVisualConfig.showRocketImpact() || !ClientAimState.hasRocketImpactPoint()) return;
        ScreenPoint impact = projectToScreen(
                minecraft,
                ClientAimState.getRocketImpactPoint(partialTick),
                width,
                height,
                partialTick);
        int margin = Math.max(4, Mth.ceil(18.0F * height / ROCKET_REFERENCE_HEIGHT));
        if (impact == null || impact.x < margin || impact.x >= width - margin
                || impact.y < margin || impact.y >= height - margin) return;
        drawRocketImpactReticle(graphics, impact.x, impact.y, height);
    }

    /** Four fluorescent diagonal arms and a centre dot, matched to 3440x1440. */
    private static void drawRocketImpactReticle(
            GuiGraphics graphics, int centreX, int centreY, int guiHeight
    ) {
        // Coordinates below are framebuffer-pixel measurements from the
        // 3440x1440 reference. Scaling against GUI height automatically also
        // compensates for Minecraft's GUI scale factor.
        float scale = Math.max(0.15F, guiHeight / ROCKET_REFERENCE_HEIGHT);
        graphics.pose().pushPose();
        graphics.pose().translate(centreX, centreY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        drawThickLine(graphics, -16, -16, -7, -7, 3, ROCKET_IMPACT);
        drawThickLine(graphics, 7, -7, 16, -16, 3, ROCKET_IMPACT);
        drawThickLine(graphics, -7, 7, -16, 16, 3, ROCKET_IMPACT);
        drawThickLine(graphics, 7, 7, 16, 16, 3, ROCKET_IMPACT);
        graphics.fill(-2, -2, 3, 3, ROCKET_IMPACT);
        graphics.pose().popPose();
    }

    private static void drawThickLine(
            GuiGraphics graphics,
            int x0,
            int y0,
            int x1,
            int y1,
            int thickness,
            int color
    ) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        int radius = Math.max(0, thickness / 2);
        while (true) {
            graphics.fill(x0 - radius, y0 - radius,
                    x0 + radius + 1, y0 + radius + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int twiceError = error * 2;
            if (twiceError >= dy) {
                error += dy;
                x0 += sx;
            }
            if (twiceError <= dx) {
                error += dx;
                y0 += sy;
            }
        }
    }

    private static void renderOpticalMarkers(
            GuiGraphics graphics,
            Minecraft minecraft,
            int width,
            int height,
            float partialTick,
            int aimX,
            int aimY
    ) {
        if (ClientAimState.isOpticalLockSearching()) {
            // Acquisition follows the aim point, with the same fixed screen
            // dimensions as the locked frame.
            ScreenPoint cannon = ClientAimState.hasCannonAimPoint()
                    ? projectToScreen(
                    minecraft,
                    ClientAimState.getCannonAimPoint(partialTick),
                    width,
                    height,
                    partialTick
                    )
                    : null;
            int centreX = cannon == null ? aimX : cannon.x;
            int centreY = cannon == null ? aimY : cannon.y;
            int half = TargetLockFrame.halfSide(height);
            drawCornerFrame(
                    graphics,
                    centreX - half,
                    centreY - half,
                    centreX + half,
                    centreY + half
            );
            return;
        }
        if (!ClientAimState.isOpticalLockActive()) {
            return;
        }
        Vec3 targetPosition = ClientAimState.getOpticalLockDisplayPoint(partialTick);
        ScreenPoint target = projectToScreen(
                minecraft,
                targetPosition,
                width,
                height,
                partialTick
        );
        if (target != null) {
            int lockColor = multiplyAlpha(LOCK_FRAME_GREEN, ClientAimState.getOpticalLockFrameAlpha());
            CannonLeadHud.render(graphics, minecraft, target.x, target.y,
                    ClientCannonLockLead.opticalAimPoint(targetPosition, partialTick),
                    ClientCannonLockLead.opticalStatus(), partialTick, lockColor);
            TargetLockFrame.render(graphics, minecraft, target.x, target.y, height,
                    TargetLockFrame.Style.OPTICAL, lockColor, targetPosition,
                    ClientAimState.getOpticalLockTelemetry());
        }
    }

    private static ScreenPoint projectToScreen(Minecraft minecraft, Vec3 world, int width, int height, float partialTick) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 relative = world.subtract(camera.getPosition());
        Vector3f forwardVector = camera.getLookVector();
        Vector3f leftVector = camera.getLeftVector();
        Vector3f upVector = camera.getUpVector();
        Vec3 forward = new Vec3(forwardVector.x, forwardVector.y, forwardVector.z);
        Vec3 left = new Vec3(leftVector.x, leftVector.y, leftVector.z);
        Vec3 up = new Vec3(upVector.x, upVector.y, upVector.z);
        double depth = relative.dot(forward);
        if (depth <= 0.05) {
            return null;
        }
        double fov = ((GameRendererAccessor) (Object) minecraft.gameRenderer)
                .stabilizedTurret$getFov(camera, partialTick, true);
        double focal = height * 0.5 / Math.tan(Mth.DEG_TO_RAD * fov * 0.5);
        int x = width / 2 + (int) Math.round(-relative.dot(left) * focal / depth);
        int y = height / 2 + (int) Math.round(-relative.dot(up) * focal / depth);
        return new ScreenPoint(x, y);
    }

    private static void drawScopePlayerReticle(
            GuiGraphics graphics,
            int x,
            int y,
            int screenWidth,
            int screenHeight
    ) {
        // Measured from the 3440x1440 reference: bracket corners are
        // 390 px horizontally and 163 px vertically from the centre, with
        // 54 px inward arms. Keeping those ratios makes the sight identical
        // at every GUI scale and aspect ratio.
        int halfWidth = Math.max(12, (int) Math.round(screenWidth * (390.0 / 3440.0)));
        int halfHeight = Math.max(8, (int) Math.round(screenHeight * (163.0 / 1440.0)));
        int arm = Math.max(4, (int) Math.round(screenHeight * (54.0 / 1440.0)));
        int minX = x - halfWidth;
        int maxX = x + halfWidth;
        int minY = y - halfHeight;
        int maxY = y + halfHeight;
        drawScopeCorner(graphics, minX, minY, arm, 1, 1);
        drawScopeCorner(graphics, maxX, minY, arm, -1, 1);
        drawScopeCorner(graphics, minX, maxY, arm, 1, -1);
        drawScopeCorner(graphics, maxX, maxY, arm, -1, -1);

        drawScopePlayerRing(graphics, x, y, screenHeight);
    }

    private static void drawScopePlayerRing(
            GuiGraphics graphics, int x, int y, int screenHeight
    ) {
        int radius = Math.max(4, (int) Math.round(screenHeight * (30.0 / 1440.0)));
        drawCircle(graphics, x, y, radius, SCOPE_RETICLE_RING);
    }

    private static void drawScopeCannonReticle(
            GuiGraphics graphics,
            int x,
            int y,
            int screenWidth,
            int screenHeight
    ) {
        int color = ScopeHudRenderer.sightLineColor(SCOPE_RETICLE_BLACK);
        int crossHalf = Math.max(5, (int) Math.round(screenHeight * (26.0 / 1440.0)));
        graphics.fill(x - crossHalf, y, x + crossHalf + 1, y + 1, color);
        graphics.fill(x, y - crossHalf, x + 1, y + crossHalf + 1, color);

        int viewRingRadius = Math.max(4, (int) Math.round(screenHeight * (30.0 / 1440.0)));
        int dotSpacing = Math.max(3, (int) Math.round(screenHeight * (12.0 / 1440.0)));
        int dotSize = Math.max(1, (int) Math.round(screenHeight * (3.0 / 1440.0)));
        int dottedHalf = Math.max(viewRingRadius + dotSpacing,
                (int) Math.round(screenWidth * (112.0 / 3440.0)));
        for (int offset = viewRingRadius + dotSpacing; offset <= dottedHalf; offset += dotSpacing) {
            int halfDot = dotSize / 2;
            graphics.fill(x - offset - halfDot, y - halfDot,
                    x - offset - halfDot + dotSize, y - halfDot + dotSize, color);
            graphics.fill(x + offset - halfDot, y - halfDot,
                    x + offset - halfDot + dotSize, y - halfDot + dotSize, color);
        }

        int innerChevron = Math.max(dottedHalf + 4,
                (int) Math.round(screenWidth * (122.0 / 3440.0)));
        int outerChevron = Math.max(innerChevron + 6,
                (int) Math.round(screenWidth * (189.0 / 3440.0)));
        int chevronHalfHeight = Math.max(1, (int) Math.round(screenHeight * (4.0 / 1440.0)));
        drawScopeChevron(graphics, x - innerChevron, y, -1, chevronHalfHeight);
        drawScopeChevron(graphics, x - outerChevron, y, -1, chevronHalfHeight);
        drawScopeChevron(graphics, x + innerChevron, y, 1, chevronHalfHeight);
        drawScopeChevron(graphics, x + outerChevron, y, 1, chevronHalfHeight);

        int ladderX = x - Math.max(18, (int) Math.round(screenWidth * (102.0 / 3440.0)));
        int firstRowY = y + Math.max(3, (int) Math.round(screenHeight * (9.0 / 1440.0)));
        int rowSpacing = Math.max(5, (int) Math.round(screenHeight * (10.0 / 1440.0)));
        int[] labels = {8, 16, 24, 32, 40, 48, 56};
        int zeroX = x + Math.max(20, (int) Math.round(screenWidth * (124.0 / 3440.0)));
        int zeroY = y + Math.max(16, (int) Math.round(screenHeight * (115.0 / 1440.0)));
        drawThinRangeScale(
                graphics,
                ladderX,
                firstRowY,
                rowSpacing,
                labels,
                zeroX,
                zeroY
        );
    }

    /**
     * White 58 px view/mouse ring from the 3440x1440 reference. Drawing in
     * reference pixels and scaling by GUI height also cancels Minecraft GUI
     * scale, so its physical screen proportion stays constant at every
     * resolution and aspect ratio.
     */
    static void drawViewReticle(GuiGraphics graphics, int x, int y) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        float scale = Math.max(0.1F, graphics.guiHeight() / HEAD_AIM_REFERENCE_HEIGHT);
        graphics.pose().scale(scale, scale, 1.0F);
        drawCircle(graphics, 0, 0, 27, RETICLE_SHADOW_OUTER);
        drawCircle(graphics, 0, 0, 28, RETICLE_SHADOW_INNER);
        drawCircle(graphics, 0, 0, 29, VIEW_RETICLE);
        graphics.pose().popPose();
    }

    /** Yellow 26 px ring marking the barrel's actual aim point. */
    static void drawCannonReticle(GuiGraphics graphics, int x, int y) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        float scale = Math.max(0.1F, graphics.guiHeight() / HEAD_AIM_REFERENCE_HEIGHT);
        graphics.pose().scale(scale, scale, 1.0F);
        drawCircle(graphics, 0, 0, 11, RETICLE_SHADOW_OUTER);
        drawCircle(graphics, 0, 0, 12, RETICLE_SHADOW_INNER);
        drawCircle(graphics, 0, 0, 13, CANNON_RETICLE);
        graphics.pose().popPose();
    }

    private static void drawScopeCorner(
            GuiGraphics graphics,
            int x,
            int y,
            int arm,
            int horizontalDirection,
            int verticalDirection
    ) {
        int color = ScopeHudRenderer.sightLineColor(SCOPE_RETICLE_BLACK);
        int horizontalEnd = x + horizontalDirection * arm;
        int verticalEnd = y + verticalDirection * arm;
        graphics.fill(Math.min(x, horizontalEnd), y, Math.max(x, horizontalEnd) + 1, y + 1, color);
        graphics.fill(x, Math.min(y, verticalEnd), x + 1, Math.max(y, verticalEnd) + 1, color);
    }

    private static void drawScopeChevron(
            GuiGraphics graphics,
            int tipX,
            int centreY,
            int direction,
            int halfHeight
    ) {
        int color = ScopeHudRenderer.sightLineColor(SCOPE_RETICLE_BLACK);
        for (int dy = -halfHeight; dy <= halfHeight; dy++) {
            int px = tipX - direction * Math.abs(dy);
            plot(graphics, px, centreY + dy, color);
        }
    }

    /**
     * Draws the range scale in a 2x coordinate space and then scales it down.
     * Its strokes therefore occupy half a GUI pixel (one framebuffer pixel at
     * the usual GUI scale 2) without shrinking the complete ladder layout.
     */
    private static void drawThinRangeScale(
            GuiGraphics graphics,
            int ladderX,
            int firstRowY,
            int rowSpacing,
            int[] labels,
            int zeroX,
            int zeroY
    ) {
        int color = ScopeHudRenderer.sightLineColor(SCOPE_RETICLE_BLACK);
        graphics.pose().pushPose();
        graphics.pose().scale(0.5F, 0.5F, 1.0F);
        for (int row = 0; row < labels.length; row++) {
            int rowY = firstRowY + row * rowSpacing;
            int tickLength = row % 2 == 1 ? 8 : 6;
            drawThinNumberRightAligned(
                    graphics,
                    labels[row],
                    (ladderX - 3) * 2,
                    (rowY - 2) * 2
            );
            graphics.fill(
                    ladderX * 2,
                    rowY * 2,
                    (ladderX + tickLength) * 2,
                    rowY * 2 + 1,
                    color
            );
        }
        drawThinSevenSegmentZero(graphics, zeroX * 2, zeroY * 2);
        graphics.pose().popPose();
    }

    private static void drawThinNumberRightAligned(
            GuiGraphics graphics,
            int value,
            int right,
            int top
    ) {
        String text = Integer.toString(value);
        int glyphWidth = 5;
        int spacing = 3;
        int totalWidth = text.length() * glyphWidth + (text.length() - 1) * spacing;
        int x = right - totalWidth;
        for (int i = 0; i < text.length(); i++) {
            drawThinDigit(graphics, text.charAt(i) - '0', x + i * (glyphWidth + spacing), top);
        }
    }

    private static void drawThinDigit(GuiGraphics graphics, int digit, int x, int y) {
        int color = ScopeHudRenderer.sightLineColor(SCOPE_RETICLE_BLACK);
        // Seven connected strokes instead of a separated dot matrix. The
        // surrounding 0.5 scale keeps every stroke thin while the joins make
        // the tiny range numerals readable at normal GUI scale.
        int segments = switch (digit) {
            case 0 -> 0b0111111;
            case 1 -> 0b0000110;
            case 2 -> 0b1011011;
            case 3 -> 0b1001111;
            case 4 -> 0b1100110;
            case 5 -> 0b1101101;
            case 6 -> 0b1111101;
            case 7 -> 0b0000111;
            case 8 -> 0b1111111;
            case 9 -> 0b1101111;
            default -> 0;
        };
        if ((segments & 0b0000001) != 0) {
            graphics.fill(x, y, x + 5, y + 1, color);
        }
        if ((segments & 0b0000010) != 0) {
            graphics.fill(x + 4, y, x + 5, y + 5, color);
        }
        if ((segments & 0b0000100) != 0) {
            graphics.fill(x + 4, y + 4, x + 5, y + 9, color);
        }
        if ((segments & 0b0001000) != 0) {
            graphics.fill(x, y + 8, x + 5, y + 9, color);
        }
        if ((segments & 0b0010000) != 0) {
            graphics.fill(x, y + 4, x + 1, y + 9, color);
        }
        if ((segments & 0b0100000) != 0) {
            graphics.fill(x, y, x + 1, y + 5, color);
        }
        if ((segments & 0b1000000) != 0) {
            graphics.fill(x, y + 4, x + 5, y + 5, color);
        }
    }

    private static void drawThinSevenSegmentZero(GuiGraphics graphics, int centreX, int centreY) {
        int color = ScopeHudRenderer.sightLineColor(SCOPE_RETICLE_BLACK);
        int halfWidth = 6;
        int halfHeight = 12;
        graphics.fill(centreX - halfWidth + 1, centreY - halfHeight,
                centreX + halfWidth - 1, centreY - halfHeight + 1, color);
        graphics.fill(centreX - halfWidth + 1, centreY + halfHeight - 1,
                centreX + halfWidth - 1, centreY + halfHeight, color);
        graphics.fill(centreX - halfWidth, centreY - halfHeight + 1,
                centreX - halfWidth + 1, centreY + halfHeight - 1, color);
        graphics.fill(centreX + halfWidth - 1, centreY - halfHeight + 1,
                centreX + halfWidth, centreY + halfHeight - 1, color);
    }

    private static void drawCircle(GuiGraphics graphics, int centreX, int centreY, int radius, int color) {
        int x = radius;
        int y = 0;
        int error = 1 - radius;
        while (x >= y) {
            plotCircleOctants(graphics, centreX, centreY, x, y, color);
            y++;
            if (error < 0) {
                error += 2 * y + 1;
            } else {
                x--;
                error += 2 * (y - x) + 1;
            }
        }
    }

    private static void plotCircleOctants(
            GuiGraphics graphics,
            int centreX,
            int centreY,
            int x,
            int y,
            int color
    ) {
        plot(graphics, centreX + x, centreY + y, color);
        plot(graphics, centreX + y, centreY + x, color);
        plot(graphics, centreX - y, centreY + x, color);
        plot(graphics, centreX - x, centreY + y, color);
        plot(graphics, centreX - x, centreY - y, color);
        plot(graphics, centreX - y, centreY - x, color);
        plot(graphics, centreX + y, centreY - x, color);
        plot(graphics, centreX + x, centreY - y, color);
    }

    private static void plot(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x, y, x + 1, y + 1, color);
    }

    private static int multiplyAlpha(int color, float multiplier) {
        int alpha = Mth.clamp(Math.round(((color >>> 24) & 0xFF) * multiplier), 0, 255);
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Search/acquisition remains the War-Thunder-style separated corner box. */
    private static void drawCornerFrame(
            GuiGraphics graphics,
            int minX,
            int minY,
            int maxX,
            int maxY
    ) {
        int arm = Math.max(5, Math.min(12, Math.min(maxX - minX, maxY - minY) / 3));
        graphics.fill(minX, minY, minX + 1, minY + arm, OPTICAL_LOCK);
        graphics.fill(minX, minY, minX + arm, minY + 1, OPTICAL_LOCK);
        graphics.fill(maxX - 1, minY, maxX, minY + arm, OPTICAL_LOCK);
        graphics.fill(maxX - arm, minY, maxX, minY + 1, OPTICAL_LOCK);
        graphics.fill(minX, maxY - arm, minX + 1, maxY, OPTICAL_LOCK);
        graphics.fill(minX, maxY - 1, minX + arm, maxY, OPTICAL_LOCK);
        graphics.fill(maxX - 1, maxY - arm, maxX, maxY, OPTICAL_LOCK);
        graphics.fill(maxX - arm, maxY - 1, maxX, maxY, OPTICAL_LOCK);
    }

    private record ScreenPoint(int x, int y) {
    }

    private ThirdPersonAimHudRenderer() {
    }
}
