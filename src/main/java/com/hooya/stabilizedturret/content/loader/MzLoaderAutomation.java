package com.hooya.stabilizedturret.content.loader;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server-side bridge between model-specific automatic loaders and assembled medium cannons. */
public final class MzLoaderAutomation {
    private static final String CBC_SYNC_COMPAT =
            "com.hooya.stabilizedturret.content.loader.MzLoaderCbcSyncCompat";
    private static final ResourceLocation AUTOMATIC_CASING =
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "apfsds_medium_cannon_casing");
    private static final String MEDIUM_CONTRAPTION =
            "riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption";
    private static final Set<ResourceLocation> SUPPORTED_MOUNTS = Set.of(
            ResourceLocation.fromNamespaceAndPath("createbigcannons", "cannon_mount"),
            ResourceLocation.fromNamespaceAndPath("createbigcannons", "fixed_cannon_mount"),
            ResourceLocation.fromNamespaceAndPath("cbcmodernwarfare", "compact_mount")
    );
    private static final Map<CannonAutoLoader, Boolean> TRACKED_LOADERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Entity, Boolean> APFSDS_SCOPE_CANNONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    static void track(CannonAutoLoader loader) {
        TRACKED_LOADERS.put(loader, Boolean.TRUE);
    }

    public static boolean isSupportedMount(Level level, BlockPos pos) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return SUPPORTED_MOUNTS.contains(id) && level.getBlockEntity(pos) != null;
    }

    /** Total APFSDS in this cannon's loaders and chamber, retained through its reload cycle. */
    public static int getScopeApfsdsReserve(Level level, BindingRef binding) {
        if (level == null || level.isClientSide || binding == null) return -1;
        Entity cannon = mountedCannon(binding.resolve(level));
        if (cannon == null || cannon.isRemoved()) return -1;
        Object contraption = invokeNoArg(cannon, "getContraption");
        if (contraption == null || !MEDIUM_CONTRAPTION.equals(contraption.getClass().getName())) return -1;
        BlockEntity breech = getBreech(contraption);
        ItemStack chamber = itemStackResult(breech, "getInputBuffer");
        if (chamber == null) return -1;
        boolean chamberedApfsds = LoaderAmmunition.isStandard(chamber);
        boolean spentCasing = !chamber.isEmpty()
                && AUTOMATIC_CASING.equals(BuiltInRegistries.ITEM.getKey(chamber.getItem()));
        if (!chamber.isEmpty() && !chamberedApfsds && !spentCasing) {
            APFSDS_SCOPE_CANNONS.remove(cannon);
            return -1;
        }
        ResourceLocation breechId = BuiltInRegistries.BLOCK.getKey(breech.getBlockState().getBlock());
        CannonAutoLoader[] snapshot;
        synchronized (TRACKED_LOADERS) {
            snapshot = TRACKED_LOADERS.keySet().toArray(CannonAutoLoader[]::new);
        }
        int rounds = 0;
        boolean nextApfsds = false;
        for (CannonAutoLoader loader : snapshot) {
            if (loader == null || loader.isRemoved() || loader.getLevel() == null
                    || loader.getLevel().isClientSide || loader.getLevel().getServer() != level.getServer()
                    || !loader.requiredBreechId().equals(breechId)) continue;
            BindingRef supply = loader.getCannonMountBinding();
            if (supply != null && mountedCannon(supply.resolve(loader.getLevel())) == cannon) {
                rounds += loader.getApfsdsCount();
                nextApfsds |= LoaderAmmunition.isStandard(loader.getNextCartridge());
            }
        }
        if (chamberedApfsds || nextApfsds) {
            APFSDS_SCOPE_CANNONS.put(cannon, Boolean.TRUE);
        }
        if (!APFSDS_SCOPE_CANNONS.containsKey(cannon)) return -1;
        return rounds + (chamberedApfsds ? 1 : 0);
    }

    /** Called immediately after CBC has replaced the fired cartridge with its casing. */
    public static void onCannonFired(
            Object contraption,
            Entity cannonEntity
    ) {
        if (contraption == null || cannonEntity == null || cannonEntity.isRemoved()) return;
        BlockEntity breech = getBreech(contraption);
        ItemStack spent = itemStackResult(breech, "getInputBuffer");
        if (breech == null || spent == null
                || !AUTOMATIC_CASING.equals(BuiltInRegistries.ITEM.getKey(spent.getItem()))) {
            return;
        }

        CannonAutoLoader loader = findReadyBoundLoader(cannonEntity, breech);
        if (loader == null || !loader.startAutomaticCycle(cannonEntity.getUUID())) return;

        // The autoloader extracts the spent case immediately. Its animation starts
        // now, then opens the breech at the two-second point on the shared timeline.
        invokeNoArg(breech, "clearInputBuffer");
        syncBreechToSableTracking(loader, cannonEntity, breech);
    }

    /**
     * Continuously checks the assembled cannon bound to this loader. An empty chamber starts
     * a loading cycle immediately; a spent automatic casing starts the post-recoil cycle.
     */
    static void tickBoundLoader(CannonAutoLoader loader) {
        if (loader == null || loader.isRemoved() || loader.getLevel() == null
                || loader.getLevel().isClientSide || loader.isBusy()
                || loader.getSlotCount() == 0) {
            return;
        }
        Level level = loader.getLevel();
        BindingRef binding = loader.getCannonMountBinding();
        BlockEntity mount = binding == null ? null : binding.resolve(level);
        if (mount == null || !isSupportedMount(level, mount.getBlockPos())) return;

        Entity cannonEntity = mountedCannon(mount);
        if (cannonEntity == null || cannonEntity.isRemoved()) return;
        Object contraption = invokeNoArg(cannonEntity, "getContraption");
        if (contraption == null || !MEDIUM_CONTRAPTION.equals(contraption.getClass().getName())) {
            return;
        }
        BlockEntity breech = getRequiredBreech(contraption, loader.requiredBreechId());
        ItemStack chamber = itemStackResult(breech, "getInputBuffer");
        if (breech == null || chamber == null) return;

        boolean spentCasing = !chamber.isEmpty()
                && AUTOMATIC_CASING.equals(BuiltInRegistries.ITEM.getKey(chamber.getItem()));
        if (!chamber.isEmpty() && !spentCasing) return;
        if (hasBusyLoaderFor(cannonEntity, loader)) return;

        if (loader.startAutomaticCycle(cannonEntity.getUUID()) && spentCasing) {
            // A casing is removed only after this loader has successfully claimed the cycle.
            invokeNoArg(breech, "clearInputBuffer");
            syncBreechToSableTracking(loader, cannonEntity, breech);
        }
    }

    /** Opens the same assembled breech only if this loader actually needs to change it. */
    static boolean openBreechIfClosed(
            CannonAutoLoader loader,
            UUID expectedCannonId
    ) {
        BlockEntity breech = resolveRequiredBreech(loader, expectedCannonId);
        if (breech == null) return false;
        boolean currentlyOpen = Boolean.TRUE.equals(invokeNoArg(breech, "isOpen"));
        if (currentlyOpen) return false;
        invokeNoArg(breech, "toggleOpening");
        // CBC's non-instant breeches remain isOpen()==false until their opening
        // animation reaches the final frame.  Treat a positive open direction as
        // an accepted command so the loader retains ownership and closes this
        // same breech at its 120-tick keyframe.
        boolean openedOrOpening = Boolean.TRUE.equals(invokeNoArg(breech, "isOpen"));
        Object direction = invokeNoArg(breech, "getOpenDirection");
        if (!openedOrOpening
                && (!(direction instanceof Number number) || number.intValue() <= 0)) {
            return false;
        }
        syncBreechToSableTracking(loader, expectedCannonId, breech);
        return true;
    }

    /** Closes a breech previously opened by this loader; an already-closed breech is untouched. */
    static boolean closeBreechIfOpen(CannonAutoLoader loader, UUID expectedCannonId) {
        BlockEntity breech = resolveRequiredBreech(loader, expectedCannonId);
        if (breech == null) return false;
        if (!Boolean.TRUE.equals(invokeNoArg(breech, "isOpen"))) return true;
        invokeNoArg(breech, "toggleOpening");
        if (Boolean.TRUE.equals(invokeNoArg(breech, "isOpen"))) return false;
        syncBreechToSableTracking(loader, expectedCannonId, breech);
        return true;
    }

    /** True only while the originally bound assembled cannon and its MZ breech are loaded. */
    static boolean isCannonAvailable(CannonAutoLoader loader, UUID expectedCannonId) {
        return resolveRequiredBreech(loader, expectedCannonId) != null;
    }

    /** Inserts exactly one cartridge at the animation's 4.39-second key point. */
    static boolean insertCartridge(CannonAutoLoader loader, UUID expectedCannonId) {
        if (loader == null || loader.getLevel() == null || expectedCannonId == null) return false;
        BlockEntity breech = resolveRequiredBreech(loader, expectedCannonId);
        ItemStack input = itemStackResult(breech, "getInputBuffer");
        if (breech == null || input == null || !input.isEmpty()) return false;
        ItemStack cartridge = loader.getNextCartridge();
        if (cartridge.isEmpty()) return false;
        cartridge.setCount(1);
        boolean inserted = invokeItemStackArg(breech, "setInputBuffer", cartridge);
        if (inserted) syncBreechToSableTracking(loader, expectedCannonId, breech);
        return inserted;
    }

    private static void syncBreechToSableTracking(
            CannonAutoLoader loader,
            UUID expectedCannonId,
            BlockEntity breech
    ) {
        if (loader == null || loader.getLevel() == null || expectedCannonId == null) return;
        BindingRef binding = loader.getCannonMountBinding();
        BlockEntity mount = binding == null ? null : binding.resolve(loader.getLevel());
        Entity entity = mountedCannon(mount);
        if (entity != null && expectedCannonId.equals(entity.getUUID())) {
            syncBreechToSableTracking(loader, entity, breech);
        }
    }

    /** Sends CBC's virtual breech data to Sable's plot trackers as well as its normal trackers. */
    private static void syncBreechToSableTracking(
            CannonAutoLoader loader,
            Entity cannonEntity,
            BlockEntity breech
    ) {
        if (loader == null || loader.getLevel() == null || cannonEntity == null || breech == null
                || !ModList.get().isLoaded("createbigcannons")) return;
        try {
            Class<?> compat = Class.forName(CBC_SYNC_COMPAT, true,
                    MzLoaderAutomation.class.getClassLoader());
            compat.getMethod("sync", CannonAutoLoader.class, Entity.class, BlockEntity.class)
                    .invoke(null, loader, cannonEntity, breech);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // CBC's normal tracking still receives its own update. This extra bridge is
            // best-effort and exists only for players tracking a moving Sable plot.
        }
    }

    private static @Nullable BlockEntity resolveRequiredBreech(
            CannonAutoLoader loader,
            UUID expectedCannonId
    ) {
        if (loader == null || loader.getLevel() == null || expectedCannonId == null) return null;
        BindingRef binding = loader.getCannonMountBinding();
        BlockEntity mount = binding == null ? null : binding.resolve(loader.getLevel());
        Entity entity = mountedCannon(mount);
        if (entity == null || !expectedCannonId.equals(entity.getUUID())) return null;
        Object contraption = invokeNoArg(entity, "getContraption");
        if (contraption == null || !MEDIUM_CONTRAPTION.equals(contraption.getClass().getName())) {
            return null;
        }
        return getRequiredBreech(contraption, loader.requiredBreechId());
    }

    private static @Nullable CannonAutoLoader findReadyBoundLoader(
            Entity cannonEntity,
            BlockEntity breech
    ) {
        CannonAutoLoader[] snapshot;
        synchronized (TRACKED_LOADERS) {
            snapshot = TRACKED_LOADERS.keySet().toArray(CannonAutoLoader[]::new);
        }
        ResourceLocation breechId = breech == null ? null
                : BuiltInRegistries.BLOCK.getKey(breech.getBlockState().getBlock());
        for (CannonAutoLoader loader : snapshot) {
            if (loader == null || loader.isRemoved() || loader.getLevel() == null
                    || loader.getLevel().isClientSide || loader.isBusy()
                    || loader.getSlotCount() == 0
                    || loader.getLevel().getServer() != cannonEntity.level().getServer()) {
                continue;
            }
            BindingRef binding = loader.getCannonMountBinding();
            BlockEntity mount = binding == null ? null : binding.resolve(loader.getLevel());
            if (loader.requiredBreechId().equals(breechId)
                    && mount != null && isSupportedMount(loader.getLevel(), mount.getBlockPos())
                    && mountedCannon(mount) == cannonEntity) {
                return loader;
            }
        }
        return null;
    }

    /** Prevents two loaders bound to the same cannon from playing at the same time. */
    private static boolean hasBusyLoaderFor(Entity cannonEntity, CannonAutoLoader candidate) {
        CannonAutoLoader[] snapshot;
        synchronized (TRACKED_LOADERS) {
            snapshot = TRACKED_LOADERS.keySet().toArray(CannonAutoLoader[]::new);
        }
        for (CannonAutoLoader loader : snapshot) {
            if (loader == null || loader == candidate || loader.isRemoved()
                    || loader.getLevel() == null || !loader.isBusy()) {
                continue;
            }
            BindingRef binding = loader.getCannonMountBinding();
            BlockEntity mount = binding == null ? null : binding.resolve(loader.getLevel());
            if (mount != null && mountedCannon(mount) == cannonEntity) return true;
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
            if (!(rawMap instanceof Map<?, ?> map)
                    || !(map.get(start) instanceof BlockEntity breech)) {
                return null;
            }
            return breech;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable BlockEntity getRequiredBreech(
            Object contraption,
            ResourceLocation requiredBreech
    ) {
        BlockEntity breech = getBreech(contraption);
        if (breech == null) return null;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(breech.getBlockState().getBlock());
        return requiredBreech.equals(id) ? breech : null;
    }

    private static @Nullable Entity mountedCannon(@Nullable BlockEntity mount) {
        Object result = invokeNoArg(mount, "getContraption");
        return result instanceof Entity entity ? entity : null;
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

    private MzLoaderAutomation() {
    }
}
