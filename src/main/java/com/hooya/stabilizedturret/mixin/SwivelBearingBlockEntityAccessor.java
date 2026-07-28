package com.hooya.stabilizedturret.mixin;

import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;

@Mixin(value = SwivelBearingBlockEntity.class, remap = false)
public interface SwivelBearingBlockEntityAccessor {
    @Accessor("targetAngleDegrees")
    double stabilizedTurret$getTargetAngleDegrees();

    @Accessor("targetAngleDegrees")
    void stabilizedTurret$setTargetAngleDegrees(double angle);

    @Accessor("lastTargetAngleDegrees")
    void stabilizedTurret$setLastTargetAngleDegrees(double angle);

    @Accessor("lockedDefaultOption")
    ScrollOptionBehaviour<SwivelBearingBlockEntity.LockingSetting>
    stabilizedTurret$getLockedDefaultOption();

    @Invoker("removeHandle")
    void stabilizedTurret$removeConstraintHandle();

}
