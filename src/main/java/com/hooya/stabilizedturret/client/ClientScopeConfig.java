package com.hooya.stabilizedturret.client;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientScopeConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.DoubleValue SCOPE_ZOOM;
    private static final ModConfigSpec.DoubleValue POD_ZOOM;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Client-only fire-control sight settings").push("scope");
        SCOPE_ZOOM = builder
                .comment("Last selected optical magnification. Mouse wheel changes this while scoped.")
                .defineInRange("zoom", 4.0, 1.0, 16.0);
        POD_ZOOM = builder
                .comment("Last selected electro-optical pod magnification.")
                .defineInRange("podZoom", 4.0, 1.0, 16.0);
        builder.pop();
        SPEC = builder.build();
    }

    public static double getZoom() {
        return SCOPE_ZOOM.get();
    }

    public static void setZoom(double zoom) {
        double rounded = Math.rint(Math.max(1.0, Math.min(16.0, zoom)));
        SCOPE_ZOOM.set(rounded);
        SPEC.save();
    }

    public static double getPodZoom() {
        return POD_ZOOM.get();
    }

    public static void setPodZoom(double zoom) {
        double rounded = Math.rint(Math.max(1.0, Math.min(16.0, zoom)));
        POD_ZOOM.set(rounded);
        SPEC.save();
    }

    private ClientScopeConfig() {
    }
}
