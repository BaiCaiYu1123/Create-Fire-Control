package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.registry.ModBlocks;
import com.hooya.stabilizedturret.registry.ModItems;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllSoundEvents;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;

public final class WirelessLecternControllerBlock extends BaseEntityBlock {
    public static final MapCodec<WirelessLecternControllerBlock> CODEC = simpleCodec(WirelessLecternControllerBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public WirelessLecternControllerBlock(Properties properties) {
        super(properties);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(FACING); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state) { return new WirelessLecternControllerBlockEntity(pos,state); }
    @Override protected BlockState rotate(BlockState state,Rotation rotation) { return state.setValue(FACING,rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state,Mirror mirror) { return rotate(state,mirror.getRotation(state.getValue(FACING))); }
    private static BlockState lectern(BlockState state) { return Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING,state.getValue(FACING)); }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context) {
        return lectern(state).getShape(level,pos,context);
    }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context) {
        return lectern(state).getCollisionShape(level,pos,context);
    }
    @Override protected boolean hasAnalogOutputSignal(BlockState state) { return true; }
    @Override protected int getAnalogOutputSignal(BlockState state,Level level,BlockPos pos) { return 15; }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit) {
        if (player.isSpectator()) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            if (!player.mayBuild()) return InteractionResult.PASS;
            if (!level.isClientSide) {
                AllSoundEvents.CONTROLLER_TAKE.playOnServer(level,pos);
                level.setBlockAndUpdate(pos,lectern(state));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!WirelessLecternControllerBlockEntity.playerInRange(player,level,pos)) return InteractionResult.PASS;
        if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof WirelessLecternControllerBlockEntity mounted)
            ControllerSessions.useLectern(server,mounted);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moving) {
        if (!state.is(next.getBlock())) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof WirelessLecternControllerBlockEntity mounted) {
                ControllerSessions.stopLectern(level,pos);
                if (!mounted.controller().isEmpty()) Block.popResource(level,pos,mounted.controller().copy());
            }
            super.onRemove(state,level,pos,next,moving);
        }
    }
    @Override public ItemStack getCloneItemStack(BlockState state,HitResult hit,LevelReader level,BlockPos pos,Player player) {
        return new ItemStack(Items.LECTERN);
    }

    /** Called before handheld use, matching the advanced controller's empty-lectern interaction. */
    public static boolean placeController(ServerPlayer player,InteractionHand hand,BlockPos pos) {
        return placeController(player,hand,pos,true);
    }
    public static boolean placeController(ServerPlayer player,InteractionHand hand,BlockPos pos,boolean feedback) {
        var level=player.serverLevel();var state=level.getBlockState(pos);
        if (!player.mayBuild() || feedback && player.isShiftKeyDown() || !state.is(Blocks.LECTERN) || state.getValue(LecternBlock.HAS_BOOK)) return false;
        var held=player.getItemInHand(hand);
        if (!held.is(ModItems.WIRELESS_CONTROLLER.get())) return false;
        var mounted=ModBlocks.WIRELESS_LECTERN_CONTROLLER.get().defaultBlockState().setValue(FACING,state.getValue(LecternBlock.FACING));
        var placed=held.copyWithCount(1);
        if (!level.setBlockAndUpdate(pos,mounted)) return false;
        if (level.getBlockEntity(pos) instanceof WirelessLecternControllerBlockEntity blockEntity) {
            blockEntity.setController(placed);
            if (!player.isCreative()) held.shrink(1);
            player.containerMenu.broadcastChanges();if (feedback) AllSoundEvents.CONTROLLER_PUT.playOnServer(level,pos);
        }
        return true;
    }
    public static void swapControllers(ServerPlayer player,InteractionHand hand,WirelessLecternControllerBlockEntity mounted) {
        if (!player.mayBuild()) return;
        var held=player.getItemInHand(hand);var previous=mounted.controller().copy();
        mounted.setController(held);player.setItemInHand(hand,previous);
        player.containerMenu.broadcastChanges();AllSoundEvents.CONTROLLER_PUT.playOnServer(player.level(),mounted.getBlockPos());
    }
}
