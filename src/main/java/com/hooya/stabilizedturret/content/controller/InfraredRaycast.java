package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/** Clips only occupied physical plots and already-loaded terrain; never requests world chunks. */
final class InfraredRaycast {
    record Hit(UUID id, Vec3 position) {}
    static @Nullable Hit clip(Level level, Vec3 from, Vec3 to, Set<UUID> ignored) {
        Hit nearest = null;
        double distance = from.distanceToSqr(to);
        for (var body : SubLevelContainer.getContainer(level).queryIntersecting(new BoundingBox3d(from, to))) {
            if (body == null || body.isRemoved() || ignored.contains(body.getUniqueId())) continue;
            var plot = body.getPlot().getBoundingBox();
            if (plot.minX() > plot.maxX() || plot.minY() > plot.maxY() || plot.minZ() > plot.maxZ()) continue;
            var pose = body.logicalPose();
            Vec3 localFrom = pose.transformPositionInverse(from), localTo = pose.transformPositionInverse(to);
            Vec3[] segment = crop(localFrom, localTo, new AABB(plot.minX(), plot.minY(), plot.minZ(),
                    plot.maxX() + 1.0, plot.maxY() + 1.0, plot.maxZ() + 1.0));
            if (segment == null) continue;
            var blocks = body.getLevel();
            Vec3 hit = BlockGetter.traverseBlocks(segment[0], segment[1], blocks, (view, pos) -> {
                var state = view.getBlockState(pos);
                if (!RadarVisibility.potentialBlocker(state)) return null;
                var shape = state.getCollisionShape(view, pos, CollisionContext.empty());
                var contact = shape.isEmpty() ? null : shape.clip(segment[0], segment[1], pos);
                return contact == null ? null : contact.getLocation();
            }, unused -> null);
            if (hit == null) continue;
            Vec3 world = pose.transformPosition(hit);
            if (from.distanceToSqr(world) < distance) {
                nearest = new Hit(body.getUniqueId(), world); distance = from.distanceToSqr(world);
            }
        }
        // A physical hit is still required for acquisition. The shared switch only bypasses
        // the intervening terrain check, just as it does for sustained IR/AGM locks and launch.
        if (nearest == null || !com.hooya.stabilizedturret.config.FireControlSettings.radarRaycastEnabled()) return nearest;
        ServerLevel root = level instanceof ServerLevel server ? server.getServer().getLevel(level.dimension()) : null;
        if (root == null) return null;
        BlockGetter loaded = new BlockGetter() {
            @Override public BlockState getBlockState(BlockPos pos) {
                var chunk = root.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                var state = chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
                return RadarVisibility.potentialBlocker(state) ? state : Blocks.AIR.defaultBlockState();
            }
            @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getHeight() { return root.getHeight(); }
            @Override public int getMinBuildHeight() { return root.getMinBuildHeight(); }
        };
        var terrain = loaded.clip(new ClipContext(from, nearest.position(), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        return terrain.getType() != HitResult.Type.MISS && terrain.getLocation().distanceToSqr(from) + .01 < distance ? null : nearest;
    }
    private static @Nullable Vec3[] crop(Vec3 from, Vec3 to, AABB box) {
        Vec3 delta = to.subtract(from);
        double enter = 0, exit = 1;
        double[] start = {from.x, from.y, from.z}, step = {delta.x, delta.y, delta.z};
        double[] min = {box.minX, box.minY, box.minZ}, max = {box.maxX, box.maxY, box.maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(step[axis]) < 1e-12) { if (start[axis] < min[axis] || start[axis] > max[axis]) return null; continue; }
            double a = (min[axis] - start[axis]) / step[axis], b = (max[axis] - start[axis]) / step[axis];
            enter = Math.max(enter, Math.min(a, b)); exit = Math.min(exit, Math.max(a, b));
            if (enter > exit) return null;
        }
        double padding = Math.max(1e-4 / Math.max(1, delta.length()), (exit - enter) * .002);
        return new Vec3[]{from.lerp(to, Math.max(0, enter - padding)), from.lerp(to, Math.min(1, exit + padding))};
    }
    private InfraredRaycast() {}
}
