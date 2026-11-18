package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.client.compat.ThermalFramebufferScope;
import com.hooya.stabilizedturret.client.compat.ThermalModelBufferSource;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;

import java.util.List;
import java.util.Map;

/** Actual vanilla upload regression, including a stale cache left by a raw renderer. */
public final class ThermalModelBatchGlChecks {
    private static int draws;

    public static void main(String[] args) {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        require(GLFW.glfwInit(), "GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 5);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(16, 16, "Thermal model batching", 0, 0);
        require(window != 0, "OpenGL context unavailable");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            runChecks();
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void runChecks() {
        int color = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(color, 1, GL30C.GL_RGBA32F, 16, 16);
        int framebuffer = GL45C.glCreateFramebuffers();
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_COLOR_ATTACHMENT0, color, 0);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL11C.glViewport(0, 0, 16, 16);
        int program = program();
        GL20C.glUseProgram(program);
        FixtureLayer solid = new FixtureLayer("thermal_fixture_solid");
        FixtureLayer cutout = new FixtureLayer("thermal_fixture_cutout");

        // Reproduce the game log: the vanilla uploader believes its VAO is
        // still bound, but Flywheel/Colorwheel has unbound it through raw GL.
        try (var bytes = new ByteBufferBuilder(4096)) {
            var old = MultiBufferSource.immediate(bytes);
            triangle(old.getBuffer(solid), 0xFF4080C0);
            old.endBatch();
            require(GL11C.glGetError() == GL11C.GL_NO_ERROR, "Fixture priming failed");
            GL30C.glBindVertexArray(0);
            triangle(old.getBuffer(solid), 0xFF4080C0);
            old.endBatch();
            require(GL11C.glGetError() == GL11C.GL_INVALID_OPERATION,
                    "The raw-renderer stale-VAO failure must be reproduced");
            while (GL11C.glGetError() != GL11C.GL_NO_ERROR) { /* drain expected fixture errors */ }
        }

        int enclosingVao = GL30C.glGenVertexArrays();
        int enclosingBuffer = GL15C.glGenBuffers();
        GL30C.glBindVertexArray(enclosingVao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, enclosingBuffer);
        GL11C.glViewport(2, 3, 7, 6);
        draws = 0;
        try (var buffers = new ThermalModelBufferSource()) {
            try (var scope = new ThermalFramebufferScope(framebuffer, 16, 16)) {
                for (int part = 0; part < 1024; part++) {
                    triangle(buffers.getBuffer((part & 1) == 0 ? solid : cutout), 0xFF4080C0);
                }
                // Also disturb the raw binding between mesh construction and flush.
                GL30C.glBindVertexArray(0);
                buffers.endBatch();
            }
            require(draws == 2, "1024 alternating parts must require only two uploads/draws: " + draws);
            require(GL11C.glGetError() == GL11C.GL_NO_ERROR, "Batched draw left an OpenGL error");
            require(GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING) == enclosingVao,
                    "Enclosing raw renderer's VAO was not restored");
            require(GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING) == enclosingBuffer,
                    "Enclosing raw renderer's vertex buffer was not restored");
            float[] pixel = new float[4];
            GL11C.glReadPixels(8, 8, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, pixel);
            require(Math.abs(pixel[0] - 64f / 255) < 0.002f
                            && Math.abs(pixel[2] - 192f / 255) < 0.002f,
                    "Batched model colors must survive the real vanilla uploader");
            // Reusing a pooled builder must not repeat a previous workspace's mesh.
            draws = 0;
            try (var scope = new ThermalFramebufferScope(framebuffer, 16, 16)) {
                triangle(buffers.getBuffer(solid), 0xFF4080C0);
                buffers.endBatch();
                buffers.endBatch();
            }
            require(draws == 1, "Reused model batch repeated stale geometry");
        }
        require(GL11C.glGetError() == GL11C.GL_NO_ERROR, "OpenGL error after batch cleanup");
        BufferUploader.invalidate();
        GL30C.glDeleteVertexArrays(enclosingVao);
        GL15C.glDeleteBuffers(enclosingBuffer);
        GL20C.glDeleteProgram(program);
        GL30C.glDeleteFramebuffers(framebuffer);
        GL11C.glDeleteTextures(color);
        System.out.println("Thermal model GPU regression passed: reproduced stale-VAO error; fixed route emits no errors;"
                + " 1024 alternating parts consolidate into 2 uploads/draws; colors and enclosing VAO restored.");
    }

    private static void triangle(VertexConsumer consumer, int color) {
        consumer.addVertex(-1, -1, 0).setColor(color);
        consumer.addVertex(3, -1, 0).setColor(color);
        consumer.addVertex(-1, 3, 0).setColor(color);
    }

    private static final class FixtureLayer extends RenderType {
        FixtureLayer(String name) {
            super(name, DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES,
                    4096, false, false, () -> {}, () -> {});
        }

        @Override
        public void draw(MeshData mesh) {
            draws++;
            // The production path uses RenderType.drawWithShader; this exercises
            // its identical vanilla upload/VAO cache with a small fixture shader.
            BufferUploader.draw(mesh);
        }
    }

    private static int program() {
        int vertex = shader(GL20C.GL_VERTEX_SHADER, """
                #version 330 core
                layout(location=0) in vec3 Position;
                layout(location=1) in vec4 Color;
                out vec4 color;
                void main() { gl_Position = vec4(Position, 1); color = Color; }
                """);
        int fragment = shader(GL20C.GL_FRAGMENT_SHADER, """
                #version 330 core
                in vec4 color;
                out vec4 result;
                void main() { result = color; }
                """);
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertex);
        GL20C.glAttachShader(program, fragment);
        GL20C.glLinkProgram(program);
        require(GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) != 0, GL20C.glGetProgramInfoLog(program));
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        return program;
    }

    private static int shader(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        require(GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != 0, GL20C.glGetShaderInfoLog(shader));
        return shader;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
