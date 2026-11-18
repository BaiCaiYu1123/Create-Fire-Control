package com.hooya.stabilizedturret.content.tiger;

import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;
import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.shapes.*;
import org.jetbrains.annotations.Nullable;

/** One engine cell, with two independent kinetic ports sharing its centre. */
public final class TigerIEngineBlock extends KineticBlock
        implements IBE<TigerIEngineBlockEntity>, BlockSubLevelCollisionShape {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<TigerIEnginePart> PART =
            EnumProperty.create("part", TigerIEnginePart.class);
    private static final ThreadLocal<Boolean> REMOVING = ThreadLocal.withInitial(() -> false);

    public TigerIEngineBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)
                .setValue(PART, TigerIEnginePart.CENTER));
    }

    @Override protected MapCodec<? extends Block> codec() { return simpleCodec(TigerIEngineBlock::new); }

    public static BlockPos offset(TigerIEnginePart part, Direction facing) {
        Direction right = facing.getClockWise();
        return BlockPos.ZERO.relative(right, part.x);
    }
    public static BlockPos controllerPos(BlockPos pos, BlockState state) {
        return pos.subtract(offset(state.getValue(PART), state.getValue(FACING)));
    }

    public static @Nullable Direction outputDirection(BlockState state) {
        TigerIEnginePart part = state.getValue(PART);
        if (!part.isOutput()) return null;
        Direction right = state.getValue(FACING).getClockWise();
        return part.x < 0 ? right.getOpposite() : right;
    }

    @Override public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                                     @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        // LEFT/RIGHT state values remain readable for migration of the earlier three-cell layout.
    }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        var part = state.getValue(PART);
        return part.isController() || part.isOutput() ? getBlockEntityType().create(pos, state) : null;
    }
    @Override public Class<TigerIEngineBlockEntity> getBlockEntityClass() { return TigerIEngineBlockEntity.class; }
    @Override public BlockEntityType<? extends TigerIEngineBlockEntity> getBlockEntityType() {
        return ModBlockEntities.TIGER_I_ENGINE.get();
    }
    @Override public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction direction) {
        return state.getValue(PART).isController() && direction.getAxis() == getRotationAxis(state);
    }
    @Override public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getClockWise().getAxis();
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return sourceCellShape(state);
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }
    @Override public VoxelShape getSubLevelCollisionShape(BlockGetter level, BlockState state) {
        return sourceCellShape(state);
    }
    private static VoxelShape sourceCellShape(BlockState state) {
        Direction output = outputDirection(state);
        if (output == null) return Shapes.block();
        return switch (output) {
            case WEST -> Block.box(6, 6, 6, 16, 10, 10);
            case EAST -> Block.box(0, 6, 6, 10, 10, 10);
            case NORTH -> Block.box(6, 6, 6, 10, 10, 16);
            case SOUTH -> Block.box(6, 6, 0, 10, 10, 10);
            default -> Shapes.empty();
        };
    }
    public static void clearLegacyOutputs(Level level, BlockPos center, Direction facing) {
        REMOVING.set(true);
        try {
            for (var part : TigerIEnginePart.values()) {
                if (!part.isOutput()) continue;
                BlockPos cell=center.offset(offset(part,facing));
                BlockState state=level.getBlockState(cell);
                if (state.getBlock() instanceof TigerIEngineBlock && state.getValue(PART)==part
                        && state.getValue(FACING)==facing) level.removeBlock(cell,false);
            }
        } finally { REMOVING.set(false); }
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!state.is(replacement.getBlock()) && !level.isClientSide && !REMOVING.get()) {
            REMOVING.set(true);
            try {
                BlockPos controller = controllerPos(pos, state);
                Direction facing = state.getValue(FACING);
                for (var part : TigerIEnginePart.values()) {
                    BlockPos cell = controller.offset(offset(part, facing));
                    BlockState other = level.getBlockState(cell);
                    if (!cell.equals(pos) && other.is(this) && other.getValue(FACING) == facing
                            && other.getValue(PART) == part) level.removeBlock(cell, false);
                }
            } finally { REMOVING.set(false); }
        }
        super.onRemove(state, level, pos, replacement, moved);
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) {
        Direction facing = state.getValue(FACING);
        Direction mirroredFacing = mirror.mirror(facing);
        // A reflection exchanges the two sides as well as rotating the facing.
        int mirroredX = mirror.mirror(facing.getClockWise()) == mirroredFacing.getClockWise()
                ? state.getValue(PART).x : -state.getValue(PART).x;
        for (var part : TigerIEnginePart.values())
            if (part.x == mirroredX)
                return state.setValue(FACING, mirroredFacing).setValue(PART, part);
        return state;
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }
}
