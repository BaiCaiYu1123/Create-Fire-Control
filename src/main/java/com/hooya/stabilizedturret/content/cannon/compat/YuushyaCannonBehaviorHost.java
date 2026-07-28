package com.hooya.stabilizedturret.content.cannon.compat;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.yuushya.modelling.registries.BlockEntityRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import rbasamoyai.createbigcannons.cannons.ItemCannonBehavior;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.MediumcannonBlock;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.MediumcannonBlockEntity;

import java.util.List;

public final class YuushyaCannonBehaviorHost extends MediumcannonBlockEntity {
    private final BlockEntity owner;
    private ItemCannonBehavior behavior;

    public YuushyaCannonBehaviorHost(BlockEntity owner) {
        super(BlockEntityRegistry.ITEM_BLOCK_ENTITY.get(), owner.getBlockPos(), owner.getBlockState());
        this.owner = owner;
        if (owner.getLevel() != null) {
            setLevel(owner.getLevel());
        }
        initialize();
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behavior = new ItemCannonBehavior(this);
        behaviours.add(behavior);
    }

    public ItemCannonBehavior behavior() {
        if (owner.getLevel() != null && getLevel() != owner.getLevel()) {
            setLevel(owner.getLevel());
        }
        setBlockState(owner.getBlockState());
        synchronizeConnections();
        return behavior;
    }

    private void synchronizeConnections() {
        if (!(owner.getLevel() instanceof ServerLevel level)
                || !(owner.getBlockState().getBlock() instanceof MediumcannonBlock ownBlock)
                || level.getBlockEntity(owner.getBlockPos()) != owner) {
            return;
        }

        BlockState ownState = owner.getBlockState();
        Direction.Axis axis = ownBlock.getFacing(ownState).getAxis();
        for (Direction direction : Direction.values()) {
            boolean connected = false;
            if (direction.getAxis() == axis) {
                BlockPos neighborPos = owner.getBlockPos().relative(direction);
                BlockState neighborState = level.getBlockState(neighborPos);
                if (neighborState.getBlock() instanceof MediumcannonBlock neighborBlock) {
                    connected = ownBlock.canConnectToSide(ownState, direction)
                            && neighborBlock.canConnectToSide(neighborState, direction.getOpposite())
                            && ownBlock.getMediumcannonMaterialInLevel(
                                    level, ownState, owner.getBlockPos()
                            ) == neighborBlock.getMediumcannonMaterialInLevel(
                                    level, neighborState, neighborPos
                            );
                }
            }
            behavior.setConnectedFace(direction, connected);
            if (!connected) {
                behavior.setWelded(direction, false);
            }
        }
    }

    @Override
    public void setChanged() {
        super.setChanged();
        owner.setChanged();
    }
}
