package com.hooya.stabilizedturret.content.controller;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.fml.loading.LoadingModList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exercises the actual launcher inventory reader and successful-launch consumption. */
public final class MianbaoSiloAmmoChecks {
    public static void main(String[] args) {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Standalone tests have no mod registration phase. Register only the
        // item IDs needed to exercise the optional integration's real reader.
        ((MappedRegistry<Item>) BuiltInRegistries.ITEM).unfreeze();
        Map<Item, MianbaoCruiseMissileCompat.Type> ammunition = new LinkedHashMap<>();
        add(ammunition, "groundmissile_1item", MianbaoCruiseMissileCompat.Type.GROUND_HIGH_EXPLOSIVE);
        add(ammunition, "groundmissile_2item", MianbaoCruiseMissileCompat.Type.GROUND_CLUSTER);
        add(ammunition, "groundmissile_3item", MianbaoCruiseMissileCompat.Type.GROUND_CRUISE);
        add(ammunition, "groundmissile_4item", MianbaoCruiseMissileCompat.Type.GROUND_LOW_ALTITUDE);
        add(ammunition, "groundnuclearcruisemissileitem", MianbaoCruiseMissileCompat.Type.GROUND_NUCLEAR);
        add(ammunition, "cruisemissilelowitem", MianbaoCruiseMissileCompat.Type.GROUND_LOW_ALTITUDE);
        add(ammunition, "cruisemissileitem", MianbaoCruiseMissileCompat.Type.CRUISE);
        add(ammunition, "nuclearcruisemissileitem", MianbaoCruiseMissileCompat.Type.NUCLEAR_CRUISE);
        BuiltInRegistries.ITEM.freeze();

        Launcher launcher = new Launcher();
        for (var entry : ammunition.entrySet()) {
            launcher.setItem(0, new ItemStack(entry.getKey()));
            require(MianbaoCruiseMissileCompat.loadedSiloType(launcher) == entry.getValue(),
                    "Inventory-only missile was not identified: " + entry.getValue());
            require(!MianbaoCruiseMissileCompat.nativeSlotsEmpty(launcher),
                    "Occupied inventory allowed compatibility loading");
            // Slot zero must win over stale old-format ammunition.
            launcher.getPersistentData().putString("ammo", "CLUSTER");
            launcher.getPersistentData().putDouble("loadmissile", 1);
            launcher.getPersistentData().putString("CreateFireControlCruiseMissileType", "nuclear_cruise");
            require(MianbaoCruiseMissileCompat.loadedSiloType(launcher) == entry.getValue(),
                    "Stale ammunition tags overrode the actual item");
            MianbaoCruiseMissileCompat.clearSiloAmmo(launcher, entry.getValue());
            require(launcher.getItem(0).isEmpty(), "Fired missile remained in the inventory");
            require(MianbaoCruiseMissileCompat.loadedSiloType(launcher) == null,
                    "Fired missile reappeared from legacy tags");
        }

        launcher.setItem(0, new ItemStack(Items.DIRT));
        launcher.getPersistentData().putString("ammo", "HE");
        require(MianbaoCruiseMissileCompat.loadedSiloType(launcher) == null,
                "Unsupported occupied slot fell back to stale ammunition");
        require(!MianbaoCruiseMissileCompat.nativeSlotsEmpty(launcher),
                "Unsupported occupied slot was considered empty");
        launcher.clearContent();

        Map<String, MianbaoCruiseMissileCompat.Type> named = Map.of(
                "HE", MianbaoCruiseMissileCompat.Type.GROUND_HIGH_EXPLOSIVE,
                "CLUSTER", MianbaoCruiseMissileCompat.Type.GROUND_CLUSTER,
                "CRUISE", MianbaoCruiseMissileCompat.Type.GROUND_CRUISE,
                "CRUISELOW", MianbaoCruiseMissileCompat.Type.GROUND_LOW_ALTITUDE,
                "NUCLEAR", MianbaoCruiseMissileCompat.Type.GROUND_NUCLEAR);
        for (var entry : named.entrySet()) {
            launcher.getPersistentData().putString("ammo", entry.getKey());
            require(MianbaoCruiseMissileCompat.loadedSiloType(launcher) == entry.getValue(),
                    "Old named ammunition stopped working: " + entry.getKey());
            MianbaoCruiseMissileCompat.clearSiloAmmo(launcher, entry.getValue());
        }
        launcher.getPersistentData().putDouble("loadmissile3", 1);
        require(MianbaoCruiseMissileCompat.loadedSiloType(launcher)
                == MianbaoCruiseMissileCompat.Type.GROUND_CRUISE, "Legacy numeric slot stopped working");
        MianbaoCruiseMissileCompat.clearSiloAmmo(launcher, MianbaoCruiseMissileCompat.Type.GROUND_CRUISE);
        require(MianbaoCruiseMissileCompat.nativeSlotsEmpty(launcher), "Consumed launcher was not empty");
        System.out.println("Mianbao launcher ammunition checks passed");
    }

    private static void add(Map<Item, MianbaoCruiseMissileCompat.Type> ammunition,
                            String path, MianbaoCruiseMissileCompat.Type type) {
        Item item = Registry.register(BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path),
                new Item(new Item.Properties()));
        ammunition.put(item, type);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Launcher extends BlockEntity implements Container {
        private final SimpleContainer inventory = new SimpleContainer(1);

        Launcher() { super(BlockEntityType.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState()); }
        @Override public int getContainerSize() { return inventory.getContainerSize(); }
        @Override public boolean isEmpty() { return inventory.isEmpty(); }
        @Override public ItemStack getItem(int slot) { return inventory.getItem(slot); }
        @Override public ItemStack removeItem(int slot, int amount) { return inventory.removeItem(slot, amount); }
        @Override public ItemStack removeItemNoUpdate(int slot) { return inventory.removeItemNoUpdate(slot); }
        @Override public void setItem(int slot, ItemStack stack) { inventory.setItem(slot, stack); }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public void clearContent() { inventory.clearContent(); }
    }
}
