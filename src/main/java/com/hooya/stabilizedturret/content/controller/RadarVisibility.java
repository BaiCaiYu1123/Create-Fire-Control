package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded, server-authoritative radar visibility. No chunk loads, tickets or synchronous disk reads. */
public final class RadarVisibility {
    static final int MAX_RAYS_PER_SCAN = 64;
    static final int MAX_WORLD_STEPS = 16_384;
    static final int MAX_CACHE_TARGETS = 4096;
    static final int PENDING_TRACK_TICKS = 12;
    static final int MAX_WORLD_READS = 128;
    static final int MAX_TRACK_READS = 126;
    static final int MAX_PENDING_READS = 128;
    private static final ExecutorService TERRAIN_DECODER = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "Radar terrain decoder");
        thread.setDaemon(true); thread.setPriority(Thread.NORM_PRIORITY - 1); return thread;
    });
    private static final Map<ServerLevel, Terrain> WORLDS = new WeakHashMap<>();
    private final Map<UUID, Sample> samples = new LinkedHashMap<>();
    private final Map<UUID, Progress> pending = new LinkedHashMap<>();
    private final Map<UUID, Motion> motion = new LinkedHashMap<>();
    private final UUID scannerId = UUID.randomUUID();
    private int cursor;
    private int trackedCursor;

    public record Target(UUID id, Vec3 center, AABB bounds, Set<UUID> members) {}
    public enum Result { CLEAR, BLOCKED, PENDING }
    private record Motion(Vec3 origin, Vec3 center, long tick, Vec3 sourceVelocity, Vec3 targetVelocity) {}
    private record Sample(Vec3 origin, Vec3 center, AABB bounds, Set<UUID> members,
                          long tick, boolean visible) {
        boolean matches(Vec3 from, Target target, long now, int ttl) {
            return now >= tick && now - tick < ttl && from.distanceToSqr(origin) < .0625
                    && target.center.distanceToSqr(center) < .0625 && nearBounds(target.bounds, bounds)
                    && members.equals(target.members);
        }
        boolean retain(Vec3 from, Target target, long now) {
            return visible && withinMotion(from, target, now);
        }
        boolean withinMotion(Vec3 from, Target target, long now) {
            return now >= tick && now - tick <= PENDING_TRACK_TICKS
                    && members.equals(target.members) && from.distanceToSqr(origin) <= 128 * 128
                    && target.center.distanceToSqr(center) <= 256 * 256;
        }
    }
    private static boolean nearBounds(AABB a, AABB b) {
        return Math.abs(a.minX - b.minX) <= .0625 && Math.abs(a.minY - b.minY) <= .0625
                && Math.abs(a.minZ - b.minZ) <= .0625 && Math.abs(a.maxX - b.maxX) <= .0625
                && Math.abs(a.maxY - b.maxY) <= .0625 && Math.abs(a.maxZ - b.maxZ) <= .0625;
    }
    private static final class Progress {
        final Sample geometry;
        final Set<UUID> own;
        double fraction;
        Progress(Vec3 from, Target target, Set<UUID> own, long now) {
            geometry = new Sample(from, target.center, target.bounds, target.members, now, false);
            this.own = Set.copyOf(own);
        }
    }

    /** Unknown/budget-limited targets wait for confirmation; round-robin prevents a blocked prefix starving others. */
    public Set<UUID> scan(Level level, Vec3 origin, Set<UUID> own, List<Target> targets) {
        return scan(level, origin, own, targets, Set.of());
    }

    public static Set<UUID> lockPriority(@Nullable UUID locked, @Nullable UUID selected) {
        UUID id = locked != null ? locked : selected;
        return id == null ? Set.of() : Set.of(id);
    }

    /** A launch needs a new complete ray; pending data neither permits launch nor erases its confirmed track. */
    public Result checkNow(Level level, Vec3 origin, Set<UUID> own, Target target) {
        if (!com.hooya.stabilizedturret.config.FireControlSettings.radarRaycastEnabled()) return Result.CLEAR;
        var container = SubLevelContainer.getContainer(level);
        if (!(container.getLevel() instanceof ServerLevel root)) return Result.BLOCKED;
        long now = root.getGameTime();
        Terrain terrain = WORLDS.computeIfAbsent(root, ignored -> new Terrain());
        terrain.begin(now, scannerId);
        if (pending.size() >= MAX_CACHE_TARGETS && !pending.containsKey(target.id))
            pending.remove(pending.keySet().iterator().next());
        pending.put(target.id, new Progress(origin, target, own, now));
        evaluate(root, terrain, origin, own, target, now, new HashSet<>(), false);
        if (pending.containsKey(target.id)) return Result.PENDING;
        Sample result = samples.get(target.id);
        return result != null && result.visible ? Result.CLEAR : Result.BLOCKED;
    }

    public boolean isPending(UUID id) { return pending.containsKey(id); }

    public Set<UUID> scan(Level level, Vec3 origin, Set<UUID> own, List<Target> targets, Set<UUID> priorities) {
        if (!com.hooya.stabilizedturret.config.FireControlSettings.radarRaycastEnabled()) {
            // Clear old obstruction results so re-enabling starts with fresh geometry.
            clear();
            Set<UUID> visible = new HashSet<>();
            for (Target target : targets) visible.add(target.id);
            return visible;
        }
        var container = SubLevelContainer.getContainer(level);
        if (!(container.getLevel() instanceof ServerLevel root) || targets.isEmpty()) {
            clear();
            return Set.of();
        }
        Terrain terrain = WORLDS.computeIfAbsent(root, ignored -> new Terrain());
        long now = root.getGameTime();
        terrain.begin(now, scannerId);
        int ttl = Math.min(20, Math.max(4, (targets.size() + 31) / 32));
        Set<UUID> present = new HashSet<>(), visible = new HashSet<>();
        List<Target> tracked = new ArrayList<>();
        for (Target target : targets) {
            present.add(target.id);
            Sample cached = samples.get(target.id);
            if (cached != null && cached.visible
                    && (cached.matches(origin, target, now, ttl) || cached.retain(origin, target, now))) {
                visible.add(target.id); tracked.add(target);
            }
        }
        samples.keySet().retainAll(present);
        pending.keySet().retainAll(present);
        motion.keySet().retainAll(present);
        int count = targets.size(), visited = 0, rays = 0;
        // Refresh established tracks before spending the whole slice on new candidates.
        // Rotation and the reserved acquisition slice prevent either group starving the other.
        tracked.sort(Comparator.comparingDouble(target -> target.center.distanceToSqr(origin)));
        if (tracked.size() > 64) tracked = tracked.subList(0, 64);
        Set<UUID> attempted = new HashSet<>();
        for (Target target : targets) {
            if (!priorities.contains(target.id) || !terrain.hasWork() || rays >= MAX_RAYS_PER_SCAN) continue;
            Sample cached = samples.get(target.id);
            if (cached != null && cached.matches(origin, target, now, ttl)) continue;
            attempted.add(target.id); rays++;
            evaluate(root, terrain, origin, own, target, now, visible);
        }
        int reserve = terrain.localRemaining / 4;
        if (!tracked.isEmpty()) {
            trackedCursor = Math.floorMod(trackedCursor, tracked.size());
            for (int i = 0; i < tracked.size() && rays < MAX_RAYS_PER_SCAN / 2
                    && terrain.hasWork() && terrain.localRemaining > reserve; i++) {
                Target target = tracked.get(trackedCursor);
                trackedCursor = (trackedCursor + 1) % tracked.size();
                if (attempted.contains(target.id)) continue;
                Sample cached = samples.get(target.id);
                if (cached != null && cached.matches(origin, target, now, ttl)) continue;
                attempted.add(target.id); rays++;
                evaluate(root, terrain, origin, own, target, now, visible);
            }
        }
        cursor = Math.floorMod(cursor, count);
        while (visited < count && rays < MAX_RAYS_PER_SCAN && terrain.hasWork()) {
            Target target = targets.get(cursor);
            cursor = (cursor + 1) % count;
            visited++;
            if (attempted.contains(target.id)) continue;
            Sample cached = samples.get(target.id);
            if (cached != null && cached.matches(origin, target, now, ttl)) continue;
            rays++;
            evaluate(root, terrain, origin, own, target, now, visible);
        }
        return visible;
    }

    private void evaluate(ServerLevel root, Terrain terrain, Vec3 origin, Set<UUID> own,
                          Target target, long now, Set<UUID> visible) {
        evaluate(root, terrain, origin, own, target, now, visible, true);
    }
    private void evaluate(ServerLevel root, Terrain terrain, Vec3 origin, Set<UUID> own,
                          Target target, long now, Set<UUID> visible, boolean predict) {
        Sample confirmed = samples.get(target.id);
        terrain.maintaining = confirmed != null && confirmed.visible && now >= confirmed.tick
                && now - confirmed.tick <= 40 && confirmed.members.equals(target.members);
        Progress progress = pending.get(target.id);
        if (progress == null || !(progress.geometry.matches(origin, target, now, 40)
                || progress.geometry.withinMotion(origin, target, now)) || !progress.own.equals(own)) {
            progress = new Progress(origin, target, own, now);
            if (pending.size() >= MAX_CACHE_TARGETS && !pending.containsKey(target.id))
                pending.remove(pending.keySet().iterator().next());
            pending.put(target.id, progress);
        }
        try {
            // Complete the exact captured segment, not a different ray with the old
            // segment's progress. Lateral motion otherwise throws away every finished
            // asynchronous read before that segment ever reaches a visibility result.
            Sample geometry = progress.geometry;
            Target captured = new Target(target.id, geometry.center, geometry.bounds, geometry.members);
            boolean clear = clear(root, terrain, geometry.origin, captured, own, progress);
            pending.remove(target.id);
            if (samples.size() >= MAX_CACHE_TARGETS && !samples.containsKey(target.id))
                samples.remove(samples.keySet().iterator().next());
            long checkedAt = geometry.matches(origin, target, now, 40) ? now : geometry.tick;
            Sample result = new Sample(geometry.origin, geometry.center, geometry.bounds, geometry.members, checkedAt, clear);
            samples.put(target.id, result);
            if (clear && result.retain(origin, target, now)) visible.add(target.id);
            else visible.remove(target.id); // A real obstruction ends a track immediately.
        } catch (Deferred ignored) {
            // A pending query is not a blocked ray. Only already-confirmed, recent tracks coast;
            // a new/unconfirmed target is never promoted, and this does not renew its age.
        }
        if (predict) prefetchMotion(root, terrain, origin, target, now, confirmed);
    }

    /** Prefetch only: predictions never confirm a contact or authorize a launch. */
    private void prefetchMotion(ServerLevel root, Terrain terrain, Vec3 origin, Target target, long now,
                                @Nullable Sample confirmed) {
        Motion previous = motion.get(target.id);
        if (previous != null && now == previous.tick) return;
        Vec3 sourceVelocity = Vec3.ZERO, targetVelocity = Vec3.ZERO;
        if (previous != null && now > previous.tick && now - previous.tick <= 20
                && origin.distanceToSqr(previous.origin) <= 128 * 128
                && target.center.distanceToSqr(previous.center) <= 256 * 256) {
            sourceVelocity = origin.subtract(previous.origin).scale(1.0 / (now - previous.tick));
            targetVelocity = target.center.subtract(previous.center).scale(1.0 / (now - previous.tick));
        }
        if (motion.size() >= MAX_CACHE_TARGETS && !motion.containsKey(target.id)) motion.remove(motion.keySet().iterator().next());
        motion.put(target.id, new Motion(origin, target.center, now, sourceVelocity, targetVelocity));
        if (confirmed == null || !confirmed.retain(origin, target, now)
                || sourceVelocity.lengthSqr() + targetVelocity.lengthSqr() < .0001) return;
        for (int ahead : new int[]{0, 2}) {
            if (!terrain.hasWork() || !terrain.canRequest()) break;
            Vec3 from = origin.add(sourceVelocity.scale(ahead)), to = target.center.add(targetVelocity.scale(ahead));
            Vec3[] segment = OpticalVisibilityRaycast.crop(from, to, new AABB(-30_000_000,
                    root.getMinBuildHeight(), -30_000_000, 30_000_000, root.getMaxBuildHeight(), 30_000_000));
            if (segment == null) continue;
            Vec3 delta = segment[1].subtract(segment[0]);
            try { prefetch(root, terrain, segment[0], delta, 0, 1e-8 / Math.max(1, delta.length())); }
            catch (Deferred ignored) { break; }
        }
    }

    public void clear() { samples.clear(); pending.clear(); motion.clear(); cursor = trackedCursor = 0; }

    private static boolean clear(ServerLevel root, Terrain terrain, Vec3 from, Target target, Set<UUID> own,
                                 Progress progress) {
        Vec3 delta = target.center.subtract(from);
        double length = delta.length();
        if (!Double.isFinite(length) || length < 1e-6) return false;
        Vec3 to = target.center.subtract(delta.scale(Math.min(.20, length * .01) / length));
        BlockPos sourceBlock = BlockPos.containing(from);
        Vec3[] segment = OpticalVisibilityRaycast.crop(from, to, new AABB(-30_000_000,
                root.getMinBuildHeight(), -30_000_000, 30_000_000, root.getMaxBuildHeight(), 30_000_000));
        if (segment != null) {
            // A pending segment may have passed this prefix before a new loaded wall
            // was placed. Recheck live blocks first, even if earlier disk data is pending.
            if (!terrainClear(root, terrain, segment[0], segment[1], sourceBlock, progress, true)
                    || !terrainClear(root, terrain, segment[0], segment[1], sourceBlock, progress, false)) return false;
        }
        // Spatial broad phase first; traverse only the occupied part of each physical plot.
        for (var body : SubLevelContainer.getContainer(root).queryIntersecting(new BoundingBox3d(from, to))) {
            terrain.step();
            if (body == null || body.isRemoved() || own.contains(body.getUniqueId())
                    || target.members.contains(body.getUniqueId())) continue;
            var box = body.getPlot().getBoundingBox();
            if (box.minX() > box.maxX() || box.minY() > box.maxY() || box.minZ() > box.maxZ()) continue;
            var pose = body.logicalPose();
            Vec3[] local = OpticalVisibilityRaycast.crop(pose.transformPositionInverse(from),
                    pose.transformPositionInverse(to), new AABB(box.minX(), box.minY(), box.minZ(),
                            box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0));
            if (local == null) continue;
            BlockGetter blocks = OpticalVisibilityRaycast.loadedView(root, body.getPlot());
            if (blocked(blocks, local[0], local[1], null, terrain)) return false;
        }
        return true;
    }

    /** 16-block section DDA skips empty sky, empty palettes and unknown chunks in constant work per section. */
    private static boolean terrainClear(ServerLevel root, Terrain terrain, Vec3 from, Vec3 to, BlockPos source,
                                        Progress progress, boolean loadedOnly) {
        Vec3 delta = to.subtract(from);
        double epsilon = 1e-8 / Math.max(1, delta.length());
        double t = loadedOnly ? 0 : progress.fraction;
        while (t < 1) {
            terrain.step();
            Vec3 point = from.add(delta.scale(Math.min(1, t + epsilon)));
            int x = ((int) Math.floor(point.x)) >> 4, y = ((int) Math.floor(point.y)) >> 4,
                    z = ((int) Math.floor(point.z)) >> 4;
            double end = Math.min(1, Math.min(boundary(from.x, delta.x, x),
                    Math.min(boundary(from.y, delta.y, y), boundary(from.z, delta.z, z))));
            if (end <= t) end = Math.min(1, t + 1e-7);
            PalettedContainer<BlockState> states;
            try {
                var chunk = root.getChunkSource().getChunkNow(x, z);
                if (loadedOnly) {
                    if (chunk == null || y < root.getMinSection() || y >= root.getMaxSection()) states = null;
                    else {
                        terrain.forgetLoaded(x, z);
                        states = chunk.getSection(root.getSectionIndexFromSectionY(y)).getStates();
                    }
                }
                else states = chunk != null ? null : terrain.section(root, x, y, z,
                            layerMask(from.y + delta.y * t, from.y + delta.y * end, y));
            } catch (Deferred deferred) {
                prefetch(root, terrain, from, delta, end, epsilon);
                throw deferred;
            }
            if (states != null && states.maybeHas(RadarVisibility::potentialBlocker)) {
                BlockGetter view = new BlockGetter() {
                    @Override public BlockState getBlockState(BlockPos pos) {
                        if ((pos.getX() >> 4) != x || (pos.getY() >> 4) != y || (pos.getZ() >> 4) != z
                                || pos.equals(source)) return Blocks.AIR.defaultBlockState();
                        return states.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                    }
                    @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
                    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
                    @Override public int getHeight() { return root.getHeight(); }
                    @Override public int getMinBuildHeight() { return root.getMinBuildHeight(); }
                };
                if (blocked(view, from.add(delta.scale(t)), from.add(delta.scale(end)), source, terrain)) return false;
            }
            t = end;
            if (!loadedOnly) progress.fraction = t;
        }
        return true;
    }

    /** Cold and established rays share bounded look-ahead; neither waits one chunk at a time. */
    private static void prefetch(ServerLevel root, Terrain terrain, Vec3 from, Vec3 delta, double t, double epsilon) {
        int lookAhead = 256;
        for (int i = 0; i < lookAhead && t < 1 && terrain.hasWork() && terrain.canRequest(); i++) {
            terrain.step();
            Vec3 point = from.add(delta.scale(Math.min(1, t + epsilon)));
            int x = ((int) Math.floor(point.x)) >> 4, y = ((int) Math.floor(point.y)) >> 4,
                    z = ((int) Math.floor(point.z)) >> 4;
            double end = Math.min(1, Math.min(boundary(from.x, delta.x, x),
                    Math.min(boundary(from.y, delta.y, y), boundary(from.z, delta.z, z))));
            try { terrain.section(root, x, y, z, layerMask(from.y + delta.y * t, from.y + delta.y * end, y)); }
            catch (Deferred ignored) { /* The same I/O limits apply to every look-ahead section. */ }
            t = end > t ? end : Math.min(1, t + 1e-7);
        }
    }

    private static double boundary(double from, double delta, int section) {
        return Math.abs(delta) < 1e-12 ? Double.POSITIVE_INFINITY
                : (((delta > 0 ? section + 1.0 : section) * 16) - from) / delta;
    }

    /** Conservative block-height mask for this section's ray segment, including both endpoints. */
    private static int layerMask(double from, double to, int section) {
        int min = Math.max(0, Math.min(15, (int) Math.floor(Math.min(from, to)) - section * 16));
        int max = Math.max(0, Math.min(15, (int) Math.floor(Math.max(from, to)) - section * 16));
        return (0xffff << min) & (0xffff >>> (15 - max));
    }

    static boolean potentialBlocker(BlockState state) {
        // Waterlogged solids still block. Liquid geometry of every fluid is ignored.
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock);
    }

    private static boolean blocked(BlockGetter blocks, Vec3 from, Vec3 to, @Nullable BlockPos source, Terrain terrain) {
        return BlockGetter.traverseBlocks(from, to, blocks, (view, pos) -> {
            terrain.step();
            if (pos.equals(source)) return null;
            BlockState state = view.getBlockState(pos);
            if (!potentialBlocker(state)) return null;
            var shape = state.getCollisionShape(view, pos, CollisionContext.empty());
            return shape.isEmpty() ? null : shape.clip(from, to, pos);
        }, unused -> null) != null;
    }

    /** Shared per dimension; values deliberately do not retain the weak ServerLevel key. */
    private static final class Terrain {
        private final Map<Long, Saved> saved = new LinkedHashMap<>(32, .75f, true);
        // Compact empty-height summaries survive full-palette eviction. Multiple long rays
        // otherwise thrash the 512-chunk cache even while traversing only empty air.
        private final Map<Long, ColumnSummary> columns = new LinkedHashMap<>(32, .75f, true);
        private final AtomicInteger inFlight = new AtomicInteger();
        private final Map<UUID, Long> scanners = new HashMap<>();
        private long tick = Long.MIN_VALUE;
        private int remaining, localRemaining, reads, acquisitionReads, trackReads;
        private boolean maintaining;
        void begin(long now, UUID scanner) {
            if (tick != now) {
                tick = now; remaining = MAX_WORLD_STEPS; reads = acquisitionReads = trackReads = 0;
                scanners.values().removeIf(last -> now < last || now - last > 40);
            }
            scanners.put(scanner, now);
            localRemaining = Math.max(1, Math.min(4096, MAX_WORLD_STEPS / scanners.size()));
        }
        boolean hasWork() { return remaining > 0 && localRemaining > 0; }
        // Established tracks run first and leave at least two requests for discovery.
        // Discovery can borrow the otherwise idle maintenance slice. Giving an unseen
        // moving target only two columns per scan made its captured ray expire before
        // reaching the target, so the maintenance budget was never available to it.
        boolean canRequest() {
            return reads < MAX_WORLD_READS && (!maintaining || trackReads < MAX_TRACK_READS);
        }
        void forgetLoaded(int x, int z) {
            long key = ChunkPos.asLong(x, z);
            if (!saved.isEmpty()) saved.remove(key);
            if (!columns.isEmpty()) columns.remove(key);
        }
        void step() { if (--remaining < 0 || --localRemaining < 0) throw Deferred.INSTANCE; }
        @Nullable PalettedContainer<BlockState> section(ServerLevel root, int x, int y, int z) {
            return section(root, x, y, z, 0xffff);
        }
        @Nullable PalettedContainer<BlockState> section(ServerLevel root, int x, int y, int z, int layerMask) {
            if (y < root.getMinSection() || y >= root.getMaxSection()) return null;
            long key = ChunkPos.asLong(x, z);
            var loaded = root.getChunkSource().getChunkNow(x, z);
            if (loaded != null) {
                // A live chunk can change while its old disk snapshot is still cached.
                // Forget that snapshot so unloading never restores obsolete terrain.
                forgetLoaded(x, z);
                return loaded.getSection(root.getSectionIndexFromSectionY(y)).getStates();
            }
            ColumnSummary column = columns.get(key);
            if (column != null) {
                if (tick < column.accessed || tick - column.accessed > 100) columns.remove(key);
                else {
                    column.accessed = tick;
                    if (!column.intersects(y, layerMask)) return null;
                }
            }
            Saved entry = saved.get(key);
            if (entry != null && entry.future.isDone() && (tick < entry.accessed || tick - entry.accessed > 100
                    || entry.future.getNow(null) == null && tick - entry.requested > 100)) {
                saved.remove(key); entry = null;
            }
            if (entry == null) {
                if (!canRequest() || inFlight.get() >= MAX_PENDING_READS)
                    throw Deferred.INSTANCE;
                if (saved.size() >= 512) {
                    var it = saved.values().iterator();
                    while (saved.size() >= 512 && it.hasNext()) if (it.next().future.isDone()) it.remove();
                    if (saved.size() >= 512) throw Deferred.INSTANCE;
                }
                reads++;
                if (maintaining) trackReads++; else acquisitionReads++;
                var future = root.getChunkSource().chunkMap.read(new ChunkPos(x, z))
                        .thenApplyAsync(RocketTerrainCache::decodeSaved, TERRAIN_DECODER)
                        .exceptionally(error -> null);
                entry = new Saved(tick, future);
                inFlight.incrementAndGet();
                entry.future.whenComplete((snapshot, error) -> inFlight.decrementAndGet());
                saved.put(key, entry);
            }
            // Keep the prefix of an active long ray alive while later chunks are
            // fetched. Expiring by request time can make a moving 4km ray restart forever.
            entry.accessed = tick;
            if (!entry.future.isDone()) throw Deferred.INSTANCE;
            var snapshot = entry.future.getNow(null);
            if (snapshot == null) throw Deferred.INSTANCE;
            entry.column.accessed = tick;
            columns.put(key, entry.column);
            if (columns.size() > 8192) columns.remove(columns.keySet().iterator().next());
            if (!entry.column.intersects(y, layerMask)) return null;
            return snapshot.sections().get(y & 0xff);
        }
    }
    private static final class ColumnSummary {
        final int minSection;
        final short[] layers;
        long accessed;
        ColumnSummary(RocketTerrainCache.Snapshot snapshot, long now) {
            accessed = now;
            // Saved section Y is a signed byte. Retain just its actual vertical span.
            this.minSection = snapshot.sections().keySet().stream().mapToInt(y -> (byte) (int) y).min().orElse(0);
            int maxSection = snapshot.sections().keySet().stream().mapToInt(y -> (byte) (int) y).max().orElse(-1) + 1;
            layers = new short[maxSection - minSection];
            for (int y = minSection; y < maxSection; y++) {
                var states = snapshot.sections().get(y & 0xff);
                if (states != null) layers[y - minSection] = (short) solidLayers(states);
            }
        }
        boolean intersects(int y, int mask) {
            int index = y - minSection;
            return index >= 0 && index < layers.length && (layers[index] & mask) != 0;
        }
        private static int solidLayers(PalettedContainer<BlockState> states) {
            if (!states.maybeHas(RadarVisibility::potentialBlocker)) return 0;
            if (!states.maybeHas(state -> !potentialBlocker(state))) return 0xffff;
            int mask = 0;
            // Only mixed palettes need inspection, once per decoded snapshot. Usually each
            // occupied height exits at its first block; fluids never mark a blocking layer.
            for (int y = 0; y < 16; y++) {
                plane: for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if (potentialBlocker(states.get(x, y, z))) { mask |= 1 << y; break plane; }
                }
            }
            return mask;
        }
    }
    private static final class Saved {
        final long requested;
        final CompletableFuture<RocketTerrainCache.Snapshot> future;
        long accessed;
        ColumnSummary column;
        Saved(long requested, CompletableFuture<RocketTerrainCache.Snapshot> future) {
            this.requested = requested; this.accessed = requested;
            // Publish palettes and their immutable occupancy summary together. No palette
            // inspection or waiting for asynchronous work on the simulation thread.
            this.future = future.thenApplyAsync(snapshot -> {
                if (snapshot != null) column = new ColumnSummary(snapshot, requested);
                return snapshot;
            }, TERRAIN_DECODER).exceptionally(error -> null);
        }
    }
    private static final class Deferred extends RuntimeException {
        static final Deferred INSTANCE = new Deferred();
        private Deferred() { super(null, null, false, false); }
    }
}
