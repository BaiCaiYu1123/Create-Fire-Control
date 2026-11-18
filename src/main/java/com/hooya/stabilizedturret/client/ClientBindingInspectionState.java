package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.BindingInspectionPayload;
import com.hooya.stabilizedturret.network.BindingSessionPayload;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class ClientBindingInspectionState {
    private static final int DISPLAY_TICKS = 20 * 20;

    private static boolean active;
    private static int remainingTicks;
    private static List<BindingSessionPayload.Candidate> bindings = List.of();
    private static List<BindingSessionPayload.Candidate> scopeReferences = List.of();
    private static Vec3 scopeOffset = Vec3.ZERO;

    public static void accept(BindingInspectionPayload payload) {
        active = payload.active();
        remainingTicks = active ? DISPLAY_TICKS : 0;
        bindings = active ? payload.bindings() : List.of();
        scopeReferences = active ? payload.scopeReferences() : List.of();
        scopeOffset = active
                ? new Vec3(payload.scopeOffsetX(), payload.scopeOffsetY(), payload.scopeOffsetZ())
                : Vec3.ZERO;
    }

    public static void tick() {
        if (!active) return;
        if (--remainingTicks <= 0) clear();
    }

    public static boolean isActive() {
        return active;
    }

    public static List<BindingSessionPayload.Candidate> getBindings() {
        return bindings;
    }

    public static List<BindingSessionPayload.Candidate> getScopeReferences() {
        return scopeReferences;
    }

    public static Vec3 getScopeOffset() {
        return scopeOffset;
    }

    private static void clear() {
        active = false;
        remainingTicks = 0;
        bindings = List.of();
        scopeReferences = List.of();
        scopeOffset = Vec3.ZERO;
    }

    private ClientBindingInspectionState() {
    }
}
