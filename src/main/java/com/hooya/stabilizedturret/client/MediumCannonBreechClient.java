package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.renderer.MediumCannonBreechRenderer;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonProjectileRenderer;

public final class MediumCannonBreechClient {
    public static final PartialModel MOVING_BREECH = PartialModel.of(ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID,
            "block/medium_cannon_breech/moving"
    ));

    public static void register(IEventBus modBus) {
        modBus.addListener(MediumCannonBreechClient::registerRenderers);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModCbcContent.MEDIUM_CANNON_BREECH_BLOCK_ENTITY.get(),
                MediumCannonBreechRenderer::new
        );
        event.registerEntityRenderer(
                ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get(),
                MediumcannonProjectileRenderer::new
        );
    }

    private MediumCannonBreechClient() {
    }
}
