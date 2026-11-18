package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.BearingHeadClientModels;
import com.hooya.stabilizedturret.content.bearing.BearingHeadVisibility;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;

/** Supplies per-bearing visibility to the normal baked block model. */
@Mixin(value = SwivelBearingPlateBlockEntity.class, remap = false)
public abstract class BearingHeadModelDataMixin extends BlockEntity {
    protected BearingHeadModelDataMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public ModelData getModelData() {
        return ModelData.of(
                BearingHeadClientModels.BEARING_HEAD_HIDDEN,
                ((BearingHeadVisibility) this).stabilizedTurret$isBearingHeadHidden()
        );
    }
}
