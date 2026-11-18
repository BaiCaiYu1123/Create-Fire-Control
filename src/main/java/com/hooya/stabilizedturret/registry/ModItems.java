package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items REGISTER = DeferredRegister.createItems(StabilizedTurret.MOD_ID);

    public static final DeferredItem<Item> COMPOSITE_ARMOR_PLATE =
            REGISTER.registerSimpleItem("composite_armor_plate", new Item.Properties());

    public static final DeferredItem<com.hooya.stabilizedturret.content.remote.WirelessControllerItem> WIRELESS_CONTROLLER =
            REGISTER.register("wireless_controller", () -> new com.hooya.stabilizedturret.content.remote.WirelessControllerItem(
                    new Item.Properties().stacksTo(1)));
    public static final DeferredItem<BlockItem> WIRELESS_SIGNAL_PROCESSOR = REGISTER.registerSimpleBlockItem(
            ModBlocks.WIRELESS_SIGNAL_PROCESSOR, new Item.Properties());

    public static final DeferredItem<BlockItem> STABILIZER_CONTROLLER = REGISTER.registerSimpleBlockItem(
            ModBlocks.STABILIZER_CONTROLLER,
            new Item.Properties()
    );

    public static final DeferredItem<BlockItem> CONNECTED_DISPLAY = REGISTER.registerSimpleBlockItem(
            ModBlocks.CONNECTED_DISPLAY,
            new Item.Properties()
    );

    public static final DeferredItem<BlockItem> CONNECTED_DISPLAY_CONSOLE = REGISTER.registerSimpleBlockItem(
            ModBlocks.CONNECTED_DISPLAY_CONSOLE,
            new Item.Properties()
    );

    public static final DeferredItem<BlockItem> IFF_TRANSPONDER = REGISTER.registerSimpleBlockItem(
            ModBlocks.IFF_TRANSPONDER,
            new Item.Properties()
    );

    public static final DeferredItem<BlockItem> GAS_TURBINE = REGISTER.registerSimpleBlockItem(
            ModBlocks.GAS_TURBINE,
            new Item.Properties()
    );

    public static final DeferredItem<BlockItem> TIGER_I_ENGINE = REGISTER.registerSimpleBlockItem(
            ModBlocks.TIGER_I_ENGINE, new Item.Properties());

    public static final DeferredItem<BlockItem> ZTZ99A_DIESEL_ENGINE = REGISTER.registerSimpleBlockItem(
            ModBlocks.ZTZ99A_DIESEL_ENGINE, new Item.Properties());

    public static final DeferredItem<BlockItem> MZ_LOADER = REGISTER.registerSimpleBlockItem(
            ModBlocks.MZ_LOADER,
            new Item.Properties()
    );

    public static final DeferredItem<BlockItem> TYPE_10_LOADER = REGISTER.registerSimpleBlockItem(
            ModBlocks.TYPE_10_LOADER, new Item.Properties());

    public static final DeferredItem<BlockItem> AZ_LOADER = REGISTER.registerSimpleBlockItem(
            ModBlocks.AZ_LOADER,
            new Item.Properties()
    );

    public static final DeferredItem<BlockItem> DF_26_BALLISTIC_MISSILE =
            REGISTER.registerSimpleBlockItem(
                    ModBlocks.DF_26_BALLISTIC_MISSILE,
                    new Item.Properties().stacksTo(16)
            );

    private ModItems() {
    }
}
