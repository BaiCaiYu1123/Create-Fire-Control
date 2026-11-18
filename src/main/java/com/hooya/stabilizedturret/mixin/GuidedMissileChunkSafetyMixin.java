package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoCountermeasureCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents AbstractArrow collision from synchronously loading remote terrain.
 * When a guided missile flies beyond loaded root-world chunks, it advances one
 * non-colliding step instead. The fire-control server handler remains the
 * authority for guidance, fuzes and lifetime.
 */
@Mixin(value = AbstractArrow.class, priority = 2100)
public abstract class GuidedMissileChunkSafetyMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void createFireControl$neverSynchronouslyLoadMissilePath(CallbackInfo ci) {
        Entity missile = (Entity) (Object) this;
        Level level = missile.level();
        if (level.isClientSide()
                && missile.getPersistentData().getBoolean("CreateFireControlRemoteVisual")) {
            ci.cancel();
            return;
        }
        if (!level.isClientSide()
                && MianbaoCountermeasureCompat.isCountermeasure(missile)
                && missile.getPersistentData().getBoolean(
                MianbaoCountermeasureCompat.TRACKED_TAG)) {
            MianbaoMissileCompat.noteNativeMissileTick(missile);
            ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
            if (root == null) {
                ci.cancel();
                return;
            }
            Vec3 start = MianbaoMissileCompat.getWorldPosition(missile);
            Vec3 movement = MianbaoMissileCompat.getWorldVelocity(missile);
            int samples = Math.max(1, Mth.ceil(Math.max(
                    Math.abs(movement.x), Math.abs(movement.z)) / 8.0));
            for (int i = 0; i <= samples; i++) {
                double fraction = (double) i / samples;
                int blockX = Mth.floor(start.x + movement.x * fraction);
                int blockZ = Mth.floor(start.z + movement.z * fraction);
                if (root.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4) == null) {
                    MianbaoMissileCompat.advanceWithoutRootChunkCollision(missile);
                    MianbaoCountermeasureCompat.applySkippedArrowPhysics(missile);
                    ci.cancel();
                    return;
                }
            }
            return;
        }
        if (level.isClientSide() || !MianbaoAirDefenseCompat.isAnyFireControlMissile(missile)) return;
        boolean cruiseGuided = missile.getPersistentData()
                .getBoolean("CreateFireControlCruiseGuided");
        boolean adopted = missile.getPersistentData().getBoolean("CreateFireControlGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlAaGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlAircraftGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlRocket")
                || cruiseGuided;
        if (!adopted) return;

        MianbaoMissileCompat.noteNativeMissileTick(missile);
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        if (root == null) {
            ci.cancel();
            return;
        }
        Vec3 start = MianbaoMissileCompat.getWorldPosition(missile);
        Vec3 movement = MianbaoMissileCompat.getWorldVelocity(missile);
        int samples = Math.max(1, Mth.ceil(Math.max(
                Math.abs(movement.x), Math.abs(movement.z)) / 8.0));
        for (int i = 0; i <= samples; i++) {
            double fraction = (double) i / samples;
            int blockX = Mth.floor(start.x + movement.x * fraction);
            int blockZ = Mth.floor(start.z + movement.z * fraction);
            if (root.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4) == null) {
                // Keep the entity moving while the ticketed chunk finishes
                // loading. CruiseMissileGuidance runs Mianbao's native trail
                // procedure once for this skipped tick.
                MianbaoMissileCompat.advanceWithoutRootChunkCollision(missile);
                if (cruiseGuided) {
                    MianbaoMissileCompat.markCruiseTrailMissed(missile);
                }
                ci.cancel();
                return;
            }
        }
    }
}
