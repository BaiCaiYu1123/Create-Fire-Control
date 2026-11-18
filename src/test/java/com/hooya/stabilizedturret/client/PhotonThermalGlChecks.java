package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.client.compat.PhotonVoxyThermalFramebufferBridge;
import com.hooya.stabilizedturret.client.compat.ThermalFramebufferScope;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL45C;

/** Opt-in GPU regression: no Minecraft instance or visible game window needed. */
public final class PhotonThermalGlChecks {
    private static final int SIZE = 8;

    public static void main(String[] args) {
        require(GLFW.glfwInit(), "GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "Photon thermal checks", 0, 0);
        require(window != 0, "OpenGL 4.6 context unavailable");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            runChecks();
            checkQModelingThermalRouting();
            require(GL11C.glGetError() == GL11C.GL_NO_ERROR, "OpenGL error during Photon checks");
            System.out.println("Photon thermal GPU checks passed: opaque + temporal + translucent,"
                    + " depth/stencil occlusion, MRT isolation, blend/state restoration, resize, TAA switching.");
            System.out.println("qModeling thermal GPU checks passed: model color and world-depth occlusion,"
                    + " framebuffer/viewport restoration, nested rendering and exception cleanup.");
        } finally {
            PhotonVoxyThermalFramebufferBridge.release();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void checkQModelingThermalRouting() {
        int mainColor = texture(GL30C.GL_RGBA32F, SIZE);
        int thermalColor = texture(GL30C.GL_RGBA32F, SIZE);
        int depth = texture(GL30C.GL_DEPTH32F_STENCIL8, SIZE);
        int mainFb = GL45C.glCreateFramebuffers();
        int thermalFb = GL45C.glCreateFramebuffers();
        GL45C.glNamedFramebufferTexture(mainFb, GL30C.GL_COLOR_ATTACHMENT0, mainColor, 0);
        GL45C.glNamedFramebufferTexture(thermalFb, GL30C.GL_COLOR_ATTACHMENT0, thermalColor, 0);
        GL45C.glNamedFramebufferTexture(thermalFb, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, depth, 0);
        GL45C.glClearNamedFramebufferfv(mainFb, GL11C.GL_COLOR, 0, new float[]{0.73f, 0, 0, 1});
        GL45C.glClearNamedFramebufferfv(thermalFb, GL11C.GL_COLOR, 0, new float[]{0.1f, 0.1f, 0.1f, 1});
        GL45C.glClearNamedFramebufferfi(thermalFb, GL30C.GL_DEPTH_STENCIL, 0, 1, 1);
        GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
        GL11C.glScissor(0, 0, 4, SIZE);
        GL45C.glClearNamedFramebufferfi(thermalFb, GL30C.GL_DEPTH_STENCIL, 0, 0.05f, 1);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_LESS);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDepthMask(true);
        int vao = GL30C.glGenVertexArrays();
        GL30C.glBindVertexArray(vao);
        int program = program(true);
        GL20C.glUseProgram(program);
        uniform(program, "sampleColor", 0.8f, 0.8f, 0.8f, 1);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "clipZ"), -0.5f);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "createFireControlThermalEnabled"), 1);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, mainFb);
        GL11C.glViewport(1, 2, 4, 3);
        try (var scope = new ThermalFramebufferScope(thermalFb, SIZE, SIZE)) {
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
            try (var nested = new ThermalFramebufferScope(mainFb, 4, 4)) {
                require(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) == mainFb,
                        "Nested workspace must have its own render scope");
            }
            require(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) == thermalFb,
                    "Nested workspace must restore the parent thermal target");
        }
        int[] viewport = new int[4];
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        require(java.util.Arrays.equals(viewport, new int[]{1, 2, 4, 3}), "Photon viewport leaked");
        require(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) == mainFb
                        && GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING) == mainFb,
                "Thermal model draw leaked framebuffer state");
        close(pixel(thermalFb, 6, 1), 0.32f, "Model geometry must enter the thermal color buffer");
        close(pixel(thermalFb, 1, 1), 0.1f, "Model geometry must remain occluded by the world depth");
        close(pixel(mainFb, 6, 1), 0.73f, "Model draw must not disappear into the normal main target");
        try {
            try (var scope = new ThermalFramebufferScope(thermalFb, SIZE, SIZE)) {
                throw new IllegalStateException("synthetic model-render failure");
            }
        } catch (IllegalStateException expected) {
            require(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) == mainFb,
                    "A failing model renderer must restore the enclosing target");
        }
        GL20C.glDeleteProgram(program);
        GL30C.glDeleteVertexArrays(vao);
        GL30C.glDeleteFramebuffers(mainFb);
        GL30C.glDeleteFramebuffers(thermalFb);
        for (int tex : new int[]{mainColor, thermalColor, depth}) GL11C.glDeleteTextures(tex);
    }

    private static void runChecks() {
        int scene = texture(GL30C.GL_RGBA32F, SIZE);
        int packed0 = texture(GL30C.GL_RGBA32F, SIZE);
        int packed1 = texture(GL30C.GL_RGBA32F, SIZE);
        int depth = texture(GL30C.GL_DEPTH32F_STENCIL8, SIZE);
        int source = GL45C.glCreateFramebuffers();
        GL45C.glNamedFramebufferTexture(source, GL30C.GL_COLOR_ATTACHMENT0, packed0, 0);
        GL45C.glNamedFramebufferTexture(source, GL30C.GL_COLOR_ATTACHMENT1, packed1, 0);
        GL45C.glNamedFramebufferTexture(source, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, depth, 0);
        GL45C.glNamedFramebufferDrawBuffers(source,
                new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1});
        int read = GL45C.glCreateFramebuffers();
        GL45C.glNamedFramebufferTexture(read, GL30C.GL_COLOR_ATTACHMENT0, scene, 0);
        GL45C.glClearNamedFramebufferfv(read, GL11C.GL_COLOR, 0, new float[]{0.1f, 0.1f, 0.1f, 1});
        GL45C.glClearNamedFramebufferfv(source, GL11C.GL_COLOR, 0, new float[]{0.33f, 0, 0, 1});
        GL45C.glClearNamedFramebufferfv(source, GL11C.GL_COLOR, 1, new float[]{0.44f, 0, 0, 1});
        GL45C.glClearNamedFramebufferfi(source, GL30C.GL_DEPTH_STENCIL, 0, 1, 1);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, source);
        GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
        GL11C.glScissor(0, 0, SIZE / 2, SIZE);
        GL45C.glClearNamedFramebufferfi(source, GL30C.GL_DEPTH_STENCIL, 0, 0.1f, 1);
        GL11C.glScissor(6, 0, 1, 1);
        GL45C.glClearNamedFramebufferfi(source, GL30C.GL_DEPTH_STENCIL, 0, 1, 0);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
        GL11C.glViewport(0, 0, SIZE, SIZE);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_LESS);
        GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        GL11C.glStencilFunc(GL11C.GL_EQUAL, 1, 255);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
        int vao = GL30C.glGenVertexArrays();
        GL30C.glBindVertexArray(vao);
        int opaque = program(true);
        int translucent = program(false);
        GL20C.glUseProgram(opaque);
        uniform(opaque, "sampleColor", 0.8f, 0.8f, 0.8f, 1);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(opaque, "clipZ"), -0.5f);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(opaque, "createFireControlThermalEnabled"), 1);
        GL30C.glDisablei(GL11C.GL_BLEND, 0);
        require(PhotonVoxyThermalFramebufferBridge.begin(scene, false), "Opaque route rejected");
        try { GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3); }
        finally { PhotonVoxyThermalFramebufferBridge.end(); }
        close(pixel(read, 1, 1), 0.1f, "Near terrain must occlude Voxy");
        close(pixel(read, 6, 1), 0.32f, "Opaque LOD must reach the thermal scene");
        close(pixel(read, 6, 0), 0.1f, "Voxy stencil mask must be respected");
        require(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) == source,
                "Draw framebuffer not restored");

        // The second draw models renderTemporal, which uses renderTerrain again.
        GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
        GL11C.glScissor(4, 4, 4, 4);
        uniform(opaque, "sampleColor", 1, 1, 1, 1);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(opaque, "clipZ"), -0.6f);
        require(PhotonVoxyThermalFramebufferBridge.begin(scene, false), "Temporal route rejected");
        try { GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3); }
        finally { PhotonVoxyThermalFramebufferBridge.end(); }
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        close(pixel(read, 6, 6), 0.4f, "Temporal LOD must reach the same scene");

        GL20C.glUseProgram(translucent);
        uniform(translucent, "sampleColor", 0.5f, 0.5f, 0.5f, 0.5f);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(translucent, "clipZ"), -0.7f);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(translucent, "createFireControlThermalEnabled"), 1);
        GL30C.glEnablei(GL11C.GL_BLEND, 0);
        GL30C.glDisablei(GL11C.GL_BLEND, 1);
        GL40C.glBlendFuncSeparatei(0, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                GL11C.GL_ONE, GL11C.GL_ZERO);
        GL40C.glBlendEquationSeparatei(0, GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_REVERSE_SUBTRACT);
        require(PhotonVoxyThermalFramebufferBridge.begin(scene, true), "Translucent route rejected");
        try { GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3); }
        finally { PhotonVoxyThermalFramebufferBridge.end(); }
        close(pixel(read, 6, 1), 0.26f, "Water must use straight-alpha blending");
        close(pixel(read, 1, 1), 0.1f, "Water must retain near-depth occlusion");
        close(pixel(read, 6, 0), 0.1f, "Water must retain Voxy stencil occlusion");
        require(GL30C.glIsEnabledi(GL11C.GL_BLEND, 0)
                        && !GL30C.glIsEnabledi(GL11C.GL_BLEND, 1)
                        && GL30C.glGetIntegeri(GL14C.GL_BLEND_SRC_RGB, 0) == GL11C.GL_ONE
                        && GL30C.glGetIntegeri(GL14C.GL_BLEND_DST_ALPHA, 0) == GL11C.GL_ZERO
                        && GL30C.glGetIntegeri(GL20C.GL_BLEND_EQUATION_ALPHA, 0)
                        == GL14C.GL_FUNC_REVERSE_SUBTRACT,
                "Photon indexed blend state leaked");
        require(GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING) == read,
                "Read framebuffer changed");
        require(GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER1) == GL30C.GL_COLOR_ATTACHMENT1,
                "Photon MRT draw buffers changed");
        GL45C.glNamedFramebufferTexture(read, GL30C.GL_COLOR_ATTACHMENT0, packed0, 0);
        close(pixel(read, 6, 1), 0.33f, "Photon packed attachment 0 was corrupted");
        GL45C.glNamedFramebufferTexture(read, GL30C.GL_COLOR_ATTACHMENT0, packed1, 0);
        close(pixel(read, 6, 1), 0.44f, "Photon packed attachment 1 was corrupted");
        require(!PhotonVoxyThermalFramebufferBridge.begin(0, false), "Missing scene must be rejected");
        int resized = texture(GL30C.GL_RGBA32F, SIZE * 2);
        require(!PhotonVoxyThermalFramebufferBridge.begin(resized, false),
                "Mismatched dimensions must be rejected without touching the source");
        require(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) == source,
                "Failed route leaked framebuffer state");
        int resizedDepth = texture(GL30C.GL_DEPTH32F_STENCIL8, SIZE * 2);
        int resizedPacked0 = texture(GL30C.GL_RGBA32F, SIZE * 2);
        int resizedPacked1 = texture(GL30C.GL_RGBA32F, SIZE * 2);
        GL45C.glNamedFramebufferTexture(source, GL30C.GL_COLOR_ATTACHMENT0, resizedPacked0, 0);
        GL45C.glNamedFramebufferTexture(source, GL30C.GL_COLOR_ATTACHMENT1, resizedPacked1, 0);
        GL45C.glNamedFramebufferTexture(source, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, resizedDepth, 0);
        GL45C.glClearNamedFramebufferfi(source, GL30C.GL_DEPTH_STENCIL, 0, 1, 1);
        GL45C.glNamedFramebufferTexture(read, GL30C.GL_COLOR_ATTACHMENT0, resized, 0);
        GL11C.glViewport(0, 0, SIZE * 2, SIZE * 2);
        GL20C.glUseProgram(opaque);
        require(PhotonVoxyThermalFramebufferBridge.begin(resized, false),
                "Route must reattach textures after a window resize");
        try { GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3); }
        finally { PhotonVoxyThermalFramebufferBridge.end(); }
        close(pixel(read, 14, 14), 0.4f, "Resized scene must receive all LOD pixels");
        GL45C.glNamedFramebufferTexture(source, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, depth, 0);
        GL45C.glNamedFramebufferTexture(source, GL30C.GL_COLOR_ATTACHMENT0, packed0, 0);
        GL45C.glNamedFramebufferTexture(source, GL30C.GL_COLOR_ATTACHMENT1, packed1, 0);
        GL45C.glNamedFramebufferTexture(read, GL30C.GL_COLOR_ATTACHMENT0, packed1, 0);
        GL11C.glViewport(0, 0, SIZE, SIZE);

        // Both stages share one runtime uniform. Ordinary Photon keeps its jitter.
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GL30C.glDisablei(GL11C.GL_BLEND, 0);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, read);
        GL20C.glUseProgram(opaque);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(opaque, "createFireControlThermalEnabled"), 0);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        close(pixel(read, 0, 0), 0.44f, "Normal Photon jitter must shift the triangle");
        GL20C.glUniform1i(GL20C.glGetUniformLocation(opaque, "createFireControlThermalEnabled"), 1);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        close(pixel(read, 0, 0), 0.4f, "Thermal mode must remove TAA jitter immediately");

        PhotonVoxyThermalFramebufferBridge.release();
        GL20C.glDeleteProgram(opaque);
        GL20C.glDeleteProgram(translucent);
        GL30C.glDeleteVertexArrays(vao);
        GL30C.glDeleteFramebuffers(read);
        GL30C.glDeleteFramebuffers(source);
        for (int texture : new int[]{scene, packed0, packed1, depth, resized, resizedDepth,
                resizedPacked0, resizedPacked1}) GL11C.glDeleteTextures(texture);
    }

    private static int texture(int format, int size) {
        int texture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(texture, 1, format, size, size);
        return texture;
    }

    private static int program(boolean opaque) {
        String vertex = "#version 460 core\nuniform float clipZ;\n"
                + VoxyThermalShaderPatcher.patchTaa("vec2 taaShift() { return vec2(0.5); }", "taaShift")
                + "\nvoid main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);"
                + " gl_Position = vec4(p * 2.0 - 1.0 + taaShift(), clipZ, 1.0); }";
        String fragment = """
                #version 460 core
                #define PATCHED_SHADER
                #ifndef PATCHED_SHADER
                layout(location = 0) out vec4 outColour;
                #endif
                struct VoxyFragmentParameters { vec4 sampledColour; vec4 tinting; vec2 lightMap; };
                uniform vec4 sampleColor;
                layout(location = 0) out vec4 gbuffer_data_0;
                layout(location = 1) out vec4 gbuffer_data;
                void voxy_emitFragment(VoxyFragmentParameters parameters) {
                    gbuffer_data_0 = vec4(0.9);
                    gbuffer_data = vec4(0.7);
                }
                void main() {
                    VoxyFragmentParameters p;
                    p.sampledColour = sampleColor;
                    p.tinting = vec4(1.0);
                    p.lightMap = vec2(0.0);
                    voxy_emitFragment(p);
                }
                """;
        int program = GL20C.glCreateProgram();
        for (int shader : new int[]{compile(GL20C.GL_VERTEX_SHADER, vertex),
                compile(GL20C.GL_FRAGMENT_SHADER, VoxyThermalShaderPatcher.patch(fragment, opaque))}) {
            GL20C.glAttachShader(program, shader);
            GL20C.glDeleteShader(shader);
        }
        GL20C.glLinkProgram(program);
        require(GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) != 0, GL20C.glGetProgramInfoLog(program));
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        require(GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != 0, GL20C.glGetShaderInfoLog(shader));
        return shader;
    }

    private static void uniform(int program, String name, float r, float g, float b, float a) {
        GL20C.glUniform4f(GL20C.glGetUniformLocation(program, name), r, g, b, a);
    }

    private static float pixel(int read, int x, int y) {
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
        float[] rgba = new float[4];
        GL11C.glReadPixels(x, y, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, rgba);
        return rgba[0];
    }

    private static void close(float value, float expected, String message) {
        require(Math.abs(value - expected) < 0.002f, message + ": " + value + " != " + expected);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
