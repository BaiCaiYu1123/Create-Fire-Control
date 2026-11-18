package com.hooya.stabilizedturret.content.diesel;

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

/** Five physical T cells; non-physical model slices are drawn by the controller renderer. */
public final class Ztz99aDieselEngineBlock extends KineticBlock
        implements IBE<Ztz99aDieselEngineBlockEntity>, BlockSubLevelCollisionShape {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Ztz99aDieselEnginePart> PART =
            EnumProperty.create("part", Ztz99aDieselEnginePart.class);
    private static final ThreadLocal<Boolean> REMOVING = ThreadLocal.withInitial(() -> false);

    public Ztz99aDieselEngineBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)
                .setValue(PART, Ztz99aDieselEnginePart.P0_P0_P0));
    }

    @Override protected MapCodec<? extends Block> codec() { return simpleCodec(Ztz99aDieselEngineBlock::new); }

    public static BlockPos offset(Ztz99aDieselEnginePart part, Direction facing) {
        Direction right = facing.getClockWise();
        return BlockPos.ZERO.relative(right, part.x).above(part.y).relative(facing, -part.z);
    }

    public static BlockPos controllerPos(BlockPos pos, BlockState state) {
        return pos.subtract(offset(state.getValue(PART), state.getValue(FACING)));
    }

    public static @Nullable Direction outputDirection(BlockState state) {
        Ztz99aDieselEnginePart part = state.getValue(PART);
        if (!part.isOutput()) return null;
        Direction right = state.getValue(FACING).getClockWise();
        return part.x < 0 ? right.getOpposite() : right;
    }

    @Override public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        for (Ztz99aDieselEnginePart part : Ztz99aDieselEnginePart.values()) {
            if (!part.isOccupied()) continue;
            BlockPos pos = context.getClickedPos().offset(offset(part, facing));
            if (!context.getLevel().isInWorldBounds(pos)
                    || !context.getLevel().getWorldBorder().isWithinBounds(pos)
                    || (!part.isController() && !context.getLevel().getBlockState(pos).canBeReplaced())) return null;
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                                     @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        for (Ztz99aDieselEnginePart part : Ztz99aDieselEnginePart.values()) {
            if (part.isOccupied() && !part.isController()) level.setBlock(pos.offset(offset(part, state.getValue(FACING))),
                    state.setValue(PART, part), Block.UPDATE_ALL);
        }
    }

    /** Remove only this engine's obsolete model placeholders, without the multiblock teardown hook. */
    public boolean removeLegacyModelCells(Level level, BlockPos controller, Direction facing) {
        boolean complete = true;
        boolean removing = REMOVING.get();
        REMOVING.set(true);
        try {
            for (var part : Ztz99aDieselEnginePart.values()) {
                if (part.isOccupied()) continue;
                BlockPos cell = controller.offset(offset(part, facing));
                if (!level.hasChunkAt(cell)) { complete = false; continue; }
                BlockState state = level.getBlockState(cell);
                if (state.is(this) && state.getValue(FACING) == facing && state.getValue(PART) == part)
                    level.removeBlock(cell, false);
            }
        } finally { REMOVING.set(removing); }
        return complete;
    }

    @Override protected RenderShape getRenderShape(BlockState state) {
        return state.getValue(PART).isOccupied() ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }
    @Override protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return !state.getValue(PART).isOccupied() || super.canBeReplaced(state, context);
    }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        var part = state.getValue(PART);
        return part.isController() || part.isOutput() ? getBlockEntityType().create(pos, state) : null;
    }
    @Override public Class<Ztz99aDieselEngineBlockEntity> getBlockEntityClass() { return Ztz99aDieselEngineBlockEntity.class; }
    @Override public BlockEntityType<? extends Ztz99aDieselEngineBlockEntity> getBlockEntityType() {
        return ModBlockEntities.ZTZ99A_DIESEL_ENGINE.get();
    }
    @Override public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction direction) {
        return direction == outputDirection(state);
    }
    @Override public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getClockWise().getAxis();
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(PART).isOccupied()
                ? Ztz99aDieselEngineCollisionShapes.getOutline(state.getValue(PART), state.getValue(FACING)) : Shapes.empty();
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Ztz99aDieselEngineCollisionShapes.getCollision(state.getValue(PART));
    }
    @Override public VoxelShape getSubLevelCollisionShape(BlockGetter level, BlockState state) {
        return Ztz99aDieselEngineCollisionShapes.getCollision(state.getValue(PART));
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (state.getValue(PART).isOccupied() && !state.is(replacement.getBlock()) && !level.isClientSide && !REMOVING.get()) {
            REMOVING.set(true);
            try {
                BlockPos controller = controllerPos(pos, state);
                Direction facing = state.getValue(FACING);
                for (var part : Ztz99aDieselEnginePart.values()) {
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
        var original = state.getValue(PART);
        for (var part : Ztz99aDieselEnginePart.values())
            if (part.x == mirroredX && part.y == original.y && part.z == original.z)
                return state.setValue(FACING, mirroredFacing).setValue(PART, part);
        return state;
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }
}
