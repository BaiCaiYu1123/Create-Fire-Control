package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Inventory-backed aircraft racks: select an installed family, release its highest slot, retain exact shot ownership. */
public final class MianbaoAircraftRackCompat {
    private static final ResourceLocation MIXED_RACK = id("quad_air_to_air_rack");
    private record Round(WeaponType type, ResourceLocation entity) { }
    private static final Map<String, Round> ROUNDS = Map.of(
            "far_missile_1", round(WeaponType.FAR, "farmissile_1tanshe"),
            "far_missile_2", round(WeaponType.FAR, "farmissile_2tanshe"),
            "medium_missile", round(WeaponType.MEDIUM, "missile_2tanshe"),
            "medium_ai_mmissile_2", round(WeaponType.MEDIUM, "medium_aim_missile_2tanshe"),
            "closemissile_1", round(WeaponType.CLOSE, "closemissile_1tanshe"),
            "closemissile_2", round(WeaponType.CLOSE, "closemissile_2tanshe"),
            "mica_em", round(WeaponType.MICA_EM, "mica_em_tanshe"),
            "aim_120", round(WeaponType.AIM_120D, "aim_120_tanshe"));
    private record Access(Method nextSlot, Method missile, Method fire, Method launch, Method sync,
                          Method roundType, Method layouts, Method point) { }
    private static final ClassValue<Optional<Access>> ACCESS = new ClassValue<>() {
        @Override protected Optional<Access> computeValue(Class<?> type) {
            try {
                ClassLoader loader = type.getClassLoader();
                Class<?> support = Class.forName("net.mcreator.myfirstmod.util.QuadAirToAirRackSupport", true, loader);
                Class<?> geometry = Class.forName("net.mcreator.myfirstmod.util.RackLaunchGeometry", true, loader);
                Class<?> layout = Class.forName("net.mcreator.myfirstmod.util.RackLaunchGeometry$Layout", true, loader);
                Method roundType = support.getMethod("type", ItemStack.class);
                Method sync = type.getDeclaredMethod("sync");
                sync.setAccessible(true);
                return Optional.of(new Access(type.getMethod("nextSlot"),
                        type.getMethod("missile", int.class), type.getMethod("fire"),
                        support.getMethod("launch", type, int.class, ItemStack.class), sync,
                        roundType, roundType.getReturnType().getMethod("slots"),
                        geometry.getMethod("point", Level.class, layout, BlockState.class, BlockPos.class, Vec3.class)));
            } catch (ReflectiveOperationException | LinkageError ignored) { return Optional.empty(); }
        }
    };
    private record Loadout(Access access, List<ItemStack> slots, int next) {
        @Nullable Round nextRound() { return next < 0 ? null : round(slots.get(next)); }
        int ammo(WeaponType expected) {
            int count = 0;
            for (int slot = next; slot >= 0; slot--) {
                Round round = round(slots.get(slot));
                if (round != null && round.type() == expected) count++;
            }
            return count;
        }
        int slot(WeaponType expected) {
            for (int slot = next; slot >= 0; slot--) {
                Round round = round(slots.get(slot));
                if (round != null && round.type() == expected) return slot;
            }
            return -1;
        }
    }
    record Launch(StabilizerControllerBlockEntity controller, BindingRef rack, ResourceLocation entity,
                  int slot, Vec3 localMuzzle) { }
    private static final ThreadLocal<Launch> LAUNCH = new ThreadLocal<>();

    public static boolean isMixedRack(BlockState state) {
        return MIXED_RACK.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    static boolean isMixedRackId(ResourceLocation id) { return MIXED_RACK.equals(id); }

    public static @Nullable WeaponType weaponAt(Level level, BindingRef ref) {
        BlockEntity rack = resolveMixed(level, ref);
        if (rack == null) return MianbaoAircraftCompat.weaponAt(level, ref);
        Loadout loadout = read(rack);
        Round round = loadout == null ? null : loadout.nextRound();
        return round == null ? null : round.type();
    }

    /** Every installed family is selectable, regardless of the native redstone release order. */
    public static EnumSet<WeaponType> weaponsAt(Level level, BindingRef ref) {
        EnumSet<WeaponType> result = EnumSet.noneOf(WeaponType.class);
        BlockEntity rack = resolveMixed(level, ref);
        if (rack == null) {
            WeaponType type = MianbaoAircraftCompat.weaponAt(level, ref);
            if (type != null) result.add(type);
        } else {
            Loadout loadout = read(rack);
            if (loadout != null) for (ItemStack stack : loadout.slots()) {
                Round round = round(stack);
                if (round != null) result.add(round.type());
            }
        }
        return result;
    }

    /** HUD and cycling count all matching slots, including those below another family. */
    public static int ammoCount(Level level, BindingRef ref, WeaponType expected) {
        BlockEntity rack = resolveMixed(level, ref);
        if (rack == null) return MianbaoAircraftCompat.ammoCount(level, ref, expected);
        Loadout loadout = read(rack);
        return loadout == null ? 0 : loadout.ammo(expected);
    }

    public static boolean fireOne(StabilizerControllerBlockEntity controller, BindingRef ref, WeaponType expected) {
        Level level = controller.getLevel();
        if (level == null || level.isClientSide()) return false;
        BlockEntity rack = resolveMixed(level, ref);
        if (rack == null) return MianbaoAircraftCompat.fireOne(level, ref, expected);
        if (LAUNCH.get() != null || controller.getSelectedAircraftWeapon() != expected
                || !controller.isAircraftWeaponGuidanceReady(expected)) return false;
        Loadout before = read(rack);
        int selected = before == null ? -1 : before.slot(expected);
        if (selected < 0) return false;
        Round round = round(before.slots().get(selected));
        try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) {
            Access access = before.access();
            Object nativeRound = access.roundType().invoke(null, before.slots().get(selected));
            Object layout = ((Object[]) access.layouts().invoke(nativeRound))[selected];
            Vec3 localMuzzle = (Vec3) access.point().invoke(null, rack.getLevel(), layout,
                    rack.getBlockState(), rack.getBlockPos(), new Vec3(0, 0, -2.5));
            LAUNCH.set(new Launch(controller, ref, round.entity(), selected, localMuzzle));
            if (selected == before.next()) {
                if (!Boolean.TRUE.equals(access.fire().invoke(rack))) return false;
            } else {
                // The native zero-argument fire method always releases the top
                // slot. Use its public slot-aware launch backend for a buried
                // selected round, then persist exactly that slot's consumption.
                CompoundTag saved = rack.saveWithoutMetadata(level.registryAccess());
                if (!Boolean.TRUE.equals(access.launch().invoke(null, rack, selected, before.slots().get(selected)))) return false;
                NonNullList<ItemStack> remaining = NonNullList.withSize(4, ItemStack.EMPTY);
                for (int slot = 0; slot < 4; slot++) if (slot != selected) remaining.set(slot, before.slots().get(slot).copy());
                ContainerHelper.saveAllItems(saved, remaining, level.registryAccess());
                rack.loadWithComponents(saved, level.registryAccess());
                // Retain the native packet/loaded-state/collision updates.
                access.sync().invoke(rack);
            }
            Loadout after = read(rack);
            if (after == null || !after.slots().get(selected).isEmpty()) return false;
            for (int slot = 0; slot < 4; slot++) {
                if (slot != selected && !ItemStack.matches(before.slots().get(slot), after.slots().get(slot))) {
                    StabilizedTurret.LOGGER.error("Mixed aircraft rack changed an unselected slot at {}", ref.pos());
                    return false;
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            StabilizedTurret.LOGGER.error("Unable to fire mixed aircraft rack at {}", ref.pos(), error);
            return false;
        } finally {
            LAUNCH.remove();
        }
    }

    static @Nullable Launch currentLaunch(Entity missile) {
        Launch launch = LAUNCH.get();
        return launch != null && launch.entity().equals(BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType()))
                && missile.getPersistentData().contains("MianbaoQuadRackSlot")
                && missile.getPersistentData().getInt("MianbaoQuadRackSlot") == launch.slot() ? launch : null;
    }

    private static @Nullable BlockEntity resolveMixed(Level level, BindingRef ref) {
        if (level == null || ref == null) return null;
        BlockEntity rack = ref.resolve(level);
        return rack != null && isMixedRack(rack.getBlockState()) ? rack : null;
    }

    private static @Nullable Loadout read(BlockEntity rack) {
        Access access = ACCESS.get(rack.getClass()).orElse(null);
        if (access == null) return null;
        try {
            int next = (int) access.nextSlot().invoke(rack);
            if (next < -1 || next > 3) return null;
            List<ItemStack> slots = new ArrayList<>(4);
            int highest = -1;
            for (int slot = 0; slot < 4; slot++) {
                Object value = access.missile().invoke(rack, slot);
                if (!(value instanceof ItemStack stack)) return null;
                slots.add(stack.copy());
                if (!stack.isEmpty()) highest = slot;
            }
            return highest == next ? new Loadout(access, List.copyOf(slots), next) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return null; }
    }

    private static @Nullable Round round(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ResourceLocation item = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return item.getNamespace().equals(MianbaoAircraftCompat.MOD_ID) ? ROUNDS.get(item.getPath()) : null;
    }
    private static Round round(WeaponType type, String entity) { return new Round(type, id(entity)); }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MianbaoAircraftCompat.MOD_ID, path); }
    private MianbaoAircraftRackCompat() { }
}
