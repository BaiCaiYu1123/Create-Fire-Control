package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.mixin.client.LevelRendererRemoteImpactAccessor;
import com.hooya.stabilizedturret.network.MissileImpactRegionPayload;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Client-side chunk cache and sparse render sections for remote missile impacts. */
public final class RemoteImpactRenderManager {
    /**
     * A remote impact is not a second full player view. Creating a normal ViewArea
     * for its 25x25 chunks also creates every vertical section in that square,
     * including thousands of air sections. Keep section allocation paced so a
     * chunk packet cannot stall the render thread.
     */
    private static final int MAX_NEW_SECTIONS_PER_TICK = 3;

    private static final Map<UUID, Region> REGIONS = new HashMap<>();
    private static final Map<Long, LevelChunk> CHUNKS = new HashMap<>();
    private static final Map<Long, RemoteSection> SECTIONS = new HashMap<>();
    private static final ObjectArrayList<RemoteSection> VISIBLE_SECTIONS =
            new ObjectArrayList<>();
    private static final Set<SectionRenderDispatcher.RenderSection> LAST_APPENDED =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private static @Nullable ClientLevel level;
    private static int sectionGeneration;
    private static int visibleGeneration = -1;
    private static long lastVisibleScanTick = Long.MIN_VALUE;
    private static long allocationTick = Long.MIN_VALUE;
    private static int allocationsThisTick;

    public static void handleRegion(MissileImpactRegionPayload payload) {
        ClientLevel current = Minecraft.getInstance().level;
        ensureLevel(current);
        if (current == null) return;
        if (!payload.active()) {
            Region removed = REGIONS.remove(payload.missileId());
            if (removed != null) removed.release();
            unloadUncoveredChunks();
            current.getChunkSource().getLightEngine().runLightUpdates();
            Minecraft.getInstance().levelRenderer.needsUpdate();
            return;
        }

        int loadRadius = Math.max(1, payload.radius());
        int renderRadius = Math.max(1, loadRadius - 1);
        Region previous = REGIONS.get(payload.missileId());
        if (previous != null && previous.matches(
                payload.centerChunkX(), payload.centerChunkZ(),
                loadRadius, renderRadius)) {
            return;
        }
        if (previous != null) previous.release();
        Region region = new Region(payload.centerChunkX(), payload.centerChunkZ(),
                loadRadius, renderRadius);
        REGIONS.put(payload.missileId(), region);
        region.discoverLoadedChunks();
        unloadUncoveredChunks();
        Minecraft.getInstance().levelRenderer.needsUpdate();
    }

    public static boolean shouldCaptureChunk(int chunkX, int chunkZ) {
        ensureLevel(Minecraft.getInstance().level);
        return level != null && isLoadChunk(chunkX, chunkZ)
                && outsideVanillaStorage(chunkX, chunkZ);
    }

    public static @Nullable LevelChunk replaceChunk(
            ClientLevel current,
            int chunkX,
            int chunkZ,
            FriendlyByteBuf data,
            CompoundTag heightmaps,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities
    ) {
        ensureLevel(current);
        if (level == null || !shouldCaptureChunk(chunkX, chunkZ)) return null;
        long key = ChunkPos.asLong(chunkX, chunkZ);
        LevelChunk chunk = CHUNKS.get(key);
        if (chunk == null) {
            chunk = new LevelChunk(current, new ChunkPos(chunkX, chunkZ));
            CHUNKS.put(key, chunk);
        }
        chunk.replaceWithPacketData(data, heightmaps, blockEntities);
        current.onChunkLoaded(chunk.getPos());
        NeoForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, false));
        observeChunkSections(chunkX, chunkZ, chunk);
        markChunkDirty(chunkX, chunkZ);
        return chunk;
    }

    public static @Nullable LevelChunk getChunk(int chunkX, int chunkZ) {
        ensureLevel(Minecraft.getInstance().level);
        if (level == null || !outsideVanillaStorage(chunkX, chunkZ)) return null;
        return CHUNKS.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static boolean dropChunk(ChunkPos position) {
        ensureLevel(Minecraft.getInstance().level);
        if (level == null || !isLoadChunk(position.x, position.z)) return false;
        LevelChunk removed = CHUNKS.remove(position.toLong());
        if (removed != null) {
            forgetChunkSections(position.x, position.z);
            if (outsideVanillaStorage(position.x, position.z)) {
                NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(removed));
                level.unload(removed);
            }
        }
        return removed != null;
    }

    public static boolean replaceBiomes(int chunkX, int chunkZ,
                                        FriendlyByteBuf data) {
        LevelChunk chunk = getChunk(chunkX, chunkZ);
        if (chunk == null) return false;
        chunk.replaceBiomes(data);
        markChunkDirty(chunkX, chunkZ);
        return true;
    }

    public static int remoteChunkCount() {
        return CHUNKS.size();
    }

    public static void appendVisibleSections(
            ObjectArrayList<SectionRenderDispatcher.RenderSection> visible,
            Camera camera,
            Frustum frustum
    ) {
        ClientLevel current = Minecraft.getInstance().level;
        ensureLevel(current);
        visible.removeIf(LAST_APPENDED::contains);
        LAST_APPENDED.clear();
        if (current == null || REGIONS.isEmpty()) return;

        refreshVisibleSections(current, camera, frustum);
        allocateVisibleSections(current);
        for (RemoteSection remote : VISIBLE_SECTIONS) {
            SectionRenderDispatcher.RenderSection section = remote.section;
            if (section == null) continue;
            visible.add(section);
            LAST_APPENDED.add(section);
        }
    }

    public static float requiredFarDistance() {
        Minecraft minecraft = Minecraft.getInstance();
        ensureLevel(minecraft.level);
        if (minecraft.level == null || minecraft.gameRenderer.getMainCamera() == null) {
            return 0.0F;
        }
        double cameraX = minecraft.gameRenderer.getMainCamera().getPosition().x;
        double cameraZ = minecraft.gameRenderer.getMainCamera().getPosition().z;
        double far = 0.0;
        for (Region region : REGIONS.values()) {
            if (!region.isRemoteFromPlayer() || !region.isViewedByCamera()) continue;
            double centerX = SectionPos.sectionToBlockCoord(region.centerX, 8);
            double centerZ = SectionPos.sectionToBlockCoord(region.centerZ, 8);
            far = Math.max(far, Math.hypot(centerX - cameraX, centerZ - cameraZ)
                    + region.renderRadius * 16.0 + 512.0);
        }
        return (float) Math.min(25000.0, far);
    }

    public static boolean hasRemoteView() {
        return requiredFarDistance() > 0.0F;
    }

    private static void refreshVisibleSections(ClientLevel current, Camera camera,
                                               Frustum frustum) {
        long tick = current.getGameTime();
        if (lastVisibleScanTick == tick && visibleGeneration == sectionGeneration) return;
        lastVisibleScanTick = tick;
        visibleGeneration = sectionGeneration;
        VISIBLE_SECTIONS.clear();

        Set<RemoteSection> seen = new HashSet<>();
        for (Region region : REGIONS.values()) {
            if (!region.isRemoteFromPlayer() || !region.isViewedByCamera()) continue;
            for (long key : region.candidateSections) {
                RemoteSection remote = SECTIONS.get(key);
                if (remote == null || !seen.add(remote)) continue;
                if (frustum.isVisible(remote.bounds)) VISIBLE_SECTIONS.add(remote);
            }
        }
        VISIBLE_SECTIONS.sort(Comparator.comparingDouble(
                remote -> remote.distanceToNearestImpactCenter));
    }

    private static void allocateVisibleSections(ClientLevel current) {
        if (allocationTick != current.getGameTime()) {
            allocationTick = current.getGameTime();
            allocationsThisTick = 0;
        }
        if (allocationsThisTick >= MAX_NEW_SECTIONS_PER_TICK) return;

        LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        SectionRenderDispatcher dispatcher = ((LevelRendererRemoteImpactAccessor) renderer)
                .stabilizedTurret$getSectionRenderDispatcher();
        if (dispatcher == null) return;
        for (RemoteSection remote : VISIBLE_SECTIONS) {
            if (allocationsThisTick >= MAX_NEW_SECTIONS_PER_TICK) break;
            if (remote.section != null) continue;
            remote.create(dispatcher);
            allocationsThisTick++;
        }
    }

    private static void observeChunkSections(int chunkX, int chunkZ, LevelChunk chunk) {
        for (Region region : REGIONS.values()) {
            if (region.containsRender(chunkX, chunkZ)) region.observeChunk(chunk);
        }
    }

    private static void forgetChunkSections(int chunkX, int chunkZ) {
        for (Region region : REGIONS.values()) region.forgetChunk(chunkX, chunkZ);
    }

    private static void markChunkDirty(int chunkX, int chunkZ) {
        for (RemoteSection remote : SECTIONS.values()) {
            if (Math.abs(remote.chunkX - chunkX) > 1
                    || Math.abs(remote.chunkZ - chunkZ) > 1
                    || remote.section == null) continue;
            remote.section.setDirty(false);
        }
        Minecraft.getInstance().levelRenderer.needsUpdate();
    }

    private static void addCandidate(Region region, long key) {
        if (!region.candidateSections.add(key)) return;
        RemoteSection remote = SECTIONS.computeIfAbsent(key, RemoteSection::new);
        remote.references++;
        double dx = remote.chunkX - region.centerX;
        double dz = remote.chunkZ - region.centerZ;
        remote.distanceToNearestImpactCenter = Math.min(
                remote.distanceToNearestImpactCenter, dx * dx + dz * dz);
        sectionGeneration++;
    }

    private static void removeCandidate(Region region, long key) {
        if (!region.candidateSections.remove(key)) return;
        RemoteSection remote = SECTIONS.get(key);
        if (remote == null) return;
        if (--remote.references <= 0) {
            remote.release();
            SECTIONS.remove(key);
            VISIBLE_SECTIONS.remove(remote);
        }
        sectionGeneration++;
    }

    private static boolean isLoadChunk(int chunkX, int chunkZ) {
        for (Region region : REGIONS.values()) {
            if (region.containsLoad(chunkX, chunkZ)) return true;
        }
        return false;
    }

    private static boolean outsideVanillaStorage(int chunkX, int chunkZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return true;
        int radius = Math.max(2, minecraft.options.getEffectiveRenderDistance()) + 3;
        ChunkPos playerChunk = minecraft.player.chunkPosition();
        return Math.abs(chunkX - playerChunk.x) > radius
                || Math.abs(chunkZ - playerChunk.z) > radius;
    }

    private static void unloadUncoveredChunks() {
        if (level == null) {
            CHUNKS.clear();
            return;
        }
        CHUNKS.entrySet().removeIf(entry -> {
            ChunkPos pos = new ChunkPos(entry.getKey());
            if (isLoadChunk(pos.x, pos.z)) return false;
            LevelChunk chunk = entry.getValue();
            forgetChunkSections(pos.x, pos.z);
            NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
            if (outsideVanillaStorage(pos.x, pos.z)) level.unload(chunk);
            return true;
        });
    }

    private static void ensureLevel(@Nullable ClientLevel current) {
        if (level == current) return;
        for (Region region : REGIONS.values()) region.release();
        if (level != null) {
            for (LevelChunk chunk : CHUNKS.values()) level.unload(chunk);
        }
        for (RemoteSection remote : SECTIONS.values()) remote.release();
        REGIONS.clear();
        CHUNKS.clear();
        SECTIONS.clear();
        VISIBLE_SECTIONS.clear();
        LAST_APPENDED.clear();
        level = current;
        sectionGeneration = 0;
        visibleGeneration = -1;
        lastVisibleScanTick = Long.MIN_VALUE;
        allocationTick = Long.MIN_VALUE;
        allocationsThisTick = 0;
    }

    private static final class Region {
        final int centerX;
        final int centerZ;
        final int loadRadius;
        final int renderRadius;
        final Set<Long> candidateSections = new java.util.LinkedHashSet<>();

        Region(int centerX, int centerZ, int loadRadius, int renderRadius) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.loadRadius = loadRadius;
            this.renderRadius = renderRadius;
        }

        boolean matches(int nextX, int nextZ, int nextLoadRadius,
                        int nextRenderRadius) {
            return centerX == nextX && centerZ == nextZ
                    && loadRadius == nextLoadRadius
                    && renderRadius == nextRenderRadius;
        }

        boolean containsLoad(int x, int z) {
            return Math.abs(x - centerX) <= loadRadius
                    && Math.abs(z - centerZ) <= loadRadius;
        }

        boolean containsRender(int x, int z) {
            return Math.abs(x - centerX) <= renderRadius
                    && Math.abs(z - centerZ) <= renderRadius;
        }

        boolean isRemoteFromPlayer() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) return true;
            ChunkPos player = minecraft.player.chunkPosition();
            int localRadius = minecraft.options.getEffectiveRenderDistance() + 1;
            return Math.abs(centerX - player.x) > localRadius + renderRadius
                    || Math.abs(centerZ - player.z) > localRadius + renderRadius;
        }

        boolean isViewedByCamera() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.gameRenderer.getMainCamera() == null) return false;
            Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
            int cameraChunkX = Mth.floor(camera.x) >> 4;
            int cameraChunkZ = Mth.floor(camera.z) >> 4;
            int margin = renderRadius + 2;
            return Math.abs(centerX - cameraChunkX) <= margin
                    && Math.abs(centerZ - cameraChunkZ) <= margin;
        }

        void discoverLoadedChunks() {
            for (Map.Entry<Long, LevelChunk> entry : CHUNKS.entrySet()) {
                ChunkPos pos = new ChunkPos(entry.getKey());
                if (containsRender(pos.x, pos.z)) observeChunk(entry.getValue());
            }
        }

        void observeChunk(LevelChunk chunk) {
            if (level == null) return;
            int chunkX = chunk.getPos().x;
            int chunkZ = chunk.getPos().z;
            Set<Long> next = new HashSet<>();
            for (int sectionIndex = 0; sectionIndex < chunk.getSections().length;
                 sectionIndex++) {
                if (chunk.getSections()[sectionIndex].hasOnlyAir()) continue;
                int sectionY = level.getSectionYFromSectionIndex(sectionIndex);
                next.add(SectionPos.asLong(chunkX, sectionY, chunkZ));
            }

            for (Iterator<Long> iterator = candidateSections.iterator(); iterator.hasNext();) {
                long key = iterator.next();
                if (SectionPos.x(key) != chunkX || SectionPos.z(key) != chunkZ
                        || next.contains(key)) continue;
                iterator.remove();
                releaseCandidate(key);
            }
            for (long key : next) addCandidate(this, key);
        }

        void forgetChunk(int chunkX, int chunkZ) {
            for (Iterator<Long> iterator = candidateSections.iterator(); iterator.hasNext();) {
                long key = iterator.next();
                if (SectionPos.x(key) != chunkX || SectionPos.z(key) != chunkZ) continue;
                iterator.remove();
                releaseCandidate(key);
            }
        }

        void release() {
            for (long key : new ArrayList<>(candidateSections)) {
                removeCandidate(this, key);
            }
        }

        private void releaseCandidate(long key) {
            RemoteSection remote = SECTIONS.get(key);
            if (remote == null) return;
            if (--remote.references <= 0) {
                remote.release();
                SECTIONS.remove(key);
                VISIBLE_SECTIONS.remove(remote);
            }
            sectionGeneration++;
        }
    }

    private static final class RemoteSection {
        final int chunkX;
        final int sectionY;
        final int chunkZ;
        final AABB bounds;
        int references;
        double distanceToNearestImpactCenter = Double.POSITIVE_INFINITY;
        private @Nullable SectionRenderDispatcher.RenderSection section;

        RemoteSection(long key) {
            chunkX = SectionPos.x(key);
            sectionY = SectionPos.y(key);
            chunkZ = SectionPos.z(key);
            int minX = SectionPos.sectionToBlockCoord(chunkX);
            int minY = SectionPos.sectionToBlockCoord(sectionY);
            int minZ = SectionPos.sectionToBlockCoord(chunkZ);
            bounds = new AABB(minX, minY, minZ, minX + 16, minY + 16, minZ + 16);
        }

        void create(SectionRenderDispatcher dispatcher) {
            // -1 keeps this sparse section out of the vanilla occlusion graph,
            // whose index array only owns the player's regular ViewArea.
            section = dispatcher.new RenderSection(-1,
                    SectionPos.sectionToBlockCoord(chunkX),
                    SectionPos.sectionToBlockCoord(sectionY),
                    SectionPos.sectionToBlockCoord(chunkZ));
        }

        void release() {
            if (section != null) section.releaseBuffers();
            section = null;
        }
    }

    private RemoteImpactRenderManager() {
    }
}
