package com.hooya.stabilizedturret.client.compat;

import com.hooya.stabilizedturret.client.ClientAimState;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;

/** Temporarily routes Voxy's visible color output into Iris colortex0. */
public final class VoxyThermalFramebufferBridge {
    private static int framebuffer;
    private static int previousColorTexture;
    private static boolean active;

    public static void begin() {
        if (!ClientAimState.isThermal() || active) {
            return;
        }
        int targetTexture = IrisThermalFramebufferBridge.getColorTexture();
        if (targetTexture == 0) {
            return;
        }
        int current = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (current == 0) {
            return;
        }
        int type = GL45C.glGetNamedFramebufferAttachmentParameteri(
                current,
                GL30C.GL_COLOR_ATTACHMENT0,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
        );
        if (type != GL11C.GL_TEXTURE) {
            return;
        }
        int old = GL45C.glGetNamedFramebufferAttachmentParameteri(
                current,
                GL30C.GL_COLOR_ATTACHMENT0,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME
        );
        if (old == targetTexture) {
            return;
        }
        framebuffer = current;
        previousColorTexture = old;
        GL45C.glNamedFramebufferTexture(
                current,
                GL30C.GL_COLOR_ATTACHMENT0,
                targetTexture,
                0
        );
        active = true;
    }

    public static void end() {
        if (!active) {
            return;
        }
        GL45C.glNamedFramebufferTexture(
                framebuffer,
                GL30C.GL_COLOR_ATTACHMENT0,
                previousColorTexture,
                0
        );
        framebuffer = 0;
        previousColorTexture = 0;
        active = false;
    }

    private VoxyThermalFramebufferBridge() {
    }
}
