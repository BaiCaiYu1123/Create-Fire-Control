package com.hooya.stabilizedturret.mixin;

import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback.CollisionResult;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * CBC 5.11.6 implements Sable 1.x's three-argument collision callback. Sable
 * 2.x added the contacted block position as a second argument without a
 * default bridge, which otherwise produces an AbstractMethodError as soon as
 * a restored CBC projectile structure participates in physics.
 */
@Pseudo
@Mixin(
        targets = "rbasamoyai.createbigcannons.compat.sable.ShellSubLevelImpactCallback",
        remap = false
)
public abstract class CbcSableCollisionCallbackMixin {
    @Shadow
    public abstract CollisionResult sable$onCollision(
            BlockPos projectilePos,
            Vector3d impactPoint,
            double velocity
    );

    public CollisionResult sable$onCollision(
            BlockPos projectilePos,
            BlockPos contactedPos,
            Vector3d impactPoint,
            double velocity
    ) {
        return sable$onCollision(projectilePos, impactPoint, velocity);
    }
}
