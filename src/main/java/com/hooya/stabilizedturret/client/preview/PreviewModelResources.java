package com.hooya.stabilizedturret.client.preview;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** Atlas UVs and baked model geometry belong to one resource-pack generation. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
public final class PreviewModelResources {
    private static int generation;
    public static int generation(){return generation;}
    @SubscribeEvent public static void register(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener)manager->{StructureHullCache.clear();generation++;});
    }
    private PreviewModelResources() {}
}
