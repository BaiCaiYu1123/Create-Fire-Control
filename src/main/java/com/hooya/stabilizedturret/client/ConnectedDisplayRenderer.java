package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlock;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayLayout;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.Sable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.Locale;

/** Interactive radar and automatic-weapon canvas rendered across a display wall. */
public final class ConnectedDisplayRenderer
        implements BlockEntityRenderer<ConnectedDisplayBlockEntity> {
    // Small physical gaps keep the canvas on the glass. layerMatrix also
    // separates perspective depth; these gaps alone disappear in the depth
    // buffer and let Iris's deferred background batch cover the map.
    private static final float MAP_Z = -0.00005F;
    private static final float BASE_Z = -0.00010F;
    private static final float CONTROL_Z = -0.00015F;
    private static final float FOREGROUND_Z = -0.00020F;
    private static final float TEXT_Z = -0.00025F;
    private static final float TEXT_SCALE = 1.5F;

    private static Matrix4f layerMatrix(Matrix4f matrix, float z) {
        // Block-entity positions are camera-relative. Scaling them along the
        // view ray moves depth without changing projected X/Y, as Minecraft's
        // VIEW_OFFSET_Z_LAYERING does. Give each canvas layer its own step so
        // background, map, tint, markers and text survive any batch order,
        // without large physical gaps or disabling world depth testing.
        float layer = Math.max(0.0F, -z / 0.00005F);
        float scale = 1.0F - layer / 4096.0F;
        return new Matrix4f().scaling(scale).mul(matrix);
    }
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
        ConnectedDisplayBlockEntity data = ConnectedDisplayBlock.findController(
                blockEntity.getLevel(), canonical);
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

        if (data.isGodModeVideoPlaying()) {
            renderGodModeVideo(data, partialTick, matrix, buffers);
            pose.popPose();
            return;
        }
        ConnectedDisplayVideoPlayer.stop(data);

        ResourceLocation mapTexture = XaeroDisplayMapCompat.liveTextureLocation(
                data,
                data.getRadarWorldOrigin(),
                data.getRadarRange(),
                data.getMapUpdateIntervalSeconds(),
                data.getMapResolution());
        renderBackdrop(matrix, buffers, mapTexture, geometry);
        geometry.begin(matrix, buffers);

        ConnectedDisplayLayout.RadarGeometry radar =
                ConnectedDisplayLayout.radarGeometry(wallWidth, wallHeight);

        Vec3 radarOrigin = data.getRadarWorldOrigin();
        Vec3 displayWorld = Sable.HELPER.projectOutOfSubLevel(
                data.getLevel(), Vec3.atCenterOf(data.getBlockPos()));
        ConnectedDisplayLayout.RadarPoint displayPoint =
                ConnectedDisplayLayout.radarPoint(
                        displayWorld, radarOrigin, data.getRadarRange(), radar);
        float displayX = (float) displayPoint.x();
        float displayY = (float) displayPoint.y();

        geometry.fillAt(displayX - 5.0F, displayY - 5.0F,
                displayX + 5.0F, displayY + 5.0F,
                0xFF5B0909, FOREGROUND_Z);
        geometry.fillAt(displayX - 2.5F, displayY - 2.5F,
                displayX + 2.5F, displayY + 2.5F,
                0xFFFF3A3A, FOREGROUND_Z);
        for (ConnectedDisplayBlockEntity.Contact contact : data.getContacts()) {
            geometry.opacity = (ClientRadarMemory.displayColor(data, contact, 0xFFFFFFFF) >>> 24) / 255.0F;
            ConnectedDisplayLayout.RadarPoint point =
                    ConnectedDisplayLayout.radarPoint(
                            contact.center(),
                            radarOrigin,
                            data.getRadarRange(),
                            radar
                    );
            float px = (float) point.x();
            float py = (float) point.y();
            if (contact.missile()) {
                int missileColor = 0xFFFFB339;
                geometry.line(px, py - 13, px - 6, py + 5, missileColor, 3.0F);
                geometry.line(px, py - 13, px + 6, py + 5, missileColor, 3.0F);
                geometry.line(px - 6, py + 5, px + 6, py + 5, missileColor, 3.0F);
                geometry.line(px - 6, py + 5, px - 11, py + 12, missileColor, 3.0F);
                geometry.line(px + 6, py + 5, px + 11, py + 12, missileColor, 3.0F);
                geometry.line(px, py + 5, px, py + 14, missileColor, 2.0F);
            } else if (contact.key().living()) {
                    geometry.circle(px, py, 8.0F, 0xFF5DE1FF, 3.0F);
            } else {
                geometry.line(px, py - 10, px - 8, py + 8,
                        0xFF5DE1FF, 3.0F);
                geometry.line(px, py - 10, px + 8, py + 8,
                        0xFF5DE1FF, 3.0F);
                geometry.line(px - 8, py + 8, px + 8, py + 8,
                        0xFF5DE1FF, 3.0F);
            }
            if (data.isSelected(contact) || (data.isCruiseMultiSelect()
                    && data.isCruiseStrikeTarget(contact))) {
                geometry.line(
                        px - 17, py - 10,
                        px - 17, py + 10,
                        0xFF5DE1FF, 4.0F);
                geometry.line(
                        px + 17, py - 10,
                        px + 17, py + 10,
                        0xFF5DE1FF, 4.0F);
            }
        }

        geometry.opacity = 1.0F;
        Vec3 manualAim = data.getManualAimPoint();
        if (manualAim != null && !data.isCruiseMultiSelect()) {
            ConnectedDisplayLayout.RadarPoint point = ConnectedDisplayLayout.radarPoint(
                    manualAim, radarOrigin, data.getRadarRange(), radar);
            float aimX = (float) point.x();
            float aimY = (float) point.y();
            geometry.line(aimX - 7, aimY - 7, aimX + 7, aimY + 7, 0xFFFF3A3A, 3.0F);
            geometry.line(aimX - 7, aimY + 7, aimX + 7, aimY - 7, 0xFFFF3A3A, 3.0F);
        }
        if (data.isCruiseMultiSelect()) {
            for (ConnectedDisplayBlockEntity.CruiseStrikeTarget target
                    : data.getCruiseStrikeTargets()) {
                if (target.targetKey() != null) continue;
                ConnectedDisplayLayout.RadarPoint point = ConnectedDisplayLayout.radarPoint(
                        target.point(), radarOrigin, data.getRadarRange(), radar);
                float aimX = (float) point.x();
                float aimY = (float) point.y();
                geometry.line(aimX - 7, aimY - 7, aimX + 7, aimY + 7,
                        0xFFFF3A3A, 3.0F);
                geometry.line(aimX - 7, aimY + 7, aimX + 7, aimY - 7,
                        0xFFFF3A3A, 3.0F);
            }
        }
        for (ConnectedDisplayBlockEntity.CruiseTelemetry telemetry : data.getCruiseTelemetry()) {
            ConnectedDisplayLayout.RadarPoint missilePoint = ConnectedDisplayLayout.radarPoint(
                    telemetry.position(), radarOrigin, data.getRadarRange(), radar);
            ConnectedDisplayLayout.RadarPoint targetPoint = ConnectedDisplayLayout.radarPoint(
                    telemetry.target(), radarOrigin, data.getRadarRange(), radar);
            float missileX = (float) missilePoint.x();
            float missileY = (float) missilePoint.y();
            float targetX = (float) targetPoint.x();
            float targetY = (float) targetPoint.y();
            geometry.line(ConnectedDisplayLayout.RADAR_CX, ConnectedDisplayLayout.RADAR_CY,
                    targetX, targetY, 0x99FFB339, 2.0F);
            geometry.line(missileX, missileY, targetX, targetY, 0xCCFFD36A, 2.0F);
            geometry.circle(missileX, missileY, 7.0F, 0xFFFFF1A8, 3.0F);
            Vec3 velocity = telemetry.velocity();
            double horizontal = Math.max(1.0e-6, velocity.horizontalDistance());
            geometry.line(missileX, missileY,
                    (float) (missileX + velocity.x / horizontal * 22.0),
                    (float) (missileY + velocity.z / horizontal * 22.0),
                    0xFFFFFFFF, 3.0F);
            geometry.line(targetX - 9, targetY - 9, targetX + 9, targetY + 9,
                    0xFFFF7A4D, 3.0F);
            geometry.line(targetX - 9, targetY + 9, targetX + 9, targetY - 9,
                    0xFFFF7A4D, 3.0F);
        }

        // Dynamic radar markers are flushed before controls.  Apart from
        // preventing flicker this keeps the selected target highlight above
        // the terrain texture but below the command drawer.
        geometry.flush();
        geometry.begin(matrix, buffers);
        drawWeaponPanelGeometry(data);
        if (ConnectedDisplayVideoPlayer.isVideoReady()) {
            drawGodModeSwitchGeometry(data);
        }
        geometry.flush();

        drawText(
                Component.translatable("screen.create_fire_control.connected_display.title"),
                12, 12, 0xFFEAF4F8, matrix, buffers);
        drawText(
                Component.translatable("screen.create_fire_control.connected_display.subtitle"),
                ConnectedDisplayLayout.PANEL_LEFT + 18,
                12, 0xFFD9DFE1, matrix, buffers);
        drawText(
                formatRange(data.getRadarRange()),
                800, 950, 0xFF00F444, matrix, buffers);
        if (data.isCruiseMultiSelect()) {
            int targetNumber = 1;
            for (ConnectedDisplayBlockEntity.CruiseStrikeTarget target
                    : data.getCruiseStrikeTargets()) {
                Vec3 targetPoint = target.point();
                if (target.targetKey() != null) {
                    ConnectedDisplayBlockEntity.Contact contact =
                            data.getContact(target.targetKey());
                    if (contact != null) targetPoint = contact.center();
                }
                ConnectedDisplayLayout.RadarPoint point = ConnectedDisplayLayout.radarPoint(
                        targetPoint, radarOrigin, data.getRadarRange(), radar);
                drawText(Component.literal(Integer.toString(targetNumber++)),
                        (float) point.x() + 10.0F, (float) point.y() - 12.0F,
                        0xFFFFE9A6, matrix, buffers);
            }
        }
        drawWeaponPanelText(data, matrix, buffers);
        if (ConnectedDisplayVideoPlayer.isVideoReady()) {
            drawGodModeSwitchText(matrix, buffers);
        }
        pose.popPose();
    }

    private static void renderBackdrop(Matrix4f matrix, MultiBufferSource buffers,
                                       ResourceLocation mapTexture, GeometryBatch geometry) {
        geometry.begin(matrix, buffers);
        geometry.fillAt(
                0, 0,
                ConnectedDisplayLayout.CANVAS_W,
                ConnectedDisplayLayout.CANVAS_H,
                0xFF08111B,
                0.0F
        );
        geometry.flush();

        if (mapTexture != null) {
            textured(
                    matrix,
                    buffers,
                    0,
                    ConnectedDisplayLayout.RADAR_MAP_Y1,
                    ConnectedDisplayLayout.RADAR_W,
                    ConnectedDisplayLayout.CANVAS_H,
                    mapTexture,
                    MAP_Z,
                    0xFF999999
            );
        }

        geometry.begin(matrix, buffers);
        geometry.fillBase(
                0, 0,
                ConnectedDisplayLayout.RADAR_W,
                ConnectedDisplayLayout.HEADER_H,
                0xFF0E1B2A
        );
        geometry.fillBase(
                ConnectedDisplayLayout.PANEL_LEFT,
                0,
                ConnectedDisplayLayout.CANVAS_W,
                ConnectedDisplayLayout.CANVAS_H,
                0xFF0D1A29
        );
        geometry.fillBase(
                ConnectedDisplayLayout.PANEL_LEFT,
                0,
                ConnectedDisplayLayout.CANVAS_W,
                ConnectedDisplayLayout.HEADER_H,
                0xFF0E1B2A
        );

        // Darken terrain with its vertex color above. A separate translucent
        // tint in this background batch would write depth before the map
        // under Iris, rejecting the entire terrain quad.
        geometry.flush();
    }

    private void renderGodModeVideo(ConnectedDisplayBlockEntity data,
                                    float partialTick, Matrix4f matrix,
                                    MultiBufferSource buffers) {
        geometry.begin(matrix, buffers);
        geometry.fillAt(0, 0, ConnectedDisplayLayout.CANVAS_W,
                ConnectedDisplayLayout.CANVAS_H, 0xFF000000, 0.0F);
        geometry.flush();

        ResourceLocation videoTexture = ConnectedDisplayVideoPlayer.textureLocation(data, partialTick);
        if (videoTexture != null) {
            float videoAspect = ConnectedDisplayVideoPlayer.VIDEO_WIDTH
                    / (float) ConnectedDisplayVideoPlayer.VIDEO_HEIGHT;
            float canvasAspect = ConnectedDisplayLayout.CANVAS_W
                    / (float) ConnectedDisplayLayout.CANVAS_H;
            float width;
            float height;
            if (canvasAspect > videoAspect) {
                height = ConnectedDisplayLayout.CANVAS_H;
                width = height * videoAspect;
            } else {
                width = ConnectedDisplayLayout.CANVAS_W;
                height = width / videoAspect;
            }
            float left = (ConnectedDisplayLayout.CANVAS_W - width) * 0.5F;
            float top = (ConnectedDisplayLayout.CANVAS_H - height) * 0.5F;
            textured(matrix, buffers, left, top, left + width, top + height,
                    videoTexture, MAP_Z);
        } else {
            Component status = ConnectedDisplayVideoPlayer.downloadStatusMessage();
            float statusX = (ConnectedDisplayLayout.CANVAS_W
                    - font.width(status) * TEXT_SCALE) * 0.5F;
            drawText(status, statusX,
                    ConnectedDisplayLayout.CANVAS_H * 0.5F,
                    0xFFD5DCDF, matrix, buffers);
        }

        if (ConnectedDisplayVideoPlayer.isVideoReady()) {
            geometry.begin(matrix, buffers);
            drawGodModeSwitchGeometry(data);
            geometry.flush();
            drawGodModeSwitchText(matrix, buffers);
        }
    }

    private void drawGodModeSwitchGeometry(ConnectedDisplayBlockEntity data) {
        boolean active = data.isGodModeVideoPlaying();
        geometry.roundedControl(ConnectedDisplayLayout.GOD_MODE_X1,
                ConnectedDisplayLayout.GOD_MODE_Y1,
                ConnectedDisplayLayout.GOD_MODE_X2,
                ConnectedDisplayLayout.GOD_MODE_Y2, 6.0F,
                active ? 0xFF8E1010 : 0xFF471414);
        geometry.roundedOutline(ConnectedDisplayLayout.GOD_MODE_X1,
                ConnectedDisplayLayout.GOD_MODE_Y1,
                ConnectedDisplayLayout.GOD_MODE_X2,
                ConnectedDisplayLayout.GOD_MODE_Y2,
                6.0F, active ? 0xFFFF4747 : 0xFFB72B2B, 2.0F);
        geometry.fillAt(1624, 10, 1672, 32,
                0xFF220707, FOREGROUND_Z);
        geometry.fillAt(active ? 1650 : 1627, 12,
                active ? 1669 : 1646, 30,
                active ? 0xFFFF3D3D : 0xFF9B2424, FOREGROUND_Z);
    }

    private void drawGodModeSwitchText(Matrix4f matrix,
                                       MultiBufferSource buffers) {
        drawText(Component.translatable(
                        "screen.create_fire_control.connected_display.god_mode"),
                1492, 11, 0xFFFFDADA, matrix, buffers);
    }

    private void drawWeaponPanelGeometry(ConnectedDisplayBlockEntity data) {
        drawWeaponTab(data, ConnectedDisplayBlockEntity.WeaponCategory.CANNON,
                ConnectedDisplayLayout.CANNON_TAB_X1, ConnectedDisplayLayout.CANNON_TAB_X2);
        drawWeaponTab(data, ConnectedDisplayBlockEntity.WeaponCategory.AIR_DEFENSE,
                ConnectedDisplayLayout.AA_TAB_X1, ConnectedDisplayLayout.AA_TAB_X2);
        drawWeaponTab(data, ConnectedDisplayBlockEntity.WeaponCategory.CRUISE,
                ConnectedDisplayLayout.CRUISE_TAB_X1, ConnectedDisplayLayout.CRUISE_TAB_X2);
        drawWeaponTab(data, ConnectedDisplayBlockEntity.WeaponCategory.BALLISTIC,
                ConnectedDisplayLayout.BALLISTIC_TAB_X1, ConnectedDisplayLayout.BALLISTIC_TAB_X2);
        boolean cruiseSelected = data.getSelectedWeapon()
                == ConnectedDisplayBlockEntity.WeaponCategory.CRUISE;
        boolean ballisticSelected = data.getSelectedWeapon()
                == ConnectedDisplayBlockEntity.WeaponCategory.BALLISTIC;
        boolean selectedMissileAvailable = cruiseSelected
                ? data.hasCruiseMissileAvailable()
                : ballisticSelected && data.hasBallisticMissileAvailable();
        geometry.roundedControl(ConnectedDisplayLayout.CRUISE_TYPE_X1,
                ConnectedDisplayLayout.CRUISE_TYPE_Y1,
                ConnectedDisplayLayout.CRUISE_TYPE_X2,
                ConnectedDisplayLayout.CRUISE_TYPE_Y2, 8.0F,
                selectedMissileAvailable ? 0xFF123F52 : 0xFF162A3B);
        geometry.roundedOutline(ConnectedDisplayLayout.CRUISE_TYPE_X1,
                ConnectedDisplayLayout.CRUISE_TYPE_Y1,
                ConnectedDisplayLayout.CRUISE_TYPE_X2,
                ConnectedDisplayLayout.CRUISE_TYPE_Y2,
                8.0F, selectedMissileAvailable ? 0xFF5DE1FF : 0xFF35546B, 2.0F);

        geometry.fillControl(1035, 242, 1662, 544, 0xFF102131);
        drawRangeSlider(ConnectedDisplayLayout.RADAR_SLIDER_Y1,
                ConnectedDisplayLayout.RADAR_SLIDER_Y2,
                ConnectedDisplayLayout.sliderX(data.getRadarRange()), 0xFF00C940);
        drawRangeSlider(ConnectedDisplayLayout.DEFENSE_SLIDER_Y1,
                ConnectedDisplayLayout.DEFENSE_SLIDER_Y2,
                ConnectedDisplayLayout.defenseSliderX(data.getAutomaticMissileDefenseRange()),
                0xFFFFB44F);
        drawRangeSlider(ConnectedDisplayLayout.PROXIMITY_SLIDER_Y1,
                ConnectedDisplayLayout.PROXIMITY_SLIDER_Y2,
                ConnectedDisplayLayout.proximitySliderX(data.getMissileProximityRange()),
                0xFFFF6A6A);

        geometry.roundedControl(ConnectedDisplayLayout.AUTO_X1,
                ConnectedDisplayLayout.AUTO_Y1, ConnectedDisplayLayout.AUTO_X2,
                ConnectedDisplayLayout.AUTO_Y2, 8.0F,
                data.isAutomaticAttack() ? 0xFF0E5B70 : 0xFF162A3B);
        geometry.roundedOutline(ConnectedDisplayLayout.AUTO_X1,
                ConnectedDisplayLayout.AUTO_Y1, ConnectedDisplayLayout.AUTO_X2,
                ConnectedDisplayLayout.AUTO_Y2,
                8.0F, data.isAutomaticAttack() ? 0xFF5DE1FF : 0xFF35546B, 3.0F);
        geometry.roundedControl(ConnectedDisplayLayout.AUTO_DEFENSE_X1,
                ConnectedDisplayLayout.AUTO_Y1, ConnectedDisplayLayout.AUTO_DEFENSE_X2,
                ConnectedDisplayLayout.AUTO_Y2, 8.0F,
                data.isAutomaticMissileDefense() ? 0xFF0E5B70 : 0xFF162A3B);
        geometry.roundedOutline(ConnectedDisplayLayout.AUTO_DEFENSE_X1,
                ConnectedDisplayLayout.AUTO_Y1, ConnectedDisplayLayout.AUTO_DEFENSE_X2,
                ConnectedDisplayLayout.AUTO_Y2,
                8.0F, data.isAutomaticMissileDefense() ? 0xFF5DE1FF : 0xFF35546B, 3.0F);

        geometry.fillControl(1035, 548, 1662, 700, 0xFF102131);
        boolean multiEnabled = data.getSelectedWeapon()
                == ConnectedDisplayBlockEntity.WeaponCategory.CRUISE
                && data.isCruiseMultiSelect();
        geometry.roundedControl(ConnectedDisplayLayout.CRUISE_MULTI_X1,
                ConnectedDisplayLayout.CRUISE_MULTI_Y1,
                ConnectedDisplayLayout.CRUISE_MULTI_X2,
                ConnectedDisplayLayout.CRUISE_MULTI_Y2, 8.0F,
                multiEnabled ? 0xFF0E5B70 : 0xFF162A3B);
        geometry.roundedOutline(ConnectedDisplayLayout.CRUISE_MULTI_X1,
                ConnectedDisplayLayout.CRUISE_MULTI_Y1,
                ConnectedDisplayLayout.CRUISE_MULTI_X2,
                ConnectedDisplayLayout.CRUISE_MULTI_Y2,
                8.0F, multiEnabled ? 0xFF5DE1FF : 0xFF35546B, 2.0F);
        geometry.roundedControl(ConnectedDisplayLayout.FIRE_X1,
                ConnectedDisplayLayout.FIRE_Y1, ConnectedDisplayLayout.FIRE_X2,
                ConnectedDisplayLayout.FIRE_Y2, 10.0F, 0xFFB86A1A);
        geometry.roundedOutline(ConnectedDisplayLayout.FIRE_X1,
                ConnectedDisplayLayout.FIRE_Y1, ConnectedDisplayLayout.FIRE_X2,
                ConnectedDisplayLayout.FIRE_Y2, 10.0F, 0xFFFFBD59, 3.0F);
        geometry.roundedControl(ConnectedDisplayLayout.CLEAR_X1,
                ConnectedDisplayLayout.CLEAR_Y1, ConnectedDisplayLayout.CLEAR_X2,
                ConnectedDisplayLayout.CLEAR_Y2, 10.0F, 0xFF162A3B);
        geometry.roundedOutline(ConnectedDisplayLayout.CLEAR_X1,
                ConnectedDisplayLayout.CLEAR_Y1, ConnectedDisplayLayout.CLEAR_X2,
                ConnectedDisplayLayout.CLEAR_Y2, 10.0F, 0xFF35546B, 3.0F);
        geometry.fillControl(1035, 806, 1662, 970, 0xFF102131);
    }

    private void drawWeaponTab(ConnectedDisplayBlockEntity data,
                               ConnectedDisplayBlockEntity.WeaponCategory category,
                               int x1, int x2) {
        boolean selected = data.getSelectedWeapon() == category;
        geometry.roundedControl(x1, ConnectedDisplayLayout.WEAPON_TAB_Y1,
                x2, ConnectedDisplayLayout.WEAPON_TAB_Y2,
                6.0F, selected ? 0xFF0E6F8A : 0xFF162A3B);
        geometry.roundedOutline(x1, ConnectedDisplayLayout.WEAPON_TAB_Y1,
                x2, ConnectedDisplayLayout.WEAPON_TAB_Y2,
                6.0F, selected ? 0xFF5DE1FF : 0xFF35546B, 2.0F);
    }

    private void drawRangeSlider(int y1, int y2, double knob, int fillColor) {
        float center = (y1 + y2) * 0.5F;
        // Track and fill share one plane; the handle is a separate, very
        // close plane so the overlap cannot flicker when the value changes.
        geometry.fillAt(ConnectedDisplayLayout.SLIDER_X1, center - 4.0F,
                ConnectedDisplayLayout.SLIDER_X2, center + 4.0F, 0xFF49555D, CONTROL_Z);
        geometry.fillAt(ConnectedDisplayLayout.SLIDER_X1, center - 4.0F,
                (float) knob, center + 4.0F, fillColor, FOREGROUND_Z);
        geometry.fillAt((float) knob - 7.0F, y1 - 3.0F,
                (float) knob + 7.0F, y2 + 3.0F, 0xFF7BFF96, TEXT_Z);
    }

    private void drawWeaponPanelText(ConnectedDisplayBlockEntity data,
                                     Matrix4f matrix, MultiBufferSource buffers) {
        drawText(Component.translatable(
                        "screen.create_fire_control.connected_display.weapon_selection"),
                1035, 52, 0xFFD5DCDF, matrix, buffers);
        drawCenteredText(Component.translatable(
                        "screen.create_fire_control.connected_display.weapon.cannon"),
                ConnectedDisplayLayout.CANNON_TAB_X1,
                ConnectedDisplayLayout.WEAPON_TAB_Y1,
                ConnectedDisplayLayout.CANNON_TAB_X2,
                ConnectedDisplayLayout.WEAPON_TAB_Y2,
                data.getSelectedWeapon() == ConnectedDisplayBlockEntity.WeaponCategory.CANNON
                        ? 0xFFB9FFC5 : 0xFFD5DCDF, matrix, buffers);
        drawCenteredText(Component.translatable(
                        "screen.create_fire_control.connected_display.weapon.air_defense"),
                ConnectedDisplayLayout.AA_TAB_X1,
                ConnectedDisplayLayout.WEAPON_TAB_Y1,
                ConnectedDisplayLayout.AA_TAB_X2,
                ConnectedDisplayLayout.WEAPON_TAB_Y2,
                data.getSelectedWeapon() == ConnectedDisplayBlockEntity.WeaponCategory.AIR_DEFENSE
                        ? 0xFFB9FFC5 : 0xFFD5DCDF, matrix, buffers);
        drawCenteredText(Component.translatable(
                        "screen.create_fire_control.connected_display.weapon.cruise"),
                ConnectedDisplayLayout.CRUISE_TAB_X1,
                ConnectedDisplayLayout.WEAPON_TAB_Y1,
                ConnectedDisplayLayout.CRUISE_TAB_X2,
                ConnectedDisplayLayout.WEAPON_TAB_Y2,
                data.getSelectedWeapon() == ConnectedDisplayBlockEntity.WeaponCategory.CRUISE
                        ? 0xFFB9FFC5 : 0xFFD5DCDF, matrix, buffers);
        drawCenteredText(Component.translatable(
                        "screen.create_fire_control.connected_display.weapon.ballistic"),
                ConnectedDisplayLayout.BALLISTIC_TAB_X1,
                ConnectedDisplayLayout.WEAPON_TAB_Y1,
                ConnectedDisplayLayout.BALLISTIC_TAB_X2,
                ConnectedDisplayLayout.WEAPON_TAB_Y2,
                data.getSelectedWeapon() == ConnectedDisplayBlockEntity.WeaponCategory.BALLISTIC
                        ? 0xFFB9FFC5 : 0xFFD5DCDF, matrix, buffers);
        if (data.getSelectedWeapon() == ConnectedDisplayBlockEntity.WeaponCategory.BALLISTIC) {
            boolean ready = data.hasBallisticMissileAvailable();
            Component ballisticType = ready
                    ? Component.translatable(
                    "screen.create_fire_control.connected_display.missile_type.available",
                    ballisticName(data.getSelectedBallisticType()))
                    : Component.translatable(
                    "screen.create_fire_control.connected_display.ballistic.unavailable");
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.ballistic_model",
                            ballisticType),
                    1060, 183, ready ? 0xFF8AFF9E : 0xFF85929A, matrix, buffers);
        } else {
            Component cruiseType = data.hasCruiseMissileAvailable()
                    ? Component.translatable(
                    "screen.create_fire_control.connected_display.missile_type.available",
                    cruiseName(data.getSelectedCruiseType()))
                    : Component.translatable(
                    "screen.create_fire_control.connected_display.cruise.unavailable");
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.missile_model",
                            cruiseType),
                    1060, 183,
                    data.hasCruiseMissileAvailable() ? 0xFF8AFF9E : 0xFF85929A,
                    matrix, buffers);
        }
        drawText(Component.translatable(
                        "screen.create_fire_control.connected_display.radar_display_range"),
                1065, 250, 0xFFD5DCDF, matrix, buffers);
        drawText(Component.translatable(
                        "screen.create_fire_control.connected_display.radar_range_value",
                        formatRange(data.getRadarRange())),
                1065, 270, 0xFF7BFF96, matrix, buffers);
        drawText(Component.translatable(
                        "screen.create_fire_control.connected_display.defense_range"),
                1065, 326, 0xFFD5DCDF, matrix, buffers);
        drawText(Component.translatable(
                        "screen.create_fire_control.connected_display.defense_range_value",
                        formatDefenseRange(data.getAutomaticMissileDefenseRange())),
                1065, 346, 0xFFFFB44F, matrix, buffers);
        drawText(Component.translatable(
                        "screen.create_fire_control.connected_display.proximity_range"),
                1065, 402, 0xFFD5DCDF, matrix, buffers);
        drawText(Component.translatable(
                        "screen.create_fire_control.connected_display.proximity_range_value",
                        decimal("%.1f", data.getMissileProximityRange())),
                1065, 422, 0xFFFF8A8A, matrix, buffers);
        drawText(Component.translatable(
                        "screen.create_fire_control.connected_display.automatic_fire",
                        toggle(data.isAutomaticAttack())),
                1070, 497, data.isAutomaticAttack() ? 0xFF8AFF9E : 0xFFD5DCDF,
                matrix, buffers);
        drawText(Component.translatable(
                        "screen.create_fire_control.connected_display.automatic_defense",
                        toggle(data.isAutomaticMissileDefense())),
                1395, 497, data.isAutomaticMissileDefense() ? 0xFF8AFF9E : 0xFFD5DCDF,
                matrix, buffers);

        ConnectedDisplayBlockEntity.Contact selected = null;
        for (ConnectedDisplayBlockEntity.Contact contact : data.getContacts()) {
            if (data.isSelected(contact)) { selected = contact; break; }
        }
        drawText(Component.translatable(
                        "screen.create_fire_control.connected_display.current_strike_target"),
                1065, 558, 0xFFD5DCDF, matrix, buffers);
        if (data.getSelectedWeapon()
                == ConnectedDisplayBlockEntity.WeaponCategory.CRUISE) {
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.multi_select",
                            toggle(data.isCruiseMultiSelect())),
                    1460, 579, data.isCruiseMultiSelect() ? 0xFF8AFF9E : 0xFFD5DCDF,
                    matrix, buffers);
        }
        Vec3 manual = data.getManualAimPoint();
        if (data.isCruiseMultiSelect()) {
            int selectedCount = data.getCruiseStrikeTargets().size();
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.targets_selected",
                            selectedCount, data.getCruiseSalvoCapacity()),
                    1065, 594, selectedCount > 0 ? 0xFFFFD36A : 0xFF85929A,
                    matrix, buffers);
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.multi_select_hint"),
                    1065, 630, 0xFFB5C0C6, matrix, buffers);
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.multi_select_capacity_hint"),
                    1065, 666, 0xFF85929A, matrix, buffers);
        } else if (selected == null && manual == null) {
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.not_selected"),
                    1065, 594, 0xFF85929A, matrix, buffers);
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.select_target_hint"),
                    1065, 630, 0xFF85929A, matrix, buffers);
        } else if (manual != null) {
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.target_type.map_point"),
                    1065, 594, 0xFFFFD36A, matrix, buffers);
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.coordinates_xz",
                            decimal("%.0f", manual.x), decimal("%.0f", manual.z)),
                    1065, 630, 0xFFD5DCDF, matrix, buffers);
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.map_point_hint"),
                    1065, 666, 0xFF85929A, matrix, buffers);
        } else {
            drawText(targetType(selected.missile(), selected.key().living()),
                    1065, 594, 0xFF7BFF96, matrix, buffers);
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.distance",
                            decimal("%.0f", selected.distance())),
                    1065, 630, 0xFFD5DCDF, matrix, buffers);
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.closing_rate",
                            decimal("%.1f", selected.closingRate())),
                    1065, 666, 0xFFD5DCDF, matrix, buffers);
        }

        Component fireText = switch (data.getSelectedWeapon()) {
            case CANNON -> Component.translatable(
                    "screen.create_fire_control.connected_display.fire.cannon");
            case AIR_DEFENSE -> Component.translatable(
                    "screen.create_fire_control.connected_display.fire.air_defense");
            case CRUISE -> data.isCruiseMultiSelect()
                    ? Component.translatable(
                    "screen.create_fire_control.connected_display.fire.cruise_multiple")
                    : Component.translatable(
                    "screen.create_fire_control.connected_display.fire.cruise_single");
            case BALLISTIC -> Component.translatable(
                    "screen.create_fire_control.connected_display.fire.ballistic");
        };
        drawText(fireText, 1110, 733, 0xFFFFD69D, matrix, buffers);
        drawText(Component.translatable(
                        "screen.create_fire_control.connected_display.cancel_target"),
                1450, 733, 0xFFD5DCDF, matrix, buffers);
        drawText(Component.translatable(
                        "screen.create_fire_control.connected_display.telemetry.cruise"),
                1065, 824, 0xFFD5DCDF, matrix, buffers);
        java.util.List<ConnectedDisplayBlockEntity.CruiseTelemetry> telemetryList =
                data.getCruiseTelemetry();
        if (telemetryList.isEmpty()) {
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.telemetry.empty"),
                    1065, 862, 0xFF85929A, matrix, buffers);
        } else {
            ConnectedDisplayBlockEntity.CruiseTelemetry telemetry = telemetryList.get(0);
            var type = com.hooya.stabilizedturret.content.controller.MianbaoCruiseMissileCompat.Type
                    .byKey(telemetry.typeKey());
            double bearing = Math.floorMod((int) Math.round(Math.toDegrees(
                    Math.atan2(telemetry.velocity().x, telemetry.velocity().z))), 360);
            Component typeName = type == null ? Component.literal(telemetry.typeKey())
                    : cruiseName(type);
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.telemetry.entry",
                            typeName, telemetryList.size()),
                    1065, 858, 0xFFFFD36A, matrix, buffers);
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.telemetry.position",
                            decimal("%.0f", telemetry.position().x),
                            decimal("%.0f", telemetry.position().y),
                            decimal("%.0f", telemetry.position().z)),
                    1065, 892, 0xFFD5DCDF, matrix, buffers);
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.telemetry.flight",
                            decimal("%.0f", bearing),
                            decimal("%.1f", telemetry.velocity().length() * 20.0),
                            decimal("%.1f", telemetry.ageTicks() / 20.0)),
                    1065, 926, 0xFFD5DCDF, matrix, buffers);
            drawText(Component.translatable(
                            "screen.create_fire_control.connected_display.telemetry.target",
                            decimal("%.0f", telemetry.target().x),
                            decimal("%.0f", telemetry.target().y),
                            decimal("%.0f", telemetry.target().z)),
                    1065, 958, 0xFFB9FFC5, matrix, buffers);
        }
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
                Component.translatable(
                        "screen.create_fire_control.connected_display.radar_detection_range"),
                1065, 98, 0xFFD5DCDF, matrix, buffers);
        drawText(
                Component.translatable(
                        "screen.create_fire_control.connected_display.radar_range_value",
                        formatRange(data.getRadarRange())),
                1065, 126, 0xFF7BFF96, matrix, buffers);
        drawText(
                Component.translatable(
                        "screen.create_fire_control.connected_display.automatic_lock_and_fire",
                        toggle(data.isAutomaticAttack())),
                1110, 290,
                data.isAutomaticAttack() ? 0xFF8AFF9E : 0xFFD5DCDF,
                matrix, buffers);
        drawText(
                Component.translatable(
                        "screen.create_fire_control.connected_display.manual_fire"),
                1145, 414, 0xFFFFD69D, matrix, buffers);
        drawText(
                Component.translatable(
                        "screen.create_fire_control.connected_display.cancel_target"),
                1442, 414, 0xFFD5DCDF, matrix, buffers);
        ConnectedDisplayBlockEntity.Contact selected = null;
        for (ConnectedDisplayBlockEntity.Contact contact : data.getContacts()) {
            if (data.isSelected(contact)) {
                selected = contact;
                break;
            }
        }
        drawText(
                Component.translatable(
                        "screen.create_fire_control.connected_display.current_selected_target"),
                1065, 535, 0xFFD5DCDF, matrix, buffers);
        if (selected == null) {
            drawText(
                    Component.translatable(
                            "screen.create_fire_control.connected_display.not_selected"),
                    1065, 575, 0xFF85929A, matrix, buffers);
            drawText(
                    Component.translatable(
                            "screen.create_fire_control.connected_display.select_radar_target_hint"),
                    1065, 615, 0xFF85929A, matrix, buffers);
        } else {
            drawText(
                    targetType(false, selected.key().living()),
                    1065, 575, 0xFF7BFF96, matrix, buffers);
            drawText(
                    Component.translatable(
                            "screen.create_fire_control.connected_display.distance",
                            decimal("%.0f", selected.distance())),
                    1065, 615, 0xFFD5DCDF, matrix, buffers);
            drawText(
                    Component.translatable(
                            "screen.create_fire_control.connected_display.closing_rate",
                            decimal("%.1f", selected.closingRate())),
                    1065, 655, 0xFFD5DCDF, matrix, buffers);
        }
        drawText(
                Component.translatable(
                        "screen.create_fire_control.connected_display.launch_missile"),
                1280, 790,
                data.hasBoundMissileLauncher()
                        ? 0xFFB9FFC5 : 0xFF81878B,
                matrix, buffers);
        drawText(
                Component.translatable(
                        "screen.create_fire_control.connected_display.launch_missile_hint"),
                1065, 875, 0xFF85929A, matrix, buffers);
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
        return range <= ConnectedDisplayBlockEntity.UNLIMITED_MISSILE_DEFENSE_RANGE
                ? Component.translatable(
                "screen.create_fire_control.connected_display.range.unlimited")
                : formatRange(range);
    }

    private static Component toggle(boolean enabled) {
        return Component.translatable(enabled
                ? "screen.create_fire_control.connected_display.state.enabled"
                : "screen.create_fire_control.connected_display.state.disabled");
    }

    private static Component targetType(boolean missile, boolean living) {
        String suffix = missile ? "cruise_missile" : living ? "living" : "sable";
        return Component.translatable(
                "screen.create_fire_control.connected_display.target_type." + suffix);
    }

    private static Component cruiseName(
            com.hooya.stabilizedturret.content.controller.MianbaoCruiseMissileCompat.Type type) {
        return Component.translatable(
                "screen.create_fire_control.connected_display.missile_type." + type.key());
    }

    private static Component ballisticName(
            com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat.Type type) {
        return Component.translatable(
                "screen.create_fire_control.connected_display.missile_type." + type.key());
    }

    private static String decimal(String format, double value) {
        return String.format(Locale.ROOT, format, value);
    }

    private void drawText(
            Component text,
            float x,
            float y,
            int color,
            Matrix4f matrix,
            MultiBufferSource buffers
    ) {
        Matrix4f textMatrix = layerMatrix(matrix, TEXT_Z)
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

    private void drawCenteredText(
            Component text,
            float x1,
            float y1,
            float x2,
            float y2,
            int color,
            Matrix4f matrix,
            MultiBufferSource buffers
    ) {
        float width = font.width(text) * TEXT_SCALE;
        float height = font.lineHeight * TEXT_SCALE;
        drawText(text,
                (x1 + x2 - width) * 0.5F,
                (y1 + y2 - height) * 0.5F,
                color, matrix, buffers);
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

    private static void textured(
            Matrix4f matrix,
            MultiBufferSource buffers,
            float x1,
            float y1,
            float x2,
            float y2,
            ResourceLocation texture,
            float z
    ) {
        textured(matrix, buffers, x1, y1, x2, y2, texture, z, -1);
    }

    private static void textured(
            Matrix4f matrix,
            MultiBufferSource buffers,
            float x1, float y1, float x2, float y2,
            ResourceLocation texture, float z, int color
    ) {
        // Use the world text layer, just like the display's glyphs. Immediate
        // position/texture draws bypass the block-entity buffer and Iris's
        // world shader routing, so their depth can disagree with GeckoLib.
        VertexConsumer builder = buffers.getBuffer(RenderType.text(texture));
        matrix = layerMatrix(matrix, z);
        // NativeImage rows are written north-to-south. Keep the first row at
        // the top of the display: top north, bottom south, right east, left west.
        // Match glyph winding: the front of the canvas faces local -Z.
        builder.addVertex(matrix, x1, y1, z).setColor(color).setUv(0, 0).setLight(LightTexture.FULL_BRIGHT);
        builder.addVertex(matrix, x1, y2, z).setColor(color).setUv(0, 1).setLight(LightTexture.FULL_BRIGHT);
        builder.addVertex(matrix, x2, y2, z).setColor(color).setUv(1, 1).setLight(LightTexture.FULL_BRIGHT);
        builder.addVertex(matrix, x2, y1, z).setColor(color).setUv(1, 0).setLight(LightTexture.FULL_BRIGHT);
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
        // Sable substitutes a plot-local camera position before invoking this
        // method for a sub-level block entity. Keep the block center in the
        // same coordinate space; projecting only this side to world space
        // incorrectly culls every moving-sub-level display.
        return Vec3.atCenterOf(blockEntity.getBlockPos())
                .closerThan(cameraPosition, getViewDistance());
    }

    private static final class GeometryBatch {
        private float[] positions = new float[4096 * 3];
        private int[] colors = new int[4096];
        private int vertices;
        private float opacity;
        private Matrix4f matrix;
        private MultiBufferSource buffers;

        void begin(Matrix4f matrix, MultiBufferSource buffers) {
            opacity = 1.0F;
            this.matrix = matrix;
            this.buffers = buffers;
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

        void roundedControl(float x1, float y1, float x2, float y2,
                            float radius, int color) {
            float r = Math.max(1.0F, Math.min(radius,
                    Math.min((x2 - x1) * 0.5F, (y2 - y1) * 0.5F)));
            int steps = Math.max(4, (int) Math.ceil(r));
            // Rasterise the two rounded caps as thin horizontal strips. This
            // produces a true rounded silhouette with the same inexpensive
            // position-color quads used by the rest of the renderer.
            fillAt(x1 + r, y1, x2 - r, y2, color, CONTROL_Z);
            for (int i = 0; i < steps; i++) {
                float top = y1 + r * i / steps;
                float bottom = y1 + r * (i + 1) / steps;
                float dy = (top + bottom) * 0.5F - (y1 + r);
                float inset = r - (float) Math.sqrt(Math.max(0.0F, r * r - dy * dy));
                fillAt(x1 + inset, top, x2 - inset, bottom, color, CONTROL_Z);
                float bottomTop = y2 - r + r * i / steps;
                float bottomBottom = y2 - r + r * (i + 1) / steps;
                float bottomDy = (bottomTop + bottomBottom) * 0.5F - (y2 - r);
                float bottomInset = r - (float) Math.sqrt(
                        Math.max(0.0F, r * r - bottomDy * bottomDy));
                fillAt(x1 + bottomInset, bottomTop,
                        x2 - bottomInset, bottomBottom, color, CONTROL_Z);
            }
        }

        // Compatibility overload for the existing control call sites. The
        // control widgets use the same compact corner radius as roundedControl.
        void roundedOutline(float x1, float y1, float x2, float y2,
                            int color, float thickness) {
            roundedOutline(x1, y1, x2, y2, 6.0F, color, thickness);
        }

        void roundedOutline(float x1, float y1, float x2, float y2,
                            float radius, int color, float thickness) {
            float r = Math.max(1.0F, Math.min(radius,
                    Math.min((x2 - x1) * 0.5F, (y2 - y1) * 0.5F)));
            int steps = Math.max(6, (int) Math.ceil(r * 1.5F));
            line(x1 + r, y1, x2 - r, y1, color, thickness);
            line(x2, y1 + r, x2, y2 - r, color, thickness);
            line(x2 - r, y2, x1 + r, y2, color, thickness);
            line(x1, y2 - r, x1, y1 + r, color, thickness);
            roundedArc(x1 + r, y1 + r, r, (float) Math.PI,
                    (float) (Math.PI * 1.5), steps, color, thickness);
            roundedArc(x2 - r, y1 + r, r, (float) (Math.PI * 1.5),
                    (float) (Math.PI * 2.0), steps, color, thickness);
            roundedArc(x2 - r, y2 - r, r, 0.0F,
                    (float) (Math.PI * 0.5), steps, color, thickness);
            roundedArc(x1 + r, y2 - r, r, (float) (Math.PI * 0.5),
                    (float) Math.PI, steps, color, thickness);
        }

        private void roundedArc(float cx, float cy, float radius,
                                float start, float end, int steps,
                                int color, float thickness) {
            float previousX = cx + (float) Math.cos(start) * radius;
            float previousY = cy + (float) Math.sin(start) * radius;
            for (int i = 1; i <= steps; i++) {
                float angle = start + (end - start) * i / steps;
                float nextX = cx + (float) Math.cos(angle) * radius;
                float nextY = cy + (float) Math.sin(angle) * radius;
                line(previousX, previousY, nextX, nextY, color, thickness);
                previousX = nextX;
                previousY = nextY;
            }
        }

        void fillAt(
                float x1, float y1, float x2, float y2,
                int color, float z
        ) {
            // Fill and stroke quads must face local -Z, just like the glyphs.
            quad(x1, y1, x1, y2, x2, y2, x2, y1, z, color);
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
            colors[index] = (Math.round((color >>> 24) * opacity) << 24) | (color & 0xffffff);
        }

        void ensure(int needed) {
            if (needed <= colors.length) return;
            int size = Math.max(needed, colors.length * 2);
            positions = java.util.Arrays.copyOf(positions, size * 3);
            colors = java.util.Arrays.copyOf(colors, size);
        }

        void flush() {
            if (vertices == 0) return;
            // The standard world text background writes and tests depth in
            // the same render pipeline as the glyphs and textured canvas.
            VertexConsumer builder = buffers.getBuffer(RenderType.textBackground());
            float previousZ = Float.NaN;
            Matrix4f plane = matrix;
            for (int index = 0; index < vertices; index++) {
                float z = positions[index * 3 + 2];
                if (z != previousZ) {
                    plane = layerMatrix(matrix, z);
                    previousZ = z;
                }
                builder.addVertex(
                        plane,
                        positions[index * 3],
                        positions[index * 3 + 1],
                        positions[index * 3 + 2]
                ).setColor(colors[index]).setLight(LightTexture.FULL_BRIGHT);
            }
            vertices = 0;
        }
    }
}
