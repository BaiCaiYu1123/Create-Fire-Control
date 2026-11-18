package com.hooya.stabilizedturret.content.loader;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Server inventory types, independent of the loaders' original visual slot masks. */
public final class LoaderAmmunition {
    private static final ResourceLocation STANDARD = ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "apfsds_medium_cannon_cartridge");
    private static final ResourceLocation PIERCING = ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "annihilation_medium_cannon_cartridge");
    private static final String TAG = "AnnihilationSlots";
    private int piercingSlots;

    public static boolean isSupported(ItemStack cartridge) {
        if (cartridge.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(cartridge.getItem());
        return STANDARD.equals(id) || PIERCING.equals(id);
    }

    public static ItemStack standardCartridge() {
        return BuiltInRegistries.ITEM.get(STANDARD).getDefaultInstance();
    }

    public static boolean isStandard(ItemStack cartridge) {
        return !cartridge.isEmpty() && STANDARD.equals(BuiltInRegistries.ITEM.getKey(cartridge.getItem()));
    }

    /** Count only live APFSDS slots; other ammunition and spent-case slots are excluded. */
    public int standardCount(int occupiedSlots) {
        return Integer.bitCount(occupiedSlots & ~piercingSlots);
    }

    public void insert(int slotBit, ItemStack cartridge) {
        piercingSlots &= ~slotBit;
        if (PIERCING.equals(BuiltInRegistries.ITEM.getKey(cartridge.getItem()))) piercingSlots |= slotBit;
    }

    public ItemStack cartridge(int slotBit) {
        if (slotBit == 0) return ItemStack.EMPTY;
        return BuiltInRegistries.ITEM.get((piercingSlots & slotBit) != 0 ? PIERCING : STANDARD).getDefaultInstance();
    }

    public void retain(int occupiedSlots) { piercingSlots &= occupiedSlots; }

    public void save(CompoundTag tag) { tag.putInt(TAG, piercingSlots); }

    public void load(CompoundTag tag, int occupiedSlots) { piercingSlots = tag.getInt(TAG) & occupiedSlots; }
}
