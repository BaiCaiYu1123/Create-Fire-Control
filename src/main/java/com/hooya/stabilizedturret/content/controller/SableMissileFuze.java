package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Collection;
import java.util.UUID;
import java.util.Set;

/** Exact Sable block-contact and physical-centre fuze queries. */
public final class SableMissileFuze {
    public record Contact(Vec3 position, UUID subLevelId) {
    }

    public static Contact firstBlockContact(ServerLevel level, Vec3 from, Vec3 to) {
        return firstBlockContact(level, from, to, Set.of());
    }

    public static Contact firstBlockContact(ServerLevel level, Vec3 from, Vec3 to, Set<UUID> ignored) {
        if (level == null || from == null || to == null) return null;
        BoundingBox3d sweptBounds = new BoundingBox3d(from, to);
        Contact closest = null;
        double closestDistanceSqr = Double.POSITIVE_INFINITY;
        for (SubLevel subLevel : SubLevelContainer.getContainer(level).queryIntersecting(sweptBounds)) {
            if (subLevel == null || subLevel.isRemoved()
                    || ignored != null && ignored.contains(subLevel.getUniqueId())) continue;
            Vec3 hit = firstNonAirBlockContact(subLevel, from, to);
            if (hit == null) continue;
            double distanceSqr = hit.distanceToSqr(from);
            if (distanceSqr < closestDistanceSqr) {
                closestDistanceSqr = distanceSqr;
                closest = new Contact(hit, subLevel.getUniqueId());
            }
        }
        return closest;
    }

    public static boolean anyCenterOfMassWithinRange(
            ServerLevel level, Vec3 from, Vec3 to, double range
    ) {
        if (level == null || range <= 0.0) return false;
        for (SubLevel subLevel : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            Vec3 center = physicalCenterOfMass(subLevel);
            if (center != null && distanceToSegmentSqr(center, from, to) <= range * range) {
                return true;
            }
        }
        return false;
    }

    public static boolean anyCenterOfMassWithinRange(
            ServerLevel level,
            Collection<UUID> subLevelIds,
            Vec3 from,
            Vec3 to,
            double range
    ) {
        if (level == null || subLevelIds == null || subLevelIds.isEmpty() || range <= 0.0) {
            return false;
        }
        var container = SubLevelContainer.getContainer(level);
        for (UUID id : subLevelIds) {
            Vec3 center = physicalCenterOfMass(container.getSubLevel(id));
            if (center != null && distanceToSegmentSqr(center, from, to) <= range * range) {
                return true;
            }
        }
        return false;
    }

    public static Vec3 physicalCenterOfMass(SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel serverSubLevel) || subLevel.isRemoved()) {
            return null;
        }
        var massData = serverSubLevel.getMassTracker();
        if (massData == null) return null;
        Vector3dc local = massData.getCenterOfMass();
        if (local == null) return null;
        Vector3d world = new Vector3d(local);
        subLevel.logicalPose().transformPosition(world);
        return new Vec3(world.x, world.y, world.z);
    }

    private static Vec3 firstNonAirBlockContact(SubLevel subLevel, Vec3 worldFrom, Vec3 worldTo) {
        Vector3d localFromVector = new Vector3d(worldFrom.x, worldFrom.y, worldFrom.z);
        Vector3d localToVector = new Vector3d(worldTo.x, worldTo.y, worldTo.z);
        subLevel.logicalPose().transformPositionInverse(localFromVector);
        subLevel.logicalPose().transformPositionInverse(localToVector);
        Vec3 localFrom = new Vec3(localFromVector.x, localFromVector.y, localFromVector.z);
        Vec3 localTo = new Vec3(localToVector.x, localToVector.y, localToVector.z);
        BlockGetter blocks = subLevel.getLevel();
        Vec3 localHit = BlockGetter.traverseBlocks(
                localFrom,
                localTo,
                blocks,
                (getter, pos) -> blockContact(getter, pos, localFrom, localTo),
                ignored -> null
        );
        if (localHit == null) return null;
        Vector3d worldHit = new Vector3d(localHit.x, localHit.y, localHit.z);
        subLevel.logicalPose().transformPosition(worldHit);
        return new Vec3(worldHit.x, worldHit.y, worldHit.z);
    }

    private static Vec3 blockContact(
            BlockGetter blocks, BlockPos pos, Vec3 localFrom, Vec3 localTo
    ) {
        var state = blocks.getBlockState(pos);
        if (state.isAir()) return null;
        VoxelShape shape = state.getCollisionShape(blocks, pos, CollisionContext.empty());
        if (shape.isEmpty()) return null;
        BlockHitResult hit = shape.clip(localFrom, localTo, pos);
        return hit == null ? null : hit.getLocation();
    }

    private static double distanceToSegmentSqr(Vec3 point, Vec3 from, Vec3 to) {
        Vec3 segment = to.subtract(from);
        double lengthSqr = segment.lengthSqr();
        if (lengthSqr < 1.0e-12) return point.distanceToSqr(from);
        double t = Math.max(0.0, Math.min(1.0, point.subtract(from).dot(segment) / lengthSqr));
        return point.distanceToSqr(from.add(segment.scale(t)));
    }

    private SableMissileFuze() {
    }
}
