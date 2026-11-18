package com.hooya.stabilizedturret.content.missile;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** One complete, original-scale DF-26 model with invisible structural parts. */
public final class Df26BallisticMissileBlock extends BaseEntityBlock {
    public static final MapCodec<Df26BallisticMissileBlock> CODEC =
            simpleCodec(Df26BallisticMissileBlock::new);
    public static final DirectionProperty FACING = DirectionProperty.create("facing");
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 42);
    public static final IntegerProperty CROSS_U = IntegerProperty.create("cross_u", 0, 2);
    public static final IntegerProperty CROSS_V = IntegerProperty.create("cross_v", 0, 2);
    public static final int LENGTH = 43;
    private static final VoxelShape FULL_BLOCK = Shapes.block();
    private static final ThreadLocal<Boolean> REMOVING_STRUCTURE =
            ThreadLocal.withInitial(() -> false);

    public Df26BallisticMissileBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.UP)
                .setValue(PART, 0)
                .setValue(CROSS_U, 1)
                .setValue(CROSS_V, 1));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return isRoot(state)
                ? RenderShape.ENTITYBLOCK_ANIMATED
                : RenderShape.INVISIBLE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isRoot(state)
                ? new Df26BallisticMissileBlockEntity(pos, state)
                : null;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getNearestLookingDirection().getOpposite();
        BlockState rootState = defaultBlockState()
                .setValue(FACING, facing)
                .setValue(PART, 0)
                .setValue(CROSS_U, 1)
                .setValue(CROSS_V, 1);
        CollisionContext collision = context.getPlayer() == null
                ? CollisionContext.empty() : CollisionContext.of(context.getPlayer());
        for (int part = 0; part < LENGTH; part++) {
            for (int crossU = 0; crossU < 3; crossU++) {
                for (int crossV = 0; crossV < 3; crossV++) {
                    BlockPos target = structurePos(
                            context.getClickedPos(), facing, part, crossU, crossV);
                    BlockPlaceContext partContext = BlockPlaceContext.at(
                            context, target, facing.getOpposite());
                    BlockState partState = rootState
                            .setValue(PART, part)
                            .setValue(CROSS_U, crossU)
                            .setValue(CROSS_V, crossV);
                    if (context.getLevel().isOutsideBuildHeight(target)
                            || !context.getLevel().getWorldBorder().isWithinBounds(target)
                            || !context.getLevel().getBlockState(target).canBeReplaced(partContext)
                            || !context.getLevel().isUnobstructed(partState, target, collision)
                            || context.getPlayer() != null
                            && !context.getPlayer().mayUseItemAt(
                            target, facing.getOpposite(), context.getItemInHand())) {
                        return null;
                    }
                }
            }
        }
        return rootState;
    }

    @Override
    public void setPlacedBy(
            Level level,
            BlockPos pos,
            BlockState state,
            @Nullable LivingEntity placer,
            ItemStack stack
    ) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        Direction facing = state.getValue(FACING);
        for (int part = 0; part < LENGTH; part++) {
            for (int crossU = 0; crossU < 3; crossU++) {
                for (int crossV = 0; crossV < 3; crossV++) {
                    if (part == 0 && crossU == 1 && crossV == 1) continue;
                    BlockPos target = structurePos(pos, facing, part, crossU, crossV);
                    level.setBlock(target, state
                                    .setValue(PART, part)
                                    .setValue(CROSS_U, crossU)
                                    .setValue(CROSS_V, crossV),
                            Block.UPDATE_ALL);
                }
            }
        }
    }

    @Override
    protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context
    ) {
        return FULL_BLOCK;
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context
    ) {
        return getShape(state, level, pos, context);
    }

    @Override
    public BlockState playerWillDestroy(
            Level level, BlockPos pos, BlockState state, Player player
    ) {
        if (!level.isClientSide) {
            if (!player.getAbilities().instabuild) {
                popResource(level, structureRoot(pos, state),
                        new ItemStack(asItem()));
            }
            removeOtherParts(level, pos, state);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState newState,
            boolean movedByPiston
    ) {
        if (!level.isClientSide && !state.is(newState.getBlock())) {
            removeOtherParts(level, pos, state);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, CROSS_U, CROSS_V);
    }

    public static BlockPos structureRoot(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        return pos
                .relative(facing.getOpposite(), state.getValue(PART))
                .relative(crossUDirection(facing), 1 - state.getValue(CROSS_U))
                .relative(crossVDirection(facing), 1 - state.getValue(CROSS_V));
    }

    /** Removes the complete placed missile without dropping an item. */
    public static void removeStructure(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof Df26BallisticMissileBlock block)
                || REMOVING_STRUCTURE.get()) return;
        REMOVING_STRUCTURE.set(true);
        try {
            Direction facing = state.getValue(FACING);
            BlockPos root = structureRoot(pos, state);
            for (int part = 0; part < LENGTH; part++) {
                for (int crossU = 0; crossU < 3; crossU++) {
                    for (int crossV = 0; crossV < 3; crossV++) {
                        BlockPos target = structurePos(root, facing, part, crossU, crossV);
                        BlockState found = level.getBlockState(target);
                        if (found.is(block)
                                && found.getValue(FACING) == facing
                                && found.getValue(PART) == part
                                && found.getValue(CROSS_U) == crossU
                                && found.getValue(CROSS_V) == crossV) {
                            level.removeBlock(target, false);
                        }
                    }
                }
            }
        } finally {
            REMOVING_STRUCTURE.set(false);
        }
    }

    private void removeOtherParts(Level level, BlockPos removedPos, BlockState state) {
        if (REMOVING_STRUCTURE.get()) return;
        REMOVING_STRUCTURE.set(true);
        try {
            Direction facing = state.getValue(FACING);
            BlockPos root = structureRoot(removedPos, state);
            for (int part = 0; part < LENGTH; part++) {
                for (int crossU = 0; crossU < 3; crossU++) {
                    for (int crossV = 0; crossV < 3; crossV++) {
                        BlockPos target = structurePos(
                                root, facing, part, crossU, crossV);
                        if (target.equals(removedPos)) continue;
                        BlockState found = level.getBlockState(target);
                        if (found.is(this)
                                && found.getValue(FACING) == facing
                                && found.getValue(PART) == part
                                && found.getValue(CROSS_U) == crossU
                                && found.getValue(CROSS_V) == crossV) {
                            level.removeBlock(target, false);
                        }
                    }
                }
            }
        } finally {
            REMOVING_STRUCTURE.set(false);
        }
    }

    private static boolean isRoot(BlockState state) {
        return state.getValue(PART) == 0
                && state.getValue(CROSS_U) == 1
                && state.getValue(CROSS_V) == 1;
    }

    private static BlockPos structurePos(
            BlockPos root,
            Direction facing,
            int part,
            int crossU,
            int crossV
    ) {
        return root.relative(facing, part)
                .relative(crossUDirection(facing), crossU - 1)
                .relative(crossVDirection(facing), crossV - 1);
    }

    private static Direction crossUDirection(Direction facing) {
        return facing.getAxis() == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
    }

    private static Direction crossVDirection(Direction facing) {
        return facing.getAxis() == Direction.Axis.Y ? Direction.SOUTH : Direction.UP;
    }
}
