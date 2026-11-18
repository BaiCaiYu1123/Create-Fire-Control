package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.renderer.MzCannonBreechRenderer;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public final class MzCannonBreechClient {
    public static final int FRAME_COUNT = 13;
    public static final PartialModel RECOIL_BODY = PartialModel.of(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "block/mz_cannon_breech/recoil")
    );
    public static final PartialModel[] OPENING_FRAMES = createFrames("frame");
    public static final PartialModel[] CO_FRAMES = createFrames("co_frame");

    public static void register(IEventBus modBus) {
        modBus.addListener(MzCannonBreechClient::registerRenderers);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModCbcContent.MZ_CANNON_BREECH_BLOCK_ENTITY.get(),
                MzCannonBreechRenderer::new
        );
    }

    private static PartialModel[] createFrames(String prefix) {
        PartialModel[] frames = new PartialModel[FRAME_COUNT];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = PartialModel.of(ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID,
                    "block/mz_cannon_breech/%s_%02d".formatted(prefix, index)
            ));
        }
        return frames;
    }

    private MzCannonBreechClient() {
    }
}
