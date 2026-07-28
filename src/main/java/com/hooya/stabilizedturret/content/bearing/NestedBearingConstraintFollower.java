package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps ordinary Simulated bearings rigidly attached to a fire-control
 * bearing's child body when the legacy stabilizer applies its post-physics
 * pose correction.
 *
 * <p>The ordinary rotary constraint has already been solved when the
 * fire-control body is teleported. Without moving its descendants by the same
 * rigid transform, those constraints spend the next substep chasing a parent
 * that jumped away and look extremely weak. Applying only the common parent
 * transform preserves each ordinary bearing's solved relative angle, motor
 * motion and external velocity.</p>
 */
public final class NestedBearingConstraintFollower {
    public static void enforceWorldLocks(SubLevelPhysicsSystem physicsSystem) {
        Map<UUID, CapturedRoot> captured = captureBoundRoots(physicsSystem);
        BearingStabilizer.enforceWorldLocks(physicsSystem);
        followOrdinaryDescendants(physicsSystem, captured);
    }

    private static Map<UUID, CapturedRoot> captureBoundRoots(
            SubLevelPhysicsSystem physicsSystem
    ) {
        Map<UUID, CapturedRoot> captured = new HashMap<>();
        SubLevelContainer container = SubLevelContainer.getContainer(physicsSystem.getLevel());
        for (SwivelBearingBlockEntity bearing
                : ControllerTracker.getBoundAssembledBearings(physicsSystem.getLevel())) {
            UUID childId = bearing.getSubLevelID();
            SubLevel attached = childId == null ? null : container.getSubLevel(childId);
            if (attached instanceof ServerSubLevel child && !child.isRemoved()) {
                Pose3d pose = child.logicalPose();
                captured.put(childId, new CapturedRoot(
                        child,
                        new Vector3d(pose.position()),
                        new Quaterniond(pose.orientation()).normalize()
                ));
            }
        }
        return captured;
    }

    private static void followOrdinaryDescendants(
            SubLevelPhysicsSystem physicsSystem,
            Map<UUID, CapturedRoot> captured
    ) {
        if (captured.isEmpty()) {
            return;
        }
        PhysicsPipeline pipeline = physicsSystem.getPipeline();
        Set<UUID> boundChildren = Set.copyOf(captured.keySet());
        for (CapturedRoot root : captured.values()) {
            if (root.child.isRemoved()) {
                continue;
            }
            Pose3d corrected = root.child.logicalPose();
            Vector3d newRootPosition = new Vector3d(corrected.position());
            Quaterniond newRootOrientation = new Quaterniond(corrected.orientation()).normalize();
            Quaterniond deltaOrientation = new Quaterniond(newRootOrientation)
                    .mul(new Quaterniond(root.oldOrientation).conjugate())
                    .normalize();
            boolean moved = newRootPosition.distanceSquared(root.oldPosition) > 1.0e-12;
            boolean rotated = 1.0 - Math.abs(deltaOrientation.w) > 1.0e-12;
            if (!moved && !rotated) {
                continue;
            }

            Set<UUID> visited = new HashSet<>();
            visited.add(root.child.getUniqueId());
            moveOrdinaryChildren(
                    root.child,
                    root.oldPosition,
                    newRootPosition,
                    deltaOrientation,
                    boundChildren,
                    visited,
                    pipeline
            );
        }
    }

    private static void moveOrdinaryChildren(
            ServerSubLevel parent,
            Vector3d oldRootPosition,
            Vector3d newRootPosition,
            Quaterniond deltaOrientation,
            Set<UUID> boundChildren,
            Set<UUID> visited,
            PhysicsPipeline pipeline
    ) {
        // Copy first: teleporting a descendant can update Sable actor state
        // and must not invalidate the plot's live actor collection.
        List<BlockEntitySubLevelActor> actors = new ArrayList<>();
        for (BlockEntitySubLevelActor actor : parent.getPlot().getBlockEntityActors()) {
            actors.add(actor);
        }

        SubLevelContainer container = SubLevelContainer.getContainer(parent.getLevel());
        for (BlockEntitySubLevelActor actor : actors) {
            if (!(actor instanceof SwivelBearingBlockEntity bearing)
                    || !bearing.isAssembled()
                    || BearingStabilizer.isBound(bearing)) {
                continue;
            }
            UUID childId = bearing.getSubLevelID();
            if (childId == null || boundChildren.contains(childId) || !visited.add(childId)) {
                continue;
            }
            SubLevel attached = container.getSubLevel(childId);
            if (!(attached instanceof ServerSubLevel child) || child.isRemoved()) {
                continue;
            }

            applyRigidDelta(
                    child,
                    oldRootPosition,
                    newRootPosition,
                    deltaOrientation,
                    pipeline
            );
            moveOrdinaryChildren(
                    child,
                    oldRootPosition,
                    newRootPosition,
                    deltaOrientation,
                    boundChildren,
                    visited,
                    pipeline
            );
        }
    }

    private static void applyRigidDelta(
            ServerSubLevel child,
            Vector3d oldRootPosition,
            Vector3d newRootPosition,
            Quaterniond deltaOrientation,
            PhysicsPipeline pipeline
    ) {
        Pose3d pose = child.logicalPose();
        Vector3d oldPosition = new Vector3d(pose.position());
        Quaterniond oldOrientation = new Quaterniond(pose.orientation()).normalize();
        Vector3d relativePosition = oldPosition.sub(oldRootPosition, new Vector3d());
        deltaOrientation.transform(relativePosition);
        Vector3d targetPosition = relativePosition.add(newRootPosition);
        Quaterniond targetOrientation = new Quaterniond(deltaOrientation)
                .mul(oldOrientation)
                .normalize();

        Vector3d oldLinearVelocity = pipeline.getLinearVelocity(child, new Vector3d());
        Vector3d oldAngularVelocity = pipeline.getAngularVelocity(child, new Vector3d());
        Vector3d targetLinearVelocity = deltaOrientation.transform(
                new Vector3d(oldLinearVelocity)
        );
        Vector3d targetAngularVelocity = deltaOrientation.transform(
                new Vector3d(oldAngularVelocity)
        );

        pipeline.teleport(child, targetPosition, targetOrientation);
        pipeline.addLinearAndAngularVelocity(
                child,
                targetLinearVelocity.sub(oldLinearVelocity),
                targetAngularVelocity.sub(oldAngularVelocity)
        );
        pipeline.wakeUp(child);
    }

    private record CapturedRoot(
            ServerSubLevel child,
            Vector3d oldPosition,
            Quaterniond oldOrientation
    ) {
    }

    private NestedBearingConstraintFollower() {
    }
}
