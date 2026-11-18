package com.hooya.stabilizedturret.client.compat;

import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonRecoilAnimation;
import com.mojang.blaze3d.pipeline.RenderCall;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yuushya.modelling.blockentity.itemblock.ItemBlockEntity;
import com.yuushya.modelling.blockentity.showblock.ShowBlockEntity;
import com.yuushya.modelling.blockentity.transformData.TransformBlockData;
import com.yuushya.modelling.blockentity.transformData.TransformItemData;
import com.yuushya.modelling.client.anvilcraft.rendering.CachedRegion;
import com.yuushya.modelling.client.anvilcraft.rendering.FullyBufferedBufferSource;
import com.yuushya.modelling.registries.DataComponentRegistry;
import com.yuushya.modelling.utils.YuushyaUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Caches ordinary component quads; custom renderers run every frame with the caller's full pose
 * and buffers. Recoil changes only the draw pose, not the cached geometry.
 */
public final class YuushyaCannonComponentRenderer {
    private static final List<Direction> DIRECTIONS = createDirections();
    private static final int MAX_CACHED_MESHES = 256;
    private static final long HASH_SEED = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;
    private static final Map<BlockEntity, CachedMesh> MESH_CACHE =
            new LinkedHashMap<>(32, 0.75F, true);
    private static final Object CACHE_LOCK = new Object();
    private static int rendersUntilCleanup = 120;

    public static void render(
            ItemBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            int overlay
    ) {
        List<ItemComponent> components = resolveItemComponents(blockEntity);
        if (com.hooya.stabilizedturret.client.preview.PreviewCaptureScope.active()) {
            poseStack.pushPose();
            try { applyRecoil(blockEntity,partialTick,poseStack);emitItemMesh(blockEntity,components,poseStack,buffer,light,overlay); }
            finally { poseStack.popPose(); }
            renderLiveItems(blockEntity,components,partialTick,poseStack,buffer,light,overlay);
            return;
        }
        CachedMesh mesh = getOrBuild(
                blockEntity,
                itemSignature(blockEntity, components, light, overlay),
                () -> buildItemMesh(blockEntity, components, light, overlay)
        );
        drawWithRecoil(mesh, blockEntity, partialTick, poseStack);
        renderLiveItems(blockEntity, components, partialTick, poseStack, buffer, light, overlay);
    }

    public static void render(
            ShowBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            int overlay
    ) {
        if (com.hooya.stabilizedturret.client.preview.PreviewCaptureScope.active()) {
            poseStack.pushPose();
            try { applyRecoil(blockEntity,partialTick,poseStack);emitShowMesh(blockEntity,poseStack,buffer,light,overlay); }
            finally { poseStack.popPose(); }
            renderLiveBlocks(blockEntity,partialTick,poseStack,buffer,light,overlay);
            return;
        }
        CachedMesh mesh = getOrBuild(
                blockEntity,
                showSignature(blockEntity, light, overlay),
                () -> buildShowMesh(blockEntity, light, overlay)
        );
        drawWithRecoil(mesh, blockEntity, partialTick, poseStack);
        renderLiveBlocks(blockEntity, partialTick, poseStack, buffer, light, overlay);
    }

    /** Called when baked models are replaced during a resource reload. */
    public static void clearCache() {
        List<CachedMesh> meshes;
        synchronized (CACHE_LOCK) {
            meshes = new ArrayList<>(MESH_CACHE.values());
            MESH_CACHE.clear();
        }
        closeOnRenderThread(meshes);
    }

    private static CachedMesh getOrBuild(
            BlockEntity blockEntity,
            long signature,
            Supplier<CachedMesh> builder
    ) {
        synchronized (CACHE_LOCK) {
            cleanupRemovedEntries();
            CachedMesh cached = MESH_CACHE.get(blockEntity);
            if (cached != null && cached.signature == signature) {
                return cached;
            }
            if (cached != null) {
                cached.close();
            }

            CachedMesh rebuilt = builder.get();
            rebuilt.signature = signature;
            MESH_CACHE.put(blockEntity, rebuilt);
            evictOldestEntries();
            return rebuilt;
        }
    }

    private static void closeOnRenderThread(List<CachedMesh> meshes) {
        if (meshes.isEmpty()) {
            return;
        }
        RenderCall close = () -> meshes.forEach(CachedMesh::close);
        if (RenderSystem.isOnRenderThread()) {
            close.execute();
        } else {
            RenderSystem.recordRenderCall(close);
        }
    }

    private static List<ItemComponent> resolveItemComponents(ItemBlockEntity blockEntity) {
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        List<ItemComponent> components = new ArrayList<>();
        for (TransformItemData transform : blockEntity.getTransformData()) {
            if (!transform.isShown || transform.itemStack.isEmpty()) continue;
            BlockState state = transform.enableBlock
                    ? transform.itemStack.get(DataComponentRegistry.BLOCKSTATE) : null;
            BakedModel model = state != null
                    ? Minecraft.getInstance().getBlockRenderer().getBlockModel(state)
                    : itemRenderer.getModel(transform.itemStack, blockEntity.getLevel(), null,
                            (int) blockEntity.getBlockPos().asLong());
            components.add(new ItemComponent(transform, state, model,
                    state == null ? YuushyaItemRenderPlan.resolve(model, transform.itemStack) : null));
        }
        return components;
    }

    private static CachedMesh buildItemMesh(
            ItemBlockEntity blockEntity, List<ItemComponent> components, int light, int overlay
    ) {
        return buildMesh(buffer -> emitItemMesh(blockEntity,components,new PoseStack(),buffer,light,overlay));
    }

    private static void emitItemMesh(ItemBlockEntity blockEntity,List<ItemComponent> components,
                                     PoseStack componentPose,MultiBufferSource buffer,int light,int overlay) {
        RandomSource random = RandomSource.create(blockEntity.getBlockPos().asLong());
        float facing = blockEntity.getBlockState()
                .getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
            for (ItemComponent component : components) {
                TransformItemData transform = component.transform;
                ItemStack stack = transform.itemStack;

                componentPose.pushPose();
                applyComponentTransform(componentPose, facing, transform.scales, transform.pos, transform.rot);
                BlockState representedState = component.state;
                if (representedState != null) {
                    if (representedState.getRenderShape() == RenderShape.MODEL) {
                        renderBlock(transform, representedState, componentPose, buffer, light, overlay, random);
                    }
                } else {
                    renderItem(
                            transform,
                            stack,
                            componentPose,
                            buffer,
                            light,
                            overlay,
                            random,
                            component.plan.cached()
                    );
                }
                componentPose.popPose();
            }
    }

    private static CachedMesh buildShowMesh(ShowBlockEntity blockEntity, int light, int overlay) {
        return buildMesh(buffer -> emitShowMesh(blockEntity,new PoseStack(),buffer,light,overlay));
    }

    private static void emitShowMesh(ShowBlockEntity blockEntity,PoseStack componentPose,
                                     MultiBufferSource buffer,int light,int overlay) {
        Minecraft minecraft = Minecraft.getInstance();
        float facing = blockEntity.getBlockState()
                .getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
            for (TransformBlockData transform : blockEntity.getTransformData()) {
                if (!transform.isShown || transform.blockState.getRenderShape() != RenderShape.MODEL) {
                    continue;
                }
                componentPose.pushPose();
                applyComponentTransform(componentPose, facing, transform.scales, transform.pos, transform.rot);
                minecraft.getBlockRenderer().renderSingleBlock(
                        transform.blockState,
                        componentPose,
                        buffer,
                        light,
                        overlay,
                        ModelData.EMPTY,
                        null
                );
                componentPose.popPose();
            }
    }

    private static void renderLiveItems(
            ItemBlockEntity blockEntity, List<ItemComponent> components, float partialTick,
            PoseStack poseStack, MultiBufferSource buffer, int light, int overlay
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        float facing = blockEntity.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
        poseStack.pushPose();
        try {
            applyRecoil(blockEntity, partialTick, poseStack);
            for (ItemComponent component : components) {
                if (component.state != null
                        ? component.state.getRenderShape() != RenderShape.ENTITYBLOCK_ANIMATED
                        : component.plan.live().isEmpty()) continue;
                TransformItemData transform = component.transform;
                poseStack.pushPose();
                try {
                    applyComponentTransform(poseStack, facing, transform.scales, transform.pos, transform.rot);
                    if (component.state != null) {
                        minecraft.getBlockRenderer().renderSingleBlock(
                                component.state, poseStack, buffer, light, overlay, ModelData.EMPTY, null);
                    } else {
                        for (BakedModel model : component.plan.live()) {
                            // Never call this while building the private mesh: TaCZ/Mesh Loader can
                            // bypass the supplied buffer and issue draws against global buffers/VBOs.
                            minecraft.getItemRenderer().render(transform.itemStack, ItemDisplayContext.NONE,
                                    false, poseStack, buffer, light, overlay, model);
                        }
                    }
                } finally {
                    poseStack.popPose();
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderLiveBlocks(
            ShowBlockEntity blockEntity, float partialTick, PoseStack poseStack,
            MultiBufferSource buffer, int light, int overlay
    ) {
        float facing = blockEntity.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
        poseStack.pushPose();
        try {
            applyRecoil(blockEntity, partialTick, poseStack);
            for (TransformBlockData transform : blockEntity.getTransformData()) {
                if (!transform.isShown
                        || transform.blockState.getRenderShape() != RenderShape.ENTITYBLOCK_ANIMATED) continue;
                poseStack.pushPose();
                try {
                    applyComponentTransform(poseStack, facing, transform.scales, transform.pos, transform.rot);
                    Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                            transform.blockState, poseStack, buffer, light, overlay, ModelData.EMPTY, null);
                } finally {
                    poseStack.popPose();
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    private static CachedMesh buildMesh(MeshEmitter emitter) {
        Map<RenderType, VertexBuffer> buffers = new LinkedHashMap<>();
        Map<RenderType, ByteBufferBuilder> sortBuffers = new HashMap<>();
        List<Runnable> uploads = new ArrayList<>();

        try (FullyBufferedBufferSource source = new FullyBufferedBufferSource()) {
            emitter.emit(source);
            source.upload(
                    renderType -> buffers.computeIfAbsent(
                            renderType, ignored -> new VertexBuffer(VertexBuffer.Usage.STATIC)),
                    renderType -> sortBuffers.computeIfAbsent(
                            renderType, ignored -> new ByteBufferBuilder(renderType.bufferSize())),
                    uploads::add
            );
            // Execute after upload() has collected all tasks because each task removes its builder.
            uploads.forEach(Runnable::run);
            return new CachedMesh(buffers);
        } catch (RuntimeException exception) {
            buffers.values().forEach(VertexBuffer::close);
            throw exception;
        } finally {
            sortBuffers.values().forEach(ByteBufferBuilder::close);
        }
    }

    private static void drawWithRecoil(
            CachedMesh mesh,
            BlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack
    ) {
        if (mesh.buffers.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        applyRecoil(blockEntity, partialTick, poseStack);
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix())
                .mul(poseStack.last().pose());
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        Window window = Minecraft.getInstance().getWindow();

        for (Map.Entry<RenderType, VertexBuffer> layer : mesh.buffers.entrySet()) {
            RenderType renderType = layer.getKey();
            VertexBuffer vertexBuffer = layer.getValue();
            renderType.setupRenderState();
            ShaderInstance shader = RenderSystem.getShader();
            shader.setDefaultUniforms(renderType.mode(), modelView, projection, window);
            vertexBuffer.bind();
            vertexBuffer.drawWithShader(modelView, projection, shader);
            VertexBuffer.unbind();
            renderType.clearRenderState();
        }
        poseStack.popPose();
    }

    private static void applyComponentTransform(
            PoseStack poseStack,
            float facing,
            org.joml.Vector3f scales,
            org.joml.Vector3d position,
            org.joml.Vector3f rotation
    ) {
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing));
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        YuushyaUtils.scale(poseStack, scales);
        YuushyaUtils.translate(poseStack, position);
        YuushyaUtils.rotate(poseStack, rotation);
    }

    private static void applyRecoil(
            BlockEntity blockEntity, float partialTick, PoseStack poseStack
    ) {
        if (!(blockEntity instanceof YuushyaCannonRecoilAnimation animation)) {
            return;
        }
        Direction direction = animation.stabilizedTurret$getRecoilDirection();
        float recoil = animation.stabilizedTurret$getRecoilOffset(partialTick);
        poseStack.translate(
                -direction.getStepX() * recoil,
                -direction.getStepY() * recoil,
                -direction.getStepZ() * recoil
        );
    }

    private static void renderBlock(
            TransformItemData transform,
            BlockState state,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            int overlay,
            RandomSource random
    ) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        for (RenderType renderType : model.getRenderTypes(state, random, ModelData.EMPTY)) {
            VertexConsumer vertices = buffer.getBuffer(renderType);
            for (Direction direction : DIRECTIONS) {
                List<BakedQuad> quads = model.getQuads(
                        state, direction, random, ModelData.EMPTY, renderType);
                renderQuads(transform, model, quads, poseStack, vertices, light, overlay);
            }
        }
    }

    private static void renderItem(
            TransformItemData transform,
            ItemStack stack,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            int overlay,
            RandomSource random,
            List<BakedModel> models
    ) {
        for (BakedModel model : models) {
            for (RenderType renderType : model.getRenderTypes(stack, true)) {
                VertexConsumer vertices = buffer.getBuffer(renderType);
                for (Direction direction : DIRECTIONS) {
                    renderQuads(
                            transform,
                            model,
                            model.getQuads(null, direction, random),
                            poseStack,
                            vertices,
                            light,
                            overlay
                    );
                }
            }
        }
    }

    private static void renderQuads(
            TransformItemData transform,
            BakedModel model,
            List<BakedQuad> quads,
            PoseStack poseStack,
            VertexConsumer vertices,
            int light,
            int overlay
    ) {
        for (BakedQuad quad : quads) {
            float[] color = CachedRegion.getColorComponents(transform, model, quad);
            vertices.putBulkData(
                    poseStack.last(),
                    quad,
                    color[0],
                    color[1],
                    color[2],
                    1.0F,
                    light,
                    overlay
            );
        }
    }

    private static long itemSignature(
            ItemBlockEntity blockEntity, List<ItemComponent> components, int light, int overlay
    ) {
        long hash = commonSignature(blockEntity, light, overlay);
        for (ItemComponent component : components) {
            TransformItemData transform = component.transform;
            hash = transformSignature(hash, transform.pos, transform.rot, transform.scales, transform.isShown);
            hash = mix(hash, transform.color);
            hash = mix(hash, transform.enableBlock ? 1 : 0);
            hash = mix(hash, ItemStack.hashItemAndComponents(transform.itemStack));
            hash = mix(hash, System.identityHashCode(component.model));
            if (component.plan != null) {
                // A wrapper can switch passes without changing its own identity. Invalidate old
                // cached quads when a pass starts/stops using a custom renderer.
                hash = mix(hash, component.plan.cached().size());
                for (BakedModel pass : component.plan.cached()) {
                    hash = mix(hash, System.identityHashCode(pass));
                }
            }
        }
        return hash;
    }

    private static long showSignature(ShowBlockEntity blockEntity, int light, int overlay) {
        Minecraft minecraft = Minecraft.getInstance();
        long hash = commonSignature(blockEntity, light, overlay);
        for (TransformBlockData transform : blockEntity.getTransformData()) {
            hash = transformSignature(hash, transform.pos, transform.rot, transform.scales, transform.isShown);
            hash = mix(hash, transform.blockState.hashCode());
            hash = mix(hash, System.identityHashCode(
                    minecraft.getBlockRenderer().getBlockModel(transform.blockState)));
        }
        return hash;
    }

    private static long commonSignature(BlockEntity blockEntity, int light, int overlay) {
        long hash = HASH_SEED;
        hash = mix(hash, blockEntity.getBlockState().hashCode());
        hash = mix(hash, light);
        return mix(hash, overlay);
    }

    private static long transformSignature(
            long hash,
            org.joml.Vector3d position,
            org.joml.Vector3f rotation,
            org.joml.Vector3f scale,
            boolean shown
    ) {
        hash = mix(hash, Double.doubleToLongBits(position.x));
        hash = mix(hash, Double.doubleToLongBits(position.y));
        hash = mix(hash, Double.doubleToLongBits(position.z));
        hash = mix(hash, Float.floatToIntBits(rotation.x));
        hash = mix(hash, Float.floatToIntBits(rotation.y));
        hash = mix(hash, Float.floatToIntBits(rotation.z));
        hash = mix(hash, Float.floatToIntBits(scale.x));
        hash = mix(hash, Float.floatToIntBits(scale.y));
        hash = mix(hash, Float.floatToIntBits(scale.z));
        return mix(hash, shown ? 1 : 0);
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * HASH_PRIME;
    }

    private static void cleanupRemovedEntries() {
        if (--rendersUntilCleanup > 0) {
            return;
        }
        rendersUntilCleanup = 120;
        Iterator<Map.Entry<BlockEntity, CachedMesh>> iterator = MESH_CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockEntity, CachedMesh> entry = iterator.next();
            if (entry.getKey().isRemoved()) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    private static void evictOldestEntries() {
        Iterator<Map.Entry<BlockEntity, CachedMesh>> iterator = MESH_CACHE.entrySet().iterator();
        while (MESH_CACHE.size() > MAX_CACHED_MESHES && iterator.hasNext()) {
            Map.Entry<BlockEntity, CachedMesh> entry = iterator.next();
            entry.getValue().close();
            iterator.remove();
        }
    }

    private static List<Direction> createDirections() {
        List<Direction> directions = new ArrayList<>(Arrays.asList(Direction.values()));
        directions.add(null);
        return directions;
    }

    @FunctionalInterface
    private interface MeshEmitter {
        void emit(MultiBufferSource buffer);
    }

    private record ItemComponent(
            TransformItemData transform, BlockState state, BakedModel model, YuushyaItemRenderPlan plan
    ) {}

    private static final class CachedMesh implements AutoCloseable {
        private final Map<RenderType, VertexBuffer> buffers;
        private long signature;

        private CachedMesh(Map<RenderType, VertexBuffer> buffers) {
            this.buffers = buffers;
        }

        @Override
        public void close() {
            buffers.values().forEach(VertexBuffer::close);
            buffers.clear();
        }
    }

    private YuushyaCannonComponentRenderer() {
    }
}
