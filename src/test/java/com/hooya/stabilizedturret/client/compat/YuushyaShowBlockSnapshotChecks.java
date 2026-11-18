package com.hooya.stabilizedturret.client.compat;

import com.hooya.stabilizedturret.mixin.StabilizedTurretMixinPlugin;
import com.yuushya.modelling.blockentity.transformData.ITransformDataInventory;
import com.yuushya.modelling.blockentity.transformData.TransformBlockData;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Reproduces the report's component reload race without a graphics context. */
public final class YuushyaShowBlockSnapshotChecks {
    private static final String QUAD_DESCRIPTOR =
            "(Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;"
                    + "Ljava/util/List;)Ljava/util/List;";
    private static final String SNAPSHOT_MIXIN =
            "com.hooya.stabilizedturret.mixin.client.YuushyaShowBlockQuadSnapshotMixin";

    public static void main(String[] args) throws Exception {
        // NeoForge's patched vanilla bootstrap consults the discovered mod list.
        // This isolated test has no loaded mods and does not open a game or world.
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        ModList.of(List.of(), List.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        reproduceOriginalFailure();
        checkDetachedGeometry();
        checkClearedArray();
        checkConcurrentReloads();
        checkUpstreamHook();
        checkOptionalModGate();
        System.out.println("PASS: Yuushya 2.4.1 reload race, detached transforms, 20,000 concurrent "
                + "snapshots, upstream quad hook and optional dependency gate");
    }

    private static void reproduceOriginalFailure() {
        List<TransformBlockData> source = new ArrayList<>(List.of(component(), component()));
        Iterator<TransformBlockData> inProgressMesh = source.iterator();
        inProgressMesh.next();
        ITransformDataInventory.load(new CompoundTag(), source);
        try {
            inProgressMesh.next();
            throw new AssertionError("the unpatched live-list iteration must reproduce the crash");
        } catch (ConcurrentModificationException expected) {
            // Same ArrayList iterator failure as ShowBlockModel.java:54 in the report.
        }
    }

    private static void checkDetachedGeometry() {
        TransformBlockData original = component();
        TransformBlockData hidden = component();
        hidden.isShown = false;
        List<TransformBlockData> source = new ArrayList<>(List.of(original, hidden));
        List<TransformBlockData> snapshot = YuushyaShowBlockRenderSnapshot.copyOf(source);
        original.pos.set(9, 8, 7);
        original.rot.set(90, 80, 70);
        original.scales.set(4, 5, 6);
        original.blockState = Blocks.GLASS.defaultBlockState();
        original.isShown = false;
        ITransformDataInventory.load(new CompoundTag(), source);

        require(snapshot.size() == 2, "reload must not replace the mesh's component list");
        TransformBlockData saved = snapshot.getFirst();
        require(saved != original && saved.pos != original.pos && saved.rot != original.rot
                        && saved.scales != original.scales, "mutable component data must be detached");
        require(saved.pos.x == 1 && saved.pos.y == 2 && saved.pos.z == 3,
                "mesh position must survive in-place edits");
        require(saved.rot.x == 10 && saved.rot.y == 20 && saved.rot.z == 30,
                "mesh rotation must survive in-place edits");
        require(saved.scales.x == 1 && saved.scales.y == 2 && saved.scales.z == 3,
                "mesh scale must survive in-place edits");
        require(saved.blockState == Blocks.STONE.defaultBlockState() && saved.isShown,
                "mesh block state and visibility must survive edits");
        require(!snapshot.get(1).isShown, "hidden components must keep their visibility and order");
    }

    private static void checkClearedArray() {
        TransformBlockData retained = component();
        List<TransformBlockData> duringClear = new ArrayList<>() {
            @Override
            public Object[] toArray() {
                return new Object[]{null, retained, null};
            }

            @Override
            public Iterator<TransformBlockData> iterator() {
                throw new AssertionError("snapshot must not iterate the editable source");
            }
        };
        List<TransformBlockData> snapshot = YuushyaShowBlockRenderSnapshot.copyOf(duringClear);
        require(snapshot.size() == 1 && snapshot.getFirst().blockState == retained.blockState,
                "concurrent clear must skip null entries without losing retained geometry");
    }

    private static void checkConcurrentReloads() throws Exception {
        List<TransformBlockData> source = new ArrayList<>(List.of(component()));
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger reloads = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> {
                CompoundTag update = new CompoundTag();
                while (!stop.get()) {
                    ITransformDataInventory.load(update, source);
                    source.add(component());
                    reloads.incrementAndGet();
                    started.countDown();
                }
            });
            try {
                require(started.await(10, java.util.concurrent.TimeUnit.SECONDS), "reload worker must start");
                for (int i = 0; i < 20_000; i++) {
                    List<TransformBlockData> snapshot = YuushyaShowBlockRenderSnapshot.copyOf(source);
                    for (TransformBlockData transform : snapshot) {
                        require(transform != null && transform.pos != null && transform.rot != null
                                && transform.scales != null && transform.blockState != null,
                                "concurrent reload must leave renderable snapshot entries");
                    }
                }
            } finally {
                stop.set(true);
            }
            writer.get();
        }
        require(reloads.get() > 0, "test must overlap real upstream component reloads");
        System.out.println("Concurrent upstream component reloads: " + reloads.get());
    }

    private static void checkUpstreamHook() throws Exception {
        ClassNode model = readClass("com/yuushya/modelling/blockentity/showblock/ShowBlockModel.class");
        require(model.methods.stream().anyMatch(method -> method.name.equals("getQuads")
                        && method.desc.equals(QUAD_DESCRIPTOR)), "snapshot's exact upstream target must exist");
        var quadMethod = model.methods.stream().filter(method -> method.name.equals("getQuads")
                && method.desc.equals(QUAD_DESCRIPTOR)).findFirst().orElseThrow();
        var firstListIterator = java.util.stream.StreamSupport.stream(quadMethod.instructions.spliterator(), false)
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && call.owner.equals("java/util/List") && call.name.equals("iterator"))
                .findFirst().orElseThrow();
        require(firstListIterator.getPrevious() instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD && load.var == 4,
                "redirect ordinal zero must snapshot the component argument, not child model quads");
        ClassNode neoModel = readClass("com/yuushya/modelling/client/NeoShowBlockModel.class");
        boolean delegates = false;
        for (var method : neoModel.methods) {
            if (!method.name.equals("getQuads")) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals("getQuads")
                        && call.owner.equals(model.name) && call.desc.equals(QUAD_DESCRIPTOR)) {
                    delegates = true;
                }
            }
        }
        require(delegates, "NeoForge world meshing must pass through the patched common model");
        try (InputStream stream = YuushyaShowBlockSnapshotChecks.class.getClassLoader()
                .getResourceAsStream("create_fire_control.mixins.json")) {
            require(stream != null && new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .contains("\"client.YuushyaShowBlockQuadSnapshotMixin\""),
                    "snapshot hook must be registered in the client mixin configuration");
        }
    }

    private static void checkOptionalModGate() throws Exception {
        StabilizedTurretMixinPlugin plugin = new StabilizedTurretMixinPlugin();
        require(!plugin.shouldApplyMixin("", SNAPSHOT_MIXIN), "absent Yuushya must skip the patch");
        var loaded = StabilizedTurretMixinPlugin.class.getDeclaredField("yuushyaModellingLoaded");
        loaded.setAccessible(true);
        loaded.setBoolean(plugin, true);
        require(plugin.shouldApplyMixin("", SNAPSHOT_MIXIN), "crash fix must work without CBC Neo Warfare");
        require(!plugin.shouldApplyMixin("", "com.hooya.stabilizedturret.mixin.client.YuushyaShowBlockModelMixin"),
                "existing cannon-only compatibility must retain its CBC Neo Warfare dependency");
    }

    private static TransformBlockData component() {
        TransformBlockData transform = new TransformBlockData();
        transform.pos.set(1, 2, 3);
        transform.rot.set(10, 20, 30);
        transform.scales.set(1, 2, 3);
        transform.blockState = Blocks.STONE.defaultBlockState();
        transform.isShown = true;
        return transform;
    }

    private static ClassNode readClass(String path) throws Exception {
        try (InputStream stream = YuushyaShowBlockSnapshotChecks.class.getClassLoader().getResourceAsStream(path)) {
            require(stream != null, "missing upstream bytecode: " + path);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG);
            return node;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
