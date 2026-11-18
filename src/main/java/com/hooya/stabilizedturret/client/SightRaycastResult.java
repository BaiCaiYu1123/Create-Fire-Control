package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.client.compat.VoxyLodRaycaster.RayHit;
import com.hooya.stabilizedturret.content.controller.AimRayLimits;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/** Keeps real collision faces separate from cached LOD terrain points. */
record SightRaycastResult(Vec3 target, boolean hit, @Nullable HitResult rawResult, @Nullable RayHit lodHit) {
    SightRaycastResult(Vec3 target, boolean hit, @Nullable HitResult rawResult) {
        this(target, hit, rawResult, null);
    }

    /** Loaded body faces stay plot-local; LOD terrain supplies its world-space voxel face. */
    @Nullable Vec3 surfaceNormal(Vec3 direction) {
        if (!hit) return null;
        if (rawResult instanceof BlockHitResult block) {
            var face = block.getDirection();
            return new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        }
        return lodHit == null ? null : lodHit.surfaceNormal(direction);
    }

    SightRaycastResult withLodFallback(Vec3 origin, Vec3 direction, Supplier<@Nullable RayHit> query) {
        // Loaded blocks and external Sable bodies keep their exact collision hit.
        if (hit) return this;
        RayHit terrain = query.get();
        // A camera inside a cached solid voxel produces an entry distance of
        // zero. It has no forward sight surface and cannot be a range sample.
        if (terrain == null || !AimRayLimits.isForwardTarget(origin, direction, terrain.point())) return this;
        // Missing LOD data remains a miss, so rangefinding into the sky resets.
        // A cached voxel has no loaded block face or physical-body anchor.
        return new SightRaycastResult(terrain.point(), true, null, terrain);
    }
}
