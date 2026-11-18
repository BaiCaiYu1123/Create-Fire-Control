package com.hooya.stabilizedturret.client.compat;

/**
 * Holds Iris' colortex0 + world-depth framebuffer for the current thermal
 * frame without introducing an Iris compile-time dependency.
 */
public final class IrisThermalFramebufferBridge {
    private static Object framebuffer;
    private static int colorTexture;
    private static int framebufferId;

    public static void set(Object nextFramebuffer) {
        framebuffer = nextFramebuffer;
    }

    public static void setColorTexture(int texture) {
        colorTexture = texture;
    }

    public static void setFramebufferId(int id) {
        framebufferId = id;
    }

    public static int getFramebufferId() {
        return framebufferId;
    }

    public static int getColorTexture() {
        return colorTexture;
    }

    public static Object get() {
        return framebuffer;
    }

    public static void clear() {
        framebuffer = null;
        colorTexture = 0;
        framebufferId = 0;
    }

    private IrisThermalFramebufferBridge() {
    }
}
