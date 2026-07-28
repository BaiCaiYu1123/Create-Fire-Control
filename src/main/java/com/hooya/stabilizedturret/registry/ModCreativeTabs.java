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
                    .title(Component.translatable("itemGroup.stabilized_turret.main"))
                    .icon(() -> ModItems.STABILIZER_CONTROLLER.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.STABILIZER_CONTROLLER.get());
                        output.accept(ModItems.CONNECTED_DISPLAY.get());
                        if (ModList.get().isLoaded("cbcmodernwarfare")) {
                            output.accept(ModCbcContent.MEDIUM_CANNON_BREECH_ITEM.get());
                            output.accept(ModCbcContent.APFSDS_MEDIUM_CANNON_CARTRIDGE
                                    .get().getDefaultInstance());
                            output.accept(ModCbcContent.APFSDS_MEDIUM_CANNON_ROUND.get());
                            output.accept(ModCbcContent.APFSDS_MEDIUM_CANNON_CASING.get());
                        }
                    })
                    .build()
    );

    private ModCreativeTabs() {
    }
}
