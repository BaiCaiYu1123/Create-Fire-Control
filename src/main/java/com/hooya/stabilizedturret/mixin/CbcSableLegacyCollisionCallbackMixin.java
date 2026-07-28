package com.hooya.stabilizedturret.mixin;

import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback.CollisionResult;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * CBC 5.11.7 implements Sable 2.x's four-argument callback. When it is used
 * with Sable 1.2.2, supply the legacy entry point and treat the projectile
 * block as the contacted block because the old API did not expose one.
 */
@Pseudo
@Mixin(
        targets = "rbasamoyai.createbigcannons.compat.sable.ShellSubLevelImpactCallback",
        remap = false
)
public abstract class CbcSableLegacyCollisionCallbackMixin {
    @Shadow
    public abstract CollisionResult sable$onCollision(
            BlockPos projectilePos,
            BlockPos contactedPos,
            Vector3d impactPoint,
            double velocity
    );

    public CollisionResult sable$onCollision(
            BlockPos projectilePos,
            Vector3d impactPoint,
            double velocity
    ) {
        return sable$onCollision(projectilePos, projectilePos, impactPoint, velocity);
    }
}
