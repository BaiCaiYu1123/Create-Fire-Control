package com.hooya.stabilizedturret.compat.yuushya;

import com.hooya.stabilizedturret.content.cannon.compat.YuushyaMediumCannonBarrelBlock;
import com.hooya.stabilizedturret.mixin.BlockEntityTypeAccessor;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import com.yuushya.modelling.blockentity.AbstractTransformBlock;
import com.yuushya.modelling.blockentity.itemblock.ItemBlockEntity;
import com.yuushya.modelling.registries.BlockEntityRegistry;
import com.yuushya.modelling.registries.BlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.MediumcannonBlock;

import java.util.HashSet;
import java.util.Set;

public final class YuushyaCannonCompat {
    public static final String CANNON_BEHAVIOR_TAG = "StabilizedTurretCannonBehavior";

    public static void initialize() {
        if (ModCbcContent.YUUSHYA_MEDIUM_CANNON_BARREL == null) {
            return;
        }
        BlockEntityType<?> type = BlockEntityRegistry.ITEM_BLOCK_ENTITY.get();
        Set<Block> validBlocks = new HashSet<>(type.getValidBlocks());
        validBlocks.add(ModCbcContent.YUUSHYA_MEDIUM_CANNON_BARREL.get());
        ((BlockEntityTypeAccessor) (Object) type).stabilizedTurret$setValidBlocks(validBlocks);
    }

    public static boolean isBarrel(BlockState state) {
        return ModCbcContent.YUUSHYA_MEDIUM_CANNON_BARREL != null
                && state.is(ModCbcContent.YUUSHYA_MEDIUM_CANNON_BARREL.get());
    }

    public static void setBarrel(ServerLevel level, BlockPos pos, boolean enabled) {
        BlockState sourceState = level.getBlockState(pos);
        BlockEntity sourceEntity = level.getBlockEntity(pos);
        if (!(sourceEntity instanceof ItemBlockEntity)) {
            return;
        }
        boolean alreadyEnabled = isBarrel(sourceState);
        if (enabled == alreadyEnabled) {
            return;
        }
        if (enabled && !sourceState.is(BlockRegistry.ITEM_BLOCK.get())) {
            return;
        }

        CompoundTag data = sourceEntity.saveWithoutMetadata(level.registryAccess());
        BlockState targetState;
        if (enabled) {
            targetState = copySharedProperties(sourceState, ModCbcContent.YUUSHYA_MEDIUM_CANNON_BARREL.get().defaultBlockState());
            targetState = targetState.setValue(
                    YuushyaMediumCannonBarrelBlock.CANNON_AXIS,
                    inferCannonAxis(level, pos, sourceState)
            );
        } else {
            data.remove(CANNON_BEHAVIOR_TAG);
            targetState = copySharedProperties(sourceState, BlockRegistry.ITEM_BLOCK.get().defaultBlockState());
        }

        level.setBlock(pos, targetState, Block.UPDATE_ALL);
        BlockEntity targetEntity = level.getBlockEntity(pos);
        if (targetEntity instanceof ItemBlockEntity) {
            targetEntity.loadWithComponents(data, level.registryAccess());
            targetEntity.setChanged();
            level.sendBlockUpdated(pos, targetState, targetState, Block.UPDATE_ALL);
            if (enabled) {
                MediumcannonBlock.onPlace(level, pos);
            }
        }
    }

    private static Direction.Axis inferCannonAxis(ServerLevel level, BlockPos pos, BlockState sourceState) {
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() == Direction.Axis.Y) {
                continue;
            }
            BlockState neighborState = level.getBlockState(pos.relative(direction));
            if (neighborState.getBlock() instanceof MediumcannonBlock cannonBlock
                    && cannonBlock.getFacing(neighborState).getAxis() == direction.getAxis()
                    && cannonBlock.canConnectToSide(neighborState, direction.getOpposite())) {
                return direction.getAxis();
            }
        }
        return sourceState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING).getAxis();
    }

    private static BlockState copySharedProperties(BlockState source, BlockState target) {
        BlockState result = target;
        for (Property<?> property : source.getProperties()) {
            if (result.hasProperty(property)) {
                result = copyProperty(source, result, property);
            }
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState copyProperty(BlockState source, BlockState target, Property property) {
        return target.setValue(property, source.getValue(property));
    }

    private YuushyaCannonCompat() {
    }
}
