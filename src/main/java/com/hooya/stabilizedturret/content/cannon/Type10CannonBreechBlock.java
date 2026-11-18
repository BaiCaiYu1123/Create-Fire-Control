package com.hooya.stabilizedturret.content.cannon;

import com.hooya.stabilizedturret.registry.ModCbcContent;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlock;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlockEntity;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareMediumcannonMaterials;

/** CBC-compatible breech used exclusively by the Type 10 automatic loader. */
public final class Type10CannonBreechBlock extends MediumcannonBreechBlock {
    public Type10CannonBreechBlock(BlockBehaviour.Properties properties) {
        super(properties, CBCModernWarfareMediumcannonMaterials.NETHERSTEEL);
    }

    @Override
    public BlockEntityType<? extends MediumcannonBreechBlockEntity> getBlockEntityType() {
        return ModCbcContent.TYPE_10_CANNON_BREECH_BLOCK_ENTITY.get();
    }
}
