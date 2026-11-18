package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.mojang.serialization.MapCodec;

/** Loads without Drive By Wire; the optional mixin supplies its multi-channel interface. */
public class WirelessSignalProcessorBlock extends BaseEntityBlock {
    public static final MapCodec<WirelessSignalProcessorBlock> CODEC = simpleCodec(WirelessSignalProcessorBlock::new);
    public WirelessSignalProcessorBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context) {
        return Block.box(0,0,0,16,8,16);
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WirelessSignalProcessorBlockEntity(pos, state);
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModBlockEntities.WIRELESS_SIGNAL_PROCESSOR.get(),
                WirelessSignalProcessorBlockEntity::serverTick);
    }
}
