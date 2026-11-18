package com.hooya.stabilizedturret.compat.simulated;

import com.hooya.stabilizedturret.compat.synaxis.SynaxisWeldCompat;
import com.hooya.stabilizedturret.mixin.PhysicsStaffDragSessionAccessor;
import com.hooya.stabilizedturret.mixin.PhysicsStaffServerHandlerAccessor;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;
import dev.simulated_team.simulated.service.SimConfigService;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only staff ownership; only the axes on the path to the grab yield their motors. */
public final class SimulatedPhysicsStaffCompat {
    /** Upcoming orientation targets of directly grabbed bodies; values do not alias staff state. */
    public static Map<UUID, Quaterniond> orientationGoals(ServerLevel level) {
        var handler = (PhysicsStaffServerHandlerAccessor) PhysicsStaffServerHandler.get(level);
        Map<UUID, Quaterniond> result = new HashMap<>();
        Set<UUID> multipleTargets = new HashSet<>();
        for (Object value : handler.stabilizedTurret$getDraggingSessions().values()) {
            var session = (PhysicsStaffDragSessionAccessor) value;
            var body = session.stabilizedTurret$getSubLevel();
            if (!session.stabilizedTurret$isMarkedForRemoval() && body != null && !body.isRemoved()) {
                UUID id = body.getUniqueId();
                if (result.put(id, new Quaterniond(session.stabilizedTurret$getOrientation())) != null) {
                    multipleTargets.add(id);
                }
            }
        }
        // Welded neighbours are deliberately not expanded: the goal describes only this body's frame.
        multipleTargets.forEach(result::remove);
        return Map.copyOf(result);
    }

    /** Match the settings read by Simulated's own upcoming staff motor. */
    public static StaffAngularMotor angularMotor() {
        var physics = SimConfigService.INSTANCE.server().physics;
        return new StaffAngularMotor(physics.physicsStaffAngularStiffness.getF(),
                physics.physicsStaffAngularDamping.getF());
    }

    public record StaffAngularMotor(double stiffness, double damping) {}

    public static Set<UUID> draggedBodies(ServerLevel level) {
        var handler = (PhysicsStaffServerHandlerAccessor) PhysicsStaffServerHandler.get(level);
        Set<UUID> result = new HashSet<>();
        for (Object value : handler.stabilizedTurret$getDraggingSessions().values()) {
            var session = (PhysicsStaffDragSessionAccessor) value;
            var body = session.stabilizedTurret$getSubLevel();
            if (!session.stabilizedTurret$isMarkedForRemoval() && body != null && !body.isRemoved()) {
                result.add(body.getUniqueId());
            }
        }
        return Set.copyOf(result);
    }

    public static Set<UUID> drivenBodies(ServerLevel level, Map<UUID, UUID> boundParents) {
        Set<UUID> grabbed = draggedBodies(level);
        if (grabbed.isEmpty()) return Set.of();
        Map<UUID, UUID> parents = new HashMap<>(boundParents);
        for (var body : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            if (body.isRemoved()) continue;
            for (BlockEntitySubLevelActor actor : body.getPlot().getBlockEntityActors()) {
                if (actor instanceof SwivelBearingBlockEntity bearing && bearing.isAssembled()
                        && bearing.getSubLevelID() != null) {
                    parents.put(bearing.getSubLevelID(), body.getUniqueId());
                }
            }
        }
        Set<UUID> roots = SynaxisWeldCompat.expandFixedWeldedSublevels(level, grabbed);
        // A staff driving the carrier is a disturbance to compensate, not ownership
        // of every turret mounted on it. Find controlled ancestors of the grab first.
        Set<UUID> controlledRoots = new HashSet<>();
        Set<UUID> visited = new HashSet<>();
        ArrayDeque<UUID> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            UUID current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (boundParents.containsKey(current)) controlledRoots.add(current);
            for (UUID welded : SynaxisWeldCompat.expandFixedWeldedSublevels(level, Set.of(current))) {
                if (!visited.contains(welded)) pending.addLast(welded);
            }
            UUID parent = parents.get(current);
            if (parent != null) pending.addLast(parent);
        }
        if (controlledRoots.isEmpty()) return Set.of();
        return SynaxisWeldCompat.expandFixedWeldedSublevels(level,controlledRoots);
    }

    private SimulatedPhysicsStaffCompat() {}
}
