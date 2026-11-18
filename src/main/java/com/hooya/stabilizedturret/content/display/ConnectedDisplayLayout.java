package com.hooya.stabilizedturret.content.display;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared pixel/world mapping for the rendered display wall and its clickable
 * surface. Keeping this outside the client renderer makes server-side clicks
 * use exactly the same radar scale as the visible contacts.
 */
public final class ConnectedDisplayLayout {
    public static final int CANVAS_W = 1697;
    public static final int CANVAS_H = 1001;
    public static final int RADAR_W = 1000;
    public static final int HEADER_H = 42;
    public static final int GOD_MODE_X1 = 1480;
    public static final int GOD_MODE_X2 = 1682;
    public static final int GOD_MODE_Y1 = 5;
    public static final int GOD_MODE_Y2 = 37;
    /** The map starts one pixel behind the header so its center is integral. */
    public static final int RADAR_MAP_Y1 = HEADER_H - 1;
    public static final int RADAR_MAP_H = CANVAS_H - RADAR_MAP_Y1;
    public static final int RADAR_CX = 500;
    public static final int RADAR_CY = RADAR_MAP_Y1 + RADAR_MAP_H / 2;
    public static final double SCREEN_INSET = 1.0 / 16.0;

    public static final int PANEL_LEFT = RADAR_W;
    public static final int WEAPON_TAB_Y1 = 78;
    public static final int WEAPON_TAB_Y2 = 142;
    public static final int CANNON_TAB_X1 = 1035;
    public static final int CANNON_TAB_X2 = 1180;
    public static final int AA_TAB_X1 = 1195;
    public static final int AA_TAB_X2 = 1340;
    public static final int CRUISE_TAB_X1 = 1355;
    public static final int CRUISE_TAB_X2 = 1500;
    public static final int BALLISTIC_TAB_X1 = 1515;
    public static final int BALLISTIC_TAB_X2 = 1662;
    public static final int CRUISE_TYPE_X1 = 1035;
    public static final int CRUISE_TYPE_X2 = 1662;
    public static final int CRUISE_TYPE_Y1 = 162;
    public static final int CRUISE_TYPE_Y2 = 222;
    public static final int SLIDER_X1 = 1065;
    public static final int SLIDER_X2 = 1635;
    public static final int RADAR_SLIDER_Y1 = 291;
    public static final int RADAR_SLIDER_Y2 = 311;
    public static final int DEFENSE_SLIDER_Y1 = 367;
    public static final int DEFENSE_SLIDER_Y2 = 387;
    public static final int PROXIMITY_SLIDER_Y1 = 443;
    public static final int PROXIMITY_SLIDER_Y2 = 463;
    /** Legacy aliases for callers that treated the only slider as radar range. */
    public static final int SLIDER_Y1 = RADAR_SLIDER_Y1;
    public static final int SLIDER_Y2 = RADAR_SLIDER_Y2;
    public static final int AUTO_X1 = 1035;
    public static final int AUTO_X2 = 1338;
    public static final int AUTO_DEFENSE_X1 = 1362;
    public static final int AUTO_DEFENSE_X2 = 1662;
    public static final int AUTO_Y1 = 480;
    public static final int AUTO_Y2 = 534;
    public static final int CRUISE_MULTI_X1 = 1432;
    public static final int CRUISE_MULTI_X2 = 1635;
    public static final int CRUISE_MULTI_Y1 = 564;
    public static final int CRUISE_MULTI_Y2 = 616;
    public static final int FIRE_X1 = 1035;
    public static final int FIRE_X2 = 1338;
    public static final int FIRE_Y1 = 704;
    public static final int FIRE_Y2 = 784;
    public static final int CLEAR_X1 = 1362;
    public static final int CLEAR_X2 = 1662;
    public static final int CLEAR_Y1 = 704;
    public static final int CLEAR_Y2 = 784;
    // Legacy renderer coordinates retained for binary/source compatibility.
    public static final int MISSILE_X1 = 1065;
    public static final int MISSILE_X2 = 1635;
    public static final int MISSILE_Y1 = 760;
    public static final int MISSILE_Y2 = 842;

    public static @Nullable ScreenHit locate(
            Level level,
            BlockPos clickedPos,
            Vec3 hitLocation
    ) {
        BlockState clickedState = level.getBlockState(clickedPos);
        if (!(clickedState.getBlock() instanceof ConnectedDisplayBlock)
                || !clickedState.hasProperty(ConnectedDisplayBlock.FACING)) {
            return null;
        }
        Direction facing = clickedState.getValue(ConnectedDisplayBlock.FACING);
        BlockPos topLeft = canonicalTopLeft(level, clickedPos, facing);
        if (topLeft == null) return null;
        Direction right = screenLeft(facing).getOpposite();
        int wallWidth = run(level, topLeft, right, facing);
        int wallHeight = run(level, topLeft, Direction.DOWN, facing);
        if (wallWidth < 1 || wallHeight < 1) return null;

        int column = coordinateAlong(clickedPos, topLeft, right);
        int row = topLeft.getY() - clickedPos.getY();
        if (column < 0 || column >= wallWidth || row < 0 || row >= wallHeight) {
            return null;
        }
        double localRight = localAlong(hitLocation, clickedPos, right);
        double localY = hitLocation.y - clickedPos.getY();
        double drawableWidth = Math.max(0.01, wallWidth - SCREEN_INSET * 2.0);
        double drawableHeight = Math.max(0.01, wallHeight - SCREEN_INSET * 2.0);
        double scaleX = drawableWidth / CANVAS_W;
        double scaleY = drawableHeight / CANVAS_H;
        double physicalX = column + localRight;
        double physicalY = row + 1.0 - localY;
        double canvasX = (wallWidth - SCREEN_INSET - physicalX) / scaleX;
        double canvasY = (physicalY - SCREEN_INSET) / scaleY;
        ConnectedDisplayBlockEntity controller =
                ConnectedDisplayBlock.findController(level, clickedPos);
        return controller == null ? null : new ScreenHit(
                controller,
                topLeft,
                wallWidth,
                wallHeight,
                clamp(canvasX, 0.0, CANVAS_W),
                clamp(canvasY, 0.0, CANVAS_H)
        );
    }

    /** Selects exactly one renderer even if connection blockstates are stale. */
    public static @Nullable BlockPos canonicalTopLeft(
            Level level,
            BlockPos anyDisplay,
            Direction facing
    ) {
        Set<BlockPos> connected = collect(level, anyDisplay, facing);
        Direction right = screenLeft(facing).getOpposite();
        BlockPos best = null;
        long bestProjection = Long.MAX_VALUE;
        int bestY = Integer.MIN_VALUE;
        for (BlockPos pos : connected) {
            long projection = (long) pos.getX() * right.getStepX()
                    + (long) pos.getZ() * right.getStepZ();
            if (pos.getY() > bestY
                    || (pos.getY() == bestY && projection < bestProjection)) {
                best = pos;
                bestY = pos.getY();
                bestProjection = projection;
            }
        }
        return best;
    }

    public static RadarGeometry radarGeometry(int wallWidth, int wallHeight) {
        // Contacts, click coordinates, and the Xaero texture must use the
        // same pixel-to-world scale. Keeping rings physically circular here
        // made their world scale diverge from the stretched map texture.
        return new RadarGeometry(RADAR_W / 2.0, RADAR_MAP_H / 2.0);
    }

    public static RadarPoint radarPoint(
            Vec3 contact,
            Vec3 origin,
            double range,
            RadarGeometry geometry
    ) {
        double safeRange = Math.max(1.0, range);
        return new RadarPoint(
                RADAR_CX + (contact.x - origin.x) * geometry.radiusX / safeRange,
                RADAR_CY + (contact.z - origin.z) * geometry.radiusY / safeRange
        );
    }

    public static Vec3 radarWorldPoint(
            double canvasX,
            double canvasY,
            Vec3 origin,
            double range,
            RadarGeometry geometry
    ) {
        return radarWorldPoint(canvasX, canvasY, origin, range, geometry, 0);
    }

    /**
     * Converts a click to the center of the Xaero sample pixel it selects.
     * The display texture is nearest-neighbour sampled, so using the raw
     * canvas edge would put a target up to half a source pixel beyond the
     * terrain that is actually visible at that location.
     */
    public static Vec3 radarWorldPoint(
            double canvasX,
            double canvasY,
            Vec3 origin,
            double range,
            RadarGeometry geometry,
            int mapResolution
    ) {
        if (mapResolution > 0) {
            double pixelX = Math.floor(
                    clamp(canvasX / RADAR_W, 0.0, 1.0 - 1.0 / mapResolution)
                            * mapResolution);
            double pixelY = Math.floor(
                    clamp((canvasY - RADAR_MAP_Y1) / (double) RADAR_MAP_H,
                            0.0, 1.0 - 1.0 / mapResolution) * mapResolution);
            canvasX = (pixelX + 0.5) * RADAR_W / mapResolution;
            canvasY = RADAR_MAP_Y1
                    + (pixelY + 0.5) * RADAR_MAP_H / mapResolution;
        }
        double safeRadiusX = Math.max(1.0, geometry.radiusX());
        double safeRadiusY = Math.max(1.0, geometry.radiusY());
        return new Vec3(
                origin.x + (canvasX - RADAR_CX) * Math.max(1.0, range) / safeRadiusX,
                origin.y,
                origin.z + (canvasY - RADAR_CY) * Math.max(1.0, range) / safeRadiusY
        );
    }

    public static boolean inside(
            double x,
            double y,
            int x1,
            int y1,
            int x2,
            int y2
    ) {
        return x >= x1 && x <= x2 && y >= y1 && y <= y2;
    }

    public static double sliderRange(double canvasX) {
        double progress = clamp(
                (canvasX - SLIDER_X1) / (SLIDER_X2 - SLIDER_X1),
                0.0,
                1.0
        );
        return Math.round(
                (ConnectedDisplayBlockEntity.MIN_RANGE
                        + progress * (ConnectedDisplayBlockEntity.MAX_RANGE
                        - ConnectedDisplayBlockEntity.MIN_RANGE)) / 10.0
        ) * 10.0;
    }

    public static double defenseSliderRange(double canvasX) {
        double progress = clamp(
                (canvasX - SLIDER_X1) / (SLIDER_X2 - SLIDER_X1),
                0.0, 1.0);
        if (progress >= 0.999) {
            return ConnectedDisplayBlockEntity.UNLIMITED_MISSILE_DEFENSE_RANGE;
        }
        return ConnectedDisplayBlockEntity.MIN_MISSILE_DEFENSE_RANGE
                + Math.round(progress * (ConnectedDisplayBlockEntity.MAX_MISSILE_DEFENSE_RANGE
                - ConnectedDisplayBlockEntity.MIN_MISSILE_DEFENSE_RANGE) / 10.0) * 10.0;
    }

    public static double sliderX(double range) {
        double progress = (clamp(
                range,
                ConnectedDisplayBlockEntity.MIN_RANGE,
                ConnectedDisplayBlockEntity.MAX_RANGE
        ) - ConnectedDisplayBlockEntity.MIN_RANGE)
                / (ConnectedDisplayBlockEntity.MAX_RANGE
                - ConnectedDisplayBlockEntity.MIN_RANGE);
        return SLIDER_X1 + progress * (SLIDER_X2 - SLIDER_X1);
    }

    public static double defenseSliderX(double range) {
        if (range <= ConnectedDisplayBlockEntity.UNLIMITED_MISSILE_DEFENSE_RANGE
                || range >= ConnectedDisplayBlockEntity.MAX_MISSILE_DEFENSE_RANGE) {
            return SLIDER_X2;
        }
        double progress = (Math.max(ConnectedDisplayBlockEntity.MIN_MISSILE_DEFENSE_RANGE,
                Math.min(ConnectedDisplayBlockEntity.MAX_MISSILE_DEFENSE_RANGE, range))
                - ConnectedDisplayBlockEntity.MIN_MISSILE_DEFENSE_RANGE)
                / (ConnectedDisplayBlockEntity.MAX_MISSILE_DEFENSE_RANGE
                - ConnectedDisplayBlockEntity.MIN_MISSILE_DEFENSE_RANGE);
        return SLIDER_X1 + progress * (SLIDER_X2 - SLIDER_X1);
    }

    public static double proximitySliderRange(double canvasX) {
        double progress = clamp(
                (canvasX - SLIDER_X1) / (SLIDER_X2 - SLIDER_X1), 0.0, 1.0);
        return Math.round((ConnectedDisplayBlockEntity.MIN_MISSILE_PROXIMITY_RANGE
                + progress * (ConnectedDisplayBlockEntity.MAX_MISSILE_PROXIMITY_RANGE
                - ConnectedDisplayBlockEntity.MIN_MISSILE_PROXIMITY_RANGE)) * 2.0) / 2.0;
    }

    public static double proximitySliderX(double range) {
        double progress = (clamp(range,
                ConnectedDisplayBlockEntity.MIN_MISSILE_PROXIMITY_RANGE,
                ConnectedDisplayBlockEntity.MAX_MISSILE_PROXIMITY_RANGE)
                - ConnectedDisplayBlockEntity.MIN_MISSILE_PROXIMITY_RANGE)
                / (ConnectedDisplayBlockEntity.MAX_MISSILE_PROXIMITY_RANGE
                - ConnectedDisplayBlockEntity.MIN_MISSILE_PROXIMITY_RANGE);
        return SLIDER_X1 + progress * (SLIDER_X2 - SLIDER_X1);
    }

    private static Set<BlockPos> collect(
            Level level,
            BlockPos origin,
            Direction facing
    ) {
        Direction left = screenLeft(facing);
        Direction[] directions = {
                Direction.UP, Direction.DOWN, left, left.getOpposite()
        };
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(origin);
        queue.add(origin);
        while (!queue.isEmpty() && visited.size() <= 256) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : directions) {
                BlockPos next = current.relative(direction);
                BlockState state = level.getBlockState(next);
                if (!visited.contains(next)
                        && state.getBlock() instanceof ConnectedDisplayBlock
                        && state.getValue(ConnectedDisplayBlock.FACING) == facing) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    private static int run(
            Level level,
            BlockPos start,
            Direction direction,
            Direction facing
    ) {
        int count = 1;
        while (count < 32) {
            BlockState state = level.getBlockState(start.relative(direction, count));
            if (!(state.getBlock() instanceof ConnectedDisplayBlock)
                    || state.getValue(ConnectedDisplayBlock.FACING) != facing) {
                break;
            }
            count++;
        }
        return count;
    }

    private static double localAlong(
            Vec3 hit,
            BlockPos block,
            Direction direction
    ) {
        double coordinate = direction.getAxis() == Direction.Axis.X
                ? hit.x - block.getX()
                : hit.z - block.getZ();
        return direction.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? coordinate : 1.0 - coordinate;
    }

    private static int coordinateAlong(
            BlockPos pos,
            BlockPos origin,
            Direction direction
    ) {
        BlockPos offset = pos.subtract(origin);
        return offset.getX() * direction.getStepX()
                + offset.getY() * direction.getStepY()
                + offset.getZ() * direction.getStepZ();
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record ScreenHit(
            ConnectedDisplayBlockEntity controller,
            BlockPos topLeft,
            int wallWidth,
            int wallHeight,
            double canvasX,
            double canvasY
    ) {}

    public record RadarGeometry(double radiusX, double radiusY) {}
    public record RadarPoint(double x, double y) {}

    private ConnectedDisplayLayout() {}
}
