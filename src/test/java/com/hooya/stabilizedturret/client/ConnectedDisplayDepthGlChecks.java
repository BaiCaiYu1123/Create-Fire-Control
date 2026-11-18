package com.hooya.stabilizedturret.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Opt-in GPU checks using vertices emitted by the actual display renderer. */
public final class ConnectedDisplayDepthGlChecks {
    private static final int SIZE = 16;
    private static final int SCREEN = 0xFF2040C0;
    private static final int MODEL = 0xFFE04020;
    // View the canvas from local -Z; magnify its small layer gaps for this fixture.
    private static final Matrix4f VIEW = new Matrix4f().scale(-1, 1, 1000);

    public static void main(String[] args) throws Exception {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Mesh screen = geometry(0, SCREEN);
        Mesh map = textured("MAP_Z");
        Mesh video = textured("MAP_Z");
        Mesh control = geometry(layer("CONTROL_Z"), 0xFF20C040);
        Mesh textPlane = geometry(layer("TEXT_Z"), 0xFFC0C020);
        Mesh stroke = batch("line", new Class<?>[]{float.class, float.class, float.class,
                float.class, int.class, float.class}, -0.8F, 0F, 0.8F, 0F, SCREEN, 0.4F);
        Mesh rounded = batch("roundedControl", new Class<?>[]{float.class, float.class, float.class,
                float.class, float.class, int.class}, -10F, -10F, 10F, 10F, 6F, SCREEN);
        Mesh circle = batch("circle", new Class<?>[]{float.class, float.class, float.class,
                int.class, float.class}, 0F, 0F, 10F, SCREEN, 1F);
        require(map.vertices.getFirst().v == 0 && map.vertices.get(1).v == 1,
                "Texture top and bottom must retain the map/video row order");
        for (Mesh mesh : List.of(screen, map, video, control, textPlane)) {
            require(mesh.vertices.size() == 4, "The display must emit complete buffered quads");
            require(state(mesh.type, "depthTestState") == RenderStateShard.LEQUAL_DEPTH_TEST,
                    "Display layers must test world depth");
            require(state(mesh.type, "writeMaskState") == RenderStateShard.COLOR_DEPTH_WRITE,
                    "Display layers must write depth so models behind them stay hidden");
            require(state(mesh.type, "outputState") == RenderStateShard.MAIN_TARGET,
                    "Every display layer must use the world render target");
            for (Vertex vertex : mesh.vertices) {
                require(vertex.light == LightTexture.FULL_BRIGHT, "The display must stay full bright");
                require(vertex.colored, "Every vertex must include its color");
            }
        }
        require(screen.type == RenderType.textBackground(), "Geometry must use the world text background");
        require(map.vertices.stream().allMatch(v -> v.textured), "Image vertices must include UVs");
        for (Mesh mesh : List.of(screen, map, stroke, rounded, circle)) {
            for (int index = 0; index < mesh.vertices.size(); index += 4) {
                Vertex a = mesh.vertices.get(index);
                Vertex b = mesh.vertices.get(index + 1);
                Vertex c = mesh.vertices.get(index + 2);
                require((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x) > 0,
                        "Fills, lines, rounded controls and circles must survive front-face culling");
            }
        }

        require(GLFW.glfwInit(), "GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "Display depth checks", 0, 0);
        require(window != 0, "OpenGL context unavailable");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            int shader = program();
            GL20C.glUseProgram(shader);
            int vao = GL30C.glGenVertexArrays();
            GL30C.glBindVertexArray(vao);
            int vbo = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, 7 * Float.BYTES, 0);
            GL20C.glEnableVertexAttribArray(1);
            GL20C.glVertexAttribPointer(1, 4, GL11C.GL_FLOAT, false, 7 * Float.BYTES, 3L * Float.BYTES);
            GL11C.glViewport(0, 0, SIZE, SIZE);
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_LEQUAL);
            GL11C.glDepthMask(true);
            GL11C.glEnable(GL11C.GL_CULL_FACE);
            GL11C.glEnable(GL11C.GL_BLEND);
            GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);

            // A model's front/back depth must win regardless of block-entity submission order.
            for (Mesh display : List.of(screen, map, video, control, textPlane, stroke)) {
                for (boolean modelFirst : new boolean[]{false, true}) {
                    for (float modelZ : new float[]{-0.7F, 0.7F}) {
                        Mesh model = geometry(modelZ / 1000, MODEL);
                        clear();
                        draw(modelFirst ? model : display);
                        draw(modelFirst ? display : model);
                        expect(modelZ < 0 ? MODEL : display.vertices.getFirst().color,
                                "World occlusion must not depend on block-entity order");
                    }
                }
            }
            // Check actual production layer offsets in both upload orders.
            for (boolean reverse : new boolean[]{false, true}) {
                clear();
                List<Mesh> layers = List.of(screen, map, control, textPlane);
                for (Mesh mesh : reverse ? layers.reversed() : layers) draw(mesh);
                expect(0xFFC0C020, "Text must remain above the map and controls");
            }
            // Preserve alpha blending for the radar tint over the image.
            clear();
            draw(geometry(0, 0xFF0000FF));
            draw(geometry(layer("BASE_Z"), 0x80FF0000));
            expect(0xFF80007F, "Radar overlays must retain alpha blending");
            // Iris batches by render type, so an opaque background can be
            // uploaded after the map. Use the actual perspective depth here:
            // the enlarged orthographic VIEW above conceals depth collisions.
            for (float distance : new float[]{4, 8, 16, 32, 64}) {
                for (boolean backgroundFirst : new boolean[]{true, false}) {
                    clear();
                    Matrix4f camera = new Matrix4f().translation(0, 0, -distance).scale(-1, 1, -1);
                    Mesh background = perspectiveDepth(geometry(camera, 0, MODEL));
                    Mesh terrain = perspectiveDepth(textured(camera, "MAP_Z"));
                    draw(backgroundFirst ? background : terrain);
                    draw(backgroundFirst ? terrain : background);
                    expect(map.vertices.getFirst().color,
                            "Map must survive background batching at " + distance + " blocks");
                    // Capture the entire production backdrop, including its
                    // tint and panels, and replay Iris's grouping by type.
                    clear();
                    List<Mesh> backdrop = backdrop(distance, true);
                    drawGrouped(backdrop, backgroundFirst);
                    expect(backdrop.get(1).vertices.getFirst().color,
                            "The complete backdrop must retain terrain when grouped by render type");
                    clear();
                    drawGrouped(backdrop(distance, false), backgroundFirst);
                    expect(0xFF08111B, "Missing optional Xaero must retain the empty background");
                    clear();
                    List<Mesh> foreground = List.of(
                            perspectiveDepth(geometry(camera, 0, MODEL)),
                            perspectiveDepth(textured(camera, "MAP_Z")),
                            perspectiveDepth(geometry(camera, layer("CONTROL_Z"), 0xFF20C040)),
                            perspectiveDepth(geometry(camera, layer("FOREGROUND_Z"), 0xFFC02020)),
                            perspectiveDepth(geometry(camera, layer("TEXT_Z"), 0xFFC0C020)));
                    for (Mesh mesh : backgroundFirst ? foreground : foreground.reversed()) draw(mesh);
                    expect(0xFFC0C020, "Text and markers must stay above terrain at " + distance + " blocks");
                    for (float modelOffset : new float[]{-0.25F, 0.25F}) {
                        clear();
                        Matrix4f modelCamera = new Matrix4f().translation(0, 0, -distance - modelOffset)
                                .scale(-1, 1, -1);
                        Mesh model = perspectiveDepth(geometry(modelCamera, 0, MODEL));
                        if (backgroundFirst) draw(model);
                        drawGrouped(backdrop, backgroundFirst);
                        if (!backgroundFirst) draw(model);
                        expect(modelOffset < 0 ? MODEL : backdrop.get(1).vertices.getFirst().color,
                                "World objects must retain front/back occlusion at " + distance + " blocks");
                    }
                }
            }
            Matrix4f camera = new Matrix4f().translation(1.5F, 0.7F, -16).rotateY(0.4F);
            var point = new org.joml.Vector4f(0.2F, 0.3F, 0, 1);
            var original = camera.transform(new org.joml.Vector4f(point));
            for (String layer : List.of("MAP_Z", "BASE_Z", "CONTROL_Z", "FOREGROUND_Z", "TEXT_Z")) {
                Matrix4f matrix = (Matrix4f) invoke(ConnectedDisplayRenderer.class, null, "layerMatrix",
                        new Class<?>[]{Matrix4f.class, float.class}, camera, layer(layer));
                var biased = matrix.transform(new org.joml.Vector4f(point));
                require(Math.abs(original.x / original.z - biased.x / biased.z) < 0.000001F
                                && Math.abs(original.y / original.z - biased.y / biased.z) < 0.000001F,
                        "Depth bias must not cause visible parallax: " + layer);
            }
            require(GL11C.glGetError() == GL11C.GL_NO_ERROR, "OpenGL error during display checks");
            GL15C.glDeleteBuffers(vbo);
            GL30C.glDeleteVertexArrays(vao);
            GL20C.glDeleteProgram(shader);
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
        System.out.println("PASS: buffered display geometry/images, world depth test/write, front-face winding,"
                + " full brightness, UV orientation, foreground/background occlusion in both orders, layers and alpha;"
                + " actual backdrop grouped by type, 4-64 block perspective, markers/text, missing Xaero, no parallax.");
    }

    private static Mesh geometry(float z, int color) throws Exception {
        return geometry(VIEW, z, color);
    }

    private static Mesh geometry(Matrix4f matrix, float z, int color) throws Exception {
        return batch(matrix, "fillAt", new Class<?>[]{float.class, float.class, float.class,
                float.class, int.class, float.class}, -0.8F, -0.8F, 0.8F, 0.8F, color, z);
    }

    private static Mesh batch(String name, Class<?>[] types, Object... arguments) throws Exception {
        return batch(VIEW, name, types, arguments);
    }

    private static Mesh batch(Matrix4f matrix, String name, Class<?>[] types, Object... arguments) throws Exception {
        Class<?> batchClass = Class.forName(ConnectedDisplayRenderer.class.getName() + "$GeometryBatch");
        var constructor = batchClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object batch = constructor.newInstance();
        Capture capture = new Capture();
        invoke(batchClass, batch, "begin", new Class<?>[]{Matrix4f.class, MultiBufferSource.class}, matrix, capture);
        invoke(batchClass, batch, name, types, arguments);
        invoke(batchClass, batch, "flush", new Class<?>[0]);
        return capture.mesh;
    }

    private static Mesh textured(String layer) throws Exception {
        return textured(VIEW, layer);
    }

    private static Mesh textured(Matrix4f matrix, String layer) throws Exception {
        Capture capture = new Capture();
        invoke(ConnectedDisplayRenderer.class, null, "textured", new Class<?>[]{Matrix4f.class,
                MultiBufferSource.class, float.class, float.class, float.class, float.class,
                ResourceLocation.class, float.class}, matrix, capture, -0.8F, -0.8F, 0.8F, 0.8F,
                ResourceLocation.fromNamespaceAndPath("create_fire_control", "test/display"), layer(layer));
        return capture.mesh;
    }

    private static List<Mesh> backdrop(float distance, boolean terrain) throws Exception {
        Capture capture = new Capture();
        Class<?> batchClass = Class.forName(ConnectedDisplayRenderer.class.getName() + "$GeometryBatch");
        var constructor = batchClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Matrix4f matrix = new Matrix4f().translation(0, 0, -distance)
                .scale(-0.001F, 0.001F, -1).translate(-500, -520, 0);
        invoke(ConnectedDisplayRenderer.class, null, "renderBackdrop",
                new Class<?>[]{Matrix4f.class, MultiBufferSource.class, ResourceLocation.class, batchClass},
                matrix, capture, terrain ? ResourceLocation.parse("create_fire_control:test/map") : null,
                constructor.newInstance());
        return capture.meshes.stream().map(ConnectedDisplayDepthGlChecks::perspectiveDepth).toList();
    }

    private static void drawGrouped(List<Mesh> meshes, boolean backgroundFirst) {
        var types = meshes.stream().map(Mesh::type).distinct().toList();
        for (var type : backgroundFirst ? types : types.reversed())
            for (Mesh mesh : meshes) if (mesh.type == type) draw(mesh);
    }

    private static Object invoke(Class<?> owner, Object target, String name, Class<?>[] types,
                                 Object... arguments) throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static float layer(String name) throws Exception {
        Field field = ConnectedDisplayRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getFloat(null);
    }

    private static Object state(RenderType type, String name) throws Exception {
        Field field = type.getClass().getDeclaredField("state");
        field.setAccessible(true);
        Object state = field.get(type);
        field = state.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(state);
    }

    private static void clear() {
        GL11C.glClearColor(0, 0, 0, 1);
        GL11C.glClearDepth(1);
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);
    }

    private static Mesh perspectiveDepth(Mesh mesh) {
        Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(70), 1, 0.05F, 1024);
        List<Vertex> vertices = new ArrayList<>();
        for (Vertex source : mesh.vertices) {
            Vertex vertex = new Vertex();
            vertex.x = source.x;
            vertex.y = source.y;
            float viewZ = source.z;
            vertex.z = (projection.m22() * viewZ + projection.m32()) / -viewZ;
            vertex.color = source.color;
            vertices.add(vertex);
        }
        return new Mesh(mesh.type, vertices);
    }

    private static void draw(Mesh mesh) {
        float[] data = new float[mesh.vertices.size() / 4 * 6 * 7];
        int cursor = 0;
        for (int quad = 0; quad < mesh.vertices.size(); quad += 4)
        for (int index : new int[]{quad, quad + 1, quad + 2, quad, quad + 2, quad + 3}) {
            Vertex vertex = mesh.vertices.get(index);
            data[cursor++] = vertex.x;
            data[cursor++] = vertex.y;
            data[cursor++] = vertex.z;
            for (int shift : new int[]{16, 8, 0, 24}) data[cursor++] = (vertex.color >>> shift & 255) / 255F;
        }
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, data, GL15C.GL_STREAM_DRAW);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, data.length / 7);
    }

    private static void expect(int color, String message) {
        int[] pixel = new int[1];
        GL11C.glReadPixels(SIZE / 2, SIZE / 2, 1, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixel);
        for (int channel = 0; channel < 3; channel++) {
            int actual = pixel[0] >>> (channel * 8) & 255;
            int expected = color >>> (16 - channel * 8) & 255;
            require(Math.abs(actual - expected) <= 1, message + ": expected " + expected + ", got " + actual);
        }
    }

    private static int program() {
        int vertex = compile(GL20C.GL_VERTEX_SHADER, "#version 330 core\n"
                + "layout(location=0) in vec3 position; layout(location=1) in vec4 color; out vec4 tint;"
                + "void main(){gl_Position=vec4(position,1);tint=color;}");
        int fragment = compile(GL20C.GL_FRAGMENT_SHADER, "#version 330 core\n"
                + "in vec4 tint; out vec4 color; void main(){color=tint;}");
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertex);
        GL20C.glAttachShader(program, fragment);
        GL20C.glLinkProgram(program);
        require(GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) != 0, GL20C.glGetProgramInfoLog(program));
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        require(GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != 0, GL20C.glGetShaderInfoLog(shader));
        return shader;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Mesh(RenderType type, List<Vertex> vertices) {}

    private static final class Vertex {
        float x, y, z, u, v;
        int color, light;
        boolean colored, textured;
    }

    private static final class Capture implements MultiBufferSource, VertexConsumer {
        final List<Mesh> meshes = new ArrayList<>();
        Mesh mesh;
        Vertex vertex;
        @Override public VertexConsumer getBuffer(RenderType type) {
            mesh = new Mesh(type, new ArrayList<>());
            meshes.add(mesh);
            return this;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            vertex = new Vertex();
            vertex.x = x; vertex.y = y; vertex.z = z;
            mesh.vertices.add(vertex);
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            vertex.color = a << 24 | r << 16 | g << 8 | b;
            vertex.colored = true;
            return this;
        }
        @Override public VertexConsumer setUv(float u, float v) {
            vertex.u = u; vertex.v = v; vertex.textured = true;
            return this;
        }
        @Override public VertexConsumer setUv2(int block, int sky) {
            vertex.light = block | sky << 16;
            return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
