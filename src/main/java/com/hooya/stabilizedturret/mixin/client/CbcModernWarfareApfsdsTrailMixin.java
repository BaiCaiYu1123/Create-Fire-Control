package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsProjectile;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile;

/** Aligns every high-speed APFSDS smoke segment with its real world motion. */
@Mixin(value = AbstractMediumcannonProjectile.class, remap = false)
public abstract class CbcModernWarfareApfsdsTrailMixin {
    @Unique
    private Vec3 stabilizedTurret$trailTickStart;

    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void stabilizedTurret$captureTrailTickStart(CallbackInfo ci) {
        stabilizedTurret$trailTickStart = (Object) this instanceof StabilizedApfsdsProjectile projectile
                ? projectile.position() : null;
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/Mth;lerp(DDD)D",
                    ordinal = 0,
                    remap = true
            ),
            require = 1
    )
    private double stabilizedTurret$alignTrailX(
            double fraction,
            double oldCoordinate,
            double currentCoordinate
    ) {
        return stabilizedTurret$alignedCoordinate(
                fraction, oldCoordinate, currentCoordinate, 0);
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/Mth;lerp(DDD)D",
                    ordinal = 1,
                    remap = true
            ),
            require = 1
    )
    private double stabilizedTurret$alignTrailY(
            double fraction,
            double oldCoordinate,
            double currentCoordinate
    ) {
        return stabilizedTurret$alignedCoordinate(
                fraction, oldCoordinate, currentCoordinate, 1);
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/Mth;lerp(DDD)D",
                    ordinal = 2,
                    remap = true
            ),
            require = 1
    )
    private double stabilizedTurret$alignTrailZ(
            double fraction,
            double oldCoordinate,
            double currentCoordinate
    ) {
        return stabilizedTurret$alignedCoordinate(
                fraction, oldCoordinate, currentCoordinate, 2);
    }

    private double stabilizedTurret$alignedCoordinate(
            double fraction,
            double oldCoordinate,
            double currentCoordinate,
            int axis
    ) {
        if (!((Object) this instanceof StabilizedApfsdsProjectile projectile)) {
            return Mth.lerp(fraction, oldCoordinate, currentCoordinate);
        }

        Vec3 tickStart = stabilizedTurret$trailTickStart;
        if (tickStart == null) {
            return Mth.lerp(fraction, oldCoordinate, currentCoordinate);
        }
        double startCoordinate = switch (axis) {
            case 0 -> tickStart.x;
            case 1 -> tickStart.y;
            default -> tickStart.z;
        };
        if (!Double.isFinite(startCoordinate) || !Double.isFinite(currentCoordinate)) {
            return Mth.lerp(fraction, oldCoordinate, currentCoordinate);
        }
        // CBC-MW creates ten particles by interpolating each coordinate
        // independently. On a projectile kicked out of a Sable plot, xOld,
        // yOld and zOld can describe a different frame from the visible
        // entity for that tick. Using deltaMovement is not a safe substitute
        // either: it can still be the plot-relative or deferred ricochet
        // velocity. The position captured immediately before super.tick and
        // the current position are the exact world-space endpoints of the
        // segment, including carrier motion and bounce displacement.
        return Mth.lerp(fraction, startCoordinate, currentCoordinate);
    }
}
