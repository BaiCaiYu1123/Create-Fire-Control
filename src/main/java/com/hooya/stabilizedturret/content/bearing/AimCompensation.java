package com.hooya.stabilizedturret.content.bearing;

/** The part of an axis's acquired command that came from muzzle compensation. */
public final class AimCompensation {
    private double requested;
    private double acquired;

    public void request(double radians, boolean automatic) {
        if (!Double.isFinite(radians)) return;
        if (automatic) acquired += radians - requested;
        requested = radians;
    }

    /** Advance with the actual operator reference, including its braking and reversal. */
    public void advance(double remaining, double step) {
        if (!Double.isFinite(remaining) || !Double.isFinite(step)) return;
        // Old-direction coasting does not acquire the new correction. Dividing
        // that step by a nearly zero reverse error would invent an enormous angle.
        if (step * remaining < 0) return;
        // OperatorMotion stops advancing below this same arrival tolerance. Finish
        // acquiring the correction there, or a cancelled/reversed goal can leave
        // a visible correction residual even though its reference has arrived.
        double fraction = Math.abs(remaining) < 1e-8 ? 1 : Math.clamp(step / remaining, 0, 1);
        acquired += (requested - acquired) * fraction;
    }

    public double acquired() { return acquired; }
    public double requested() { return requested; }
    public void reset() { requested = acquired = 0; }
}
