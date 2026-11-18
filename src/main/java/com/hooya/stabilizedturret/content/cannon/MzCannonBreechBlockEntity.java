package com.hooya.stabilizedturret.content.cannon;

import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonRecoilAnimation;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlockEntity;

/** CBC-compatible MZ breech with the same recoil behavior as the original custom breech. */
public final class MzCannonBreechBlockEntity extends MediumcannonBreechBlockEntity
        implements YuushyaCannonRecoilAnimation {
    private static final int RECOIL_STROKE_TICKS = 3;
    private static final int RECOIL_HOLD_TICKS = 2;
    private static final int RECOIL_RETURN_TICKS = 16;
    private static final int RECOIL_TOTAL_TICKS =
            RECOIL_STROKE_TICKS + RECOIL_HOLD_TICKS + RECOIL_RETURN_TICKS;

    private int recoilTicks = RECOIL_TOTAL_TICKS;
    private Direction recoilDirection = Direction.NORTH;

    public MzCannonBreechBlockEntity(BlockPos pos, BlockState state) {
        super(ModCbcContent.MZ_CANNON_BREECH_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void handleFiring() {
        super.handleFiring();
        stabilizedTurret$startRecoil(getBlockState().getValue(BlockStateProperties.FACING));
    }

    @Override
    public void stabilizedTurret$startRecoil(Direction muzzleDirection) {
        recoilDirection = muzzleDirection;
        recoilTicks = 0;
    }

    @Override
    public void stabilizedTurret$tickRecoil() {
        if (recoilTicks < RECOIL_TOTAL_TICKS) recoilTicks++;
    }

    @Override
    public float stabilizedTurret$getRecoilOffset(float partialTick) {
        float time = Math.min(RECOIL_TOTAL_TICKS, recoilTicks + partialTick);
        if (time < RECOIL_STROKE_TICKS) {
            float progress = time / RECOIL_STROKE_TICKS;
            float inverse = 1.0F - progress;
            return 0.5F * (1.0F - inverse * inverse * inverse);
        }
        float returnStart = RECOIL_STROKE_TICKS + RECOIL_HOLD_TICKS;
        if (time < returnStart) return 0.5F;
        float progress = Math.min(1.0F, (time - returnStart) / RECOIL_RETURN_TICKS);
        float smooth = progress * progress * (3.0F - 2.0F * progress);
        return 0.5F * (1.0F - smooth);
    }

    @Override
    public Direction stabilizedTurret$getRecoilDirection() {
        return recoilDirection;
    }

    @Override
    public void tickFromContraption(
            Level level,
            PitchOrientedContraptionEntity entity,
            BlockPos localPos
    ) {
        super.tickFromContraption(level, entity, localPos);
        stabilizedTurret$tickRecoil();
        if (level.isClientSide) {
            BlockEntity renderedEntity = entity.getContraption().getBlockEntityClientSide(localPos);
            if (renderedEntity instanceof YuushyaCannonRecoilAnimation animation
                    && renderedEntity != this) {
                animation.stabilizedTurret$tickRecoil();
            }
        }
    }
}
