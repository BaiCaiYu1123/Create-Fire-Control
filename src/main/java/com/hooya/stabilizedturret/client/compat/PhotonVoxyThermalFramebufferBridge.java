package com.hooya.stabilizedturret.client.compat;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL45C;

/**
 * A color-only route into the thermal scene using Voxy's existing depth/stencil.
 * Owns only the FBO, never the textures. Photon's packed MRTs stay untouched.
 */
public final class PhotonVoxyThermalFramebufferBridge {
    private static int framebuffer;
    private static int previousDrawFramebuffer;
    private static boolean active;
    private static boolean blend;
    private static int srcRgb, dstRgb, srcAlpha, dstAlpha, equationRgb, equationAlpha;

    /** Caller gates this on Photon + thermal and an available direct-color shader. */
    public static boolean begin(int sceneTexture, boolean translucent) {
        if (active || sceneTexture == 0) {
            return false;
        }
        int source = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (source == 0) {
            return false;
        }
        int depthType = attachment(source, GL30C.GL_DEPTH_ATTACHMENT,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (depthType != GL11C.GL_TEXTURE) {
            return false;
        }
        int depth = attachment(source, GL30C.GL_DEPTH_ATTACHMENT,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        // Thermal is native resolution even if Photon normally uses TAAU.
        if (GL45C.glGetTextureLevelParameteri(depth, 0, GL11C.GL_TEXTURE_WIDTH)
                != GL45C.glGetTextureLevelParameteri(sceneTexture, 0, GL11C.GL_TEXTURE_WIDTH)
                || GL45C.glGetTextureLevelParameteri(depth, 0, GL11C.GL_TEXTURE_HEIGHT)
                != GL45C.glGetTextureLevelParameteri(sceneTexture, 0, GL11C.GL_TEXTURE_HEIGHT)) {
            return false;
        }
        if (framebuffer == 0) {
            framebuffer = GL45C.glCreateFramebuffers();
        }
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_COLOR_ATTACHMENT0, sceneTexture, 0);
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_DEPTH_ATTACHMENT, depth, 0);
        if (attachment(source, GL30C.GL_STENCIL_ATTACHMENT,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) == GL11C.GL_TEXTURE) {
            int stencil = attachment(source, GL30C.GL_STENCIL_ATTACHMENT,
                    GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_STENCIL_ATTACHMENT, stencil, 0);
        }
        GL45C.glNamedFramebufferDrawBuffer(framebuffer, GL30C.GL_COLOR_ATTACHMENT0);
        if (GL45C.glCheckNamedFramebufferStatus(framebuffer, GL30C.GL_DRAW_FRAMEBUFFER)
                != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            detach();
            return false;
        }

        previousDrawFramebuffer = source;
        blend = GL30C.glIsEnabledi(GL11C.GL_BLEND, 0);
        srcRgb = GL30C.glGetIntegeri(GL14C.GL_BLEND_SRC_RGB, 0);
        dstRgb = GL30C.glGetIntegeri(GL14C.GL_BLEND_DST_RGB, 0);
        srcAlpha = GL30C.glGetIntegeri(GL14C.GL_BLEND_SRC_ALPHA, 0);
        dstAlpha = GL30C.glGetIntegeri(GL14C.GL_BLEND_DST_ALPHA, 0);
        equationRgb = GL30C.glGetIntegeri(GL20C.GL_BLEND_EQUATION_RGB, 0);
        equationAlpha = GL30C.glGetIntegeri(GL20C.GL_BLEND_EQUATION_ALPHA, 0);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        if (translucent) {
            GL30C.glEnablei(GL11C.GL_BLEND, 0);
            GL40C.glBlendEquationSeparatei(0, GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
            // The direct shader emits straight alpha. Photon normally expects
            // premultiplied light at this output, so its blend must be overridden.
            GL40C.glBlendFuncSeparatei(0, GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                    GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            GL30C.glDisablei(GL11C.GL_BLEND, 0);
        }
        active = true;
        return true;
    }

    public static void end() {
        if (!active) {
            return;
        }
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL40C.glBlendFuncSeparatei(0, srcRgb, dstRgb, srcAlpha, dstAlpha);
        GL40C.glBlendEquationSeparatei(0, equationRgb, equationAlpha);
        if (blend) {
            GL30C.glEnablei(GL11C.GL_BLEND, 0);
        } else {
            GL30C.glDisablei(GL11C.GL_BLEND, 0);
        }
        detach();
        active = false;
    }

    public static void release() {
        end();
        if (framebuffer != 0) {
            GL30C.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
    }

    private static int attachment(int source, int attachment, int parameter) {
        return GL45C.glGetNamedFramebufferAttachmentParameteri(source, attachment, parameter);
    }

    private static void detach() {
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_COLOR_ATTACHMENT0, 0, 0);
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_DEPTH_ATTACHMENT, 0, 0);
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_STENCIL_ATTACHMENT, 0, 0);
    }

    private PhotonVoxyThermalFramebufferBridge() {
    }
}
