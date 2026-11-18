package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.MaidLoadingAnimationPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-only animation clocks, keyed by entity UUID so Sable mirrors use the same state. */
@OnlyIn(Dist.CLIENT)
public final class MaidLoadingClientState {
    private static final Map<UUID, AnimationClock> ACTIVE = new HashMap<>();

    public static void accept(MaidLoadingAnimationPayload payload) {
        if (!payload.active()) {
            ACTIVE.remove(payload.maidId());
            return;
        }
        long durationMillis = Math.max(1, payload.durationTicks()) * 50L;
        ACTIVE.put(payload.maidId(), new AnimationClock(System.currentTimeMillis(), durationMillis));
    }

    public static float progress(UUID maidId) {
        AnimationClock clock = ACTIVE.get(maidId);
        if (clock == null) return -1.0F;
        long elapsed = System.currentTimeMillis() - clock.startedAtMillis;
        if (elapsed < 0L || elapsed >= clock.durationMillis) {
            ACTIVE.remove(maidId);
            return -1.0F;
        }
        return (float) elapsed / (float) clock.durationMillis;
    }

    private record AnimationClock(long startedAtMillis, long durationMillis) {
    }

    private MaidLoadingClientState() {
    }
}
