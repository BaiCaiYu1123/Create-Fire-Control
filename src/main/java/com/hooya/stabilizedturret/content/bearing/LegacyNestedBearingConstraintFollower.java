package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.compat.synaxis.SynaxisWeldCompat;
import com.hooya.stabilizedturret.compat.simulated.SimulatedMergingGlueCompat;
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
public final class LegacyNestedBearingConstraintFollower {
    public static void enforceWorldLocks(SubLevelPhysicsSystem physicsSystem) {
        if (!LegacyBearingStabilizer.hasWorldLocks(physicsSystem)) return;
        CaptureResult capture = captureBoundRoots(physicsSystem);
        LegacyBearingStabilizer.enforceWorldLocks(
                physicsSystem, capture.suspendedRootIds);
        followOrdinaryDescendants(physicsSystem, capture.capturedRoots);
    }

    private static CaptureResult captureBoundRoots(
            SubLevelPhysicsSystem physicsSystem
    ) {
        Map<UUID, CapturedRoot> captured = new HashMap<>();
        Set<UUID> suspended = new HashSet<>();
        SubLevelContainer container = SubLevelContainer.getContainer(physicsSystem.getLevel());
        for (SwivelBearingBlockEntity bearing
                : ControllerTracker.getBoundAssembledBearings(physicsSystem.getLevel())) {
            if (!BearingStabilizer.usesLegacyImplementation(bearing)
                    || !LegacyBearingStabilizer.isActive(bearing)) continue;
            UUID childId = bearing.getSubLevelID();
            SubLevel attached = childId == null ? null : container.getSubLevel(childId);
            if (attached instanceof ServerSubLevel child && !child.isRemoved()) {
                if (SimulatedMergingGlueCompat.isActivelyMerging(child)) {
                    suspended.add(childId);
                    continue;
                }
                Pose3d pose = child.logicalPose();
                captured.put(childId, new CapturedRoot(
                        child,
                        new Vector3d(pose.position()),
                        new Quaterniond(pose.orientation()).normalize(),
                        Map.of()
                ));
            }
        }

        // Capture the already-solved Synaxis weld graph without changing it.
        // The weld service remains solely responsible for creating/aliging a
        // new weld and for deciding its actual block-face anchors.
        Set<UUID> boundChildren = Set.copyOf(captured.keySet());
        for (Map.Entry<UUID, CapturedRoot> entry
                : List.copyOf(captured.entrySet())) {
            Map<UUID, ServerSubLevel> welded = new HashMap<>();
            boolean mergingWeldedBody = false;
            for (UUID weldedId : SynaxisWeldCompat.expandFixedWeldedSublevels(
                    physicsSystem.getLevel(), Set.of(entry.getKey()))) {
                if (weldedId.equals(entry.getKey())
                        || boundChildren.contains(weldedId)) {
                    continue;
                }
                SubLevel candidate = container.getSubLevel(weldedId);
                if (candidate instanceof ServerSubLevel body && !body.isRemoved()) {
                    if (SimulatedMergingGlueCompat.isActivelyMerging(body)) {
                        mergingWeldedBody = true;
                        break;
                    }
                    welded.put(weldedId, body);
                }
            }
            if (mergingWeldedBody) {
                suspended.add(entry.getKey());
                captured.remove(entry.getKey());
                continue;
            }
            CapturedRoot root = entry.getValue();
            captured.put(entry.getKey(), new CapturedRoot(
                    root.child,
                    root.oldPosition,
                    root.oldOrientation,
                    Map.copyOf(welded)
            ));
        }
        return new CaptureResult(Map.copyOf(captured), Set.copyOf(suspended));
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
        Set<UUID> visited = new HashSet<>(boundChildren);
        List<Map.Entry<UUID, CapturedRoot>> orderedRoots = new ArrayList<>(captured.entrySet());
        orderedRoots.sort(Map.Entry.comparingByKey());
        for (Map.Entry<UUID, CapturedRoot> rootEntry : orderedRoots) {
            CapturedRoot root = rootEntry.getValue();
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

            // Apply only the rigid correction that the fire-control lock made
            // this substep. We never solve or replace the weld itself, so a
            // weld can be created, edited or removed normally and its anchor
            // stays on Synaxis' selected block face instead of a body COM.
            for (Map.Entry<UUID, ServerSubLevel> welded
                    : root.weldedBodies.entrySet()) {
                if (!visited.add(welded.getKey()) || welded.getValue().isRemoved()) {
                    continue;
                }
                applyRigidDelta(
                        welded.getValue(),
                        root.oldPosition,
                        newRootPosition,
                        deltaOrientation,
                        pipeline
                );
                moveOrdinaryChildren(
                        welded.getValue(),
                        root.oldPosition,
                        newRootPosition,
                        deltaOrientation,
                        boundChildren,
                        visited,
                        pipeline
                );
            }
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
        if (SimulatedMergingGlueCompat.isActivelyMerging(parent)) return;
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
            if (SimulatedMergingGlueCompat.isActivelyMerging(child)) {
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
            Quaterniond oldOrientation,
            Map<UUID, ServerSubLevel> weldedBodies
    ) {
    }

    private record CaptureResult(
            Map<UUID, CapturedRoot> capturedRoots,
            Set<UUID> suspendedRootIds
    ) {
    }

    private LegacyNestedBearingConstraintFollower() {
    }
}
