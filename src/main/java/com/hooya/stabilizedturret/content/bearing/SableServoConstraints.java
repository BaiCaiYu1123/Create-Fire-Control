package com.hooya.stabilizedturret.content.bearing;


import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Read-only copies of Sable constraint commands, including commands from other actors. */
public final class SableServoConstraints {
    private static final Map<Object, Tracked> TRACKED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ServerLevel, SceneOrder> SCENE_ORDERS = new WeakHashMap<>();
    private static final Map<String, Long> REJECTION_DETAILS = new java.util.HashMap<>();

    private SableServoConstraints() {}

    public static void created(ServerLevel level, PhysicsPipelineBody parent, PhysicsPipelineBody child,
                               PhysicsConstraintConfiguration<?> configuration, PhysicsConstraintHandle handle) {
        if (handle == null) return;
        synchronized (TRACKED) {
            SceneOrder scene = SCENE_ORDERS.computeIfAbsent(level, ignored -> new SceneOrder());
            if (!scene.known) return;
            Tracked value = new Tracked(level, parent, child, configuration,
                    scene.edges.size(), scene.nextCreationSequence++, handle.getClass().getSimpleName());
            // Sable's four addConstraint APIs each append exactly one ImpulseJoint graph edge.
            // Zero-row no-contact joints still occupy an edge slot and must participate here.
            scene.edges.add(value);
            if (TRACKED.put(handle, value) != null) markOrderUnknown(scene, "duplicate-handle");
        }
    }

    public static void motor(Object handle, ConstraintJointAxis axis, double goal, double stiffness,
                             double damping, boolean hasForceLimit, double maxForce) {
        synchronized (TRACKED) {
            Tracked value = TRACKED.get(handle);
            if (value == null || value.frame == null) return;
            int index = axis.ordinal();
            // Rapier retains the motor-axis bit even when both gains are zero.
            value.motorMask |= 1 << index;
            value.goals[index] = goal;
            value.stiffness[index] = stiffness;
            value.damping[index] = damping;
            // Sable's false flag preserves an already configured native force cap.
            if (hasForceLimit) value.caps[index] = maxForce;
            if (stiffness > 0.0 || damping > 0.0) value.activeMask |= 1 << index;
            else value.activeMask &= ~(1 << index);
        }
    }

    /** Generic free joints can change either native local frame after creation. */
    public static void frame(Object handle, boolean first, Vector3dc position, Quaterniondc orientation) {
        synchronized (TRACKED) {
            Tracked value = TRACKED.get(handle);
            if (value == null || value.frame == null) return;
            (first ? value.parentAnchor : value.childAnchor).set(position);
            (first ? value.frame : value.childFrame).set(orientation);
        }
    }

    public static void lockedAxes(Object handle, ConstraintJointAxis[] axes) {
        synchronized (TRACKED) {
            Tracked value = TRACKED.get(handle);
            // Rapier GenericJoint.lock_axes ORs new bits; passing an empty list
            // does not clear rows enabled at creation or by a preceding call.
            if (value != null && axes.length != 0) {
                value.freeAxes = false;
                for (ConstraintJointAxis axis : axes) value.lockedMask |= 1 << axis.ordinal();
            }
        }
    }

    /** Limits need extra rows that the optional world-motor model does not represent. */
    public static void limit(Object handle, ConstraintJointAxis axis, double min, double max) {
        synchronized (TRACKED) {
            Tracked value = TRACKED.get(handle);
            if (value == null) return;
            // set_limits also ORs its axis bit, even with infinite bounds. The
            // public handle has no operation that removes that native limit row.
            value.limitMask |= 1 << axis.ordinal();
        }
    }

    public static void removed(Object handle) {
        synchronized (TRACKED) {
            Tracked removed = TRACKED.remove(handle);
            if (removed == null) return;
            SceneOrder scene = SCENE_ORDERS.get(removed.level);
            if (scene == null) return;
            removeTrackedSwap(scene, removed);
        }
    }

    /** Mirrors Rapier's adjacency traversal when body removal deletes joints without handle.remove(). */
    public static void bodyRemoving(ServerLevel level, PhysicsPipelineBody body) {
        synchronized (TRACKED) {
            SceneOrder scene = SCENE_ORDERS.get(level);
            if (scene == null || !scene.known) return;
            // Graph.add_edge prepends to the outgoing list of body1 and incoming list of body2.
            // Graph.edges visits outgoing first, then incoming; each list is newest-first.
            // Swap-removing a dense edge changes its index but preserves those adjacency links.
            // ImpulseJointSet collects this whole list before deleting any of its entries.
            Comparator<Tracked> newestFirst = Comparator.comparingLong((Tracked edge) -> edge.creationSequence).reversed();
            List<Tracked> attached = new ArrayList<>();
            scene.edges.stream().filter(edge -> edge.parent == body).sorted(newestFirst).forEach(attached::add);
            scene.edges.stream().filter(edge -> edge.child == body).sorted(newestFirst).forEach(attached::add);
            for (Tracked edge : attached) {
                if (!scene.known) break;
                TRACKED.values().removeIf(value -> value == edge);
                removeTrackedSwap(scene, edge);
            }
        }
    }

    private static void removeTrackedSwap(SceneOrder scene, Tracked removed) {
        int index = (int) removed.order;
        if (index < 0 || index >= scene.edges.size() || scene.edges.get(index) != removed) {
            markOrderUnknown(scene, "inconsistent-edge-slot");
            return;
        }
        // Rapier ImpulseJointSet.remove uses Graph.remove_edge (swap-remove), then updates
        // the moved handle's EdgeIndex. Neither creation time nor arena handle index is that order.
        Tracked moved = scene.edges.remove(scene.edges.size() - 1);
        if (moved != removed) {
            scene.edges.set(index, moved);
            moved.order = index;
        }
    }

    /** Ropes create/remove native graph joints internally, outside the constraint handle API. */
    public static void internalJointsCreated(ServerLevel level) {
        synchronized (TRACKED) {
            markOrderUnknown(SCENE_ORDERS.computeIfAbsent(level, ignored -> new SceneOrder()),
                    "untracked-internal-joints");
        }
    }

    private static void markOrderUnknown(SceneOrder scene, String reason) {
        if (!scene.known) return;
        scene.known = false;
        Set<Tracked> forgotten = Collections.newSetFromMap(new IdentityHashMap<>());
        forgotten.addAll(scene.edges);
        TRACKED.values().removeIf(forgotten::contains);
        scene.edges.clear();
        REJECTION_DETAILS.merge("order-unknown:" + reason, 1L, Long::sum);
    }

    static long order(Object handle) {
        synchronized (TRACKED) {
            Tracked value = TRACKED.get(handle);
            return value == null ? Long.MAX_VALUE : value.order;
        }
    }

    public static void clear(ServerLevel level) {
        synchronized (TRACKED) {
            TRACKED.values().removeIf(value -> value.level == level);
            SCENE_ORDERS.remove(level);
        }
    }

    /** Include real rigid weld members instead of abandoning compensation on a welded vehicle. */
    static Map<UUID,Set<UUID>> fixedAdjacency(ServerLevel level) {
        Map<UUID,Set<UUID>> graph=new HashMap<>();
        synchronized (TRACKED) {
            SceneOrder scene=SCENE_ORDERS.get(level);
            if (scene==null || !scene.known) return graph;
            for (Tracked edge:scene.edges) {
                if (!edge.rigid() || !(edge.parent instanceof ServerSubLevel parent)
                        || !(edge.child instanceof ServerSubLevel child) || parent.isRemoved() || child.isRemoved()) continue;
                UUID a=parent.getUniqueId(),b=child.getUniqueId();
                graph.computeIfAbsent(a,ignored->new HashSet<>()).add(b);
                graph.computeIfAbsent(b,ignored->new HashSet<>()).add(a);
            }
        }
        return graph;
    }

    /** Orientation of source-local vectors in root-local space, from rigid joint frames.
     * Physical weld deflection must never be baked into a controller's calibration. */
    static Quaterniond fixedRotation(ServerLevel level, UUID root, UUID source) {
        if (root == null || source == null) return null;
        if (root.equals(source)) return new Quaterniond();
        synchronized (TRACKED) {
            SceneOrder scene = SCENE_ORDERS.get(level);
            if (scene == null || !scene.known) return null;
            Map<UUID, Quaterniond> rotations = new HashMap<>();
            ArrayDeque<UUID> pending = new ArrayDeque<>();
            rotations.put(root, new Quaterniond()); pending.add(root);
            while (!pending.isEmpty()) {
                UUID current = pending.removeFirst();
                for (Tracked edge : scene.edges) {
                    if (!edge.fixed() || !(edge.parent instanceof ServerSubLevel a)
                            || !(edge.child instanceof ServerSubLevel b)) continue;
                    UUID next;
                    Quaterniond relative = new Quaterniond(edge.frame).mul(new Quaterniond(edge.childFrame).conjugate());
                    if (a.getUniqueId().equals(current)) next = b.getUniqueId();
                    else if (b.getUniqueId().equals(current)) { next = a.getUniqueId(); relative.conjugate(); }
                    else continue;
                    if (rotations.containsKey(next)) continue;
                    Quaterniond rotation = new Quaterniond(rotations.get(current)).mul(relative).normalize();
                    if (next.equals(source)) return rotation;
                    rotations.put(next, rotation); pending.addLast(next);
                    if (rotations.size() > ArticulatedServoSolver.MAX_BODIES) return null;
                }
            }
            return null;
        }
    }

    /** The same physical pivot expressed in each member's nominal rigid-weld frame.
     * Solver deflection is an error to correct, not a new attachment/calibration. */
    static Map<UUID, RigidWeldGeometry.Geometry> fixedGeometry(ServerLevel level, UUID source,
                                                Vector3d sourceAnchor, Vector3d sourceAxis) {
        List<RigidWeldGeometry.Link<UUID>> links = new ArrayList<>();
        synchronized (TRACKED) {
            SceneOrder scene = SCENE_ORDERS.get(level);
            if (scene != null && scene.known) {
                for (Tracked edge : scene.edges) {
                    if (!edge.fixed() || !(edge.parent instanceof ServerSubLevel a)
                            || !(edge.child instanceof ServerSubLevel b) || a.isRemoved() || b.isRemoved()) continue;
                    links.add(new RigidWeldGeometry.Link<>(a.getUniqueId(), b.getUniqueId(),
                            new Vector3d(edge.parentAnchor), new Vector3d(edge.childAnchor),
                            new Quaterniond(edge.frame), new Quaterniond(edge.childFrame)));
                }
            }
        }
        return RigidWeldGeometry.from(source, sourceAnchor, sourceAxis, links);
    }

    static void expandFixedBodies(ServerLevel level, List<ServerSubLevel> bodies) {
        synchronized (TRACKED) {
            SceneOrder scene = SCENE_ORDERS.get(level);
            if (scene == null || !scene.known) return;
            boolean expanded;
            do {
                expanded = false;
                for (Tracked edge : scene.edges) {
                    if (!edge.fixed() || !(edge.child instanceof ServerSubLevel child)
                            || !(edge.parent instanceof ServerSubLevel parent)) continue;
                    if (bodies.contains(parent) && !bodies.contains(child)) {
                        bodies.add(child); expanded = true;
                    } else if (bodies.contains(child) && !bodies.contains(parent)) {
                        bodies.add(parent); expanded = true;
                    }
                    if (bodies.size() > ArticulatedServoSolver.MAX_BODIES) return;
                }
            } while (expanded);
        }
    }

    static List<ArticulatedServoPredictor.HingeMotor> fixedJoints(ServerLevel level,
                                                                 List<ServerSubLevel> bodies) {
        List<ArticulatedServoPredictor.HingeMotor> result = new ArrayList<>();
        synchronized (TRACKED) {
            SceneOrder scene = SCENE_ORDERS.get(level);
            if (scene == null || !scene.known) return result;
            for (Tracked edge : scene.edges) {
                if ((!edge.fixed() && !edge.angularHold()) || !(edge.child instanceof ServerSubLevel child)) continue;
                int a = edge.parent == null ? -1 : bodies.indexOf(edge.parent), b = bodies.indexOf(child);
                if (b < 0 || edge.parent != null && a < 0) continue;
                if (edge.angularHold()) {
                    result.add(edge.lockedMask==56 ? ArticulatedServoPredictor.HingeMotor.angularHold(b,edge.frame,edge.order)
                            : ArticulatedServoPredictor.HingeMotor.angularHinge(b,edge.frame,edge.childFrame,edge.order));
                    continue;
                }
                var childMass = child.getMassTracker();
                var parentMass = a < 0 ? null : bodies.get(a).getMassTracker();
                if (childMass == null || childMass.getCenterOfMass() == null
                        || a >= 0 && (parentMass == null || parentMass.getCenterOfMass() == null))
                    throw new IllegalArgumentException("Incomplete fixed-weld mass snapshot");
                Vector3d anchorA = new Vector3d(edge.parentAnchor);
                if (a >= 0) anchorA.sub(parentMass.getCenterOfMass());
                // World anchors are translated with the rest of the component by the caller.
                result.add(ArticulatedServoPredictor.HingeMotor.fixed(a, b, anchorA,
                        new Vector3d(edge.childAnchor).sub(childMass.getCenterOfMass()),
                        new Quaterniond(edge.frame), new Quaterniond(edge.childFrame), edge.order));
            }
        }
        return result;
    }

    /** Null means this component has a connection that the predictor does not model. */
    static List<ArticulatedServoPredictor.WorldMotor> worldMotors(ServerLevel level,
                                                                 List<ServerSubLevel> bodies,
                                                                 Set<Object> controlledHandles) {
        Map<PhysicsPipelineBody, Integer> indices = new IdentityHashMap<>();
        for (int i = 0; i < bodies.size(); i++) indices.put(bodies.get(i), i);
        List<ArticulatedServoPredictor.WorldMotor> result = new ArrayList<>();
        synchronized (TRACKED) {
            SceneOrder scene = SCENE_ORDERS.get(level);
            if (scene == null || !scene.known) {
                REJECTION_DETAILS.merge("unknown-native-edge-order", 1L, Long::sum);
                return null;
            }
            long firstHinge = Long.MAX_VALUE;
            Set<Tracked> controlledEdges = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object handle : controlledHandles) {
                Tracked value = TRACKED.get(handle);
                if (value == null) {
                    REJECTION_DETAILS.merge("missing-owned:" + handle.getClass().getSimpleName(), 1L, Long::sum);
                    return null;
                }
                firstHinge = Math.min(firstHinge, value.order);
                controlledEdges.add(value);
            }
            // A collected wrapper does not destroy its native joint. Read the retained edge records,
            // not the weak keys, so those joints keep both their graph slot and their solver rows.
            for (Tracked value : scene.edges) {
                if (controlledEdges.contains(value)) continue;
                if (!indices.containsKey(value.parent) && !indices.containsKey(value.child)) continue;
                if ((value.fixed() || value.angularHold()) && indices.containsKey(value.child)
                        && (value.parent == null || indices.containsKey(value.parent))) continue;
                // Sable's pairwise no-contact links are free joints with no active motor rows.
                // They suppress collisions without connecting the bodies' motion.
                if (value.frame != null && value.freeAxes && value.limitMask == 0 && value.activeMask == 0) continue;
                if (value.parent != null || !(value.child instanceof ServerSubLevel child)
                        || value.frame == null || !value.freeAxes || value.limitMask != 0 || !indices.containsKey(child)) {
                    REJECTION_DETAILS.merge("extra:" + value.handleType
                            + ":" + (value.parent == null ? "world" : value.parent.getClass().getSimpleName()
                            + "#" + value.parent.getRuntimeId() + "/" + indices.get(value.parent))
                            + ":child=" + (value.child == null ? "world" : value.child.getClass().getSimpleName()
                            + "#" + value.child.getRuntimeId() + "/" + indices.get(value.child)), 1L, Long::sum);
                    return null;
                }
                var mass = child.getMassTracker();
                if (mass == null || mass.getCenterOfMass() == null) return null;
                result.add(new ArticulatedServoPredictor.WorldMotor(indices.get(child),
                        new Quaterniond(value.frame), new Vector3d(value.childAnchor).sub(mass.getCenterOfMass()),
                        new Vector3d(value.parentAnchor), Arrays.copyOfRange(value.goals, 3, 6),
                        Arrays.copyOfRange(value.goals, 0, 3), value.stiffness.clone(), value.damping.clone(),
                        value.caps.clone(), value.activeMask, value.order < firstHinge, value.order,
                        new Quaterniond(value.childFrame)));
            }
        }
        return List.copyOf(result);
    }

    private static final class Tracked {
        final ServerLevel level;
        final PhysicsPipelineBody parent, child;
        final String handleType;
        final long creationSequence;
        long order;
        final Quaterniond frame, childFrame;
        final Vector3d parentAnchor, childAnchor;
        final double[] goals = new double[6], stiffness = new double[6], damping = new double[6];
        final double[] caps = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        int activeMask;
        int motorMask;
        boolean freeAxes;
        int lockedMask;
        int limitMask;

        boolean rigid() { return frame != null && lockedMask == 63; }
        boolean fixed() { return rigid() && limitMask == 0 && motorMask == 0; }
        boolean angularHold() { return frame!=null && parent==null && limitMask==0 && activeMask==0
                && (lockedMask==48 || lockedMask==56 && childFrame.x==0 && childFrame.y==0 && childFrame.z==0 && childFrame.w==1); }

        Tracked(ServerLevel level, PhysicsPipelineBody parent, PhysicsPipelineBody child,
                PhysicsConstraintConfiguration<?> configuration, long order, long creationSequence, String handleType) {
            this.level = level;
            this.parent = parent;
            this.child = child;
            this.order = order;
            this.creationSequence = creationSequence;
            this.handleType = handleType;
            if (configuration instanceof FixedConstraintConfiguration fixed) {
                frame = new Quaterniond(fixed.orientation());
                childFrame = new Quaterniond();
                parentAnchor = new Vector3d(fixed.pos1());
                childAnchor = new Vector3d(fixed.pos2());
                lockedMask = 63;
            } else if (configuration instanceof FreeConstraintConfiguration free) {
                frame = new Quaterniond(free.orientation());
                childFrame = new Quaterniond();
                parentAnchor = new Vector3d(free.pos1());
                childAnchor = new Vector3d(free.pos2());
                freeAxes = true;
            } else if (configuration instanceof GenericConstraintConfiguration generic) {
                frame = new Quaterniond(generic.orientation1());
                childFrame = new Quaterniond(generic.orientation2());
                parentAnchor = new Vector3d(generic.pos1());
                childAnchor = new Vector3d(generic.pos2());
                freeAxes = generic.lockedAxes().isEmpty();
                for (ConstraintJointAxis axis : generic.lockedAxes()) lockedMask |= 1 << axis.ordinal();
            } else {
                frame = childFrame = null;
                parentAnchor = childAnchor = null;
            }
        }
    }

    private static final class SceneOrder {
        // Entries outlive weak handle keys: GC of a Java wrapper does not remove its native edge.
        final List<Tracked> edges = new ArrayList<>();
        long nextCreationSequence;
        boolean known = true;
    }
}
