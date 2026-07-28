package com.hooya.stabilizedturret.content.turbine;

import com.mojang.serialization.MapCodec;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;
import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * One item places three real blocks. Keeping every mesh slice in its own cell
 * is required because Sable clamps each physical collider to that block cell.
 */
public final class GasTurbineBlock extends KineticBlock
        implements IBE<GasTurbineBlockEntity>, BlockSubLevelCollisionShape {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<GasTurbinePart> PART =
            EnumProperty.create("part", GasTurbinePart.class);
    private static final ThreadLocal<Boolean> REMOVING_STRUCTURE =
            ThreadLocal.withInitial(() -> false);

    public GasTurbineBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, GasTurbinePart.CENTER));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(GasTurbineBlock::new);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos center = context.getClickedPos();
        if (!canOccupy(context, center.relative(facing))
                || !canOccupy(context, center.relative(facing.getOpposite()))) {
            return null;
        }
        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(PART, GasTurbinePart.CENTER);
    }

    private static boolean canOccupy(BlockPlaceContext context, BlockPos pos) {
        return context.getLevel().getWorldBorder().isWithinBounds(pos)
                && context.getLevel().getBlockState(pos).canBeReplaced();
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == GasTurbinePart.NEGATIVE
                ? getBlockEntityType().create(pos, state)
                : null;
    }

    @Override
    public Class<GasTurbineBlockEntity> getBlockEntityClass() {
        return GasTurbineBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends GasTurbineBlockEntity> getBlockEntityType() {
        return ModBlockEntities.GAS_TURBINE.get();
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
        if (level.isClientSide) {
            return;
        }
        Direction facing = state.getValue(FACING);
        level.setBlock(
                pos.relative(facing.getOpposite()),
                state.setValue(PART, GasTurbinePart.NEGATIVE),
                Block.UPDATE_ALL
        );
        level.setBlock(
                pos.relative(facing),
                state.setValue(PART, GasTurbinePart.POSITIVE),
                Block.UPDATE_ALL
        );
    }

    @Override
    protected VoxelShape getShape(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context
    ) {
        return collisionShape(state);
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context
    ) {
        return collisionShape(state);
    }

    @Override
    public VoxelShape getSubLevelCollisionShape(BlockGetter level, BlockState state) {
        return collisionShape(state);
    }

    private static VoxelShape collisionShape(BlockState state) {
        return GasTurbineCollisionShapes.get(
                state.getValue(PART),
                state.getValue(FACING)
        );
    }

    @Override
    public boolean hasShaftTowards(
            net.minecraft.world.level.LevelReader level,
            BlockPos pos,
            BlockState state,
            Direction direction
    ) {
        return state.getValue(PART) == GasTurbinePart.NEGATIVE
                && direction.getAxis() == outputAxis(state);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return outputAxis(state);
    }

    public static Direction.Axis outputAxis(BlockState state) {
        return counterClockwiseOutput(state).getAxis();
    }

    public static Direction counterClockwiseOutput(BlockState state) {
        return state.getValue(FACING).getCounterClockWise();
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        if (state.getValue(PART) != GasTurbinePart.NEGATIVE
                || !AllItems.WRENCH.isIn(stack)
                || hit.getDirection().getAxis() != outputAxis(state)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // Wrench clicks are consumed by GasTurbineBindingHandler and are
        // reserved for connecting the advanced wireless signal processor.
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState newState,
            boolean movedByPiston
    ) {
        if (!state.is(newState.getBlock())
                && !level.isClientSide
                && !REMOVING_STRUCTURE.get()) {
            REMOVING_STRUCTURE.set(true);
            try {
                BlockPos center = centerPos(pos, state);
                Direction facing = state.getValue(FACING);
                removeMatchingPart(level, center, facing, GasTurbinePart.CENTER);
                removeMatchingPart(
                        level,
                        center.relative(facing.getOpposite()),
                        facing,
                        GasTurbinePart.NEGATIVE
                );
                removeMatchingPart(
                        level,
                        center.relative(facing),
                        facing,
                        GasTurbinePart.POSITIVE
                );
            } finally {
                REMOVING_STRUCTURE.set(false);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private static BlockPos centerPos(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        return switch (state.getValue(PART)) {
            case NEGATIVE -> pos.relative(facing);
            case CENTER -> pos;
            case POSITIVE -> pos.relative(facing.getOpposite());
        };
    }

    private static void removeMatchingPart(
            Level level,
            BlockPos pos,
            Direction facing,
            GasTurbinePart part
    ) {
        BlockState other = level.getBlockState(pos);
        if (other.getBlock() instanceof GasTurbineBlock
                && other.getValue(FACING) == facing
                && other.getValue(PART) == part) {
            level.removeBlock(pos, false);
        }
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
        builder.add(FACING, PART);
    }
}
