package com.hooya.stabilizedturret.content.controller;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Passive seeker: aircraft radar and pod power are not prerequisites. */
public final class AntiRadiationTargeting {
    public static final int SEARCH_DELAY_TICKS = 30;
    private static final Map<StabilizerControllerBlockEntity, State> STATES = new WeakHashMap<>();
    private static final class State {
        Frame frame;
        UUID operator;
        long tick;
        long searchStartedAt;
        BindingRef target;
        boolean enabled;
    }

    public record Frame(Vec3 origin, Vec3 forward, Vec3 right, Vec3 up, double halfTanY, double aspect,
                        double aimX, double aimY) {
        public Frame(Vec3 origin, Vec3 forward, Vec3 right, Vec3 up, double halfTanY, double aspect) {
            this(origin, forward, right, up, halfTanY, aspect, AntiRadiationGeometry.WIDTH / 2, AntiRadiationGeometry.HEIGHT / 2);
        }
        public Frame withAim(double x, double y) {
            return new Frame(origin, forward, right, up, halfTanY, aspect, x, y);
        }
        public boolean valid() {
            return finite(origin) && unit(forward) && unit(right) && unit(up)
                    && Math.abs(forward.dot(right)) < 0.01 && Math.abs(forward.dot(up)) < 0.01
                    && Math.abs(right.dot(up)) < 0.01 && forward.cross(up).dot(right) > 0.99
                    && Double.isFinite(halfTanY) && halfTanY >= 0.001 && halfTanY <= 120
                    && Double.isFinite(aspect) && aspect >= 0.25 && aspect <= 8
                    && Double.isFinite(aimX) && Double.isFinite(aimY)
                    && Math.abs(aimX) <= 32768 && Math.abs(aimY) <= 32768;
        }
        public @Nullable Vec3 project(Vec3 world) {
            Vec3 relative = world.subtract(origin);
            double depth = relative.dot(forward);
            if (depth <= 0.05) return null;
            return new Vec3(0.5 + relative.dot(right) / (2 * depth * halfTanY * aspect),
                    0.5 - relative.dot(up) / (2 * depth * halfTanY), depth);
        }
        public @Nullable Vec3 projectDirection(Vec3 direction) {
            double depth = direction.dot(forward);
            if (depth <= 1e-5) return null;
            return new Vec3(0.5 + direction.dot(right) / (2 * depth * halfTanY * aspect),
                    0.5 - direction.dot(up) / (2 * depth * halfTanY), depth);
        }
        public double aimDistanceSquared(Vec3 screen) {
            double x = AntiRadiationGeometry.referenceX(screen.x, aspect) - aimX;
            double y = screen.y * AntiRadiationGeometry.HEIGHT - aimY;
            return x * x + y * y;
        }
        private static boolean unit(Vec3 v) { return finite(v) && Math.abs(v.lengthSqr() - 1) < 0.01; }
        private static boolean finite(Vec3 v) { return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    }

    public static boolean selected(@Nullable StabilizerControllerBlockEntity controller) {
        return controller != null && !controller.isRemoved() && controller.getLevel() != null
                && controller.isAntiRadiationWeaponSelected();
    }

    public static boolean updateFrame(StabilizerControllerBlockEntity controller, ServerPlayer player, Frame frame) {
        if (!selected(controller) || !frame.valid() || !player.isAlive() || player.isSpectator()
                || ControllerTracker.findForVehicle(player.level(), player.getVehicle()) != controller
                || frame.origin.distanceToSqr(MianbaoMissileCompat.getWorldPosition(player)) > 512 * 512) return false;
        State state = STATES.computeIfAbsent(controller, ignored -> new State());
        if (!player.getUUID().equals(state.operator)) {
            state.target = null;
            state.enabled = false;
        }
        state.frame = frame;
        state.operator = player.getUUID();
        state.tick = controller.getLevel().getGameTime();
        acquire(controller, state);
        return true;
    }

    /** Called only by the configured air-to-ground lock input (action 10). */
    public static void toggle(StabilizerControllerBlockEntity controller, @Nullable ServerPlayer player) {
        if (!selected(controller) || player == null || !player.isAlive() || player.isSpectator()
                || ControllerTracker.findForVehicle(player.level(), player.getVehicle()) != controller) return;
        State state = STATES.computeIfAbsent(controller, ignored -> new State());
        if (!player.getUUID().equals(state.operator)) {
            state.frame = null;
            state.target = null;
            state.enabled = false;
        }
        if (state.enabled) {
            state.target = null;
            state.enabled = false;
            return;
        }
        state.operator = player.getUUID();
        state.enabled = true;
        state.searchStartedAt = controller.getLevel().getGameTime();
        acquire(controller, state);
    }

    private static void acquire(StabilizerControllerBlockEntity controller, State state) {
        if (!state.enabled || state.target != null || state.frame == null) return;
        long now = controller.getLevel().getGameTime();
        // Camera updates refresh the view, but must not restart the initial search delay.
        if (now - state.searchStartedAt < SEARCH_DELAY_TICKS) return;
        long age = now - state.tick;
        if (age < 0 || age > 10) return;
        var mount = AntiRadiationSearchFrame.from(controller);
        if (mount == null) return;
        var aperture = mount.basis();
        var own = controller.getOwnControlledSubLevelIds();
        double best = Double.POSITIVE_INFINITY;
        for (BindingRef radar : GroundRadarEmitters.active(controller.getLevel())) {
            if (GroundRadarEmitters.own(radar, controller, own)) continue;
            Vec3 position = GroundRadarEmitters.position(controller.getLevel(), radar);
            if (position == null) continue;
            Vec3 screen = state.frame.project(position);
            if (screen == null || screen.x < 0 || screen.x > 1 || screen.y < 0 || screen.y > 1
                    || !aperture.contains(position.subtract(state.frame.origin))) continue;
            double error = state.frame.aimDistanceSquared(screen);
            if (error < best) { best = error; state.target = radar; }
        }
    }

    public static @Nullable BindingRef target(StabilizerControllerBlockEntity controller) {
        State state = STATES.get(controller);
        if (state == null) return null;
        if (!selected(controller)) { clear(controller); return null; }
        if (state.target != null && GroundRadarEmitters.boundPosition(controller.getLevel(), state.target) == null)
            state.target = null;
        acquire(controller, state);
        return state.target;
    }

    public static boolean searching(StabilizerControllerBlockEntity controller) {
        State state = STATES.get(controller);
        return enabled(controller) && state.target == null;
    }

    public static boolean enabled(StabilizerControllerBlockEntity controller) {
        State state = STATES.get(controller);
        return selected(controller) && state != null && state.enabled;
    }

    public static void clear(StabilizerControllerBlockEntity controller) { STATES.remove(controller); }
    private AntiRadiationTargeting() {}
}
