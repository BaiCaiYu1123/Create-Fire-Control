package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.compat.yuushya.MediumCannonRecoilOverride;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.recoil_barrel.MediumcannonRecoilBarrelBlockEntity;

/** Replaces CBC-MW's short recoil animation when one of our breeches is used. */
@Mixin(MediumcannonRecoilBarrelBlockEntity.class)
public abstract class MediumcannonRecoilBarrelBlockEntityMixin
        implements MediumCannonRecoilOverride {
    @Unique
    private static final String STABILIZED_TURRET_OVERRIDE_TAG =
            "CreateFireControlRecoilOverride";
    @Unique
    private static final int STABILIZED_TURRET_RECOIL_STROKE_TICKS = 3;
    @Unique
    private static final int STABILIZED_TURRET_RECOIL_HOLD_TICKS = 2;
    @Unique
    private static final int STABILIZED_TURRET_RECOIL_RETURN_TICKS = 16;
    @Unique
    private static final int STABILIZED_TURRET_RECOIL_TOTAL_TICKS =
            STABILIZED_TURRET_RECOIL_STROKE_TICKS
                    + STABILIZED_TURRET_RECOIL_HOLD_TICKS
                    + STABILIZED_TURRET_RECOIL_RETURN_TICKS;

    @Unique
    private boolean stabilizedTurret$recoilOverride;
    @Unique
    private int stabilizedTurret$recoilTicks = STABILIZED_TURRET_RECOIL_TOTAL_TICKS;
    @Unique
    private Direction stabilizedTurret$recoilDirection = Direction.NORTH;

    @Override
    public void stabilizedTurret$setRecoilOverride(boolean enabled) {
        stabilizedTurret$recoilOverride = enabled;
        if (!enabled) {
            stabilizedTurret$recoilTicks = STABILIZED_TURRET_RECOIL_TOTAL_TICKS;
        }
    }

    @Override
    public boolean stabilizedTurret$hasRecoilOverride() {
        return stabilizedTurret$recoilOverride;
    }

    @Override
    public void stabilizedTurret$startRecoil(Direction muzzleDirection) {
        if (!stabilizedTurret$recoilOverride) return;
        stabilizedTurret$recoilDirection = muzzleDirection;
        stabilizedTurret$recoilTicks = 0;
    }

    @Override
    public void stabilizedTurret$tickRecoil() {
        if (stabilizedTurret$recoilOverride
                && stabilizedTurret$recoilTicks < STABILIZED_TURRET_RECOIL_TOTAL_TICKS) {
            stabilizedTurret$recoilTicks++;
        }
    }

    @Override
    public float stabilizedTurret$getRecoilOffset(float partialTick) {
        float time = Math.min(
                STABILIZED_TURRET_RECOIL_TOTAL_TICKS,
                stabilizedTurret$recoilTicks + partialTick
        );
        if (time < STABILIZED_TURRET_RECOIL_STROKE_TICKS) {
            float progress = time / STABILIZED_TURRET_RECOIL_STROKE_TICKS;
            float inverse = 1.0F - progress;
            return 0.5F * (1.0F - inverse * inverse * inverse);
        }
        float returnStart = STABILIZED_TURRET_RECOIL_STROKE_TICKS
                + STABILIZED_TURRET_RECOIL_HOLD_TICKS;
        if (time < returnStart) return 0.5F;
        float progress = Math.min(
                1.0F,
                (time - returnStart) / STABILIZED_TURRET_RECOIL_RETURN_TICKS
        );
        float smooth = progress * progress * (3.0F - 2.0F * progress);
        return 0.5F * (1.0F - smooth);
    }

    @Override
    public Direction stabilizedTurret$getRecoilDirection() {
        return stabilizedTurret$recoilDirection;
    }

    @Inject(method = "getAnimateOffset", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$replaceNativeAnimation(
            float partialTicks,
            CallbackInfoReturnable<Float> cir
    ) {
        if (!stabilizedTurret$recoilOverride) return;
        // CBC-MW's renderer converts scale 1 -> 0 into a 0 -> 0.5 block offset.
        cir.setReturnValue(1.0F - stabilizedTurret$getRecoilOffset(partialTicks) * 2.0F);
    }

    @Inject(method = "tickFromContraption", at = @At("TAIL"))
    private void stabilizedTurret$tickReplacementAnimation(
            Level level,
            PitchOrientedContraptionEntity entity,
            BlockPos localPos,
            CallbackInfo ci
    ) {
        stabilizedTurret$tickRecoil();
        if (!level.isClientSide) return;
        if (entity.getContraption().getBlockEntityClientSide(localPos)
                instanceof MediumCannonRecoilOverride clientOverride
                && clientOverride != this) {
            clientOverride.stabilizedTurret$tickRecoil();
        }
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void stabilizedTurret$writeOverride(
            CompoundTag tag,
            HolderLookup.Provider registries,
            boolean clientPacket,
            CallbackInfo ci
    ) {
        tag.putBoolean(STABILIZED_TURRET_OVERRIDE_TAG, stabilizedTurret$recoilOverride);
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void stabilizedTurret$readOverride(
            CompoundTag tag,
            HolderLookup.Provider registries,
            boolean clientPacket,
            CallbackInfo ci
    ) {
        stabilizedTurret$setRecoilOverride(tag.getBoolean(STABILIZED_TURRET_OVERRIDE_TAG));
    }
}
