package com.hooya.stabilizedturret.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Quaterniond;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler$DragSession", remap = false)
public interface PhysicsStaffDragSessionAccessor {
    @Accessor("subLevel")
    ServerSubLevel stabilizedTurret$getSubLevel();

    @Accessor("markedForRemoval")
    boolean stabilizedTurret$isMarkedForRemoval();

    @Accessor("orientation")
    Quaterniond stabilizedTurret$getOrientation();
}
