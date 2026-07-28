package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
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
    private static final int VIEW_RETICLE = 0xEEE0A83D;
    private static final int CANNON_RETICLE = 0xEE8FE8F3;
    private static final int SCOPE_RETICLE_BLACK = 0xEE000000;
    private static final int SCOPE_RETICLE_RING = 0xEED8D8D8;
    private static final int OPTICAL_LOCK = 0xEE5DFF78;

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientAimState.isActive()
                || ClientAimState.isOpticalView()
                || minecraft.options.hideGui
                || minecraft.screen != null) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        float partialTick = event.getPartialTick().getGameTimeDeltaTicks();
        if (minecraft.options.getCameraType() == CameraType.FIRST_PERSON) {
            // First person keeps optical-lock feedback only. The large
            // War-Thunder sight belongs exclusively to the gun sight.
            renderOpticalMarkers(
                    graphics, minecraft, width, height, partialTick,
                    width / 2, height / 2
            );
        } else {
            renderAimMarkers(graphics, minecraft, width, height, partialTick, false);
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

        ScreenPoint cannon = null;
        if (ClientAimState.hasCannonAimPoint()) {
            cannon = projectToScreen(
                    minecraft,
                    ClientAimState.getCannonAimPoint(partialTick),
                    width,
                    height,
                    partialTick
            );
        }
        boolean cannonVisible = cannon != null
                && cannon.x >= 4 && cannon.x < width - 4
                && cannon.y >= 4 && cannon.y < height - 4;

        if (scoped) {
            // The cannon scale is underneath the view ring in the reference.
            if (cannonVisible) {
                drawScopeCannonReticle(graphics, cannon.x, cannon.y, width, height);
            }
            drawScopePlayerReticle(graphics, viewX, viewY, width, height);
        } else {
            // Aircraft installations use radar symbology and do not show the tank head-aim ring.
            if (!ClientAimState.hasAircraftEquipment()) {
                drawViewReticle(graphics, viewX, viewY);
            }
            if (cannonVisible) {
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
        final int green = 0xFF58FF72;
        if (ClientAimState.isAirToGroundLockSearching()) {
            int cx = width / 2;
            int cy = height / 2;
            drawCornerFrame(graphics, cx - 42, cy - 32, cx + 42, cy + 32);
        }
        if (!ClientAimState.isAirToGroundLockActive()) return;
        AABB bounds = ClientAimState.getAirToGroundLockBounds(partialTick);
        if (bounds == null) return;
        ScreenPoint center = projectToScreen(
                minecraft, bounds.getCenter(), width, height, partialTick);
        ScreenSize size = projectBoundsSize(minecraft, bounds, height, partialTick);
        if (center == null || size == null) return;
        int half = Math.max(5, Math.max(size.width, size.height) / 2 + 2);
        drawConnectedSquare(graphics, center.x, center.y, half, green);
        // The centre cross makes the locked air-to-ground marker the requested
        // connected "田" symbol without changing the acquisition frame.
        int arm = Math.max(2, half / 2);
        graphics.fill(center.x - 1, center.y - arm, center.x + 1, center.y + arm + 1, green);
        graphics.fill(center.x - arm, center.y - 1, center.x + arm + 1, center.y + 1, green);
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
            // Represent the acquisition area as a nominal tank-sized volume
            // at the cannon convergence point. Its angular size is calculated
            // with the unmagnified projection, so mouse-wheel zoom cannot
            // enlarge the green frame.
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
            AABB searchBounds = null;
            if (ClientAimState.hasCannonAimPoint()) {
                Vec3 centre = ClientAimState.getCannonAimPoint(partialTick);
                searchBounds = new AABB(
                        centre.x - 1.2, centre.y - 1.0, centre.z - 1.2,
                        centre.x + 1.2, centre.y + 1.0, centre.z + 1.2
                );
            }
            ScreenSize size = searchBounds == null
                    ? null
                    : projectBoundsSize(minecraft, searchBounds, height, partialTick);
            int halfWidth = size == null ? 38 : Math.max(9, size.width / 2 + 2);
            int halfHeight = size == null ? 28 : Math.max(9, size.height / 2 + 2);
            drawCornerFrame(
                    graphics,
                    centreX - halfWidth,
                    centreY - halfHeight,
                    centreX + halfWidth,
                    centreY + halfHeight
            );
            return;
        }
        if (!ClientAimState.isOpticalLockActive()) {
            return;
        }
        ScreenPoint target = projectToScreen(
                minecraft,
                ClientAimState.getOpticalLockDisplayPoint(partialTick),
                width,
                height,
                partialTick
        );
        if (target != null) {
            ScreenSize size = projectBoundsSize(
                    minecraft,
                    ClientAimState.getOpticalLockBounds(partialTick),
                    height,
                    partialTick
            );
            // One unbroken, one-pixel square. Its side is the larger projected
            // target dimension, so the complete target fits without turning
            // the marker into a zoom-dependent rectangle or thick bracket.
            int halfSide = size == null
                    ? 14
                    : Math.max(7, (Math.max(size.width, size.height) + 1) / 2 + 2);
            int lockColor = multiplyAlpha(OPTICAL_LOCK, ClientAimState.getOpticalLockFrameAlpha());
            drawConnectedSquare(graphics, target.x, target.y, halfSide, lockColor);
        }
    }

    /**
     * Returns the target's on-screen size using the normal, unzoomed FOV.
     * Position is still projected by {@link #projectToScreen} with the current
     * optic FOV, while size remains tied to the target's physical bounds.
     */
    private static ScreenSize projectBoundsSize(
            Minecraft minecraft,
            AABB bounds,
            int height,
            float partialTick
    ) {
        if (bounds == null) {
            return null;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vector3f forwardVector = camera.getLookVector();
        Vector3f leftVector = camera.getLeftVector();
        Vector3f upVector = camera.getUpVector();
        Vec3 forward = new Vec3(forwardVector.x, forwardVector.y, forwardVector.z);
        Vec3 left = new Vec3(leftVector.x, leftVector.y, leftVector.z);
        Vec3 up = new Vec3(upVector.x, upVector.y, upVector.z);
        double fov = ((GameRendererAccessor) (Object) minecraft.gameRenderer)
                .stabilizedTurret$getFov(camera, partialTick, true);
        if (ClientAimState.isScoped()) {
            fov = Math.toDegrees(2.0 * Math.atan(
                    Math.tan(Math.toRadians(fov) * 0.5) * ClientAimState.getScopeZoom()
            ));
        }
        double focal = height * 0.5 / Math.tan(Mth.DEG_TO_RAD * fov * 0.5);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        int visibleCorners = 0;
        for (int ix = 0; ix < 2; ix++) {
            for (int iy = 0; iy < 2; iy++) {
                for (int iz = 0; iz < 2; iz++) {
                    Vec3 corner = new Vec3(
                            ix == 0 ? bounds.minX : bounds.maxX,
                            iy == 0 ? bounds.minY : bounds.maxY,
                            iz == 0 ? bounds.minZ : bounds.maxZ
                    );
                    Vec3 relative = corner.subtract(camera.getPosition());
                    double depth = relative.dot(forward);
                    if (depth <= 0.05) {
                        continue;
                    }
                    double x = -relative.dot(left) * focal / depth;
                    double y = -relative.dot(up) * focal / depth;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    visibleCorners++;
                }
            }
        }
        if (visibleCorners == 0) {
            return null;
        }
        int width = Mth.clamp((int) Math.ceil(maxX - minX), 1, 4096);
        int projectedHeight = Mth.clamp((int) Math.ceil(maxY - minY), 1, 4096);
        return new ScreenSize(width, projectedHeight);
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
        int x = (int) Math.round(width * 0.5 - relative.dot(left) * focal / depth);
        int y = (int) Math.round(height * 0.5 - relative.dot(up) * focal / depth);
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
        int crossHalf = Math.max(5, (int) Math.round(screenHeight * (26.0 / 1440.0)));
        graphics.fill(x - crossHalf, y, x + crossHalf + 1, y + 1, SCOPE_RETICLE_BLACK);
        graphics.fill(x, y - crossHalf, x + 1, y + crossHalf + 1, SCOPE_RETICLE_BLACK);

        int viewRingRadius = Math.max(4, (int) Math.round(screenHeight * (30.0 / 1440.0)));
        int dotSpacing = Math.max(3, (int) Math.round(screenHeight * (12.0 / 1440.0)));
        int dotSize = Math.max(1, (int) Math.round(screenHeight * (3.0 / 1440.0)));
        int dottedHalf = Math.max(viewRingRadius + dotSpacing,
                (int) Math.round(screenWidth * (112.0 / 3440.0)));
        for (int offset = viewRingRadius + dotSpacing; offset <= dottedHalf; offset += dotSpacing) {
            int halfDot = dotSize / 2;
            graphics.fill(x - offset - halfDot, y - halfDot,
                    x - offset - halfDot + dotSize, y - halfDot + dotSize, SCOPE_RETICLE_BLACK);
            graphics.fill(x + offset - halfDot, y - halfDot,
                    x + offset - halfDot + dotSize, y - halfDot + dotSize, SCOPE_RETICLE_BLACK);
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

    /** Exact third-person view marker used before the gun-sight redesign. */
    static void drawViewReticle(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y - 7, x + 1, y - 5, VIEW_RETICLE);
        graphics.fill(x - 4, y - 5, x + 5, y - 4, VIEW_RETICLE);
        graphics.fill(x - 6, y - 3, x - 5, y + 4, VIEW_RETICLE);
        graphics.fill(x + 6, y - 3, x + 7, y + 4, VIEW_RETICLE);
        graphics.fill(x - 4, y + 5, x + 5, y + 6, VIEW_RETICLE);
        graphics.fill(x, y + 6, x + 1, y + 8, VIEW_RETICLE);
    }

    /** Exact third-person cannon marker used before the gun-sight redesign. */
    static void drawCannonReticle(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 5, y, x - 2, y + 1, CANNON_RETICLE);
        graphics.fill(x + 3, y, x + 6, y + 1, CANNON_RETICLE);
        graphics.fill(x, y - 5, x + 1, y - 2, CANNON_RETICLE);
        graphics.fill(x, y + 3, x + 1, y + 6, CANNON_RETICLE);
        graphics.fill(x, y, x + 1, y + 1, CANNON_RETICLE);
    }

    private static void drawScopeCorner(
            GuiGraphics graphics,
            int x,
            int y,
            int arm,
            int horizontalDirection,
            int verticalDirection
    ) {
        int horizontalEnd = x + horizontalDirection * arm;
        int verticalEnd = y + verticalDirection * arm;
        graphics.fill(Math.min(x, horizontalEnd), y, Math.max(x, horizontalEnd) + 1, y + 1, SCOPE_RETICLE_BLACK);
        graphics.fill(x, Math.min(y, verticalEnd), x + 1, Math.max(y, verticalEnd) + 1, SCOPE_RETICLE_BLACK);
    }

    private static void drawScopeChevron(
            GuiGraphics graphics,
            int tipX,
            int centreY,
            int direction,
            int halfHeight
    ) {
        for (int dy = -halfHeight; dy <= halfHeight; dy++) {
            int px = tipX - direction * Math.abs(dy);
            plot(graphics, px, centreY + dy, SCOPE_RETICLE_BLACK);
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
                    SCOPE_RETICLE_BLACK
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
            graphics.fill(x, y, x + 5, y + 1, SCOPE_RETICLE_BLACK);
        }
        if ((segments & 0b0000010) != 0) {
            graphics.fill(x + 4, y, x + 5, y + 5, SCOPE_RETICLE_BLACK);
        }
        if ((segments & 0b0000100) != 0) {
            graphics.fill(x + 4, y + 4, x + 5, y + 9, SCOPE_RETICLE_BLACK);
        }
        if ((segments & 0b0001000) != 0) {
            graphics.fill(x, y + 8, x + 5, y + 9, SCOPE_RETICLE_BLACK);
        }
        if ((segments & 0b0010000) != 0) {
            graphics.fill(x, y + 4, x + 1, y + 9, SCOPE_RETICLE_BLACK);
        }
        if ((segments & 0b0100000) != 0) {
            graphics.fill(x, y, x + 1, y + 5, SCOPE_RETICLE_BLACK);
        }
        if ((segments & 0b1000000) != 0) {
            graphics.fill(x, y + 4, x + 5, y + 5, SCOPE_RETICLE_BLACK);
        }
    }

    private static void drawThinSevenSegmentZero(GuiGraphics graphics, int centreX, int centreY) {
        int halfWidth = 6;
        int halfHeight = 12;
        graphics.fill(centreX - halfWidth + 1, centreY - halfHeight,
                centreX + halfWidth - 1, centreY - halfHeight + 1, SCOPE_RETICLE_BLACK);
        graphics.fill(centreX - halfWidth + 1, centreY + halfHeight - 1,
                centreX + halfWidth - 1, centreY + halfHeight, SCOPE_RETICLE_BLACK);
        graphics.fill(centreX - halfWidth, centreY - halfHeight + 1,
                centreX - halfWidth + 1, centreY + halfHeight - 1, SCOPE_RETICLE_BLACK);
        graphics.fill(centreX + halfWidth - 1, centreY - halfHeight + 1,
                centreX + halfWidth, centreY + halfHeight - 1, SCOPE_RETICLE_BLACK);
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

    private static void drawConnectedSquare(
            GuiGraphics graphics,
            int centreX,
            int centreY,
            int halfSide,
            int color
    ) {
        int minX = centreX - halfSide;
        int minY = centreY - halfSide;
        int maxX = centreX + halfSide;
        int maxY = centreY + halfSide;
        graphics.fill(minX, minY, maxX + 1, minY + 1, color);
        graphics.fill(minX, maxY, maxX + 1, maxY + 1, color);
        graphics.fill(minX, minY + 1, minX + 1, maxY, color);
        graphics.fill(maxX, minY + 1, maxX + 1, maxY, color);
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

    private record ScreenSize(int width, int height) {
    }

    private ThirdPersonAimHudRenderer() {
    }
}
