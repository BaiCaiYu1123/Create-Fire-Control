package com.hooya.stabilizedturret.content.turbine;

/** Signed audio-only RPM. It never supplies speed or capacity to a kinetic network. */
public final class EngineSoundRpmController {
    private float rpm;
    private float synchronizedRpm;

    public float rpm() { return rpm; }

    public void restore(float value) {
        rpm = Float.isFinite(value) ? Math.max(-1024, Math.min(1024, value)) : 0;
    }

    public void synchronize(float value, boolean initialObservation) {
        synchronizedRpm = Float.isFinite(value) ? Math.max(-1024, Math.min(1024, value)) : 0;
        if (initialObservation) restore(synchronizedRpm);
    }

    /** Follow authoritative snapshots at the configured rates, without predicting ahead
     * and then snapping backward when the server stalls or packets arrive late. */
    public void tickClient(EngineControlSettings settings, boolean running, float seconds) {
        tick(settings, running, synchronizedRpm, seconds);
    }

    public void tick(EngineControlSettings settings, boolean running, float target, float seconds) {
        if (!running || !settings.soundRpmEnabled()) {
            rpm = 0;
            return;
        }
        rpm = EngineBehaviorController.advance(rpm, target,
                settings.soundRpmAcceleration(), settings.soundRpmDeceleration(), seconds);
    }

    public static float target(EngineControlSettings settings, EngineDriveBehavior behavior, boolean active) {
        if (!active || !settings.soundRpmEnabled()) return 0;
        // A zero-output behavior still revs the audio; either reversing side reverses it.
        return (behavior.leftSpeed() < 0 || behavior.rightSpeed() < 0 ? -1 : 1) * settings.soundMaxRpm();
    }

    public static boolean acceleratingMagnitude(float current, float target) {
        return Math.abs(target) > Math.abs(current) + .001F
                && (current == 0 || Math.signum(current) == Math.signum(target));
    }
}
