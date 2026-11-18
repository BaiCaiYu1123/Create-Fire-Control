package com.hooya.stabilizedturret.content.loader;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Low-frequency, server-side target lookup used by the optional Touhou Little Maid task.
 * All distances are checked in root-world space so depots, maids and assembled cannons may
 * independently live in normal chunks or in nested/rotated Sable sub-levels.
 */
public final class MaidCannonLoading {
    public static final int SEARCH_RADIUS = 5;
    private static final double SEARCH_RADIUS_SQR = SEARCH_RADIUS * SEARCH_RADIUS;
    private static final int MAX_BLOCK_CHECKS_PER_LEVEL = 40_000;
    private static final int MAX_CANDIDATES_PER_KIND = 32;
    private static final long CLAIM_TICKS = 100L;
    private static final String LOADING_SEAT_MEMORY = "CreateFireControlLoadingSeat";

    private static final String MEDIUM_CONTRAPTION =
            "riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption";
    private static final String MEDIUM_AMMO_ITEM =
            "riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonAmmoItem";
    private static final String CBC_SYNC_COMPAT =
            "com.hooya.stabilizedturret.content.loader.MzLoaderCbcSyncCompat";

    private static final Map<UUID, Claim> CLAIMS = new HashMap<>();

    public static Optional<Target> find(Level maidLevel, Vec3 maidPosition) {
        if (maidLevel == null || maidLevel.isClientSide
                || !ModList.get().isLoaded("cbcmodernwarfare")) {
            return Optional.empty();
        }

        Vec3 worldCenter = Sable.HELPER.projectOutOfSubLevel(maidLevel, maidPosition);
        if (!finite(worldCenter)) return Optional.empty();

        SubLevelContainer container = SubLevelContainer.getContainer(maidLevel);
        Level root = container.getLevel();
        ScanResults results = new ScanResults();
        BlockPos rootCenter = BlockPos.containing(worldCenter);
        scan(root, rootCenter.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                rootCenter.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS),
                null, worldCenter, results);

        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel == null || subLevel.isRemoved()
                    || (results.depots.size() >= MAX_CANDIDATES_PER_KIND
                    && results.cannons.size() >= MAX_CANDIDATES_PER_KIND)
                    || !intersectsSearchRadius(subLevel, worldCenter)) {
                continue;
            }
            LocalBounds bounds = localSearchBounds(subLevel, worldCenter);
            scan(subLevel.getLevel(), bounds.min, bounds.max, subLevel.getUniqueId(),
                    worldCenter, results);
        }

        if (results.depots.isEmpty() || results.cannons.isEmpty()) return Optional.empty();
        results.depots.sort(Comparator.comparingDouble(DepotRef::distanceSqr));
        results.cannons.sort(Comparator.comparingDouble(CannonRef::distanceSqr));

        Target best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (CannonRef cannon : results.cannons) {
            for (DepotRef depot : results.depots) {
                if (!isCompatibleAmmunition(depot.depot.getHeldItem(), cannon)) continue;
                double score = cannon.distanceSqr + depot.distanceSqr;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Target(depot, cannon);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Finds a Create block seat for loading-mode idle time. The depot is selected first by
     * distance to the maid; seats are then ordered by distance to that depot, with distance to
     * the maid as the explicit tie-breaker. Both searches use root-world coordinates so a depot,
     * seat and maid may each live in a different nested or rotated Sable sub-level.
     */
    public static Optional<SeatTarget> findSeat(
            Level maidLevel, Vec3 maidPosition, Entity rider
    ) {
        if (maidLevel == null || maidLevel.isClientSide || rider == null
                || !ModList.get().isLoaded("cbcmodernwarfare")) {
            return Optional.empty();
        }
        Vec3 maidWorld = Sable.HELPER.projectOutOfSubLevel(maidLevel, maidPosition);
        if (!finite(maidWorld)) return Optional.empty();

        SubLevelContainer container = SubLevelContainer.getContainer(maidLevel);
        ScanResults results = new ScanResults();
        Level root = container.getLevel();
        BlockPos rootCenter = BlockPos.containing(maidWorld);
        scan(root, rootCenter.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                rootCenter.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS),
                null, maidWorld, results);
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel == null || subLevel.isRemoved()
                    || results.depots.size() >= MAX_CANDIDATES_PER_KIND
                    || !intersectsSearchRadius(subLevel, maidWorld)) continue;
            LocalBounds bounds = localSearchBounds(subLevel, maidWorld);
            scan(subLevel.getLevel(), bounds.min, bounds.max, subLevel.getUniqueId(),
                    maidWorld, results);
        }
        DepotRef nearestDepot = results.depots.stream()
                .min(Comparator.comparingDouble(DepotRef::distanceSqr)
                        .thenComparingLong(depot -> depot.pos.asLong()))
                .orElse(null);
        if (nearestDepot == null) return Optional.empty();

        Vec3 depotWorld = nearestDepot.worldCenter();
        SeatSearch search = new SeatSearch(maidWorld, rider);
        scanSeats(root, null, depotWorld, search);
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel == null || subLevel.isRemoved()
                    || !intersectsSearchRadius(subLevel, depotWorld)) continue;
            scanSeats(subLevel.getLevel(), subLevel.getUniqueId(), depotWorld, search);
        }
        return Optional.ofNullable(search.best);
    }

    /** Restores the exact seat locked before the last world shutdown. */
    public static Optional<SeatTarget> rememberedSeat(Entity rider) {
        if (rider == null) return Optional.empty();
        CompoundTag owner = rider.getPersistentData();
        if (!owner.contains(LOADING_SEAT_MEMORY, Tag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        CompoundTag saved = owner.getCompound(LOADING_SEAT_MEMORY);
        if (!saved.contains("Pos", Tag.TAG_LONG)) return Optional.empty();
        if (saved.contains("Dimension", Tag.TAG_STRING)
                && !rider.level().dimension().location().toString()
                .equals(saved.getString("Dimension"))) {
            return Optional.empty();
        }
        UUID subLevelId = saved.hasUUID("SubLevel") ? saved.getUUID("SubLevel") : null;
        BlockPos pos = BlockPos.of(saved.getLong("Pos"));
        Level level = SubLevelContainer.getContainer(rider.level()).getLevel();
        Vec3 fallback = Sable.HELPER.projectOutOfSubLevel(
                rider.level(), rider.position());
        return Optional.of(new SeatTarget(level, pos, subLevelId, fallback));
    }

    /** Adopts a Create seat relationship that vanilla restored before the maid brain starts. */
    public static Optional<SeatTarget> currentSeat(Entity rider) {
        if (rider == null || !(rider.getVehicle() instanceof SeatEntity seat)
                || !seat.hasPassenger(rider)) {
            return Optional.empty();
        }
        Level level = seat.level();
        BlockPos pos = seat.blockPosition();
        if (!(level.getBlockState(pos).getBlock() instanceof SeatBlock)) {
            return Optional.empty();
        }
        SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        UUID subLevelId = subLevel == null || subLevel.isRemoved()
                ? null : subLevel.getUniqueId();
        Vec3 world = Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(pos));
        return Optional.of(new SeatTarget(level, pos, subLevelId, world));
    }

    public static void forgetRememberedSeat(Entity rider) {
        if (rider != null) rider.getPersistentData().remove(LOADING_SEAT_MEMORY);
    }

    private static void scanSeats(
            Level level, @Nullable UUID subLevelId, Vec3 depotWorld, SeatSearch search
    ) {
        BlockPos min;
        BlockPos max;
        if (subLevelId == null) {
            BlockPos center = BlockPos.containing(depotWorld);
            min = center.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS);
            max = center.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS);
        } else {
            SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(subLevelId);
            if (subLevel == null || subLevel.isRemoved()) return;
            LocalBounds bounds = localSearchBounds(subLevel, depotWorld);
            min = bounds.min;
            max = bounds.max;
        }
        long sx = (long) max.getX() - min.getX() + 1L;
        long sy = (long) max.getY() - min.getY() + 1L;
        long sz = (long) max.getZ() - min.getZ() + 1L;
        if (sx <= 0L || sy <= 0L || sz <= 0L || sx * sy * sz > MAX_BLOCK_CHECKS_PER_LEVEL) {
            return;
        }

        int minChunkX = Math.floorDiv(min.getX(), 16);
        int maxChunkX = Math.floorDiv(max.getX(), 16);
        int minChunkZ = Math.floorDiv(min.getZ(), 16);
        int maxChunkZ = Math.floorDiv(max.getZ(), 16);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                if (!(level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)
                        instanceof LevelChunk chunk)) continue;
                int fromX = Math.max(min.getX(), chunkX << 4);
                int toX = Math.min(max.getX(), (chunkX << 4) + 15);
                int fromZ = Math.max(min.getZ(), chunkZ << 4);
                int toZ = Math.min(max.getZ(), (chunkZ << 4) + 15);
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = fromZ; z <= toZ; z++) {
                        for (int x = fromX; x <= toX; x++) {
                            cursor.set(x, y, z);
                            if (!(chunk.getBlockState(cursor).getBlock() instanceof SeatBlock)) {
                                continue;
                            }
                            BlockPos pos = cursor.immutable();
                            Vec3 seatWorld = Sable.HELPER.projectOutOfSubLevel(
                                    level, Vec3.atCenterOf(pos));
                            double depotDistance = seatWorld.distanceToSqr(depotWorld);
                            if (depotDistance > SEARCH_RADIUS_SQR
                                    || !SeatTarget.available(level, pos, search.rider)) continue;
                            search.consider(new SeatTarget(level, pos, subLevelId, seatWorld),
                                    depotDistance, seatWorld.distanceToSqr(search.maidWorld));
                        }
                    }
                }
            }
        }
    }

    private static void scan(
            Level level,
            BlockPos min,
            BlockPos max,
            @Nullable UUID subLevelId,
            Vec3 worldCenter,
            ScanResults results
    ) {
        long sx = (long) max.getX() - min.getX() + 1L;
        long sy = (long) max.getY() - min.getY() + 1L;
        long sz = (long) max.getZ() - min.getZ() + 1L;
        if (sx <= 0L || sy <= 0L || sz <= 0L || sx * sy * sz > MAX_BLOCK_CHECKS_PER_LEVEL) {
            return;
        }

        int minChunkX = Math.floorDiv(min.getX(), 16);
        int maxChunkX = Math.floorDiv(max.getX(), 16);
        int minChunkZ = Math.floorDiv(min.getZ(), 16);
        int maxChunkZ = Math.floorDiv(max.getZ(), 16);
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                if (!(level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)
                        instanceof LevelChunk chunk)) continue;
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (results.depots.size() >= MAX_CANDIDATES_PER_KIND
                            && results.cannons.size() >= MAX_CANDIDATES_PER_KIND) return;
                    BlockPos pos = blockEntity.getBlockPos();
                    if (pos.getX() < min.getX() || pos.getX() > max.getX()
                            || pos.getY() < min.getY() || pos.getY() > max.getY()
                            || pos.getZ() < min.getZ() || pos.getZ() > max.getZ()
                            || blockEntity.isRemoved()) continue;

                    Vec3 world = Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(pos));
                    double distanceSqr = world.distanceToSqr(worldCenter);
                    if (distanceSqr > SEARCH_RADIUS_SQR) continue;

                    if (blockEntity instanceof DepotBlockEntity depot
                            && results.depots.size() < MAX_CANDIDATES_PER_KIND
                            && isSupportedAmmunition(depot.getHeldItem())) {
                        results.depots.add(new DepotRef(level, pos.immutable(), subLevelId,
                                depot, distanceSqr));
                    }
                    if (results.cannons.size() < MAX_CANDIDATES_PER_KIND
                            && MzLoaderAutomation.isSupportedMount(level, pos)) {
                        CannonRef cannon = resolveCannon(level, pos.immutable(), subLevelId,
                                blockEntity, distanceSqr);
                        if (cannon != null) results.cannons.add(cannon);
                    }
                }
            }
        }
    }

    private static @Nullable CannonRef resolveCannon(
            Level level,
            BlockPos pos,
            @Nullable UUID subLevelId,
            BlockEntity mount,
            double distanceSqr
    ) {
        Object mounted = invokeNoArg(mount, "getContraption");
        if (!(mounted instanceof Entity entity) || entity.isRemoved()) return null;
        Object contraption = invokeNoArg(entity, "getContraption");
        if (contraption == null || !hasClassName(contraption.getClass(), MEDIUM_CONTRAPTION)) return null;
        AmmunitionKind ammunitionKind = AmmunitionKind.MEDIUM_CANNON;
        BlockEntity breech = getBreech(contraption);
        ItemStack chamber = itemStackResult(breech, "getInputBuffer");
        Number openDirection = numberResult(breech, "getOpenDirection");
        if (breech == null || chamber == null || openDirection == null
                || openDirection.intValue() != 0 || !isUnloaded(chamber, ammunitionKind)) {
            return null;
        }
        return new CannonRef(level, pos, subLevelId, mount, entity.getUUID(), breech,
                ammunitionKind, distanceSqr);
    }

    private static boolean intersectsSearchRadius(SubLevel subLevel, Vec3 center) {
        var bounds = subLevel.boundingBox();
        double x = Math.max(bounds.minX(), Math.min(bounds.maxX(), center.x));
        double y = Math.max(bounds.minY(), Math.min(bounds.maxY(), center.y));
        double z = Math.max(bounds.minZ(), Math.min(bounds.maxZ(), center.z));
        return center.distanceToSqr(x, y, z) <= SEARCH_RADIUS_SQR;
    }

    private static LocalBounds localSearchBounds(SubLevel subLevel, Vec3 center) {
        Vector3d min = new Vector3d(Double.POSITIVE_INFINITY);
        Vector3d max = new Vector3d(Double.NEGATIVE_INFINITY);
        for (int corner = 0; corner < 8; corner++) {
            Vector3d point = new Vector3d(
                    center.x + ((corner & 1) == 0 ? -SEARCH_RADIUS : SEARCH_RADIUS),
                    center.y + ((corner & 2) == 0 ? -SEARCH_RADIUS : SEARCH_RADIUS),
                    center.z + ((corner & 4) == 0 ? -SEARCH_RADIUS : SEARCH_RADIUS));
            subLevel.logicalPose().transformPositionInverse(point);
            min.min(point);
            max.max(point);
        }
        return new LocalBounds(
                BlockPos.containing(Math.floor(min.x) - 1.0, Math.floor(min.y) - 1.0,
                        Math.floor(min.z) - 1.0),
                BlockPos.containing(Math.ceil(max.x) + 1.0, Math.ceil(max.y) + 1.0,
                        Math.ceil(max.z) + 1.0));
    }

    private static boolean isSupportedAmmunition(ItemStack stack) {
        return isAmmunitionKind(stack, AmmunitionKind.MEDIUM_CANNON);
    }

    private static boolean isCompatibleAmmunition(ItemStack stack, CannonRef cannon) {
        return cannon != null && isAmmunitionKind(stack, cannon.ammunitionKind);
    }

    private static boolean isAmmunitionKind(ItemStack stack, AmmunitionKind kind) {
        if (stack == null || stack.isEmpty() || kind == null) return false;
        return switch (kind) {
            case MEDIUM_CANNON -> OptionalClasses.MEDIUM_AMMO_ITEM != null
                    && OptionalClasses.MEDIUM_AMMO_ITEM.isInstance(stack.getItem());
        };
    }

    /** A non-ammunition buffer item is the spent case left after firing. */
    private static boolean isUnloaded(ItemStack stack, AmmunitionKind kind) {
        return stack.isEmpty() || !isAmmunitionKind(stack, kind);
    }

    private static boolean finite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    private static boolean hasClassName(Class<?> type, String expectedName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (expectedName.equals(current.getName())) return true;
        }
        return false;
    }

    private static @Nullable BlockEntity getBreech(Object contraption) {
        Object rawStart = invokeNoArg(contraption, "getStartPos");
        Field presentField = findField(contraption.getClass(), "presentBlockEntities");
        if (!(rawStart instanceof BlockPos start) || presentField == null) return null;
        try {
            presentField.setAccessible(true);
            Object rawMap = presentField.get(contraption);
            return rawMap instanceof Map<?, ?> map && map.get(start) instanceof BlockEntity breech
                    ? breech : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Object invokeNoArg(@Nullable Object owner, String name) {
        if (owner == null) return null;
        try {
            Method method = owner.getClass().getMethod(name);
            return method.invoke(owner);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable ItemStack itemStackResult(@Nullable Object owner, String name) {
        Object result = invokeNoArg(owner, name);
        return result instanceof ItemStack stack ? stack : null;
    }

    private static @Nullable Number numberResult(@Nullable Object owner, String name) {
        Object result = invokeNoArg(owner, name);
        return result instanceof Number number ? number : null;
    }

    private static boolean invokeItemStackArg(Object owner, String name, ItemStack stack) {
        try {
            Method method = owner.getClass().getMethod(name, ItemStack.class);
            method.invoke(owner, stack);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static @Nullable Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException | LinkageError ignored) {
            }
        }
        return null;
    }

    private static void sync(CannonRef cannon) {
        if (cannon.subLevelId == null || !ModList.get().isLoaded("createbigcannons")) return;
        Entity entity = cannon.entity();
        if (entity == null) return;
        try {
            Class<?> compat = Class.forName(CBC_SYNC_COMPAT, true,
                    MaidCannonLoading.class.getClassLoader());
            compat.getMethod("sync", Level.class, UUID.class, Entity.class, BlockEntity.class)
                    .invoke(null, cannon.level, cannon.subLevelId, entity, cannon.breech);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // CBC's normal contraption sync remains the fallback for non-Sable tracking.
        }
    }

    public static final class Target {
        private final DepotRef depot;
        private final CannonRef cannon;

        private Target(DepotRef depot, CannonRef cannon) {
            this.depot = depot;
            this.cannon = cannon;
        }

        public UUID cannonId() {
            return cannon.entityId;
        }

        public Vec3 depotWorldCenter() {
            return depot.worldCenter();
        }

        public Vec3 breechWorldCenter() {
            return cannon.breechWorldCenter();
        }

        public boolean isBreechOpen() {
            return cannon.valid(false)
                    && Boolean.TRUE.equals(invokeNoArg(cannon.breech, "isOpen"));
        }

        public boolean tryClaim(UUID maidId, long gameTime) {
            synchronized (CLAIMS) {
                CLAIMS.entrySet().removeIf(entry -> entry.getValue().expiresAt < gameTime);
                Claim current = CLAIMS.get(cannon.entityId);
                if (current != null && !current.maidId.equals(maidId)) return false;
                CLAIMS.put(cannon.entityId, new Claim(maidId, gameTime + CLAIM_TICKS));
                return true;
            }
        }

        public void release(UUID maidId) {
            synchronized (CLAIMS) {
                Claim current = CLAIMS.get(cannon.entityId);
                if (current != null && current.maidId.equals(maidId)) {
                    CLAIMS.remove(cannon.entityId);
                }
            }
        }

        public boolean isReady(Vec3 maidWorldPosition) {
            return depot.valid(true) && cannon.valid(true)
                    && isCompatibleAmmunition(depot.depot.getHeldItem(), cannon)
                    && depot.worldCenter().distanceToSqr(maidWorldPosition) <= SEARCH_RADIUS_SQR
                    && cannon.worldCenter().distanceToSqr(maidWorldPosition) <= SEARCH_RADIUS_SQR;
        }

        public boolean isCannonAvailable(Vec3 maidWorldPosition, boolean requireUnloaded) {
            return cannon.valid(requireUnloaded)
                    && cannon.worldCenter().distanceToSqr(maidWorldPosition) <= SEARCH_RADIUS_SQR;
        }

        /** Starts opening and removes the spent case left by the compatible ammunition. */
        public boolean openBreech() {
            if (!cannon.valid(true)) return false;
            ItemStack chamber = itemStackResult(cannon.breech, "getInputBuffer");
            if (chamber == null) return false;
            if (!chamber.isEmpty()) {
                if (!isUnloaded(chamber, cannon.ammunitionKind)) return false;
                invokeNoArg(cannon.breech, "clearInputBuffer");
            }
            if (Boolean.TRUE.equals(invokeNoArg(cannon.breech, "isOpen"))) {
                sync(cannon);
                return true;
            }
            invokeNoArg(cannon.breech, "toggleOpening");
            Number direction = numberResult(cannon.breech, "getOpenDirection");
            boolean accepted = Boolean.TRUE.equals(invokeNoArg(cannon.breech, "isOpen"))
                    || direction != null && direction.intValue() > 0;
            if (accepted) sync(cannon);
            return accepted;
        }

        public ItemStack takeAmmunition() {
            if (!depot.valid(true)) return ItemStack.EMPTY;
            ItemStack held = depot.depot.getHeldItem();
            if (!isCompatibleAmmunition(held, cannon)) return ItemStack.EMPTY;
            ItemStack round = held.copy();
            round.setCount(1);
            ItemStack remaining = held.copy();
            remaining.shrink(1);
            depot.depot.setHeldItem(remaining);
            depot.sync();
            return round;
        }

        public boolean returnAmmunition(ItemStack ammunition) {
            if (!isCompatibleAmmunition(ammunition, cannon) || !depot.valid(false)) return false;
            ItemStack held = depot.depot.getHeldItem();
            if (held.isEmpty()) {
                depot.depot.setHeldItem(ammunition.copy());
                depot.sync();
                return true;
            }
            if (!ItemStack.isSameItemSameComponents(held, ammunition)
                    || held.getCount() >= held.getMaxStackSize()) return false;
            ItemStack merged = held.copy();
            merged.grow(ammunition.getCount());
            if (merged.getCount() > merged.getMaxStackSize()) return false;
            depot.depot.setHeldItem(merged);
            depot.sync();
            return true;
        }

        public boolean insertAmmunition(ItemStack ammunition) {
            if (!isCompatibleAmmunition(ammunition, cannon) || !cannon.valid(true)
                    || !Boolean.TRUE.equals(invokeNoArg(cannon.breech, "isOpen"))) {
                return false;
            }
            ItemStack chamber = itemStackResult(cannon.breech, "getInputBuffer");
            if (chamber == null || !chamber.isEmpty()) return false;
            ItemStack inserted = ammunition.copy();
            inserted.setCount(1);
            if (!invokeItemStackArg(cannon.breech, "setInputBuffer", inserted)) return false;
            sync(cannon);
            return true;
        }

        /** Commands closure. A closing breech counts as accepted; its visual motion may finish later. */
        public boolean closeBreech() {
            if (!cannon.valid(false)) return false;
            if (Boolean.TRUE.equals(invokeNoArg(cannon.breech, "isOpen"))) {
                invokeNoArg(cannon.breech, "toggleOpening");
            }
            Number direction = numberResult(cannon.breech, "getOpenDirection");
            boolean accepted = !Boolean.TRUE.equals(invokeNoArg(cannon.breech, "isOpen"))
                    || direction != null && direction.intValue() < 0;
            if (accepted) sync(cannon);
            return accepted;
        }
    }

    private record DepotRef(
            Level level,
            BlockPos pos,
            @Nullable UUID subLevelId,
            DepotBlockEntity depot,
            double distanceSqr
    ) {
        private boolean valid(boolean requireCartridge) {
            if (!level.hasChunkAt(pos) || level.getBlockEntity(pos) != depot || depot.isRemoved()) {
                return false;
            }
            return !requireCartridge || isSupportedAmmunition(depot.getHeldItem());
        }

        private Vec3 worldCenter() {
            return Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(pos));
        }

        private void sync() {
            depot.setChanged();
            depot.notifyUpdate();
            if (subLevelId == null) return;
            SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(subLevelId);
            if (!(subLevel instanceof ServerSubLevel serverSubLevel)) return;
            ClientboundBlockEntityDataPacket packet = depot.getUpdatePacket();
            if (packet == null) return;
            for (UUID playerId : serverSubLevel.getTrackingPlayers()) {
                if (serverSubLevel.getLevel().getPlayerByUUID(playerId)
                        instanceof ServerPlayer player) {
                    player.connection.send(packet);
                }
            }
        }

    }

    private record CannonRef(
            Level level,
            BlockPos pos,
            @Nullable UUID subLevelId,
            BlockEntity mount,
            UUID entityId,
            BlockEntity breech,
            AmmunitionKind ammunitionKind,
            double distanceSqr
    ) {
        private @Nullable Entity entity() {
            Object mounted = invokeNoArg(mount, "getContraption");
            return mounted instanceof Entity entity && entityId.equals(entity.getUUID())
                    && !entity.isRemoved() ? entity : null;
        }

        private boolean valid(boolean requireUnloaded) {
            Entity mountedEntity = entity();
            Object contraption = mountedEntity == null ? null
                    : invokeNoArg(mountedEntity, "getContraption");
            if (!level.hasChunkAt(pos) || level.getBlockEntity(pos) != mount || mount.isRemoved()
                    || contraption == null || getBreech(contraption) != breech) return false;
            ItemStack chamber = itemStackResult(breech, "getInputBuffer");
            return chamber != null && (!requireUnloaded || isUnloaded(chamber, ammunitionKind));
        }

        private Vec3 worldCenter() {
            return Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(pos));
        }

        private Vec3 breechWorldCenter() {
            Entity mountedEntity = entity();
            if (mountedEntity instanceof AbstractContraptionEntity contraptionEntity) {
                Vec3 localCenter = contraptionEntity.toGlobalVector(
                        Vec3.atCenterOf(breech.getBlockPos()), 0.0F);
                Vec3 worldCenter = Sable.HELPER.projectOutOfSubLevel(
                        mountedEntity.level(), localCenter);
                if (finite(worldCenter)) return worldCenter;
            }
            return worldCenter();
        }
    }

    /** One unoccupied Create block seat selected for the maid's loading-mode idle position. */
    public static final class SeatTarget {
        private final Level level;
        private final BlockPos pos;
        private final @Nullable UUID subLevelId;
        private final Vec3 worldCenter;

        private SeatTarget(
                Level level, BlockPos pos, @Nullable UUID subLevelId, Vec3 worldCenter
        ) {
            this.level = level;
            this.pos = pos;
            this.subLevelId = subLevelId;
            this.worldCenter = worldCenter;
        }

        public Vec3 worldCenter() {
            if (subLevelId != null) {
                SubLevel subLevel = SubLevelContainer.getContainer(level)
                        .getSubLevel(subLevelId);
                if (subLevel != null && !subLevel.isRemoved()) {
                    Vec3 current = subLevel.logicalPose()
                            .transformPosition(Vec3.atCenterOf(pos));
                    if (finite(current)) return current;
                }
            }
            return worldCenter;
        }

        public boolean isSableSeat() {
            return subLevelId != null;
        }

        public void remember(Entity rider) {
            if (rider == null) return;
            CompoundTag saved = new CompoundTag();
            saved.putLong("Pos", pos.asLong());
            saved.putString("Dimension", level.dimension().location().toString());
            if (subLevelId != null) saved.putUUID("SubLevel", subLevelId);
            rider.getPersistentData().put(LOADING_SEAT_MEMORY, saved);
        }

        public boolean isCurrentSeat(Entity rider) {
            Entity vehicle = rider == null ? null : rider.getVehicle();
            return vehicle instanceof SeatEntity && vehicle.level() == level
                    && vehicle.blockPosition().equals(pos) && vehicle.hasPassenger(rider);
        }

        public boolean isValidFor(Entity rider) {
            return level.hasChunkAt(pos)
                    && level.getBlockState(pos).getBlock() instanceof SeatBlock
                    && available(level, pos, rider);
        }

        /** Uses Create's real seat entity, never Touhou Little Maid's entity cushion. */
        public boolean sit(Entity rider) {
            if (rider == null || !isValidFor(rider)) return false;
            if (isCurrentSeat(rider)) return true;
            if (rider.isPassenger()) rider.stopRiding();

            SubLevel targetSubLevel = subLevelId == null ? null
                    : SubLevelContainer.getContainer(level).getSubLevel(subLevelId);
            if (subLevelId != null && (targetSubLevel == null || targetSubLevel.isRemoved())) {
                return false;
            }
            if (rider instanceof dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension movement) {
                movement.sable$setTrackingSubLevel(targetSubLevel);
            }

            List<SeatEntity> existing = seatEntities(level, pos);
            for (SeatEntity seat : existing) {
                if (seat.hasPassenger(rider)) return true;
                if (seat.getPassengers().isEmpty() && rider.startRiding(seat, true)) return true;
            }
            if (!existing.isEmpty()) return false;
            SeatBlock.sitDown(level, pos, rider);
            return isCurrentSeat(rider);
        }

        private static boolean available(Level level, BlockPos pos, Entity rider) {
            List<SeatEntity> seats = seatEntities(level, pos);
            if (seats.isEmpty()) return true;
            for (SeatEntity seat : seats) {
                if (seat.hasPassenger(rider)) return true;
                if (!seat.getPassengers().isEmpty()) return false;
            }
            return true;
        }

        private static List<SeatEntity> seatEntities(Level level, BlockPos pos) {
            return level.getEntitiesOfClass(SeatEntity.class, new AABB(pos),
                    seat -> !seat.isRemoved());
        }
    }

    private static final class SeatSearch {
        private final Vec3 maidWorld;
        private final Entity rider;
        private @Nullable SeatTarget best;
        private double bestDepotDistance = Double.POSITIVE_INFINITY;
        private double bestMaidDistance = Double.POSITIVE_INFINITY;

        private SeatSearch(Vec3 maidWorld, Entity rider) {
            this.maidWorld = maidWorld;
            this.rider = rider;
        }

        private void consider(
                SeatTarget candidate, double depotDistance, double maidDistance
        ) {
            int depotOrder = Double.compare(depotDistance, bestDepotDistance);
            int maidOrder = Double.compare(maidDistance, bestMaidDistance);
            boolean deterministicTie = depotOrder == 0 && maidOrder == 0 && best != null
                    && candidate.pos.asLong() < best.pos.asLong();
            if (best == null || depotOrder < 0
                    || depotOrder == 0 && maidOrder < 0 || deterministicTie) {
                best = candidate;
                bestDepotDistance = depotDistance;
                bestMaidDistance = maidDistance;
            }
        }
    }

    private static final class ScanResults {
        private final List<DepotRef> depots = new ArrayList<>();
        private final List<CannonRef> cannons = new ArrayList<>();
    }

    private record LocalBounds(BlockPos min, BlockPos max) {
    }

    private record Claim(UUID maidId, long expiresAt) {
    }

    private enum AmmunitionKind {
        MEDIUM_CANNON
    }

    private static final class OptionalClasses {
        private static final Class<?> MEDIUM_AMMO_ITEM = load(
                MaidCannonLoading.MEDIUM_AMMO_ITEM);

        private static @Nullable Class<?> load(String name) {
            try {
                return Class.forName(name, false, MaidCannonLoading.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError ignored) {
                return null;
            }
        }
    }

    private MaidCannonLoading() {
    }
}
