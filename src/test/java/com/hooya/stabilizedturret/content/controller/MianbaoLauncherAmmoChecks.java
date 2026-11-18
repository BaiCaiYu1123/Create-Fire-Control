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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Tests the production readers used both before and after native firing. */
public final class MianbaoLauncherAmmoChecks {
    private static final Map<String, Item> ITEMS = new HashMap<>();

    public static void main(String[] args) {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ((MappedRegistry<Item>) BuiltInRegistries.ITEM).unfreeze();
        for (String path : List.of("antitankmissile_1", "antitankmissile_2",
                "portableantiairmissile", "portableantiairmissile_2", "rockets", "rockets_visual_guided", "farrocketsexplosion",
                "farrocketsfire", "farrocketlaserguided")) {
            ITEMS.put(path, Registry.register(BuiltInRegistries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path),
                    new Item(new Item.Properties())));
        }
        BuiltInRegistries.ITEM.freeze();
        for (String path : List.of("medium_rocket_pod_type_1", "medium_rocket_pod_type_2", "medium_rocket_pod_type_3",
                "rocketpod", "rocketpod_14", "rocketpodsecond_9", "rocketpodsecondleft_9",
                "rocketpodsecondright_9", "mediumrocketpod", "heavy_rocketpod", "heavy_rocketpod_2")) {
            require(MianbaoRocketPodCompat.isPodId(ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path)),
                    "Rocket pod recognition lost for " + path);
        }
        require(!MianbaoRocketPodCompat.isPodId(ResourceLocation.parse("minecraft:medium_rocket_pod_type_1")),
                "Rocket recognition ignored namespace");
        require(!MianbaoRocketPodCompat.isPodId(ResourceLocation.parse("mianbaos_modernwarfare:medium_rocket_pod_type_4")),
                "Unknown 2.7 pod was accepted");

        for (String path : List.of("antitankemissilelauncher", "antitankemissilelauncherh_2",
                "antitankmissilelaunchererxing_right", "antitankmissilelaunchererxing_left",
                "antitankmissilelaunchersanxing", "antitankmissilelaunchersixing")) {
            Launcher launcher = new Launcher(path.endsWith("sanxing") || path.endsWith("sixing") ? 9 : 1);
            ToIntFunction<BlockEntity> reader = be -> MianbaoLauncherAmmo.antiTank(be, path);
            exercise(launcher, "antitankmissile_1", reader);
            if (!path.endsWith("sixing")) exercise(launcher, "antitankmissile_2", reader);
            else {
                launcher.setItem(0, stack("antitankmissile_2", 4));
                require(reader.applyAsInt(launcher) == 0, "ATGM IV accepted unsupported ammunition");
            }
        }
        for (String path : List.of("antiairmissilelauncherblock", "antiairmissilelauncherleft",
                "antiairmissilelauncherright", "antiairmissilelauncherthird_h_1",
                "antiairmissilelauncherthird_h_2", "antiairmissilelauncherthird_h_1sand",
                "antiairmissilelauncherthird_h_2sand")) {
            Launcher launcher = new Launcher(1);
            exercise(launcher, "portableantiairmissile_2", MianbaoLauncherAmmo::airDefense);
            exercise(launcher, "portableantiairmissile", MianbaoLauncherAmmo::airDefense);
            require(MianbaoAirDefenseCompat.fireProcedureClass(launcher, path).endsWith(".AntiairmissilelaunchergoProcedure"),
                    path + " selected legacy firing for an empty container");
            launcher.setItem(0, new ItemStack(Items.DIRT));
            require(MianbaoAirDefenseCompat.fireProcedureClass(launcher, path).endsWith(".AntiairmissilelaunchergoProcedure"),
                    path + " selected legacy firing for an invalid round");
        }
        exercise(new Launcher(1), "rockets", be -> MianbaoLauncherAmmo.rocketPod(be, false));
        exercise(new Launcher(1), "rockets", be -> MianbaoLauncherAmmo.rocketPod(be, false, true));
        exercise(new Launcher(1), "rockets_visual_guided", be -> MianbaoLauncherAmmo.rocketPod(be, false, true));
        Launcher guided = new Launcher(1);
        guided.setItem(0, stack("rockets_visual_guided", 4));
        require(MianbaoLauncherAmmo.hasVisualGuidedRound(guided), "2.7 guided ammunition not detected");
        require(MianbaoLauncherAmmo.rocketPod(guided, false) == 0, "Legacy pod accepted 2.7-only ammunition");
        require(MianbaoLauncherAmmo.rocketPod(guided, true, true) == 0, "Heavy pod accepted medium ammunition");
        for (int variant = 0; variant < 2; variant++) {
            for (String path : List.of("farrocketsexplosion", "farrocketsfire", "farrocketlaserguided")) {
                Launcher launcher = new Launcher(1);
                launcher.setItem(0, stack(path, 5));
                require(MianbaoLauncherAmmo.heavyMuzzleSpeed(launcher) == 9.0, "Heavy round speed disagrees with native firing");
                exercise(launcher, path, be -> MianbaoLauncherAmmo.rocketPod(be, true));
            }
        }

        // Retain legacy reads for a backend with no usable inventory.
        BlockEntity legacy = new BlockEntity(BlockEntityType.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState()) {};
        staleTags(legacy);
        require(MianbaoLauncherAmmo.rocketPod(legacy, false, true) == 0, "2.7 inventory pod revived legacy NBT");
        require(MianbaoLauncherAmmo.antiTank(legacy, "antitankemissilelauncher") == 6, "Legacy ATGM tags lost");
        require(MianbaoLauncherAmmo.antiTank(legacy, "antitankmissilelaunchersixing") == 2, "Legacy IV counted missile 2");
        require(MianbaoLauncherAmmo.airDefense(legacy) == 3, "Legacy AA tags lost");
        for (String path : List.of("antiairmissilelauncherleft", "antiairmissilelauncherright")) {
            require(MianbaoAirDefenseCompat.fireProcedureClass(legacy, path).endsWith(".Anti_air_missile_launcher_second_fireProcedure"),
                    "Legacy AA II firing lost for " + path);
            require(MianbaoAirDefenseCompat.fireProcedureClass(new Launcher(0), path)
                    .endsWith(".Anti_air_missile_launcher_second_fireProcedure"), "Zero-slot AA II backend selected container firing");
        }
        require(MianbaoLauncherAmmo.rocketPod(legacy, false) == 36, "Legacy medium capacity lost");
        require(MianbaoLauncherAmmo.rocketPod(legacy, true) == 15, "Legacy heavy capacity lost");
        require(MianbaoLauncherAmmo.heavyMuzzleSpeed(legacy) == 10.0, "Legacy mixed heavy speed changed");
        legacy.getPersistentData().putDouble("\u706b\u7130", 0);
        legacy.getPersistentData().putDouble("\u5236\u5bfc", 0);
        require(MianbaoLauncherAmmo.heavyMuzzleSpeed(legacy) == 9.0, "Legacy HE speed changed");
        require(MianbaoLauncherAmmo.rocketPod(new Launcher(0), false) == 0, "Zero-slot container crashed");
        System.out.println("Mianbao missile and rocket launcher ammunition checks passed");
    }

    private static void exercise(Launcher launcher, String item, ToIntFunction<BlockEntity> reader) {
        staleTags(launcher);
        launcher.setItem(0, stack(item, 4));
        require(reader.applyAsInt(launcher) == 4, "Inventory count lost for " + item);
        launcher.removeItem(0, 1); // Native firing owns consumption; Fire Control must only observe it.
        require(reader.applyAsInt(launcher) == 3, "Native consumption not detected for " + item);
        launcher.removeItem(0, 3);
        require(reader.applyAsInt(launcher) == 0, "Empty inventory revived stale tags for " + item);
        launcher.setItem(0, new ItemStack(Items.DIRT));
        require(reader.applyAsInt(launcher) == 0, "Unsupported item revived stale tags for " + item);
        launcher.setItem(0, ItemStack.EMPTY);
        if (launcher.getContainerSize() > 1) {
            launcher.setItem(1, stack(item, 4));
            require(reader.applyAsInt(launcher) == 0, "Counted a slot the native procedure does not fire");
        }
    }

    private static void staleTags(BlockEntity launcher) {
        var data = launcher.getPersistentData();
        data.putDouble("missile1ammo", 2);
        data.putDouble("missile2ammo", 4);
        data.putDouble("missileammo", 3);
        data.putDouble("rockets", 90);
        data.putDouble("\u9ad8\u7206", 9);
        data.putDouble("\u706b\u7130", 9);
        data.putDouble("\u5236\u5bfc", 9);
    }

    private static ItemStack stack(String path, int count) { return new ItemStack(ITEMS.get(path), count); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Launcher extends BlockEntity implements Container {
        private final SimpleContainer inventory;
        Launcher(int slots) {
            super(BlockEntityType.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
            inventory = new SimpleContainer(slots);
        }
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
