package com.hooya.stabilizedturret.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Verifies GPU reuse, invalidation, live body transforms and transparent index sorting. */
public final class ThermalModelMeshGlChecks {
    private static int program;

    public static void main(String[] args) {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        require(GLFW.glfwInit(), "GLFW unavailable");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 5);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(32, 32, "Thermal model cache", 0, 0);
        require(window != 0, "OpenGL context unavailable");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            program = createProgram();
            GL20C.glUseProgram(program);
            int texture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(texture, 1, GL30C.GL_RGBA32F, 32, 32);
            int framebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_COLOR_ATTACHMENT0, texture, 0);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
            GL11C.glViewport(0, 0, 32, 32);
            checkReuseAndInvalidation();
            checkTransparentCameraOrder();
            checkLiveOpaqueBehindCachedGlass();
            require(GL11C.glGetError() == GL11C.GL_NO_ERROR, "GPU cache generated an OpenGL error");
            GL30C.glDeleteFramebuffers(framebuffer);
            GL11C.glDeleteTextures(texture);
            GL20C.glDeleteProgram(program);
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
        System.out.println("Thermal mesh GPU checks passed: 300 frames reuse one mesh build; revision/light/overlay invalidation;"
                + " moving body/camera transforms; transparent camera ordering; bounded eviction and unsupported fallback.");
    }

    private static void checkReuseAndInvalidation() {
        var layer = new FixtureLayer("cached_solid", false);
        Object owner = new Object();
        AtomicInteger builds = new AtomicInteger();
        Supplier<ThermalModelMesh> build = () -> {
            builds.incrementAndGet();
            return ThermalModelMesh.capture(source -> quad(source.getBuffer(layer), 0, 0xFFFF0000));
        };
        var stamp = new ThermalModelMeshCache.Stamp(1, 10, 0, "state");
        try (var cache = new ThermalModelMeshCache(1, 1024 * 1024)) {
            var mesh = cache.get(owner, stamp, () -> true, build);
            for (int frame = 0; frame < 300; frame++) {
                require(cache.get(owner, stamp, () -> true, build) == mesh, "Stable revision rebuilt GPU vertices");
                draw(mesh, new Matrix4f().translate(frame % 2 == 0 ? -0.6f : 0.6f, 0, 0));
            }
            require(builds.get() == 1, "Static geometry must be generated only once");
            close(pixel(25)[0], 1, "Moving cached model must follow the current body matrix");
            close(pixel(6)[0], 0, "Cached model must not retain the old camera-relative position");
            var revised = cache.get(owner, new ThermalModelMeshCache.Stamp(2, 10, 0, "state"), () -> true, build);
            require(mesh.layers.getFirst().buffer.isInvalid(), "Revision invalidation leaked old GPU storage");
            cache.get(owner, new ThermalModelMeshCache.Stamp(2, 11, 0, "state"), () -> true, build);
            var pinned = cache.get(owner, new ThermalModelMeshCache.Stamp(2, 11, 1, "state"), () -> true, build);
            require(builds.get() == 4 && revised.layers.getFirst().buffer.isInvalid(),
                    "Light and overlay changes must invalidate captured vertex attributes");
            pinned.retain();
            cache.get(new Object(), stamp, () -> true, build);
            require(builds.get() == 5, "Bounded cache must create the second owner's mesh");
            require(!pinned.layers.getFirst().buffer.isInvalid(), "Eviction must not free a queued draw's GPU data");
            pinned.release();
            require(pinned.layers.getFirst().buffer.isInvalid(), "Queued GPU storage must release after its final draw");
            AtomicInteger eligibility = new AtomicInteger();
            Object dynamic = new Object();
            require(cache.get(dynamic, stamp, () -> { eligibility.incrementAndGet(); return false; }, build) == null,
                    "Dynamic parts must use the original renderer");
            require(cache.get(dynamic, stamp, () -> { eligibility.incrementAndGet(); return false; }, build) == null
                            && eligibility.get() == 1,
                    "An unchanged rejected revision must not rescan every model part each frame");
            Object unsupported = new Object();
            require(cache.get(unsupported, stamp, () -> true, () -> ThermalModelMesh.capture(source ->
                            source.getBuffer(new FixtureLayer("unsupported", false, VertexFormat.Mode.TRIANGLES)))) == null,
                    "Unsupported primitive modes must retain the live fallback");
        }
    }

    private static void checkTransparentCameraOrder() {
        var transparent = new FixtureLayer("cached_alpha", true);
        try (var mesh = ThermalModelMesh.capture(source -> {
            quad(source.getBuffer(transparent), -0.5f, 0x80FF0000);
            quad(source.getBuffer(transparent), 0.5f, 0x800000FF);
        })) {
            GL11C.glEnable(GL11C.GL_BLEND);
            GL14C.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE,
                    GL11C.GL_ONE_MINUS_SRC_ALPHA);
            draw(mesh, new Matrix4f().translate(0, 0, -2));
            float[] forward = pixel(16);
            require(forward[2] > forward[0], "Forward camera must draw the farther red quad first");
            draw(mesh, new Matrix4f().translate(0, 0, 2));
            float[] reverse = pixel(16);
            require(reverse[0] > reverse[2], "Reversed camera must update transparent index order");
            GL11C.glDisable(GL11C.GL_BLEND);
        }
    }

    private static void checkLiveOpaqueBehindCachedGlass() {
        int depth = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(depth, 1, GL14C.GL_DEPTH_COMPONENT24, 32, 32);
        int target = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        GL45C.glNamedFramebufferTexture(target, GL30C.GL_DEPTH_ATTACHMENT, depth, 0);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_LEQUAL);
        GL11C.glDepthMask(true);
        GL11C.glClearDepth(1);
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "FlattenDepth"), 0);
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "Model"), false, new Matrix4f().get(new float[16]));
        try (var live = new ThermalModelBufferSource();
             var glass = ThermalModelMesh.capture(source -> quad(source.getBuffer(new FixtureLayer("glass", true)),
                     0, 0x80FF0000))) {
            quad(live.getBuffer(new FixtureLayer("live_opaque", false)), 0.5f, 0xFF0000FF);
            live.endOpaqueBatch();
            GL11C.glEnable(GL11C.GL_BLEND);
            GL14C.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                    GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
            for (var layer : glass.layers) {
                layer.buffer.bind();
                layer.sort(new Matrix4f());
                layer.buffer.draw();
            }
            live.endBatch();
            float[] result = pixel(16);
            require(result[0] > 0.49f && result[2] > 0.49f,
                    "Live items/block entities must remain visible behind cached transparent geometry");
        }
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL45C.glNamedFramebufferTexture(target, GL30C.GL_DEPTH_ATTACHMENT, 0, 0);
        GL11C.glDeleteTextures(depth);
    }

    private static void draw(ThermalModelMesh mesh, Matrix4f model) {
        GL11C.glClearColor(0, 0, 0, 0);
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "FlattenDepth"), 1);
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "Model"), false, model.get(new float[16]));
        for (var layer : mesh.layers) {
            layer.buffer.bind();
            layer.sort(model);
            layer.buffer.draw();
        }
        VertexBuffer.unbind();
    }

    private static void quad(VertexConsumer output, float z, int color) {
        output.addVertex(-0.35f, -0.35f, z).setColor(color);
        output.addVertex(0.35f, -0.35f, z).setColor(color);
        output.addVertex(0.35f, 0.35f, z).setColor(color);
        output.addVertex(-0.35f, 0.35f, z).setColor(color);
    }

    private static float[] pixel(int x) {
        float[] result = new float[4];
        GL11C.glReadPixels(x, 16, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, result);
        return result;
    }

    private static final class FixtureLayer extends RenderType {
        FixtureLayer(String name, boolean sort) { this(name, sort, VertexFormat.Mode.QUADS); }
        FixtureLayer(String name, boolean sort, VertexFormat.Mode mode) {
            super(name, DefaultVertexFormat.POSITION_COLOR, mode, 4096, false, sort, () -> {}, () -> {});
        }

        @Override public void draw(MeshData data) { BufferUploader.draw(data); }
    }

    private static int createProgram() {
        int vertex = shader(GL20C.GL_VERTEX_SHADER, """
                #version 330 core
                layout(location=0) in vec3 Position;
                layout(location=1) in vec4 Color;
                uniform mat4 Model;
                uniform int FlattenDepth;
                out vec4 color;
                void main() { gl_Position = Model * vec4(Position, 1); if (FlattenDepth != 0) gl_Position.z = 0; color = Color; }
                """);
        int fragment = shader(GL20C.GL_FRAGMENT_SHADER, """
                #version 330 core
                in vec4 color;
                out vec4 result;
                void main() { result = color; }
                """);
        int result = GL20C.glCreateProgram();
        GL20C.glAttachShader(result, vertex);
        GL20C.glAttachShader(result, fragment);
        GL20C.glLinkProgram(result);
        require(GL20C.glGetProgrami(result, GL20C.GL_LINK_STATUS) != 0, GL20C.glGetProgramInfoLog(result));
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        return result;
    }

    private static int shader(int type, String source) {
        int result = GL20C.glCreateShader(type);
        GL20C.glShaderSource(result, source);
        GL20C.glCompileShader(result);
        require(GL20C.glGetShaderi(result, GL20C.GL_COMPILE_STATUS) != 0, GL20C.glGetShaderInfoLog(result));
        return result;
    }

    private static void close(float value, float expected, String message) {
        require(Math.abs(value - expected) < 0.002f, message + ": " + value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
