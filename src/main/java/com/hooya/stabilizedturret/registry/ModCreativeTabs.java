package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> REGISTER = DeferredRegister.create(
            Registries.CREATIVE_MODE_TAB,
            StabilizedTurret.MOD_ID
    );

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = REGISTER.register("main", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.create_fire_control.main"))
                    .icon(() -> ModItems.STABILIZER_CONTROLLER.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.WIRELESS_CONTROLLER.get());
                        output.accept(ModItems.WIRELESS_SIGNAL_PROCESSOR.get());
                        output.accept(ModItems.STABILIZER_CONTROLLER.get());
                        output.accept(ModItems.CONNECTED_DISPLAY.get());
                        output.accept(ModItems.CONNECTED_DISPLAY_CONSOLE.get());
                        output.accept(ModItems.IFF_TRANSPONDER.get());
                        output.accept(ModItems.GAS_TURBINE.get());
                        output.accept(ModItems.ZTZ99A_DIESEL_ENGINE.get());
                        output.accept(ModItems.TIGER_I_ENGINE.get());
                        output.accept(ModItems.DF_26_BALLISTIC_MISSILE.get());
                        output.accept(com.caca.copypoly.Copypoly.ITEM.get());
                        output.accept(ModItems.COMPOSITE_ARMOR_PLATE.get());

                        boolean cbcModernWarfareLoaded =
                                ModList.get().isLoaded("cbcmodernwarfare");
                        boolean cannonModLoaded = cbcModernWarfareLoaded
                                || ModList.get().isLoaded("createbigcannons");
                        if (!cannonModLoaded) {
                            return;
                        }

                        output.accept(ModItems.MZ_LOADER.get());
                        output.accept(ModItems.AZ_LOADER.get());
                        output.accept(ModItems.TYPE_10_LOADER.get());
                        if (cbcModernWarfareLoaded) {
                            output.accept(ModCbcContent.MEDIUM_CANNON_BREECH_ITEM.get());
                            output.accept(ModCbcContent.MZ_CANNON_BREECH_ITEM.get());
                            output.accept(ModCbcContent.AZ_CANNON_BREECH_ITEM.get());
                            output.accept(ModCbcContent.TYPE_10_CANNON_BREECH_ITEM.get());
                            output.accept(ModCbcContent.APFSDS_MEDIUM_CANNON_CARTRIDGE
                                    .get().getDefaultInstance());
                            output.accept(ModCbcContent.APFSDS_MEDIUM_CANNON_ROUND.get());
                            output.accept(ModCbcContent.APFSDS_MEDIUM_CANNON_CASING.get());
                            output.accept(ModCbcContent.ANNIHILATION_MEDIUM_CANNON_CARTRIDGE
                                    .get().getDefaultInstance());
                            // The separate round is only CBC's internal cartridge payload.
                        }
                    })
                    .build()
    );

    private ModCreativeTabs() {
    }
}
