package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlock;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayLayout;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ryanhcode.sable.Sable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.Locale;

/** Interactive radar and automatic-weapon canvas rendered across a display wall. */
public final class ConnectedDisplayRenderer
        implements BlockEntityRenderer<ConnectedDisplayBlockEntity> {
    private static final float MAP_Z = -0.010F;
    private static final float BASE_Z = -0.018F;
    private static final float CONTROL_Z = -0.026F;
    private static final float FOREGROUND_Z = -0.034F;
    private static final float TEXT_Z = -0.042F;
    private static final float TEXT_SCALE = 1.5F;
    private final Font font;
    private final GeometryBatch geometry = new GeometryBatch();

    public ConnectedDisplayRenderer(BlockEntityRendererProvider.Context context) {
        font = context.getFont();
    }

    @Override
    public void render(
            ConnectedDisplayBlockEntity blockEntity,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            int overlay
    ) {
        BlockState state = blockEntity.getBlockState();
        if (!state.hasProperty(ConnectedDisplayBlock.FACING)) {
            return;
        }
        Direction facing = state.getValue(ConnectedDisplayBlock.FACING);
        BlockPos canonical = ConnectedDisplayLayout.canonicalTopLeft(
                blockEntity.getLevel(), blockEntity.getBlockPos(), facing);
        if (canonical == null) return;
        Direction right = screenLeft(facing).getOpposite();
        int wallWidth = run(
                canonical, right, facing, blockEntity);
        int wallHeight = run(
                canonical, Direction.DOWN, facing, blockEntity);
        BlockPos renderOwner = nearestRenderOwner(
                blockEntity,
                canonical,
                right,
                wallWidth,
                wallHeight,
                Minecraft.getInstance().gameRenderer
                        .getMainCamera().getPosition()
        );
        if (!blockEntity.getBlockPos().equals(renderOwner)) return;
        ConnectedDisplayBlockEntity data = findData(
                blockEntity, canonical, right, wallWidth, wallHeight);
        if (data == null) data = blockEntity;

        pose.pushPose();
        pose.translate(
                canonical.getX() - blockEntity.getBlockPos().getX(),
                canonical.getY() - blockEntity.getBlockPos().getY(),
                canonical.getZ() - blockEntity.getBlockPos().getZ()
        );
        orient(pose, facing);
        double drawableWidth = Math.max(
                0.01, wallWidth - ConnectedDisplayLayout.SCREEN_INSET * 2.0);
        double drawableHeight = Math.max(
                0.01, wallHeight - ConnectedDisplayLayout.SCREEN_INSET * 2.0);
        double scaleX = drawableWidth / ConnectedDisplayLayout.CANVAS_W;
        double scaleY = drawableHeight / ConnectedDisplayLayout.CANVAS_H;
        pose.translate(
                ConnectedDisplayLayout.SCREEN_INSET + drawableWidth,
                -ConnectedDisplayLayout.SCREEN_INSET,
                0.0
        );
        pose.scale((float) -scaleX, (float) -scaleY, 1.0F);
        Matrix4f matrix = pose.last().pose();

        geometry.begin(matrix);
        geometry.fillAt(
                0, 0,
                ConnectedDisplayLayout.CANVAS_W,
                ConnectedDisplayLayout.CANVAS_H,
                0xFF10171E,
                0.0F
        );
        geometry.flush();

        int mapTexture = XaeroDisplayMapCompat.liveTextureId(
                data.getRadarWorldOrigin(), data.getRadarRange());
        if (mapTexture > 0) {
            textured(
                    matrix,
                    0,
                    ConnectedDisplayLayout.HEADER_H,
                    ConnectedDisplayLayout.RADAR_W,
                    ConnectedDisplayLayout.CANVAS_H,
                    mapTexture,
                    MAP_Z
            );
        }

        geometry.begin(matrix);
        geometry.fillBase(
                0,
                ConnectedDisplayLayout.HEADER_H,
                ConnectedDisplayLayout.RADAR_W,
                ConnectedDisplayLayout.CANVAS_H,
                0x4D08120D
        );
        geometry.fillBase(
                0, 0,
                ConnectedDisplayLayout.RADAR_W,
                ConnectedDisplayLayout.HEADER_H,
                0xFF29313A
        );
        geometry.fillBase(
                ConnectedDisplayLayout.PANEL_LEFT,
                0,
                ConnectedDisplayLayout.CANVAS_W,
                ConnectedDisplayLayout.CANVAS_H,
                0xFF121A22
        );
        geometry.fillBase(
                ConnectedDisplayLayout.PANEL_LEFT,
                0,
                ConnectedDisplayLayout.CANVAS_W,
                ConnectedDisplayLayout.HEADER_H,
                0xFF29313A
        );

        ConnectedDisplayLayout.RadarGeometry radar =
                ConnectedDisplayLayout.radarGeometry(wallWidth, wallHeight);
        for (int ring = 1; ring <= 4; ring++) {
            geometry.ellipse(
                    ConnectedDisplayLayout.RADAR_CX,
                    ConnectedDisplayLayout.RADAR_CY,
                    (float) (radar.radiusX() * ring / 4.0),
                    (float) (radar.radiusY() * ring / 4.0),
                    0xFF00E33F,
                    3.0F
            );
        }
        geometry.line(
                ConnectedDisplayLayout.RADAR_CX,
                (float) (ConnectedDisplayLayout.RADAR_CY - radar.radiusY()),
                ConnectedDisplayLayout.RADAR_CX,
                (float) (ConnectedDisplayLayout.RADAR_CY + radar.radiusY()),
                0xD800E33F,
                2.0F
        );
        geometry.line(
                (float) (ConnectedDisplayLayout.RADAR_CX - radar.radiusX()),
                ConnectedDisplayLayout.RADAR_CY,
                (float) (ConnectedDisplayLayout.RADAR_CX + radar.radiusX()),
                ConnectedDisplayLayout.RADAR_CY,
                0xD800E33F,
                2.0F
        );

        Vec3 radarOrigin = data.getRadarWorldOrigin();
        for (ConnectedDisplayBlockEntity.Contact contact : data.getContacts()) {
            ConnectedDisplayLayout.RadarPoint point =
                    ConnectedDisplayLayout.radarPoint(
                            contact.center(),
                            radarOrigin,
                            data.getRadarRange(),
                            radar
                    );
            float px = (float) point.x();
            float py = (float) point.y();
            if (contact.key().living()) {
                geometry.circle(px, py, 8.0F, 0xFF00F444, 3.0F);
            } else {
                geometry.line(px, py - 10, px - 8, py + 8,
                        0xFF00F444, 3.0F);
                geometry.line(px, py - 10, px + 8, py + 8,
                        0xFF00F444, 3.0F);
                geometry.line(px - 8, py + 8, px + 8, py + 8,
                        0xFF00F444, 3.0F);
            }
            if (data.isSelected(contact)) {
                geometry.line(
                        px - 17, py - 10,
                        px - 17, py + 10,
                        0xFF5AFF6E, 4.0F);
                geometry.line(
                        px + 17, py - 10,
                        px + 17, py + 10,
                        0xFF5AFF6E, 4.0F);
            }
        }

        drawPanelGeometry(data);
        geometry.flush();

        drawText(
                Component.literal("平面位置指示器"),
                12, 12, 0xFFD9DFE1, matrix, buffers);
        drawText(
                Component.literal("自动武器控制"),
                ConnectedDisplayLayout.PANEL_LEFT + 18,
                12, 0xFFD9DFE1, matrix, buffers);
        drawText(
                Component.literal("0°"),
                ConnectedDisplayLayout.RADAR_CX - 10,
                (float) (ConnectedDisplayLayout.RADAR_CY
                        - radar.radiusY() + 10),
                0xFF00F444, matrix, buffers);
        drawText(
                Component.literal("90°"),
                (float) (ConnectedDisplayLayout.RADAR_CX
                        + radar.radiusX() - 45),
                ConnectedDisplayLayout.RADAR_CY - 12,
                0xFF00F444, matrix, buffers);
        drawText(
                Component.literal("180°"),
                ConnectedDisplayLayout.RADAR_CX - 22,
                (float) (ConnectedDisplayLayout.RADAR_CY
                        + radar.radiusY() - 22),
                0xFF00F444, matrix, buffers);
        drawText(
                Component.literal("270°"),
                (float) (ConnectedDisplayLayout.RADAR_CX
                        - radar.radiusX() + 8),
                ConnectedDisplayLayout.RADAR_CY - 12,
                0xFF00F444, matrix, buffers);
        drawText(
                Component.literal(formatRange(data.getRadarRange())),
                800, 950, 0xFF00F444, matrix, buffers);
        drawPanelText(data, matrix, buffers);
        pose.popPose();
    }

    private void drawPanelGeometry(ConnectedDisplayBlockEntity data) {
        geometry.fillControl(
                1035, 78, 1662, 228,
                0xFF1D2933);
        geometry.fillControl(
                ConnectedDisplayLayout.SLIDER_X1,
                177,
                ConnectedDisplayLayout.SLIDER_X2,
                187,
                0xFF49555D);
        double knob = ConnectedDisplayLayout.sliderX(data.getRadarRange());
        geometry.fillControl(
                ConnectedDisplayLayout.SLIDER_X1,
                177,
                (float) knob,
                187,
                0xFF00C940);
        geometry.fillControl(
                (float) knob - 7,
                164,
                (float) knob + 7,
                200,
                0xFF7BFF96);

        geometry.fillControl(
                ConnectedDisplayLayout.AUTO_X1,
                ConnectedDisplayLayout.AUTO_Y1,
                ConnectedDisplayLayout.AUTO_X2,
                ConnectedDisplayLayout.AUTO_Y2,
                data.isAutomaticAttack() ? 0xFF176B32 : 0xFF39454E
        );
        geometry.outline(
                ConnectedDisplayLayout.AUTO_X1,
                ConnectedDisplayLayout.AUTO_Y1,
                ConnectedDisplayLayout.AUTO_X2,
                ConnectedDisplayLayout.AUTO_Y2,
                data.isAutomaticAttack() ? 0xFF66FF88 : 0xFF71808A,
                3.0F
        );
        geometry.fillControl(
                ConnectedDisplayLayout.FIRE_X1,
                ConnectedDisplayLayout.FIRE_Y1,
                ConnectedDisplayLayout.FIRE_X2,
                ConnectedDisplayLayout.FIRE_Y2,
                0xFF774E16
        );
        geometry.outline(
                ConnectedDisplayLayout.FIRE_X1,
                ConnectedDisplayLayout.FIRE_Y1,
                ConnectedDisplayLayout.FIRE_X2,
                ConnectedDisplayLayout.FIRE_Y2,
                0xFFFFB44F,
                3.0F
        );
        geometry.fillControl(
                ConnectedDisplayLayout.CLEAR_X1,
                ConnectedDisplayLayout.CLEAR_Y1,
                ConnectedDisplayLayout.CLEAR_X2,
                ConnectedDisplayLayout.CLEAR_Y2,
                0xFF39454E
        );
        geometry.outline(
                ConnectedDisplayLayout.CLEAR_X1,
                ConnectedDisplayLayout.CLEAR_Y1,
                ConnectedDisplayLayout.CLEAR_X2,
                ConnectedDisplayLayout.CLEAR_Y2,
                0xFF71808A,
                3.0F
        );
        geometry.fillControl(1035, 510, 1662, 735, 0xFF1D2933);
        geometry.fillControl(
                ConnectedDisplayLayout.MISSILE_X1,
                ConnectedDisplayLayout.MISSILE_Y1,
                ConnectedDisplayLayout.MISSILE_X2,
                ConnectedDisplayLayout.MISSILE_Y2,
                data.hasBoundMissileLauncher()
                        ? 0xFF176B32 : 0xFF343B40
        );
        geometry.outline(
                ConnectedDisplayLayout.MISSILE_X1,
                ConnectedDisplayLayout.MISSILE_Y1,
                ConnectedDisplayLayout.MISSILE_X2,
                ConnectedDisplayLayout.MISSILE_Y2,
                data.hasBoundMissileLauncher()
                        ? 0xFF66FF88 : 0xFF5A6268,
                3.0F
        );
    }

    private void drawPanelText(
            ConnectedDisplayBlockEntity data,
            Matrix4f matrix,
            MultiBufferSource buffers
    ) {
        drawText(
                Component.literal("雷达探测距离"),
                1065, 98, 0xFFD5DCDF, matrix, buffers);
        drawText(
                Component.literal(formatRange(data.getRadarRange())
                        + "  （100 m—10 km）"),
                1065, 126, 0xFF7BFF96, matrix, buffers);
        drawText(
                Component.literal(data.isAutomaticAttack()
                        ? "自动锁定并开火：开启"
                        : "自动锁定并开火：关闭"),
                1110, 290,
                data.isAutomaticAttack() ? 0xFF8AFF9E : 0xFFD5DCDF,
                matrix, buffers);
        drawText(
                Component.literal("手动开火"),
                1145, 414, 0xFFFFD69D, matrix, buffers);
        drawText(
                Component.literal("取消目标"),
                1442, 414, 0xFFD5DCDF, matrix, buffers);
        ConnectedDisplayBlockEntity.Contact selected = null;
        for (ConnectedDisplayBlockEntity.Contact contact : data.getContacts()) {
            if (data.isSelected(contact)) {
                selected = contact;
                break;
            }
        }
        drawText(
                Component.literal("当前选择目标"),
                1065, 535, 0xFFD5DCDF, matrix, buffers);
        if (selected == null) {
            drawText(
                    Component.literal("未选择"),
                    1065, 575, 0xFF85929A, matrix, buffers);
            drawText(
                    Component.literal("直接右键雷达目标进行选择"),
                    1065, 615, 0xFF85929A, matrix, buffers);
        } else {
            drawText(
                    Component.literal(selected.key().living()
                            ? "类型：生物"
                            : "类型：Sable 物理结构"),
                    1065, 575, 0xFF7BFF96, matrix, buffers);
            drawText(
                    Component.literal(String.format(
                            Locale.ROOT,
                            "距离：%.0f m",
                            selected.distance())),
                    1065, 615, 0xFFD5DCDF, matrix, buffers);
            drawText(
                    Component.literal(String.format(
                            Locale.ROOT,
                            "接近率：%.1f m/s",
                            selected.closingRate())),
                    1065, 655, 0xFFD5DCDF, matrix, buffers);
        }
        drawText(
                Component.literal("发射导弹"),
                1280, 790,
                data.hasBoundMissileLauncher()
                        ? 0xFFB9FFC5 : 0xFF81878B,
                matrix, buffers);
        drawText(
                Component.literal("导弹追踪发射前最后选择的雷达目标"),
                1065, 875, 0xFF85929A, matrix, buffers);
    }

    private static String formatRange(double range) {
        return range >= 1000.0
                ? String.format(Locale.ROOT, "%.1f km", range / 1000.0)
                : String.format(Locale.ROOT, "%.0f m", range);
    }

    private void drawText(
            Component text,
            float x,
            float y,
            int color,
            Matrix4f matrix,
            MultiBufferSource buffers
    ) {
        Matrix4f textMatrix = new Matrix4f(matrix)
                .translate(0.0F, 0.0F, TEXT_Z)
                .scale(TEXT_SCALE, TEXT_SCALE, 1.0F);
        font.drawInBatch(
                text,
                x / TEXT_SCALE,
                y / TEXT_SCALE,
                color,
                false,
                textMatrix,
                buffers,
                Font.DisplayMode.NORMAL,
                0,
                15728880
        );
    }

    private static void orient(PoseStack pose, Direction facing) {
        switch (facing) {
            case NORTH -> pose.translate(0, 1, -0.002);
            case SOUTH -> {
                pose.translate(1, 1, 1.002);
                pose.mulPose(new Quaternionf().rotationY((float) Math.PI));
            }
            case EAST -> {
                pose.translate(1.002, 1, 0);
                pose.mulPose(new Quaternionf().rotationY(
                        -(float) Math.PI / 2));
            }
            case WEST -> {
                pose.translate(-0.002, 1, 1);
                pose.mulPose(new Quaternionf().rotationY(
                        (float) Math.PI / 2));
            }
            default -> {
            }
        }
    }

    private static int run(
            BlockPos start,
            Direction direction,
            Direction facing,
            ConnectedDisplayBlockEntity blockEntity
    ) {
        int count = 1;
        while (count < 32) {
            BlockState state = blockEntity.getLevel().getBlockState(
                    start.relative(direction, count));
            if (!(state.getBlock() instanceof ConnectedDisplayBlock)
                    || state.getValue(ConnectedDisplayBlock.FACING) != facing) {
                break;
            }
            count++;
        }
        return count;
    }

    private static ConnectedDisplayBlockEntity findData(
            ConnectedDisplayBlockEntity blockEntity,
            BlockPos canonical,
            Direction right,
            int width,
            int height
    ) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                var candidate = blockEntity.getLevel().getBlockEntity(
                        canonical.relative(right, x).below(y));
                if (candidate instanceof ConnectedDisplayBlockEntity display
                        && (!display.getDevices().isEmpty()
                        || display.getSelectedTarget() != null
                        || display.isAutomaticAttack())) {
                    return display;
                }
            }
        }
        return blockEntity;
    }

    private static BlockPos nearestRenderOwner(
            ConnectedDisplayBlockEntity blockEntity,
            BlockPos canonical,
            Direction right,
            int width,
            int height,
            Vec3 cameraPosition
    ) {
        BlockPos nearest = canonical;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                BlockPos candidate = canonical.relative(right, x).below(y);
                BlockState state = blockEntity.getLevel().getBlockState(candidate);
                if (!(state.getBlock() instanceof ConnectedDisplayBlock)) {
                    continue;
                }
                Vec3 center = Sable.HELPER.projectOutOfSubLevel(
                        blockEntity.getLevel(),
                        Vec3.atCenterOf(candidate)
                );
                double distance = center.distanceToSqr(cameraPosition);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = candidate;
                }
            }
        }
        return nearest;
    }

    private static Direction screenLeft(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            case WEST -> Direction.SOUTH;
            default -> Direction.WEST;
        };
    }

    private static void prepareWorldLayer() {
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(515);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    private static void textured(
            Matrix4f matrix,
            float x1,
            float y1,
            float x2,
            float y2,
            int texture,
            float z
    ) {
        prepareWorldLayer();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder builder = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX
        );
        builder.addVertex(matrix, x1, y1, z).setUv(0, 1);
        builder.addVertex(matrix, x2, y1, z).setUv(1, 1);
        builder.addVertex(matrix, x2, y2, z).setUv(1, 0);
        builder.addVertex(matrix, x1, y2, z).setUv(0, 0);
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    @Override
    public boolean shouldRenderOffScreen(
            ConnectedDisplayBlockEntity blockEntity
    ) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 64;
    }

    @Override
    public boolean shouldRender(
            ConnectedDisplayBlockEntity blockEntity,
            Vec3 cameraPosition
    ) {
        Vec3 worldCenter = Sable.HELPER.projectOutOfSubLevel(
                blockEntity.getLevel(),
                Vec3.atCenterOf(blockEntity.getBlockPos())
        );
        return worldCenter.closerThan(cameraPosition, getViewDistance());
    }

    private static final class GeometryBatch {
        private float[] positions = new float[4096 * 3];
        private int[] colors = new int[4096];
        private int vertices;
        private Matrix4f matrix;

        void begin(Matrix4f matrix) {
            this.matrix = matrix;
            vertices = 0;
        }

        void fillBase(
                float x1, float y1, float x2, float y2, int color
        ) {
            fillAt(x1, y1, x2, y2, color, BASE_Z);
        }

        void fillControl(
                float x1, float y1, float x2, float y2, int color
        ) {
            fillAt(x1, y1, x2, y2, color, CONTROL_Z);
        }

        void fillAt(
                float x1, float y1, float x2, float y2,
                int color, float z
        ) {
            quad(x1, y1, x2, y1, x2, y2, x1, y2, z, color);
        }

        void outline(
                float x1, float y1, float x2, float y2,
                int color, float thickness
        ) {
            line(x1, y1, x2, y1, color, thickness);
            line(x2, y1, x2, y2, color, thickness);
            line(x2, y2, x1, y2, color, thickness);
            line(x1, y2, x1, y1, color, thickness);
        }

        void line(
                float x1, float y1, float x2, float y2,
                int color, float thickness
        ) {
            double dx = x2 - x1;
            double dy = y2 - y1;
            double length = Math.hypot(dx, dy);
            if (length < 0.01) return;
            float ox = (float) (-dy / length * thickness / 2.0);
            float oy = (float) (dx / length * thickness / 2.0);
            quad(
                    x1 + ox, y1 + oy,
                    x2 + ox, y2 + oy,
                    x2 - ox, y2 - oy,
                    x1 - ox, y1 - oy,
                    FOREGROUND_Z, color
            );
        }

        void circle(
                float centerX, float centerY, float radius,
                int color, float thickness
        ) {
            ellipse(
                    centerX, centerY, radius, radius, color, thickness);
        }

        void ellipse(
                float centerX, float centerY,
                float radiusX, float radiusY,
                int color, float thickness
        ) {
            for (int segment = 0; segment < 96; segment++) {
                double first = segment * Math.PI * 2.0 / 96.0;
                double second = (segment + 1) * Math.PI * 2.0 / 96.0;
                line(
                        centerX + (float) Math.cos(first) * radiusX,
                        centerY + (float) Math.sin(first) * radiusY,
                        centerX + (float) Math.cos(second) * radiusX,
                        centerY + (float) Math.sin(second) * radiusY,
                        color,
                        thickness
                );
            }
        }

        void quad(
                float x1, float y1,
                float x2, float y2,
                float x3, float y3,
                float x4, float y4,
                float z,
                int color
        ) {
            ensure(vertices + 4);
            vertex(x1, y1, z, color);
            vertex(x2, y2, z, color);
            vertex(x3, y3, z, color);
            vertex(x4, y4, z, color);
        }

        void vertex(float x, float y, float z, int color) {
            int index = vertices++;
            positions[index * 3] = x;
            positions[index * 3 + 1] = y;
            positions[index * 3 + 2] = z;
            colors[index] = color;
        }

        void ensure(int needed) {
            if (needed <= colors.length) return;
            int size = Math.max(needed, colors.length * 2);
            positions = java.util.Arrays.copyOf(positions, size * 3);
            colors = java.util.Arrays.copyOf(colors, size);
        }

        void flush() {
            if (vertices == 0) return;
            prepareWorldLayer();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder builder = Tesselator.getInstance().begin(
                    VertexFormat.Mode.QUADS,
                    DefaultVertexFormat.POSITION_COLOR
            );
            for (int index = 0; index < vertices; index++) {
                builder.addVertex(
                        matrix,
                        positions[index * 3],
                        positions[index * 3 + 1],
                        positions[index * 3 + 2]
                ).setColor(colors[index]);
            }
            BufferUploader.drawWithShader(builder.buildOrThrow());
            vertices = 0;
        }
    }
}
