package com.hooya.stabilizedturret.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Distinguishes a confirmed normal death from zero health with death cancelled by a mod. */
@Mixin(LivingEntity.class)
public interface AnnihilationLivingEntityAccessor {
    @Accessor("dead")
    boolean stabilizedTurret$hasDied();
}
