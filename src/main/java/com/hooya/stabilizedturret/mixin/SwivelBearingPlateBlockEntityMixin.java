package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.bearing.BearingHeadVisibility;
import com.hooya.stabilizedturret.compat.sable.SableBlockEntitySyncCompat;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SwivelBearingPlateBlockEntity.class, remap = false)
public abstract class SwivelBearingPlateBlockEntityMixin implements BearingHeadVisibility {
    @Unique
    private static final String STABILIZED_TURRET_HIDE_HEAD_TAG = "StabilizedTurretHideBearingHead";

    @Unique
    private boolean stabilizedTurret$bearingHeadHidden;

    @Override
    public boolean stabilizedTurret$isBearingHeadHidden() {
        return stabilizedTurret$bearingHeadHidden;
    }

    @Override
    public void stabilizedTurret$setBearingHeadHidden(boolean hidden) {
        if (stabilizedTurret$bearingHeadHidden == hidden) {
            return;
        }
        SwivelBearingPlateBlockEntity plate = (SwivelBearingPlateBlockEntity) (Object) this;
        if (plate.getLevel() != null && !plate.getLevel().isClientSide
                && !SableBlockEntitySyncCompat.canSendBlockEntityUpdate(
                plate.getLevel(), plate.getBlockPos())) {
            // Do not commit the value yet: the controller retries every tick,
            // so the client receives it as soon as Sable installs the holder.
            return;
        }
        stabilizedTurret$bearingHeadHidden = hidden;
        plate.setChanged();
        if (plate.getLevel() != null && !plate.getLevel().isClientSide) {
            plate.sendData();
        }
    }

    @Inject(method = "write", at = @At("TAIL"), remap = false)
    private void stabilizedTurret$writeBearingHeadVisibility(
            CompoundTag tag,
            HolderLookup.Provider registries,
            boolean clientPacket,
            CallbackInfo ci
    ) {
        tag.putBoolean(STABILIZED_TURRET_HIDE_HEAD_TAG, stabilizedTurret$bearingHeadHidden);
    }

    @Inject(method = "read", at = @At("TAIL"), remap = false)
    private void stabilizedTurret$readBearingHeadVisibility(
            CompoundTag tag,
            HolderLookup.Provider registries,
            boolean clientPacket,
            CallbackInfo ci
    ) {
        boolean hidden = tag.getBoolean(STABILIZED_TURRET_HIDE_HEAD_TAG);
        boolean changed = stabilizedTurret$bearingHeadHidden != hidden;
        stabilizedTurret$bearingHeadHidden = hidden;
        if (!changed) {
            return;
        }
        SwivelBearingPlateBlockEntity plate = (SwivelBearingPlateBlockEntity) (Object) this;
        plate.requestModelDataUpdate();
        if (plate.getLevel() != null && plate.getLevel().isClientSide) {
            plate.getLevel().sendBlockUpdated(
                    plate.getBlockPos(),
                    plate.getBlockState(),
                    plate.getBlockState(),
                    Block.UPDATE_CLIENTS
            );
        }
    }
}
