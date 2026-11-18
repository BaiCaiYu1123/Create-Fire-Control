package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.renderer.AzCannonBreechRenderer;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public final class AzCannonBreechClient {
    public static final int FRAME_COUNT = 13;
    public static final PartialModel RECOIL_BODY = partial("recoil");
    public static final PartialModel[] CO_FRAMES = frames("co_frame");
    public static final PartialModel[] OPENING_FRAMES = frames("opening_frame");

    public static void register(IEventBus modBus) {
        modBus.addListener(AzCannonBreechClient::registerRenderers);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModCbcContent.AZ_CANNON_BREECH_BLOCK_ENTITY.get(),
                AzCannonBreechRenderer::new);
    }

    private static PartialModel[] frames(String prefix) {
        PartialModel[] frames = new PartialModel[FRAME_COUNT];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = partial("%s_%02d".formatted(prefix, index));
        }
        return frames;
    }

    private static PartialModel partial(String name) {
        return PartialModel.of(ResourceLocation.fromNamespaceAndPath(
                StabilizedTurret.MOD_ID, "block/az_cannon_breech/" + name));
    }

    private AzCannonBreechClient() {
    }
}
