package com.hooya.stabilizedturret.content.missile;

import com.hooya.stabilizedturret.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Render anchor for the complete DF-26 mesh. */
public final class Df26BallisticMissileBlockEntity extends BlockEntity {
    public Df26BallisticMissileBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DF_26_BALLISTIC_MISSILE.get(), pos, state);
    }
}
