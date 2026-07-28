package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items REGISTER = DeferredRegister.createItems(StabilizedTurret.MOD_ID);

    public static final DeferredItem<BlockItem> STABILIZER_CONTROLLER = REGISTER.registerSimpleBlockItem(
            ModBlocks.STABILIZER_CONTROLLER,
            new Item.Properties()
    );

    public static final DeferredItem<BlockItem> CONNECTED_DISPLAY = REGISTER.registerSimpleBlockItem(
            ModBlocks.CONNECTED_DISPLAY,
            new Item.Properties()
    );

    public static final DeferredItem<BlockItem> GAS_TURBINE = REGISTER.registerSimpleBlockItem(
            ModBlocks.GAS_TURBINE,
            new Item.Properties()
    );

    private ModItems() {
    }
}
