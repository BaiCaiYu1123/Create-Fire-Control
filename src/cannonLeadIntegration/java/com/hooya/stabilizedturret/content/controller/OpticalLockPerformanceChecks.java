package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/** Real Sable plots and terrain, including unloaded terrain beyond the player's view distance. */
final class OpticalLockPerformanceChecks {
    private static final List<SubLevel> BODIES = new ArrayList<>();

    static void check(ServerLevel level) throws Exception {
        try {
            checkWorld(level);
        } finally {
            // These synchronous fixtures do not run a native physics step; remove their
            // temporary plots before shutdown tries to serialize a native body handle.
            for (SubLevel body : BODIES)
                SubLevelContainer.getContainer(level).removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            BODIES.clear();
        }
    }

    private static void checkWorld(ServerLevel level) throws Exception {
        // Negative control reproduces the old Level.clip path on fresh, disposable terrain.
        Vec3 legacyFrom = new Vec3(20_000.5, 200.75, 20_000.5);
        level.getChunk(BlockPos.containing(legacyFrom));
        int beforeLegacy = level.getChunkSource().getLoadedChunksCount();
        long legacyStart = System.nanoTime();
        level.clip(new ClipContext(legacyFrom, legacyFrom.add(0, 0, 384), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        double legacyMs = (System.nanoTime() - legacyStart) / 1e6;
        int legacyChunks = level.getChunkSource().getLoadedChunksCount() - beforeLegacy;
        require(legacyChunks > 0, "negative control: old optical visibility loads remote terrain chunks");

        Vec3 from = legacyFrom.add(1_024, 0, 0), to = from.add(0, 0, 1_380);
        BlockPos wall = BlockPos.containing(from).south(8);
        level.getChunk(BlockPos.containing(from));
        var controller = new StabilizerControllerBlockEntity(BlockPos.containing(from),
                ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        controller.setLevel(level);
        var ray = StabilizerControllerBlockEntity.class.getDeclaredMethod("isVisibilityRayClear",
                Vec3.class, Vec3.class, java.util.UUID.class, Set.class);
        ray.setAccessible(true);
        int before = level.getChunkSource().getLoadedChunksCount();
        long started = System.nanoTime();
        for (int i = 0; i < 200; i++)
            require((boolean) ray.invoke(controller, from, to, null, Set.of()), "distant clear optical ray");
        double averageMs = (System.nanoTime() - started) / 1e6 / 200;
        require(level.getChunkSource().getLoadedChunksCount() == before,
                "200 optical checks at the logged 1.38 km range must load zero chunks");
        require(level.getChunkSource().getChunkNow(BlockPos.containing(to).getX() >> 4,
                BlockPos.containing(to).getZ() >> 4) == null, "target's world terrain remains unloaded");
        StabilizedTurret.LOGGER.info("OPTICAL TERRAIN CHECK: old 384m={}ms/{} new chunks; new 1380m average={}ms/0 new chunks",
                legacyMs, legacyChunks, averageMs);

        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        require(!OpticalVisibilityRaycast.isClear(level, from, to, null, Set.of()), "loaded terrain still blocks lock");
        level.setBlockAndUpdate(wall, Blocks.OAK_SLAB.defaultBlockState());
        require(OpticalVisibilityRaycast.isClear(level, from, to, null, Set.of()), "slab collision shape leaves upper ray clear");
        level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());

        SubLevel target = body(level, to);
        var hit = OpticalVisibilityRaycast.clip(level, from, to, Set.of());
        BlockPos targetPos = target.getPlot().getCenterBlock().atY(200);
        var targetChunk = target.getPlot().getChunk(target.getPlot().toLocal(new ChunkPos(targetPos)));
        StabilizedTurret.LOGGER.info("OPTICAL PLOT CHECK: hit={} localBounds={} worldBounds={} localTarget={} block={} chunks={}",
                hit, target.getPlot().getBoundingBox(), target.boundingBox(), target.logicalPose().transformPositionInverse(to),
                targetChunk == null ? "missing" : targetChunk.getBlockState(targetPos), target.getPlot().getLoadedChunks().size());
        require(hit != null && target.getUniqueId().equals(hit.subLevelId()), "remote physical target is still detected");
        require(OpticalVisibilityRaycast.isClear(level, from, to, target.getUniqueId(), Set.of()), "hitting target itself is visible");
        require(!OpticalVisibilityRaycast.isClear(level, from, to, null, Set.of()), "same structure blocks a different target");
        SubLevel own = body(level, from.add(0, 0, 4));
        require(!OpticalVisibilityRaycast.isClear(level, from, to, target.getUniqueId(), Set.of()), "near physical structure occludes target");
        require(OpticalVisibilityRaycast.isClear(level, from, to, target.getUniqueId(), Set.of(own.getUniqueId())), "own vehicle is ignored");
        own.logicalPose().orientation().rotationY(.65).rotateZ(.3);
        own.updateBoundingBox();
        own.forceUpdateGlobalBounds();
        require(!OpticalVisibilityRaycast.isClear(level, from, to, target.getUniqueId(), Set.of()), "rotated physical blocker is tested in plot coordinates");
        int plotChunks = target.getPlot().getLoadedChunks().size() + own.getPlot().getLoadedChunks().size();
        before = level.getChunkSource().getLoadedChunksCount();
        for (int i = 0; i < 200; i++)
            require(OpticalVisibilityRaycast.isClear(level, from, to, target.getUniqueId(), Set.of(own.getUniqueId())), "sustained physical tracking");
        require(before == level.getChunkSource().getLoadedChunksCount(), "plot visibility never loads world chunks");
        require(plotChunks == target.getPlot().getLoadedChunks().size() + own.getPlot().getLoadedChunks().size(),
                "plot clipping never creates empty chunks beyond the occupied bounds");

        // A sparse/empty locked plot must not trigger a voxel-volume scan or create chunks.
        var present = StabilizerControllerBlockEntity.class.getDeclaredMethod("lockedSubLevelStillExists", SubLevel.class);
        present.setAccessible(true);
        require((boolean) present.invoke(controller, target), "section metadata detects a live target");
        BlockPos targetBlock = target.getPlot().getCenterBlock().atY(200);
        level.setBlockAndUpdate(targetBlock, Blocks.AIR.defaultBlockState());
        target.getPlot().setBoundingBox(new BoundingBox3i(targetBlock.getX() - 400, 0, targetBlock.getZ() - 400,
                targetBlock.getX() + 400, 319, targetBlock.getZ() + 400));
        level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 10);
        require(!(boolean) present.invoke(controller, target), "empty target with stale large bounds is gone");
        require(plotChunks == target.getPlot().getLoadedChunks().size() + own.getPlot().getLoadedChunks().size(),
                "empty target presence check cannot allocate plot chunks");
        StabilizedTurret.LOGGER.info("OPTICAL PERFORMANCE PASS: old 384m ray={}ms/{} new terrain chunks; new 1380m ray average={}ms/0 new chunks; terrain, shapes, rotated plots, own-body exclusion and empty-target presence verified",
                legacyMs, legacyChunks, averageMs);
    }

    private static SubLevel body(ServerLevel level, Vec3 position) {
        var body = SubLevelContainer.getContainer(level).allocateNewSubLevel(new Pose3d());
        BODIES.add(body);
        BlockPos block = body.getPlot().getCenterBlock().atY(200);
        body.getPlot().newEmptyChunk(new ChunkPos(block));
        level.setBlockAndUpdate(block, Blocks.STONE.defaultBlockState());
        body.getPlot().updateBoundingBox();
        // Adding the first block recalculates the physical centre of mass and rebases the pose.
        body.logicalPose().rotationPoint().set(block.getX() + .5, block.getY() + .5, block.getZ() + .5);
        body.logicalPose().position().set(position.x, position.y, position.z);
        body.updateBoundingBox();
        body.forceUpdateGlobalBounds();
        return body;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
