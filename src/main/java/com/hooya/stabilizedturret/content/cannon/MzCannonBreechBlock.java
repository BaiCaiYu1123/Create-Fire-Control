package com.hooya.stabilizedturret.content.cannon;

import com.hooya.stabilizedturret.registry.ModCbcContent;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlock;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlockEntity;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareMediumcannonMaterials;

/** The animated MZ breech used exclusively by the MZ automatic loader. */
public final class MzCannonBreechBlock extends MediumcannonBreechBlock {
    public MzCannonBreechBlock(BlockBehaviour.Properties properties) {
        super(properties, CBCModernWarfareMediumcannonMaterials.NETHERSTEEL);
    }

    @Override
    public BlockEntityType<? extends MediumcannonBreechBlockEntity> getBlockEntityType() {
        return ModCbcContent.MZ_CANNON_BREECH_BLOCK_ENTITY.get();
    }
}
