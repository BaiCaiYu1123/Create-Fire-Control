package com.hooya.stabilizedturret.client.ponder;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public final class FireControlPonderPlugin implements PonderPlugin {
    public static final ResourceLocation FIRE_CONTROL = ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "fire_control"
    );

    @Override
    public String getModId() {
        return StabilizedTurret.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        FireControlPonderScenes.register(helper);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.registerTag(FIRE_CONTROL)
                .addToIndex()
                .item(ModBlocks.STABILIZER_CONTROLLER.get(), true, true)
                .title("Fire-control systems")
                .description("Build, bind, configure and operate a complete vehicle fire-control system")
                .register();
        helper.addToTag(FIRE_CONTROL)
                .add(ModBlocks.STABILIZER_CONTROLLER.getId())
                .add(ModBlocks.MZ_LOADER.getId())
                .add(ModBlocks.CONNECTED_DISPLAY.getId());
    }
}
