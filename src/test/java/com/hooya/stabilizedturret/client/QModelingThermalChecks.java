package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.client.compat.IrisThermalFramebufferBridge;
import com.hooya.stabilizedturret.client.compat.QModelingThermalRenderBridge;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/** Checks the installed optional mod's ABI without adding it to the test runtime. */
public final class QModelingThermalChecks {
    private static final String RENDER = "qwxon/qmodeling/client/render/";
    private static final String WORKSPACE = "Lqwxon/qmodeling/block/entity/WorkspaceBlockEntity;";

    public static void main(String[] args) throws Exception {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkModeTransitions();
        require(args.length == 1 && !args[0].isBlank(), "Supply -PqModelingJar=<installed JAR>");
        try (var jar = new JarFile(args[0])) {
            var renderer = read(jar, RENDER + "WorkspaceBlockEntityRenderer");
            var render = method(renderer, "render", "(" + WORKSPACE + "FLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;II)V");
            require(backendCalls(render) == 1, "qModeling renderer fallback gate changed");
            method(renderer, "renderNativeGeometry", "(" + WORKSPACE + "FLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;II)V");
            var visual = read(jar, RENDER + "WorkspaceFlywheelVisual");
            require(backendCalls(method(visual, "shouldRenderNativeGeometry", "(" + WORKSPACE + ")Z")) == 1,
                    "qModeling geometry/item-display fallback gate changed");
            require(backendCalls(method(visual, "shouldDisplaySnapshot", "()Z")) == 1,
                    "qModeling instance visibility gate changed");
            method(visual, "beginFrame", "(Ldev/engine_room/flywheel/api/visual/DynamicVisual$Context;)V");
            require(visual.fields.stream().anyMatch(f -> f.name.equals("nativeVisuals")
                    && f.desc.equals("Ljava/util/Map;")), "Native child storage changed");
            require(visual.fields.stream().anyMatch(f -> f.name.equals("appliedWorldLight") && f.desc.equals("I")),
                    "Paused-animation refresh field changed");
            method(read(jar, RENDER + "WorkspaceNativeVisual"), "visible", "(Z)V");
            var workspace = read(jar, "qwxon/qmodeling/block/entity/WorkspaceBlockEntity");
            method(workspace, "getFlywheelRenderRevision", "()J");
            method(workspace, "getRenderableAnimationSlot", "()I");
            method(workspace, "isAnimationPlaying", "()Z");
            method(workspace, "getRenderedPartsView", "()Ljava/util/List;");
            var part = read(jar, "qwxon/qmodeling/block/entity/WorkspacePartData");
            method(part, "isTransientForceDynamicRender", "()Z");
            method(part, "isBillboard", "()Z");
            method(part, "getBlockEntityData", "()Lnet/minecraft/nbt/CompoundTag;");
            for (String name : new String[]{"isBonePart", "isTextDisplayPart", "isImageDisplayPart",
                    "isItemDisplayPart", "isParticleEmitterPart", "isSoundEmitterPart"}) method(part, name, "()Z");
        }
        System.out.println("qModeling thermal checks passed: installed geometry, item and child-visual hooks;"
                + " shader/optic activation, deactivation and absent-Iris paths.");
    }

    private static void checkModeTransitions() throws Exception {
        ThermalRenderMode.refresh();
        require(!ThermalRenderMode.isShaderPackActive(),
                "The optional Iris dependency must remain absent from this test runtime");
        var aim = ClientAimState.class;
        try {
            set(aim, "active", true);
            set(aim, "scoped", true);
            set(aim, "thermal", true);
            set(ThermalRenderMode.class, "shaderPackActive", false);
            require(!QModelingThermalRenderBridge.useNativeGeometry(),
                    "Thermal without a shader pack must retain qModeling acceleration");
            set(ThermalRenderMode.class, "shaderPackActive", true);
            require(QModelingThermalRenderBridge.useNativeGeometry(), "Shader-pack thermal must use the fallback");
            set(aim, "thermal", false);
            require(!QModelingThermalRenderBridge.useNativeGeometry(), "Closing thermal must restore acceleration");
            set(aim, "thermal", true);
            set(aim, "active", false);
            require(!QModelingThermalRenderBridge.useNativeGeometry(), "Ending the aim session must restore acceleration");
            IrisThermalFramebufferBridge.setFramebufferId(42);
            IrisThermalFramebufferBridge.clear();
            require(IrisThermalFramebufferBridge.getFramebufferId() == 0, "Stale thermal framebuffer survived clear");
        } finally {
            set(aim, "active", false);
            set(aim, "scoped", false);
            set(aim, "thermal", false);
            set(ThermalRenderMode.class, "shaderPackActive", false);
        }
    }

    private static void set(Class<?> owner, String name, boolean value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(null, value);
    }

    private static ClassNode read(JarFile jar, String name) throws Exception {
        var entry = jar.getJarEntry(name + ".class");
        require(entry != null, "Missing class: " + name);
        try (var stream = jar.getInputStream(entry)) {
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing hook: " + node.name + "." + name + descriptor));
    }

    private static int backendCalls(MethodNode method) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("isBackendOn")
                    && call.owner.equals("dev/engine_room/flywheel/api/backend/BackendManager")
                    && call.desc.equals("()Z")) count++;
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
