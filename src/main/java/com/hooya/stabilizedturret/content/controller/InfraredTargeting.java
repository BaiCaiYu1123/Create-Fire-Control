package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Automatic physical-body acquisition. Neither radar tracks nor IFF participate. */
public final class InfraredTargeting {
    private static final Map<StabilizerControllerBlockEntity, State> STATES = new WeakHashMap<>();
    private static final class State {
        Frame frame;
        UUID operator;
        long receivedAt;
        long evaluatedAt = Long.MIN_VALUE;
        long lostAt = Long.MIN_VALUE;
        InfraredSeeker.Track track;
        Vec3 lastPosition;
    }

    public record Frame(Vec3 origin, Vec3 forward, Vec3 right, Vec3 up, double halfTanY, double aspect,
                        double seekerX, double seekerY, Vec3 aircraftOrigin, Vec3 aircraftForward, boolean seekerActive) {
        public Frame(Vec3 origin, Vec3 forward, Vec3 right, Vec3 up, double halfTanY, double aspect,
                     double seekerX, double seekerY, Vec3 aircraftOrigin, Vec3 aircraftForward) {
            this(origin, forward, right, up, halfTanY, aspect, seekerX, seekerY, aircraftOrigin, aircraftForward, true);
        }
        public Frame(Vec3 origin, Vec3 forward, Vec3 right, Vec3 up, double halfTanY, double aspect) {
            this(origin, forward, right, up, halfTanY, aspect, InfraredGeometry.SEEKER_X, InfraredGeometry.SEEKER_Y);
        }
        public Frame(Vec3 origin, Vec3 forward, Vec3 right, Vec3 up, double halfTanY, double aspect, double seekerX, double seekerY) {
            this(origin, forward, right, up, halfTanY, aspect, seekerX, seekerY, origin, forward);
        }
        public Frame withSeeker(double x, double y) {
            return new Frame(origin, forward, right, up, halfTanY, aspect, x, y, aircraftOrigin, aircraftForward, seekerActive);
        }
        public Frame withAircraft(Vec3 position, Vec3 direction) {
            return new Frame(origin, forward, right, up, halfTanY, aspect, seekerX, seekerY, position, direction, seekerActive);
        }
        public Frame withoutSeeker() {
            return new Frame(origin, forward, right, up, halfTanY, aspect, seekerX, seekerY, aircraftOrigin, aircraftForward, false);
        }
        public boolean valid() {
            return finite(origin) && unit(forward) && unit(right) && unit(up)
                    && Math.abs(forward.dot(right)) < .01 && Math.abs(forward.dot(up)) < .01
                    && Math.abs(right.dot(up)) < .01 && forward.cross(up).dot(right) > .99
                    && Double.isFinite(halfTanY) && halfTanY >= .001 && halfTanY <= 120
                    && Double.isFinite(aspect) && aspect >= .25 && aspect <= 8
                    && Double.isFinite(seekerX) && Double.isFinite(seekerY)
                    && Math.abs(seekerX) <= 32768 && Math.abs(seekerY) <= 32768
                    && finite(aircraftOrigin) && unit(aircraftForward) && aircraftOrigin.distanceToSqr(origin) <= 1024.0 * 1024.0;
        }
        public @Nullable Vec3 project(Vec3 world) {
            Vec3 delta = world.subtract(origin);
            double depth = delta.dot(forward);
            return depth <= .05 ? null : new Vec3(.5 + delta.dot(right) / (2 * depth * halfTanY * aspect),
                    .5 - delta.dot(up) / (2 * depth * halfTanY), depth);
        }
        public Vec3 direction(double referenceX, double referenceY) {
            return forward.add(right.scale((referenceX - InfraredGeometry.WIDTH / 2) * 2 * halfTanY / InfraredGeometry.HEIGHT))
                    .add(up.scale((InfraredGeometry.HEIGHT / 2 - referenceY) * 2 * halfTanY / InfraredGeometry.HEIGHT)).normalize();
        }
        public boolean inRange(Vec3 world) {
            return InfraredSeeker.detectable(aircraftOrigin, aircraftForward, world, InfraredGeometry.GIMBAL_TANGENT);
        }
        public Vec3 rangeCenter() {
            return aircraftOrigin.add(aircraftForward.scale(InfraredGeometry.RANGE * InfraredGeometry.GIMBAL_COS));
        }
        /**
         * A finite circular HUD angle guide, separate from the physical hemisphere.
         * Its centre moves with the aircraft axis in the current camera basis;
         * a 90-degree look places that axis one guide radius from screen centre.
         * Oblique views enlarge the guide using a bounded half-angle scale.
         * The public WT scripts establish circular drawing, not this angular mapping.
         */
        public @Nullable Vec3 rangeHudCircle() {
            double x = aircraftForward.dot(right), y = aircraftForward.dot(up), z = aircraftForward.dot(forward);
            // No forward-hemisphere direction can appear in this camera frustum.
            if (z + halfTanY * (Math.abs(y) + aspect * Math.abs(x)) < -1e-8) return null;
            // sec(viewAngle / 2): continuous at the nose and symmetric in yaw/pitch.
            // Cap the display magnification at 4x before the rear-view singularity.
            double viewScale = 1 / Math.sqrt(Math.max(1.0 / 16, (1 + Math.clamp(z, -1, 1)) * .5));
            double radius = InfraredGeometry.RADIUS * InfraredGeometry.HUD_REFERENCE_HALF_TAN_Y / halfTanY * viewScale;
            double transverse = Math.hypot(x, y);
            double scale = transverse < 1e-12 ? 0 : radius * Math.atan2(transverse, z) / (Math.PI * .5 * transverse);
            return new Vec3(InfraredGeometry.WIDTH * .5 + x * scale, InfraredGeometry.HEIGHT * .5 - y * scale, radius);
        }
        /** Keep the visible centre inside both the circular guide and the physical hemisphere. */
        public @Nullable Vec3 limitSeeker(double x, double y) {
            Vec3 circle = rangeHudCircle();
            if (circle == null) return null;
            Vec3 point = limitPhysicalSeeker(x, y);
            if (point == null) return null;
            double dx = point.x - circle.x, dy = point.y - circle.y, length = Math.hypot(dx, dy);
            if (length > circle.z) {
                point = limitPhysicalSeeker(circle.x + dx * circle.z / length, circle.y + dy * circle.z / length);
                if (point == null || Math.hypot(point.x - circle.x, point.y - circle.y) > circle.z + 1e-6) return null;
            }
            return point;
        }
        /** Physical 2 km hemisphere constraint; HUD sizing never changes eligibility. */
        private @Nullable Vec3 limitPhysicalSeeker(double x, double y) {
            if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
            Vec3 offset = origin.subtract(aircraftOrigin), ray = direction(x, y);
            double along = offset.dot(ray);
            double discriminant = along * along + InfraredGeometry.RANGE * InfraredGeometry.RANGE - offset.lengthSqr();
            if (discriminant < 0) return null;
            double distance = -along + Math.sqrt(discriminant);
            if (distance <= .05) return null;
            Vec3 point = offset.add(ray.scale(distance));
            double depth = point.dot(aircraftForward);
            if (depth >= -1e-8) return new Vec3(x, y, 0);
            Vec3 radial = point.subtract(aircraftForward.scale(depth));
            if (radial.lengthSqr() < 1e-16) return null;
            Vec3 edge = project(aircraftOrigin.add(radial.normalize().scale(InfraredGeometry.RANGE)));
            return edge == null ? null : new Vec3(InfraredGeometry.referenceX(edge.x, aspect), edge.y * InfraredGeometry.HEIGHT, 0);
        }
        /** World-space rim of the aircraft's fixed seeker cone at maximum range. */
        public Vec3 rangeBoundary(double angle) {
            Vec3 axisRight = aircraftForward.cross(new Vec3(0, 1, 0));
            if (axisRight.lengthSqr() < 1e-8) axisRight = aircraftForward.cross(new Vec3(1, 0, 0));
            axisRight = axisRight.normalize();
            Vec3 axisUp = axisRight.cross(aircraftForward).normalize();
            double radius = InfraredGeometry.RANGE * InfraredGeometry.GIMBAL_SIN;
            return rangeCenter().add(axisRight.scale(radius * Math.cos(angle))).add(axisUp.scale(radius * Math.sin(angle)));
        }
        private static boolean unit(Vec3 v) { return finite(v) && Math.abs(v.lengthSqr() - 1) < .01; }
        private static boolean finite(Vec3 v) { return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    }

    public static boolean selected(@Nullable StabilizerControllerBlockEntity controller) {
        return controller != null && !controller.isRemoved() && controller.getLevel() != null
                && controller.isInfraredWeaponArmed();
    }

    public static void updateFrame(StabilizerControllerBlockEntity controller, ServerPlayer player, Frame frame, int epoch) {
        if (!selected(controller) || !frame.valid() || player.isSpectator()
                || epoch != controller.getInfraredSelectionEpoch()
                || ControllerTracker.findForVehicle(player.level(), player.getVehicle()) != controller
                || frame.origin.distanceToSqr(MianbaoMissileCompat.getWorldPosition(player)) > 512 * 512) return;
        frame = authoritativeFrame(controller, frame);
        if (!frame.valid()) return;
        State state = STATES.computeIfAbsent(controller, ignored -> new State());
        if (!player.getUUID().equals(state.operator)) {
            clear(controller);
            state = new State();
            STATES.put(controller, state);
        }
        state.operator = player.getUUID();
        state.frame = frame;
        state.receivedAt = controller.getLevel().getGameTime();
        evaluate(controller, state);
    }

    private static void evaluate(StabilizerControllerBlockEntity controller, State state) {
        long now = controller.getLevel().getGameTime();
        if (state.evaluatedAt == now) return;
        state.evaluatedAt = now;
        if (state.track != null) {
            var observation = InfraredSeeker.observe(controller.getLevel(), state.track.id(), state.track.members(), now);
            if (observation == null || !state.frame.inRange(observation.center())
                    || !visible(controller, state.frame, observation, false)) {
                state.lostAt = now;
                state.track = null;
                return;
            }
            state.track = observation.withVelocityFrom(state.track);
            state.lastPosition = state.track.center();
            return;
        }
        if (InfraredGeometry.blinking(now, state.lostAt)) return;
        state.lostAt = Long.MIN_VALUE;
        state.lastPosition = null;
        state.track = acquire(controller, state.frame, now);
        if (state.track != null) state.lastPosition = state.track.center();
    }

    private static @Nullable InfraredSeeker.Track acquire(StabilizerControllerBlockEntity controller, Frame frame, long now) {
        if (!frame.seekerActive) return null;
        frame = authoritativeFrame(controller, frame);
        Vec3 limited = frame.limitSeeker(frame.seekerX, frame.seekerY);
        if (limited == null) return null;
        frame = frame.withSeeker(limited.x, limited.y);
        // Do not walk 25 long terrain rays when no physical body overlaps
        // the seeker disc. The bounds test is conservative; blocks decide the hit.
        boolean candidate = false;
        Vec3 candidatePixel = null;
        for (SubLevel body : SubLevelContainer.getContainer(controller.getLevel()).getAllSubLevels()) {
            if (body == null || body.isRemoved() || controller.getOwnControlledSubLevelIds().contains(body.getUniqueId())) continue;
            var local = body.getPlot().getBoundingBox();
            if (local.minX() > local.maxX() || local.minY() > local.maxY() || local.minZ() > local.maxZ()) continue;
            var box = StabilizerControllerBlockEntity.subLevelWorldBounds(body);
            if (!frame.inRange(box.getCenter())) continue;
            double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
            for (int corner = 0; corner < 8; corner++) {
                Vec3 screen = frame.project(new Vec3((corner & 1) == 0 ? box.minX : box.maxX,
                        (corner & 2) == 0 ? box.minY : box.maxY, (corner & 4) == 0 ? box.minZ : box.maxZ));
                if (screen == null) continue;
                double x = InfraredGeometry.referenceX(screen.x, frame.aspect), y = screen.y * InfraredGeometry.HEIGHT;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
            double x = Math.clamp(frame.seekerX, minX, Math.max(minX, maxX));
            double y = Math.clamp(frame.seekerY, minY, Math.max(minY, maxY));
            if (Double.isFinite(x) && Double.isFinite(y)
                    && Math.hypot(x - frame.seekerX, y - frame.seekerY) <= InfraredGeometry.SEEKER_RADIUS) {
                Vec3 middle = frame.project(box.getCenter());
                if (middle != null) {
                    double mx = InfraredGeometry.referenceX(middle.x, frame.aspect), my = middle.y * InfraredGeometry.HEIGHT;
                    if (Math.hypot(mx - frame.seekerX, my - frame.seekerY) <= InfraredGeometry.SEEKER_RADIUS)
                        candidatePixel = new Vec3(mx, my, 0);
                }
                candidate = true; break;
            }
        }
        if (!candidate) return null;
        // Sample the centre and two rings inside the drawn seeker disc. A hit
        // must be an actual physical block, not an empty part of an AABB.
        Set<UUID> own = controller.getOwnControlledSubLevelIds();
        for (int sample = 0; sample < 25; sample++) {
            // Use an actual on-disc candidate centre before the coarse grid.
            // A distant small structure can otherwise sit between all 25 samples.
            int grid = candidatePixel == null ? sample : sample - 1;
            double radius = grid <= 0 ? 0 : InfraredGeometry.SEEKER_RADIUS * (grid <= 8 ? .45 : .9);
            double angle = grid <= 8 ? (grid - 1) * Math.PI / 4 : (grid - 9) * Math.PI / 8;
            double x = sample == 0 && candidatePixel != null ? candidatePixel.x : frame.seekerX + radius * Math.cos(angle);
            double y = sample == 0 && candidatePixel != null ? candidatePixel.y : frame.seekerY + radius * Math.sin(angle);
            Vec3 end = frame.origin.add(frame.direction(x, y).scale(InfraredGeometry.RANGE + frame.origin.distanceTo(frame.aircraftOrigin)));
            var hit = InfraredRaycast.clip(controller.getLevel(), frame.origin, end, own);
            if (hit == null) continue;
            SubLevel body = SubLevelContainer.getContainer(controller.getLevel()).getSubLevel(hit.id());
            if (body == null || body.isRemoved() || own.contains(body.getUniqueId())) continue;
            Set<UUID> members = new HashSet<>();
            members.add(body.getUniqueId());
            for (SubLevel connected : SubLevelHelper.getConnectedChain(body))
                if (connected != null && !connected.isRemoved()) members.add(connected.getUniqueId());
            if (members.stream().anyMatch(own::contains)) continue;
            var track = InfraredSeeker.observe(controller.getLevel(), body.getUniqueId(), members, now);
            Vec3 screen = track == null ? null : frame.project(track.center());
            if (screen != null && frame.inRange(track.center()) && visible(controller, frame, track, false)) return track;
        }
        return null;
    }

    public static @Nullable InfraredSeeker.Track target(StabilizerControllerBlockEntity controller) {
        State state = state(controller);
        return state == null ? null : state.track;
    }
    /** Launch checks cannot reuse a position sampled before this tick's physical movement. */
    public static @Nullable InfraredSeeker.Track launchTarget(StabilizerControllerBlockEntity controller) {
        State state = state(controller);
        if (state == null || state.track == null) return null;
        var observed = InfraredSeeker.observe(controller.getLevel(), state.track.id(), state.track.members(), controller.getLevel().getGameTime());
        if (observed == null || !state.frame.inRange(observed.center())) {
            state.lostAt = controller.getLevel().getGameTime(); state.track = null;
            return null;
        }
        if (!visible(controller, state.frame, observed, true)) {
            if (!controller.isMissileLockPending(observed.id())) {
                state.lostAt = controller.getLevel().getGameTime(); state.track = null;
            }
            return null;
        }
        state.track = observed.withVelocityFrom(state.track); state.lastPosition = state.track.center();
        return state.track;
    }
    private static boolean visible(StabilizerControllerBlockEntity controller, Frame frame,
                                   InfraredSeeker.Track track, boolean fresh) {
        return controller.isMissileLockVisible(frame.origin(), track.id(), track.center(), track.bounds(), track.members(), fresh);
    }
    public static @Nullable Vec3 displayTarget(StabilizerControllerBlockEntity controller) {
        State state = state(controller);
        return state == null ? null : state.lastPosition;
    }
    public static long lostAt(StabilizerControllerBlockEntity controller) {
        State state = state(controller);
        return state == null ? Long.MIN_VALUE : state.lostAt;
    }
    public static double seekerTangent(StabilizerControllerBlockEntity controller) {
        return InfraredGeometry.GIMBAL_TANGENT;
    }
    private static @Nullable State state(StabilizerControllerBlockEntity controller) {
        if (!selected(controller)) { clear(controller); return null; }
        State state = STATES.get(controller);
        long now = controller.getLevel().getGameTime();
        if (state == null) return null;
        if (now < state.receivedAt || now - state.receivedAt > 10) { clear(controller); return null; }
        state.frame = authoritativeFrame(controller, state.frame);
        if (state.track != null && !state.frame.inRange(state.track.center())) {
            state.track = null; state.lostAt = now;
        }
        evaluate(controller, state);
        return state;
    }
    private static Frame authoritativeFrame(StabilizerControllerBlockEntity controller, Frame frame) {
        return frame.withAircraft(controller.getInfraredAircraftOrigin(), frame.aircraftForward());
    }
    public static void clear(StabilizerControllerBlockEntity controller) { STATES.remove(controller); }
    private InfraredTargeting() {}
}
