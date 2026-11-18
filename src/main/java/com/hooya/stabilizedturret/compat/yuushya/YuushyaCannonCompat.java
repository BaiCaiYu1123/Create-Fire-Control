package com.hooya.stabilizedturret.compat.yuushya;

import com.hooya.stabilizedturret.content.cannon.compat.YuushyaMediumCannonBarrelBlock;
import com.hooya.stabilizedturret.content.cannon.compat.YuushyaShowMediumCannonBarrelBlock;
import com.hooya.stabilizedturret.mixin.BlockEntityTypeAccessor;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import com.yuushya.modelling.blockentity.AbstractTransformBlock;
import com.yuushya.modelling.blockentity.itemblock.ItemBlockEntity;
import com.yuushya.modelling.blockentity.showblock.ShowBlockEntity;
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
        addValidBlock(
                BlockEntityRegistry.ITEM_BLOCK_ENTITY.get(),
                ModCbcContent.YUUSHYA_MEDIUM_CANNON_BARREL.get()
        );
        addValidBlock(
                BlockEntityRegistry.SHOW_BLOCK_ENTITY.get(),
                ModCbcContent.YUUSHYA_SHOW_MEDIUM_CANNON_BARREL.get()
        );
    }

    public static boolean isBarrel(BlockState state) {
        return ModCbcContent.YUUSHYA_MEDIUM_CANNON_BARREL != null
                && (state.is(ModCbcContent.YUUSHYA_MEDIUM_CANNON_BARREL.get())
                || state.is(ModCbcContent.YUUSHYA_SHOW_MEDIUM_CANNON_BARREL.get()));
    }

    public static void setBarrel(ServerLevel level, BlockPos pos, boolean enabled) {
        BlockState sourceState = level.getBlockState(pos);
        BlockEntity sourceEntity = level.getBlockEntity(pos);
        boolean itemBlock = sourceEntity instanceof ItemBlockEntity;
        boolean showBlock = sourceEntity instanceof ShowBlockEntity;
        if (!itemBlock && !showBlock) {
            return;
        }
        boolean alreadyEnabled = isBarrel(sourceState);
        if (enabled == alreadyEnabled) {
            return;
        }
        if (enabled && !(itemBlock && sourceState.is(BlockRegistry.ITEM_BLOCK.get()))
                && !(showBlock && sourceState.is(BlockRegistry.SHOW_BLOCK.get()))) {
            return;
        }

        CompoundTag data = sourceEntity.saveWithoutMetadata(level.registryAccess());
        BlockState targetState;
        if (enabled) {
            Block targetBlock = itemBlock
                    ? ModCbcContent.YUUSHYA_MEDIUM_CANNON_BARREL.get()
                    : ModCbcContent.YUUSHYA_SHOW_MEDIUM_CANNON_BARREL.get();
            targetState = copySharedProperties(sourceState, targetBlock.defaultBlockState());
            Direction.Axis cannonAxis = inferCannonAxis(level, pos, sourceState);
            targetState = itemBlock
                    ? targetState.setValue(YuushyaMediumCannonBarrelBlock.CANNON_AXIS, cannonAxis)
                    : targetState.setValue(YuushyaShowMediumCannonBarrelBlock.CANNON_AXIS, cannonAxis);
        } else {
            data.remove(CANNON_BEHAVIOR_TAG);
            Block targetBlock = itemBlock
                    ? BlockRegistry.ITEM_BLOCK.get()
                    : BlockRegistry.SHOW_BLOCK.get();
            targetState = copySharedProperties(sourceState, targetBlock.defaultBlockState());
        }

        level.setBlock(pos, targetState, Block.UPDATE_ALL);
        BlockEntity targetEntity = level.getBlockEntity(pos);
        if (targetEntity instanceof ItemBlockEntity || targetEntity instanceof ShowBlockEntity) {
            targetEntity.loadWithComponents(data, level.registryAccess());
            targetEntity.setChanged();
            level.sendBlockUpdated(pos, targetState, targetState, Block.UPDATE_ALL);
            if (enabled) {
                MediumcannonBlock.onPlace(level, pos);
            }
        }
    }

    private static void addValidBlock(BlockEntityType<?> type, Block block) {
        Set<Block> validBlocks = new HashSet<>(type.getValidBlocks());
        validBlocks.add(block);
        ((BlockEntityTypeAccessor) (Object) type).stabilizedTurret$setValidBlocks(validBlocks);
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
