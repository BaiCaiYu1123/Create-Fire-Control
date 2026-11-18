package com.hooya.stabilizedturret.client;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Reads the installed optional mods without loading or executing their code. */
public final class PhotonVoxyHookChecks {
    private static final String CORE = "me/cortex/voxy/client/core/";
    private static final String VIEWPORT = "L" + CORE + "rendering/Viewport;";
    private static final String MDIC_VIEWPORT = "L" + CORE
            + "rendering/section/backend/mdic/MDICViewport;";
    private static final String DRAW_OWNER = "org/lwjgl/opengl/ARBIndirectParameters";
    private static final String DRAW_NAME = "glMultiDrawElementsIndirectCountARB";
    private static final String DRAW_DESC = "(IIJJII)V";

    public static void main(String[] args) throws Exception {
        require(args.length == 2 && !args[0].isBlank() && !args[1].isBlank(),
                "Supply the installed Voxy and Iris JARs");
        try (JarFile voxy = new JarFile(Path.of(args[0]).toFile());
             JarFile iris = new JarFile(Path.of(args[1]).toFile())) {
            ClassNode mdic = read(voxy, CORE + "rendering/section/backend/mdic/MDICSectionRenderer");
            MethodNode terrain = method(mdic, "renderTerrain", "(" + MDIC_VIEWPORT + "JJI)V");
            require(calls(terrain, CORE + "AbstractRenderPipeline", "setupAndBindOpaque",
                    "(" + VIEWPORT + ")V") == 1, "Opaque target setup moved");
            require(calls(terrain, DRAW_OWNER, DRAW_NAME, DRAW_DESC) == 1, "Opaque draw hook moved");
            MethodNode translucent = method(mdic, "renderTranslucent", "(" + MDIC_VIEWPORT + ")V");
            require(calls(translucent, DRAW_OWNER, DRAW_NAME, DRAW_DESC) == 1,
                    "Translucent draw hook moved");
            require(calls(method(mdic, "renderTemporal", "(" + MDIC_VIEWPORT + ")V"),
                    mdic.name, "renderTerrain", terrain.desc) == 1,
                    "Temporal redraw no longer shares the opaque hook");
            require(calls(method(mdic, "buildDrawCalls", "(" + MDIC_VIEWPORT + ")V"),
                    CORE + "gl/shader/Shader", "bind", "()V") >= 2,
                    "Culling uniform upload hook moved");
            ClassNode pipeline = read(voxy, CORE + "IrisVoxyRenderPipeline");
            method(pipeline, "getRenderScalingFactor", "()[F");
            method(pipeline, "taaFunction", "(ILjava/lang/String;)Ljava/lang/String;");
            method(pipeline, "free", "()V");
            method(pipeline, "patchOpaqueShader", "(L" + CORE
                    + "rendering/section/backend/AbstractSectionRenderer;Ljava/lang/String;)Ljava/lang/String;");
            method(pipeline, "patchTranslucentShader", "(L" + CORE
                    + "rendering/section/backend/AbstractSectionRenderer;Ljava/lang/String;)Ljava/lang/String;");
            method(read(iris, "net/irisshaders/iris/Iris"), "getCurrentPackName", "()Ljava/lang/String;");
            ClassNode targets = read(iris, "net/irisshaders/iris/targets/RenderTargets");
            require(targets.fields.stream().anyMatch(field -> field.name.equals("depthSourceFb")),
                    "Iris thermal framebuffer field moved");
        }
        System.out.println("Installed Voxy/Iris hook checks passed: real opaque, temporal, translucent,"
                + " culling, TAA, scaling and Photon detection entry points exist.");
    }

    private static ClassNode read(JarFile jar, String name) throws Exception {
        var entry = jar.getJarEntry(name + ".class");
        require(entry != null, "Missing optional-mod class: " + name);
        try (InputStream stream = jar.getInputStream(entry)) {
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(method -> method.name.equals(name)
                        && method.desc.equals(descriptor)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing hook: " + owner.name + "." + name + descriptor));
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(descriptor)) count++;
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
