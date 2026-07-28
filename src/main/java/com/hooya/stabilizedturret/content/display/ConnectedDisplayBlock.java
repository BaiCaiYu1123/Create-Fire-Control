package com.hooya.stabilizedturret.content.display;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import com.simibubi.create.AllItems;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.hooya.stabilizedturret.network.OpenAutoDefenseDisplayPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import com.mojang.serialization.MapCodec;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** A model-only display whose casing changes when matching displays form a rectangle. */
public final class ConnectedDisplayBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final EnumProperty<DisplayConnection> CONNECTION =
            EnumProperty.create("connection", DisplayConnection.class);
    private static final int MAX_CONNECTED_DISPLAYS = 256;

    public ConnectedDisplayBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(CONNECTION, DisplayConnection.SINGLE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(ConnectedDisplayBlock::new);
    }

    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ConnectedDisplayBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level,
                                               BlockPos pos, Player player, BlockHitResult hit) {
        ConnectedDisplayBlockEntity display=findController(level,pos);
        if (display == null) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide
                    && player instanceof net.minecraft.server.level.ServerPlayer server) {
                PacketDistributor.sendToPlayer(
                        server, OpenAutoDefenseDisplayPayload.from(display));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        ConnectedDisplayLayout.ScreenHit screen =
                ConnectedDisplayLayout.locate(level, pos, hit.getLocation());
        if (screen == null) return InteractionResult.PASS;
        if (!level.isClientSide) display.handleScreenInteraction(screen);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            net.minecraft.world.level.Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        if (AllItems.WRENCH.isIn(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        ConnectedDisplayBlockEntity display = findController(level, pos);
        if (display == null || player.isShiftKeyDown()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        ConnectedDisplayLayout.ScreenHit screen =
                ConnectedDisplayLayout.locate(level, pos, hit.getLocation());
        if (screen == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) display.handleScreenInteraction(screen);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    public static @Nullable ConnectedDisplayBlockEntity findController(
            net.minecraft.world.level.Level level, BlockPos origin) {
        BlockState rootState=level.getBlockState(origin);
        if(!(rootState.getBlock() instanceof ConnectedDisplayBlock))return null;
        Direction facing=rootState.getValue(FACING), left=screenLeft(facing);
        Direction[] directions={Direction.UP,Direction.DOWN,left,left.getOpposite()};
        Set<BlockPos> visited=new HashSet<>(); ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        visited.add(origin);queue.add(origin); ConnectedDisplayBlockEntity fallback=null;
        while(!queue.isEmpty()&&visited.size()<=MAX_CONNECTED_DISPLAYS){
            BlockPos pos=queue.removeFirst();
            if(level.getBlockEntity(pos) instanceof ConnectedDisplayBlockEntity display){
                if(fallback==null)fallback=display;
                if(!display.getDevices().isEmpty()||display.isAutomaticAttack())return display;
            }
            for(Direction direction:directions){BlockPos next=pos.relative(direction);
                BlockState state=level.getBlockState(next);
                if(!visited.contains(next)&&state.getBlock() instanceof ConnectedDisplayBlock
                        &&state.getValue(FACING)==facing){visited.add(next);queue.add(next);}}
        }
        return fallback;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            net.minecraft.world.level.Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.CONNECTED_DISPLAY.get(),
                ConnectedDisplayBlockEntity::tick);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CONNECTION);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getNearestLookingDirection().getOpposite();
        if (facing.getAxis() == Direction.Axis.Y) {
            facing = context.getHorizontalDirection().getOpposite();
        }
        return updateConnection(defaultBlockState().setValue(FACING, facing),
                context.getLevel(), context.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return updateConnection(state, level, pos);
    }

    private BlockState updateConnection(BlockState state, LevelAccessor level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        Direction screenLeft = screenLeft(facing);
        Set<BlockPos> connected = collectConnected(level, pos, facing, screenLeft);
        if (connected.size() < 4) {
            return state.setValue(CONNECTION, DisplayConnection.SINGLE);
        }

        Direction screenRight = screenLeft.getOpposite();
        Set<Long> occupied = new HashSet<>();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos connectedPos : connected) {
            int x = coordinateAlong(connectedPos, pos, screenRight);
            int y = connectedPos.getY() - pos.getY();
            occupied.add(pack(x, y));
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }

        Rect rect = findBestRect(occupied, minX, maxX, minY, maxY);
        if (rect == null || !rect.contains(0, 0)) {
            return state.setValue(CONNECTION, DisplayConnection.SINGLE);
        }

        boolean top = rect.maxY == 0;
        boolean bottom = rect.minY == 0;
        boolean left = rect.minX == 0;
        boolean right = rect.maxX == 0;
        DisplayConnection connection;
        if (top && left) connection = DisplayConnection.TOP_LEFT;
        else if (top && right) connection = DisplayConnection.TOP_RIGHT;
        else if (bottom && left) connection = DisplayConnection.BOTTOM_LEFT;
        else if (bottom && right) connection = DisplayConnection.BOTTOM_RIGHT;
        else if (top) connection = DisplayConnection.TOP;
        else if (bottom) connection = DisplayConnection.BOTTOM;
        else if (left) connection = DisplayConnection.LEFT;
        else if (right) connection = DisplayConnection.RIGHT;
        else connection = DisplayConnection.CENTER;
        return state.setValue(CONNECTION, connection);
    }

    private Set<BlockPos> collectConnected(LevelAccessor level, BlockPos origin, Direction facing,
                                           Direction screenLeft) {
        Direction[] directions = {Direction.UP, Direction.DOWN, screenLeft, screenLeft.getOpposite()};
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(origin);
        queue.add(origin);
        while (!queue.isEmpty() && visited.size() <= MAX_CONNECTED_DISPLAYS) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : directions) {
                BlockPos next = current.relative(direction);
                if (!visited.contains(next) && canConnect(level, next, facing)) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        return visited.size() > MAX_CONNECTED_DISPLAYS ? Set.of(origin) : visited;
    }

    private boolean canConnect(LevelAccessor level, BlockPos pos, Direction facing) {
        BlockState state = level.getBlockState(pos);
        return state.is(this) && state.getValue(FACING) == facing;
    }

    private static Rect findBestRect(Set<Long> occupied, int minX, int maxX, int minY, int maxY) {
        Rect base = horizontalRun(occupied, 0);
        if (base != null) {
            int bottom = 0;
            int top = 0;
            for (int y = -1; y >= minY && sameHorizontal(base, horizontalRun(occupied, y)); y--) bottom = y;
            for (int y = 1; y <= maxY && sameHorizontal(base, horizontalRun(occupied, y)); y++) top = y;
            if (base.width() >= 2 && top - bottom + 1 >= 2) {
                return new Rect(base.minX, base.maxX, bottom, top);
            }
        }

        Rect vertical = verticalRun(occupied, 0);
        if (vertical == null) return null;
        int left = 0;
        int right = 0;
        for (int x = -1; x >= minX && sameVertical(vertical, verticalRun(occupied, x)); x--) left = x;
        for (int x = 1; x <= maxX && sameVertical(vertical, verticalRun(occupied, x)); x++) right = x;
        return right - left + 1 >= 2 && vertical.height() >= 2
                ? new Rect(left, right, vertical.minY, vertical.maxY) : null;
    }

    private static Rect horizontalRun(Set<Long> occupied, int y) {
        if (!occupied.contains(pack(0, y))) return null;
        int left = 0;
        int right = 0;
        while (occupied.contains(pack(left - 1, y))) left--;
        while (occupied.contains(pack(right + 1, y))) right++;
        return new Rect(left, right, y, y);
    }

    private static Rect verticalRun(Set<Long> occupied, int x) {
        if (!occupied.contains(pack(x, 0))) return null;
        int bottom = 0;
        int top = 0;
        while (occupied.contains(pack(x, bottom - 1))) bottom--;
        while (occupied.contains(pack(x, top + 1))) top++;
        return new Rect(x, x, bottom, top);
    }

    private static boolean sameHorizontal(Rect a, Rect b) {
        return a != null && b != null && a.minX == b.minX && a.maxX == b.maxX;
    }

    private static boolean sameVertical(Rect a, Rect b) {
        return a != null && b != null && a.minY == b.minY && a.maxY == b.maxY;
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

    private static int coordinateAlong(BlockPos pos, BlockPos origin, Direction direction) {
        BlockPos offset = pos.subtract(origin);
        return offset.getX() * direction.getStepX()
                + offset.getY() * direction.getStepY()
                + offset.getZ() * direction.getStepZ();
    }

    private static long pack(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private record Rect(int minX, int maxX, int minY, int maxY) {
        boolean contains(int x, int y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }

        int width() {
            return maxX - minX + 1;
        }

        int height() {
            return maxY - minY + 1;
        }
    }
}
