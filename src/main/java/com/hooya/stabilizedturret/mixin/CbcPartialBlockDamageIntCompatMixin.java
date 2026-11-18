package com.hooya.stabilizedturret.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import rbasamoyai.createbigcannons.base.PartialBlockDamageManager;

/**
 * Restores the integer-damage overload expected by CBC Advanced Technology
 * 0.1.4c when it runs against CBC 5.11.7's float-damage API.
 *
 * <p>The mixin plugin only enables this bridge when the integer overload is
 * absent and the float overload is present. This keeps newer CBC versions in
 * control of their own implementation and makes the conversion behavior
 * exactly equivalent to calling CBC's current overload.</p>
 */
@Mixin(value = PartialBlockDamageManager.class, remap = false)
public abstract class CbcPartialBlockDamageIntCompatMixin {

    @Shadow
    public abstract boolean damageBlock(
            BlockPos pos,
            float damage,
            BlockState state,
            Level level
    );

    @Unique
    public boolean damageBlock(
            BlockPos pos,
            int damage,
            BlockState state,
            Level level
    ) {
        return damageBlock(pos, (float) damage, state, level);
    }
}
