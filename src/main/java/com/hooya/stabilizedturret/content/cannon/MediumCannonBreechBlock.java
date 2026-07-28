package com.hooya.stabilizedturret.content.cannon;

import com.hooya.stabilizedturret.registry.ModCbcContent;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlock;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlockEntity;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareMediumcannonMaterials;

public final class MediumCannonBreechBlock extends MediumcannonBreechBlock {
    public MediumCannonBreechBlock(BlockBehaviour.Properties properties) {
        super(properties, CBCModernWarfareMediumcannonMaterials.NETHERSTEEL);
    }

    @Override
    public BlockEntityType<? extends MediumcannonBreechBlockEntity> getBlockEntityType() {
        return ModCbcContent.MEDIUM_CANNON_BREECH_BLOCK_ENTITY.get();
    }
}
