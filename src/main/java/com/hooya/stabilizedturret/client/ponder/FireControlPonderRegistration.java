package com.hooya.stabilizedturret.client.ponder;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Registers the fire-control W/Ponder pages after all block registries exist. */
@EventBusSubscriber(
        modid = StabilizedTurret.MOD_ID,
        value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD
)
public final class FireControlPonderRegistration {
    private static boolean registered;

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (registered) return;
            registered = true;
            PonderIndex.addPlugin(new FireControlPonderPlugin());
        });
    }

    private FireControlPonderRegistration() {
    }
}
