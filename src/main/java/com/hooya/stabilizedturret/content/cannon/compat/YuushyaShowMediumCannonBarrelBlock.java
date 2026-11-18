package com.hooya.stabilizedturret.content.cannon.compat;

import com.simibubi.create.api.contraption.transformable.TransformableBlock;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.yuushya.modelling.blockentity.showblock.ShowBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import rbasamoyai.createbigcannons.crafting.casting.CannonCastShape;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.MediumcannonBlock;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.material.MediumcannonMaterial;
import riftyboi.cbcmodernwarfare.crafting.casting.CBCModernWarfareCastShapes;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareMediumcannonMaterials;

public final class YuushyaShowMediumCannonBarrelBlock extends ShowBlock
        implements MediumcannonBlock, TransformableBlock {
    public static final EnumProperty<Direction.Axis> CANNON_AXIS =
            EnumProperty.create("cannon_axis", Direction.Axis.class);

    public YuushyaShowMediumCannonBarrelBlock(BlockBehaviour.Properties properties) {
        super(properties, 1);
        registerDefaultState(defaultBlockState().setValue(CANNON_AXIS, Direction.Axis.Z));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CANNON_AXIS);
    }

    @Override
    public MediumcannonMaterial getMediumcannonMaterial() {
        return CBCModernWarfareMediumcannonMaterials.NETHERSTEEL;
    }

    @Override
    public Direction getFacing(BlockState state) {
        return Direction.get(Direction.AxisDirection.POSITIVE, state.getValue(CANNON_AXIS));
    }

    @Override
    public CannonCastShape getCannonShape() {
        return CBCModernWarfareCastShapes.MEDIUMCANNON_BARREL;
    }

    @Override
    public boolean isBreechMechanism(BlockState state) {
        return false;
    }

    @Override
    public boolean isComplete(BlockState state) {
        return true;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!state.is(oldState.getBlock())) {
            MediumcannonBlock.onPlace(level, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock())) {
            onRemoveCannon(state, level, pos, newState, movedByPiston);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockState transform(BlockState state, StructureTransform transform) {
        Direction.Axis axis = transform.rotateAxis(state.getValue(CANNON_AXIS));
        BlockState transformed = state.setValue(CANNON_AXIS, axis);
        if (transform.mirror != null) {
            transformed = mirror(transformed, transform.mirror);
        }
        if (transform.rotationAxis == Direction.Axis.Y) {
            transformed = rotate(transformed, transform.rotation);
        }
        return transformed;
    }
}
