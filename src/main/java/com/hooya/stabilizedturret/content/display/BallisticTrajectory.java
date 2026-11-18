package com.hooya.stabilizedturret.content.display;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** A single quadratic flight path whose initial tangent matches missile release. */
final class BallisticTrajectory {
    private static final int LENGTH_SAMPLES = 64;
    private static final int ADVANCE_ITERATIONS = 12;
    private static final int ADVANCE_LENGTH_SAMPLES = 6;

    private final Vec3 start;
    private final Vec3 control;

    private BallisticTrajectory(Vec3 start, Vec3 control) {
        this.start = start;
        this.control = control;
    }

    static BallisticTrajectory fromLaunch(Vec3 start, Vec3 launchDirection,
                                          Vec3 target, double requestedArcHeight) {
        Vec3 fallback = target.subtract(start);
        Vec3 direction = launchDirection.lengthSqr() >= 1.0e-12
                ? launchDirection.normalize()
                : fallback.lengthSqr() >= 1.0e-12
                ? fallback.normalize() : new Vec3(0.0, 1.0, 0.0);
        double horizontalRange = Math.hypot(target.x - start.x, target.z - start.z);
        double horizontalDirection = Math.hypot(direction.x, direction.z);
        double arcHeight = Math.max(1.0, requestedArcHeight);

        // For an ordinary angled launch, placing the control point no farther
        // than halfway across the horizontal range gives the unique y(s)
        // parabola implied by the launch elevation. Near vertical launch has
        // no finite y(s) slope, so its requested apogee bounds the control ray.
        double byHorizontal = horizontalDirection > 1.0e-6
                ? Math.max(8.0, horizontalRange * 0.5) / horizontalDirection
                : Double.POSITIVE_INFINITY;
        double byRise = direction.y > 1.0e-6
                ? arcHeight * 2.0 / direction.y
                : Double.POSITIVE_INFINITY;
        double controlDistance = Math.min(byHorizontal, byRise);
        if (!Double.isFinite(controlDistance)) {
            controlDistance = Math.max(8.0, target.distanceTo(start) * 0.5);
        }
        return new BallisticTrajectory(start,
                start.add(direction.scale(Math.max(8.0, controlDistance))));
    }

    /** Builds one smooth distance based climb and descent arc. */
    static BallisticTrajectory fromArc(Vec3 start, Vec3 target,
                                       double requestedArcHeight) {
        double arcHeight = Math.max(1.0, requestedArcHeight);
        double apex = Math.max(start.y, target.y) + arcHeight;
        Vec3 control = new Vec3(
                (start.x + target.x) * 0.5,
                2.0 * apex - (start.y + target.y) * 0.5,
                (start.z + target.z) * 0.5);
        return new BallisticTrajectory(start, control);
    }

    Vec3 point(double parameter, Vec3 target) {
        double t = Mth.clamp(parameter, 0.0, 1.0);
        double oneMinusT = 1.0 - t;
        return start.scale(oneMinusT * oneMinusT)
                .add(control.scale(2.0 * oneMinusT * t))
                .add(target.scale(t * t));
    }

    Vec3 tangent(double parameter, Vec3 target) {
        double t = Mth.clamp(parameter, 0.0, 1.0);
        return control.subtract(start).scale(2.0 * (1.0 - t))
                .add(target.subtract(control).scale(2.0 * t));
    }

    double apexParameter(Vec3 target) {
        double rise = control.y - start.y;
        double denominator = 2.0 * control.y - start.y - target.y;
        if (rise <= 0.0 || denominator <= 1.0e-8) return 0.0;
        return Mth.clamp(rise / denominator, 0.0, 1.0);
    }

    double approximateLength(Vec3 target) {
        return approximateLength(0.0, 1.0, target, LENGTH_SAMPLES);
    }

    double approximateLength(double from, double to, Vec3 target) {
        return approximateLength(from, to, target, LENGTH_SAMPLES);
    }

    double advance(double parameter, double distance, Vec3 target) {
        double from = Mth.clamp(parameter, 0.0, 1.0);
        if (from >= 1.0 || distance <= 0.0) return from;
        if (approximateLength(from, 1.0, target, LENGTH_SAMPLES) <= distance) {
            return 1.0;
        }
        double low = from;
        double high = 1.0;
        for (int iteration = 0; iteration < ADVANCE_ITERATIONS; iteration++) {
            double middle = (low + high) * 0.5;
            double length = approximateLength(
                    from, middle, target, ADVANCE_LENGTH_SAMPLES);
            if (length < distance) low = middle;
            else high = middle;
        }
        return (low + high) * 0.5;
    }

    private double approximateLength(double from, double to, Vec3 target,
                                     int samples) {
        if (to <= from) return 0.0;
        Vec3 previous = point(from, target);
        double length = 0.0;
        for (int index = 1; index <= samples; index++) {
            double t = Mth.lerp((double) index / samples, from, to);
            Vec3 current = point(t, target);
            length += current.distanceTo(previous);
            previous = current;
        }
        return length;
    }
}
