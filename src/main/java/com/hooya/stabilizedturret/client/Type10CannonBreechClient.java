package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.renderer.Type10CannonBreechRenderer;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public final class Type10CannonBreechClient {
    public static final PartialModel BODY = partial("body");
    public static final PartialModel SLIDING_BLOCK = partial("sliding_block");

    public static void register(IEventBus bus) {
        bus.addListener(Type10CannonBreechClient::registerRenderers);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModCbcContent.TYPE_10_CANNON_BREECH_BLOCK_ENTITY.get(),
                Type10CannonBreechRenderer::new);
    }

    private static PartialModel partial(String name) {
        return PartialModel.of(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,
                "block/type_10_cannon_breech/" + name));
    }

    private Type10CannonBreechClient() { }
}
