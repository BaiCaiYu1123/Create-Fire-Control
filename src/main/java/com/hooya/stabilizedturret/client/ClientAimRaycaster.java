package com.hooya.stabilizedturret.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/** Physical bodies are clipped inside their plots before querying the nearer world terrain. */
public final class ClientAimRaycaster {
    private ClientAimRaycaster() {}

    public record Result(Vec3 point, BlockHitResult rawHit) {
        public boolean hit() { return rawHit != null; }
    }

    public static Result clip(Level level, Entity viewer, Vec3 from, Vec3 to,
                              Set<SubLevel> ignored, float partialTick) {
        Vec3 nearest = to;
        BlockHitResult selected = null;
        double distance = from.distanceToSqr(to);
        for (SubLevel body : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            if (body.isRemoved() || ignored.contains(body)) continue;
            var pose = body instanceof ClientSubLevel client ? client.renderPose(partialTick) : body.logicalPose();
            Vec3 localFrom = pose.transformPositionInverse(from);
            Vec3 localTo = pose.transformPositionInverse(nearest);
            var bounds = body.getPlot().getBoundingBox();
            if (bounds.minX() > bounds.maxX() || bounds.minY() > bounds.maxY() || bounds.minZ() > bounds.maxZ()) continue;
            var segment = AimRaycastGeometry.crop(localFrom, localTo, new AABB(
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX() + 1.0, bounds.maxY() + 1.0, bounds.maxZ() + 1.0));
            if (segment == null) continue;
            BlockHitResult hit = rawClip(body.getLevel(), viewer, segment.from(), segment.to());
            if (hit.getType() == HitResult.Type.MISS) continue;
            // Collision, distance selection and projection must use one pose.
            // A logical-pose reprojection of a render-pose hit jitters at close range.
            Vec3 world = pose.transformPosition(hit.getLocation());
            double candidate = from.distanceToSqr(world);
            if (candidate <= distance) {
                distance = candidate;
                nearest = world;
                selected = hit;
            }
        }
        // Rays above/below the world need no thousands-of-block air traversal.
        var worldSegment = AimRaycastGeometry.crop(from, nearest, new AABB(
                Math.min(from.x, nearest.x) - 1, level.getMinBuildHeight(), Math.min(from.z, nearest.z) - 1,
                Math.max(from.x, nearest.x) + 1, level.getMaxBuildHeight(), Math.max(from.z, nearest.z) + 1));
        if (worldSegment != null) {
            BlockHitResult hit = rawClip(level, viewer, worldSegment.from(), worldSegment.to());
            if (hit.getType() != HitResult.Type.MISS && from.distanceToSqr(hit.getLocation()) <= distance) {
                nearest = hit.getLocation();
                selected = hit;
            }
        }
        return new Result(selected == null ? to : nearest, selected);
    }

    private static BlockHitResult rawClip(Level level, Entity viewer, Vec3 from, Vec3 to) {
        ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer);
        ((ClipContextExtension) context).sable$setDoNotProject(true);
        return level.clip(context);
    }
}
