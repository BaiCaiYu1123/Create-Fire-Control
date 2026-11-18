package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonCompat;
import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonRecoilAnimation;
import com.hooya.stabilizedturret.content.cannon.compat.YuushyaCannonBehaviorHost;
import com.yuushya.modelling.blockentity.itemblock.ItemBlockEntity;
import com.yuushya.modelling.blockentity.showblock.ShowBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.cannons.ItemCannonBehavior;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.IMediumcannonBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

@Mixin({ItemBlockEntity.class, ShowBlockEntity.class})
public abstract class YuushyaItemBlockEntityCannonMixin extends BlockEntity
        implements IMediumcannonBlockEntity, YuushyaCannonRecoilAnimation {
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
    private int stabilizedTurret$recoilTicks = STABILIZED_TURRET_RECOIL_TOTAL_TICKS;
    @Unique
    private Direction stabilizedTurret$recoilDirection = Direction.NORTH;
    @Unique
    private YuushyaCannonBehaviorHost stabilizedTurret$cannonHost;

    protected YuushyaItemBlockEntityCannonMixin(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state
    ) {
        super(type, pos, state);
    }

    @Override
    public ItemCannonBehavior cannonBehavior() {
        if (stabilizedTurret$cannonHost == null) {
            stabilizedTurret$cannonHost = new YuushyaCannonBehaviorHost(this);
        }
        return stabilizedTurret$cannonHost.behavior();
    }

    @Override
    public void stabilizedTurret$startRecoil(Direction muzzleDirection) {
        stabilizedTurret$recoilDirection = muzzleDirection;
        stabilizedTurret$recoilTicks = 0;
    }

    @Override
    public void stabilizedTurret$tickRecoil() {
        if (stabilizedTurret$recoilTicks < STABILIZED_TURRET_RECOIL_TOTAL_TICKS) {
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
        if (time < returnStart) {
            return 0.5F;
        }
        float progress = Math.min(1.0F, (time - returnStart) / STABILIZED_TURRET_RECOIL_RETURN_TICKS);
        float smooth = progress * progress * (3.0F - 2.0F * progress);
        return 0.5F * (1.0F - smooth);
    }

    @Override
    public Direction stabilizedTurret$getRecoilDirection() {
        return stabilizedTurret$recoilDirection;
    }

    @Override
    public void tickFromContraption(Level level, PitchOrientedContraptionEntity entity, BlockPos localPos) {
        stabilizedTurret$tickRecoil();
        if (level.isClientSide) {
            BlockEntity renderedEntity = entity.getContraption().getBlockEntityClientSide(localPos);
            if (renderedEntity instanceof YuushyaCannonRecoilAnimation animation
                    && renderedEntity != this) {
                animation.stabilizedTurret$tickRecoil();
            }
        }
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void stabilizedTurret$saveCannonBehavior(
            CompoundTag tag,
            HolderLookup.Provider registries,
            CallbackInfo ci
    ) {
        if (!YuushyaCannonCompat.isBarrel(getBlockState())) {
            return;
        }
        CompoundTag cannonTag = new CompoundTag();
        cannonBehavior().write(cannonTag, registries, false);
        tag.put(YuushyaCannonCompat.CANNON_BEHAVIOR_TAG, cannonTag);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void stabilizedTurret$loadCannonBehavior(
            CompoundTag tag,
            HolderLookup.Provider registries,
            CallbackInfo ci
    ) {
        if (tag.contains(YuushyaCannonCompat.CANNON_BEHAVIOR_TAG)) {
            cannonBehavior().read(
                    tag.getCompound(YuushyaCannonCompat.CANNON_BEHAVIOR_TAG),
                    registries,
                    false
            );
        }
    }
}
