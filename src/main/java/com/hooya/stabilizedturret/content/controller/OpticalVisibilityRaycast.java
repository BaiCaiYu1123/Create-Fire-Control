package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/** Visibility must never create terrain chunks or traverse empty space outside a physical plot. */
final class OpticalVisibilityRaycast {
    record Hit(@Nullable UUID subLevelId, Vec3 location) {}

    static boolean isClear(Level level, Vec3 from, Vec3 to, @Nullable UUID target, Set<UUID> ignored) {
        Hit hit = clip(level, from, to, ignored);
        return hit == null || target != null && target.equals(hit.subLevelId())
                || from.distanceTo(hit.location()) + .20 >= from.distanceTo(to);
    }

    static @Nullable Hit clip(Level level, Vec3 from, Vec3 to, Set<UUID> ignored) {
        var container = SubLevelContainer.getContainer(level);
        if (!(container.getLevel() instanceof ServerLevel root)) return null;
        Vec3 terrain = clipBlocks(loadedView(root, null), from, to);
        Hit nearest = terrain == null ? null : new Hit(null, terrain);
        double distance = from.distanceToSqr(terrain == null ? to : terrain);
        for (var body : container.queryIntersecting(new BoundingBox3d(from, terrain == null ? to : terrain))) {
            if (body == null || body.isRemoved() || ignored.contains(body.getUniqueId())) continue;
            var bounds = body.getPlot().getBoundingBox();
            if (bounds.minX() > bounds.maxX() || bounds.minY() > bounds.maxY() || bounds.minZ() > bounds.maxZ()) continue;
            var pose = body.logicalPose();
            Vec3[] segment = crop(pose.transformPositionInverse(from), pose.transformPositionInverse(to),
                    new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                            bounds.maxX() + 1.0, bounds.maxY() + 1.0, bounds.maxZ() + 1.0));
            if (segment == null) continue;
            Vec3 localHit = clipBlocks(loadedView(root, body.getPlot()), segment[0], segment[1]);
            if (localHit == null) continue;
            Vec3 worldHit = pose.transformPosition(localHit);
            double hitDistance = from.distanceToSqr(worldHit);
            if (hitDistance <= distance) {
                nearest = new Hit(body.getUniqueId(), worldHit);
                distance = hitDistance;
            }
        }
        return nearest;
    }

    /** A plain BlockGetter avoids Sable's Level.clip projection and its loading getBlockState calls. */
    static BlockGetter loadedView(ServerLevel root, @Nullable LevelPlot plot) {
        return new BlockGetter() {
            private int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
            private @Nullable LevelChunk chunk;

            @Override public BlockState getBlockState(BlockPos pos) {
                if (pos.getY() < getMinBuildHeight() || pos.getY() >= getMinBuildHeight() + getHeight())
                    return Blocks.AIR.defaultBlockState();
                int x = pos.getX() >> 4, z = pos.getZ() >> 4;
                if (x != chunkX || z != chunkZ) {
                    chunkX = x; chunkZ = z;
                    // LevelPlot.getChunk is a lookup of existing holders, never a chunk request.
                    chunk = plot == null ? root.getChunkSource().getChunkNow(x, z)
                            : plot.getChunk(plot.toLocal(new ChunkPos(x, z)));
                }
                return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
            }
            @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getHeight() { return root.getHeight(); }
            @Override public int getMinBuildHeight() { return root.getMinBuildHeight(); }
        };
    }

    private static @Nullable Vec3 clipBlocks(BlockGetter blocks, Vec3 from, Vec3 to) {
        var hit = blocks.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS ? null : hit.getLocation();
    }

    /** Slab clipping bounds the voxel traversal even when a target is kilometres from a tiny plot. */
    static @Nullable Vec3[] crop(Vec3 from, Vec3 to, AABB box) {
        Vec3 delta = to.subtract(from);
        double enter = 0, exit = 1;
        double[] start = {from.x, from.y, from.z}, step = {delta.x, delta.y, delta.z};
        double[] min = {box.minX, box.minY, box.minZ}, max = {box.maxX, box.maxY, box.maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(step[axis]) < 1e-12) {
                if (start[axis] < min[axis] || start[axis] > max[axis]) return null;
                continue;
            }
            double a = (min[axis] - start[axis]) / step[axis], b = (max[axis] - start[axis]) / step[axis];
            enter = Math.max(enter, Math.min(a, b)); exit = Math.min(exit, Math.max(a, b));
            if (enter > exit) return null;
        }
        double padding = 1e-4 / Math.max(1, delta.length());
        return new Vec3[]{from.lerp(to, Math.max(0, enter - padding)), from.lerp(to, Math.min(1, exit + padding))};
    }

    private OpticalVisibilityRaycast() {}
}
