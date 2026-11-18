package com.hooya.stabilizedturret.client.compat;

import com.mojang.blaze3d.vertex.BufferUploader;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;

/** Routes an immediate model draw into the existing thermal scene and world depth. */
public final class ThermalFramebufferScope implements AutoCloseable {
    private final int readFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
    private final int drawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int vertexArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
    private final int arrayBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
    private final int[] viewport = new int[4];

    public ThermalFramebufferScope(int framebuffer, int width, int height) {
        BufferUploader.invalidate();
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL11C.glViewport(0, 0, width, height);
    }

    @Override
    public void close() {
        // Never leave BufferUploader pointing at a VAO that a raw renderer has
        // unbound. Restoring only the framebuffer was insufficient here.
        BufferUploader.invalidate();
        GL30C.glBindVertexArray(vertexArray);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, arrayBuffer);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }
}
