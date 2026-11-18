package com.hooya.stabilizedturret.client;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientScopeConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.IntValue SCOPE_MIN_ZOOM, SCOPE_MAX_ZOOM;
    private static final ModConfigSpec.IntValue POD_MIN_ZOOM, POD_MAX_ZOOM;
    // Current wheel selections are session state, separate from the configured range.
    private static double scopeZoom = Double.NaN, podZoom = Double.NaN;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Client-only fire-control sight settings").push("scope");
        SCOPE_MIN_ZOOM = builder.comment("Minimum sight magnification. If greater than the maximum, the maximum takes precedence.")
                .defineInRange("minZoom", 1, 1, 100);
        SCOPE_MAX_ZOOM = builder.comment("Maximum sight magnification available with the mouse wheel.")
                .defineInRange("maxZoom", 32, 1, 100);
        POD_MIN_ZOOM = builder.comment("Minimum optical pod magnification. If greater than the maximum, the maximum takes precedence.")
                .defineInRange("podMinZoom", 1, 1, 100);
        POD_MAX_ZOOM = builder.comment("Maximum optical pod magnification available with the mouse wheel.")
                .defineInRange("podMaxZoom", 32, 1, 100);
        builder.pop();
        SPEC = builder.build();
    }

    public static double getZoom() {
        return scopeZoom = clamp(scopeZoom, getMinZoom(), getMaxZoom());
    }

    public static void setZoom(double zoom) {
        if (Double.isFinite(zoom)) scopeZoom = clamp(Math.rint(zoom), getMinZoom(), getMaxZoom());
    }

    public static double getPodZoom() {
        return podZoom = clamp(podZoom, getPodMinZoom(), getPodMaxZoom());
    }

    public static void setPodZoom(double zoom) {
        if (Double.isFinite(zoom)) podZoom = clamp(Math.rint(zoom), getPodMinZoom(), getPodMaxZoom());
    }

    public static int getMinZoom() { return Math.min(value(SCOPE_MIN_ZOOM), getMaxZoom()); }
    public static int getMaxZoom() { return value(SCOPE_MAX_ZOOM); }
    public static int getPodMinZoom() { return Math.min(value(POD_MIN_ZOOM), getPodMaxZoom()); }
    public static int getPodMaxZoom() { return value(POD_MAX_ZOOM); }

    private static int value(ModConfigSpec.IntValue value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    private static double clamp(double zoom, int min, int max) {
        return Double.isFinite(zoom) ? Math.max(min, Math.min(max, zoom)) : min;
    }

    private ClientScopeConfig() {
    }
}
