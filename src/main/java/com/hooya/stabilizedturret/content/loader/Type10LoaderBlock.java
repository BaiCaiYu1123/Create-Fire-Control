package com.hooya.stabilizedturret.content.loader;

import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Redstone-driven thirteen-round Type 10 bustle autoloader. */
public class Type10LoaderBlock extends BaseEntityBlock {
    public static final MapCodec<Type10LoaderBlock> CODEC = simpleCodec(Type10LoaderBlock::new);

    public Type10LoaderBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(
                HorizontalDirectionalBlock.FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HorizontalDirectionalBlock.FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(HorizontalDirectionalBlock.FACING,
                context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(HorizontalDirectionalBlock.FACING,
                rotation.rotate(state.getValue(HorizontalDirectionalBlock.FACING)));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
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
        if (!LoaderAmmunition.isSupported(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof Type10LoaderBlockEntity loader) {
            if (loader.insertRound(stack) && !player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onProjectileHit(Level level, BlockState state, BlockHitResult hit,
                                Projectile projectile) {
        if (MzLoaderDetonation.isCbcProjectile(projectile)) {
            MzLoaderDetonation.tryDetonate(level, hit.getBlockPos());
        }
        super.onProjectileHit(level, state, hit, projectile);
    }

    @Override
    public void wasExploded(Level level, BlockPos pos, Explosion explosion) {
        MzLoaderDetonation.tryDetonateByExplosion(level, pos);
        super.wasExploded(level, pos, explosion);
    }

    @Override
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos,
                                   int eventId, int eventParam) {
        if (eventId == MzLoaderDetonation.SPALL_DETONATION_BLOCK_EVENT) {
            return MzLoaderDetonation.tryDetonate(level, pos);
        }
        return super.triggerEvent(state, level, pos, eventId, eventParam);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Type10LoaderBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, ModBlockEntities.TYPE_10_LOADER.get(),
                Type10LoaderBlockEntity::serverTick);
    }
}
