package com.hooya.stabilizedturret.content.turbine;

import java.util.List;
import java.util.function.Predicate;

/** Keeps activation history while a simpler chord remains held underneath a newer chord. */
public final class EngineBehaviorController {
    private final boolean[] matched = new boolean[EngineControlSettings.MAX_BEHAVIORS];
    private final long[] activation = new long[EngineControlSettings.MAX_BEHAVIORS];
    private long tick;
    private EngineDriveBehavior lastBehavior = EngineDriveBehavior.defaults();
    private EngineDriveBehavior motionBehavior = lastBehavior;
    private int activeBehaviorIndex = -1;

    public void reset(EngineDriveBehavior fallback) {
        java.util.Arrays.fill(matched, false);
        java.util.Arrays.fill(activation, 0);
        tick = 0;
        lastBehavior = fallback;
        motionBehavior = fallback;
        activeBehaviorIndex = -1;
    }

    public int select(List<EngineDriveBehavior> behaviors, Predicate<String> pressed) {
        return select(behaviors, pressed, index -> false);
    }

    public int select(List<EngineDriveBehavior> behaviors, Predicate<String> pressed, java.util.function.IntPredicate redstonePowered) {
        tick++;
        int selected = -1;
        for (int i = 0; i < behaviors.size(); i++) {
            var behavior = behaviors.get(i);
            boolean active = redstonePowered.test(i)
                    || !behavior.keys().isEmpty() && behavior.keys().stream().allMatch(pressed);
            if (active && !matched[i]) activation[i] = tick;
            matched[i] = active;
            if (active && (selected < 0 || activation[i] > activation[selected]
                    || activation[i] == activation[selected]
                    && behavior.keys().size() >= behaviors.get(selected).keys().size())) selected = i;
        }
        if (selected >= 0) {
            var next = behaviors.get(selected);
            // Latch the selected transition rates until the target is reached.
            // A new switch resolves the actual outgoing/incoming pair again.
            if (selected != activeBehaviorIndex)
                motionBehavior = activeBehaviorIndex >= 0 ? EngineTransitionRules.rates(lastBehavior, next) : next;
            lastBehavior = next;
        } else {
            // Release still brakes using the last active behavior's own rates.
            motionBehavior = lastBehavior;
        }
        activeBehaviorIndex = selected;
        return selected;
    }

    public EngineDriveBehavior lastBehavior() {
        return lastBehavior;
    }

    public EngineDriveBehavior motionBehavior() { return motionBehavior; }

    public void completeTransition() { motionBehavior = lastBehavior; }

    /** Integrates each side separately, including both parts of a reversal within one tick. */
    public static float advance(float current, float target, float acceleration,
                                float deceleration, float seconds) {
        if (current == target) return target;
        if (current != 0 && target != 0 && Math.signum(current) != Math.signum(target)) {
            if (deceleration == 0) return current;
            float timeToZero = instantaneous(deceleration) ? 0 : Math.abs(current) / deceleration;
            if (timeToZero > seconds) return approach(current, 0, deceleration * seconds);
            if (instantaneous(acceleration)) return target;
            return approach(0, target, acceleration * (seconds - timeToZero));
        }
        float rate = Math.abs(target) > Math.abs(current) ? acceleration : deceleration;
        if (instantaneous(rate)) return target;
        return approach(current, target, rate * seconds);
    }

    private static boolean instantaneous(float rate) { return rate >= EngineDriveBehavior.MAX_SPEED; }

    private static float approach(float current, float target, float amount) {
        return current < target ? Math.min(target, current + amount) : Math.max(target, current - amount);
    }
}
